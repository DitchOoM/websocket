package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.stream.AutoFillingSuspendingStreamProcessor
import com.ditchoom.buffer.stream.EndOfStreamException
import com.ditchoom.websocket.frame.AssembledMessage
import com.ditchoom.websocket.frame.AssemblyResult
import com.ditchoom.websocket.frame.CloseCode
import com.ditchoom.websocket.frame.FrameReader
import com.ditchoom.websocket.frame.FrameWriter
import com.ditchoom.websocket.frame.MessageAssembler
import com.ditchoom.websocket.frame.ParsedFrame
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * WebSocket codec connection over a [ByteStream].
 *
 * Composes [FrameReader], [FrameWriter], and [MessageAssembler] to implement
 * the RFC 6455 framing protocol. Constructed fully-initialized by [connectWebSocket] —
 * no two-phase init, no unconnected state.
 *
 * @param transport The underlying byte stream (already connected, handshake completed).
 * @param frameWriter Frame serializer with masking and optional compression.
 * @param stream Auto-filling stream processor for frame parsing.
 * @param compression Compression config from permessage-deflate negotiation.
 * @param pool Buffer pool for zero-copy memory reuse.
 * @param readTimeout Timeout for transport read operations.
 * @param writeTimeout Timeout for transport write operations.
 * @param parentScope Optional parent scope for structured concurrency.
 */
internal class WebSocketCodec(
    private val transport: ByteStream,
    private val frameWriter: FrameWriter,
    private val stream: AutoFillingSuspendingStreamProcessor,
    private val compression: CompressionConfig,
    private val bufferFactory: BufferFactory,
    private val readTimeout: Duration,
    private val writeTimeout: Duration,
    parentScope: CoroutineScope?,
) : Connection<WebSocketMessage> {
    private val codecJob = kotlinx.coroutines.Job(parentScope?.coroutineContext?.get(kotlinx.coroutines.Job))

    init {
        codecJob.invokeOnCompletion { closed = true }
    }

    private val scope: CoroutineScope =
        CoroutineScope(
            (parentScope?.coroutineContext ?: Dispatchers.Default) +
                codecJob +
                CoroutineName("WebSocketCodec"),
        )

    override val id: Long = 0L

    @Volatile
    private var closed = false
    private var serverInitiatedClose = false
    private val stringDecoder = StreamingStringDecoder()

    private val incomingMessageChannel = Channel<WebSocketMessage>(Channel.UNLIMITED)

    override fun receive(): Flow<WebSocketMessage> = incomingMessageChannel.receiveAsFlow()

    fun isOpen() = !closed && transport.isOpen

    internal fun startReadLoop() {
        val compressionEnabled = compression is CompressionConfig.Enabled
        scope.launch {
            val frameReader = FrameReader(stream, bufferFactory)
            val messageAssembler = MessageAssembler(compressionEnabled, bufferFactory)

            try {
                readLoop@ while (isOpen() && isActive) {
                    val frame = readNextFrame(frameReader, stream) ?: break@readLoop

                    when (val result = messageAssembler.addFrame(frame)) {
                        is AssemblyResult.ControlFrame -> {
                            handleControlFrame(result.frame)
                            if (result.frame.opcode == Opcode.Close) {
                                return@launch
                            }
                        }
                        is AssemblyResult.CompleteMessage -> {
                            emitMessage(result.message)
                            result.fragmentsToClose.freeAll()
                        }
                        is AssemblyResult.NeedMoreFrames -> {}
                        is AssemblyResult.Error -> {
                            sendCloseFrame(result.code.code, result.reason)
                            closed = true
                            return@launch
                        }
                    }
                }
            } catch (_: EndOfStreamException) {
                // Clean EOF
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Transport or protocol error
            } finally {
                messageAssembler.reset()
                stream.release()
                incomingMessageChannel.close()
            }
        }
    }

    private suspend fun readNextFrame(
        frameReader: FrameReader,
        stream: AutoFillingSuspendingStreamProcessor,
    ): ParsedFrame? {
        while (coroutineContext.isActive) {
            if (stream.available() < 2) {
                stream.peekByte(0)
            }
            val frame = frameReader.readFrame()
            if (frame != null) return frame
            stream.peekByte(stream.available())
        }
        return null
    }

    private suspend fun handleControlFrame(frame: ParsedFrame) {
        when (frame.opcode) {
            Opcode.Ping -> {
                val pongData =
                    if (frame.payloadLength > 0) frame.payload else EMPTY_BUFFER
                val payloadStart = frame.payload.position()
                writePongFrame(pongData)
                if (frame.payloadLength > 0) {
                    frame.payload.position(payloadStart)
                }
                incomingMessageChannel.trySend(WebSocketMessage.Ping(frame.payload))
            }
            Opcode.Pong -> {
                incomingMessageChannel.trySend(WebSocketMessage.Pong(frame.payload))
            }
            Opcode.Close -> {
                serverInitiatedClose = true
                val closeFrame = frame as ParsedFrame.ControlFrame.Close
                val code = closeFrame.closeCode.code
                val reason = closeFrame.closeReason

                if (!closed) {
                    val responseCode =
                        if (closeFrame.closeCode == CloseCode.PROTOCOL_ERROR) {
                            CloseCode.PROTOCOL_ERROR.code
                        } else {
                            CloseCode.NORMAL.code
                        }
                    sendCloseFrame(responseCode, reason)
                }

                incomingMessageChannel.trySend(WebSocketMessage.Close(code, reason))
                closed = true
            }
            else -> {}
        }
    }

    private suspend fun emitMessage(message: AssembledMessage) {
        when (message.opcode) {
            Opcode.Text -> {
                val text =
                    try {
                        if (message.compressed && compression is CompressionConfig.Enabled) {
                            decompressPayloadToString(message.payload)
                        } else {
                            message.payload.readString(message.payload.remaining(), Charset.UTF8)
                        }
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Throwable,
                    ) {
                        sendCloseFrame(CloseCode.INVALID_PAYLOAD.code, "Invalid UTF-8")
                        message.payload.freeIfNeeded()
                        return
                    }
                message.payload.freeIfNeeded()
                incomingMessageChannel.trySend(WebSocketMessage.Text(text))
            }
            Opcode.Binary -> {
                val payload =
                    if (message.compressed && compression is CompressionConfig.Enabled) {
                        val decompressed = decompressPayload(message.payload)
                        if (decompressed !== message.payload) {
                            message.payload.freeIfNeeded()
                        }
                        decompressed
                    } else {
                        message.payload
                    }
                incomingMessageChannel.trySend(WebSocketMessage.Binary(payload))
            }
            else -> {}
        }
    }

    private fun decompressPayload(payload: ReadBuffer): ReadBuffer {
        val config = compression as? CompressionConfig.Enabled ?: return payload
        return try {
            decompressToBufferSync(payload, config.decompressor, factory = bufferFactory)
        } catch (e: Exception) {
            payload
        } finally {
            if (config.serverNoContextTakeover) {
                try {
                    config.decompressor.reset()
                } catch (_: Exception) {}
            }
        }
    }

    private fun decompressPayloadToString(payload: ReadBuffer): String {
        val config = compression as? CompressionConfig.Enabled
            ?: return payload.readString(payload.remaining(), Charset.UTF8)
        return try {
            decompressToStringSync(payload, config.decompressor, stringDecoder)
        } catch (e: Throwable) {
            throw e
        } finally {
            if (config.serverNoContextTakeover) {
                try {
                    config.decompressor.reset()
                } catch (_: Exception) {}
            }
        }
    }

    override suspend fun send(message: WebSocketMessage) {
        if (closed) {
            throw WebSocketException.ConnectionClosed("Cannot send: not connected")
        }
        val frameBuffer =
            when (message) {
                is WebSocketMessage.Text -> frameWriter.writeTextFrame(message.value)
                is WebSocketMessage.Binary -> frameWriter.writeBinaryFrame(message.value)
                is WebSocketMessage.Ping -> frameWriter.writePingFrame(message.value)
                is WebSocketMessage.Pong -> frameWriter.writePongFrame(message.value)
                is WebSocketMessage.Close -> frameWriter.writeCloseFrame(message.code, message.reason)
            }
        writeToTransport(frameBuffer)
        frameBuffer.freeIfNeeded()
    }

    private suspend fun writePongFrame(payloadData: ReadBuffer) {
        val frameBuffer = frameWriter.writePongFrame(payloadData)
        writeToTransport(frameBuffer)
        frameBuffer.freeIfNeeded()
    }

    private suspend fun sendCloseFrame(
        code: UShort,
        reason: String = "",
    ) {
        try {
            val frameBuffer = frameWriter.writeCloseFrame(code, reason)
            writeToTransport(frameBuffer)
            frameBuffer.freeIfNeeded()
        } catch (e: Exception) {
            // Ignore errors when sending close
        }
    }

    private suspend fun writeToTransport(buffer: ReadBuffer) {
        try {
            while (buffer.hasRemaining()) {
                transport.write(buffer, writeTimeout)
            }
        } catch (e: Exception) {
            closed = true
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true

        if (!serverInitiatedClose) {
            try {
                sendCloseFrame(CloseCode.NORMAL.code, "Client closing")
            } catch (_: Exception) {}
        }
        incomingMessageChannel.close()
        // Clean up compression resources
        val config = compression as? CompressionConfig.Enabled
        config?.compressor?.close()
        config?.decompressor?.close()
        // Close transport first to break pending I/O, then cancelAndJoin.
        // Order matters for K/N Darwin: cancel() on a coroutine suspended in a
        // GCD-dispatched I/O operation crashes because GCD still holds a reference.
        try {
            transport.close()
        } catch (_: Exception) {}
        try {
            codecJob.cancelAndJoin()
        } catch (_: Exception) {}
        // If the factory is a pool, clear it
        (bufferFactory as? com.ditchoom.buffer.pool.BufferPool)?.clear()
    }

    companion object {
        /** Default read buffer size (64KB) - matches typical socket receive buffer */
        const val DEFAULT_READ_BUFFER_SIZE = 65536
    }
}
