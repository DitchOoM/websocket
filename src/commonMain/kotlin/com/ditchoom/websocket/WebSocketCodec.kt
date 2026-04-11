package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.stream.AutoFillingSuspendingStreamProcessor
import com.ditchoom.buffer.stream.EndOfStreamException
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.websocket.frame.AssembledMessage
import com.ditchoom.websocket.frame.AssemblyResult
import com.ditchoom.websocket.frame.CloseCode
import com.ditchoom.websocket.frame.FrameHeaderByte1
import com.ditchoom.websocket.frame.MessageAssembler
import com.ditchoom.websocket.frame.WsFrame
import com.ditchoom.websocket.frame.WsFrameBinaryContext
import com.ditchoom.websocket.frame.WsFrameCodec
import com.ditchoom.websocket.frame.WsFrameContinuationContext
import com.ditchoom.websocket.frame.WsFramePingContext
import com.ditchoom.websocket.frame.WsFramePongContext
import com.ditchoom.websocket.frame.WsFrameTextContext
import com.ditchoom.websocket.frame.WsCloseBody
import com.ditchoom.websocket.frame.WsFrameHeader
import com.ditchoom.websocket.frame.WsFrameHeaderCodec
import com.ditchoom.websocket.frame.WsMaskingKey
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
 * Uses generated [WsFrameCodec] for frame decode (sealed dispatch by opcode)
 * and [WsFrameHeaderCodec] + [WsPayloadCodec] for encode (with masking via context).
 * Fragment assembly via [MessageAssembler].
 */
internal class WebSocketCodec(
    private val transport: ByteStream,
    private val stream: AutoFillingSuspendingStreamProcessor,
    private val compression: CompressionConfig,
    private val bufferFactory: BufferFactory,
    private val readTimeout: Duration,
    private val writeTimeout: Duration,
    parentScope: CoroutineScope?,
    private val compressor: StreamingCompressor? = null,
    private val compressionEnabled: Boolean = false,
    private val resetCompressorPerMessage: Boolean = true,
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
        val compressionNegotiated = compression is CompressionConfig.Enabled
        scope.launch {
            val messageAssembler = MessageAssembler(compressionNegotiated, bufferFactory)

            try {
                readLoop@ while (isOpen() && isActive) {
                    val frame = readNextFrame() ?: break@readLoop

                    when (val result = messageAssembler.addFrame(frame)) {
                        is AssemblyResult.ControlFrame -> {
                            handleControlFrame(result.frame)
                            if (result.frame is WsFrame.Close) {
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
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                // Transport or protocol error
            } finally {
                messageAssembler.reset()
                stream.release()
                incomingMessageChannel.close()
            }
        }
    }

    /**
     * Reads the next complete frame from the stream using [WsFrameCodec].
     *
     * Manual peekFrameSize: determines header size via [WsFrameHeaderCodec.peekFrameSize],
     * peeks payload length from byte2, waits for all bytes, then decodes.
     */
    private suspend fun readNextFrame(): WsFrame? {
        while (coroutineContext.isActive) {
            // Ensure at least 2 bytes (minimum header)
            if (stream.available() < 2) {
                stream.peekByte(stream.available())
                continue
            }

            // Determine header size
            val headerSize =
                when (val peek = WsFrameHeaderCodec.peekFrameSize(stream, 0)) {
                    is PeekResult.NeedsMoreData -> {
                        stream.peekByte(stream.available())
                        continue
                    }
                    is PeekResult.Size -> peek.bytes
                }

            // Peek payload length from byte2
            val byte2 = stream.peekByte(1).toInt() and 0xFF
            val len7 = byte2 and 0x7F
            val payloadLength = peekPayloadLength(len7) ?: continue
            val totalFrameSize = headerSize + payloadLength

            // Wait for complete frame
            if (stream.available() < totalFrameSize) {
                stream.peekByte(stream.available())
                continue
            }

            // Read and decode
            val buffer = stream.readBuffer(totalFrameSize)
            val frame = try {
                val copyPayload: Any.(com.ditchoom.buffer.codec.payload.PayloadReader) -> ReadBuffer = { pr ->
                    pr.copyToBuffer(bufferFactory)
                }
                @Suppress("UNCHECKED_CAST")
                WsFrameCodec.decode(
                    buffer,
                    decodeBinaryPayload = copyPayload as WsFrameBinaryContext.(com.ditchoom.buffer.codec.payload.PayloadReader) -> ReadBuffer,
                    decodeContinuationPayload = copyPayload as WsFrameContinuationContext.(com.ditchoom.buffer.codec.payload.PayloadReader) -> ReadBuffer,
                    decodePingPayload = copyPayload as WsFramePingContext.(com.ditchoom.buffer.codec.payload.PayloadReader) -> ReadBuffer,
                    decodePongPayload = copyPayload as WsFramePongContext.(com.ditchoom.buffer.codec.payload.PayloadReader) -> ReadBuffer,
                    decodeTextPayload = copyPayload as WsFrameTextContext.(com.ditchoom.buffer.codec.payload.PayloadReader) -> ReadBuffer,
                )
            } catch (_: IllegalArgumentException) {
                // Reserved opcode — protocol error
                sendCloseFrame(CloseCode.PROTOCOL_ERROR.code, "Reserved opcode")
                closed = true
                return null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Throwable,
            ) {
                // Decode error (e.g., invalid UTF-8 in close reason — JS throws non-Exception errors)
                sendCloseFrame(CloseCode.INVALID_PAYLOAD.code, "Invalid payload data")
                closed = true
                return null
            }

            // Post-decode validation: close frame reason must be valid UTF-8.
            // If readString replaced invalid bytes, re-encoding produces different bytes
            // than the original wire payload (payloadLength - 2 bytes for status code).
            @Suppress("USELESS_IS_CHECK")
            if (frame is WsFrame.Close && frame.body != null && frame.header.payloadLength > 2) {
                val reasonByteCount = frame.body.reason.encodeToByteArray().size
                val expectedByteCount = frame.header.payloadLength.toInt() - 2
                if (reasonByteCount != expectedByteCount) {
                    sendCloseFrame(CloseCode.INVALID_PAYLOAD.code, "Invalid UTF-8 in close reason")
                    closed = true
                    return null
                }
            }

            return frame
        }
        return null
    }

    private suspend fun peekPayloadLength(len7: Int): Int? =
        when (len7) {
            126 -> {
                if (stream.available() < 4) {
                    stream.peekByte(stream.available())
                    return null
                }
                stream.peekShort(2).toInt() and 0xFFFF
            }
            127 -> {
                if (stream.available() < 10) {
                    stream.peekByte(stream.available())
                    return null
                }
                val len64 = stream.peekLong(2)
                if (len64 > Int.MAX_VALUE || len64 < 0) {
                    throw com.ditchoom.websocket.frame.FrameParseException("Payload length out of range: $len64")
                }
                len64.toInt()
            }
            else -> len7
        }

    private suspend fun handleControlFrame(frame: WsFrame) {
        when (frame) {
            is WsFrame.Ping<*> -> {
                val payload = frame.payload as ReadBuffer
                val pongData = if (frame.header.payloadLength > 0) payload else EMPTY_BUFFER
                val payloadStart = payload.position()
                writePongFrame(pongData)
                if (frame.header.payloadLength > 0) {
                    payload.position(payloadStart)
                }
                incomingMessageChannel.trySend(WebSocketMessage.Ping(payload))
            }
            is WsFrame.Pong<*> -> {
                incomingMessageChannel.trySend(WebSocketMessage.Pong(frame.payload as ReadBuffer))
            }
            is WsFrame.Close -> {
                serverInitiatedClose = true
                val closeCode = frame.body?.statusCode ?: CloseCode.NO_STATUS_RECEIVED
                val reason = frame.body?.reason ?: ""

                if (!closed) {
                    val responseCode =
                        if (closeCode == CloseCode.PROTOCOL_ERROR) {
                            CloseCode.PROTOCOL_ERROR.code
                        } else {
                            CloseCode.NORMAL.code
                        }
                    sendCloseFrame(responseCode, reason)
                }

                incomingMessageChannel.trySend(WebSocketMessage.Close(closeCode.code, reason))
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

    // ──────────────────────── Send path ────────────────────────

    override suspend fun send(message: WebSocketMessage) {
        if (closed) {
            throw WebSocketException.ConnectionClosed("Cannot send: not connected")
        }
        val frameBuffer =
            when (message) {
                is WebSocketMessage.Text -> writeTextFrame(message.value)
                is WebSocketMessage.Binary -> writeBinaryFrame(message.value)
                is WebSocketMessage.Ping -> writePingFrame(message.value)
                is WebSocketMessage.Pong -> encodePongFrame(message.value)
                is WebSocketMessage.Close -> writeCloseFrame(message.code, message.reason)
            }
        writeToTransport(frameBuffer)
        frameBuffer.freeIfNeeded()
    }

    private fun writeTextFrame(
        text: String,
        fin: Boolean = true,
    ): ReadBuffer {
        if (text.isEmpty()) {
            return encodeDataFrame(Opcode.Text, EMPTY_BUFFER, fin)
        }
        val payload = (bufferFactory.allocate(text.length * 3) as PlatformBuffer)
        payload.writeString(text, Charset.UTF8)
        payload.resetForRead()
        val frame = encodeDataFrame(Opcode.Text, payload, fin)
        payload.freeIfNeeded()
        return frame
    }

    private fun writeBinaryFrame(
        data: ReadBuffer,
        fin: Boolean = true,
    ): ReadBuffer = encodeDataFrame(Opcode.Binary, data, fin)

    private fun writeCloseFrame(
        statusCode: UShort? = null,
        reason: String? = null,
    ): ReadBuffer {
        val body = if (statusCode != null) {
            val truncated = if (reason != null && reason.length > 123) reason.substring(0, 123) else reason
            // Cap reason bytes at 123 (RFC 6455: control payload ≤ 125, minus 2 for status code)
            val reasonStr = truncated ?: ""
            val reasonBytes = reasonStr.encodeToByteArray()
            val cappedReason = if (reasonBytes.size > 123) reasonStr.substring(0, 123) else reasonStr
            WsCloseBody(CloseCode(statusCode), cappedReason)
        } else {
            null
        }
        val payloadSize = if (body != null) 2 + body.reason.encodeToByteArray().size else 0
        return encodeWsFrame(
            WsFrame.Close(
                header = buildMaskedHeader(fin = true, rsv1 = false, Opcode.Close, payloadSize),
                body = body,
            ),
        )
    }

    private fun writePingFrame(data: ReadBuffer = EMPTY_BUFFER): ReadBuffer {
        val truncated = if (data.remaining() > 125) data.readBytes(125) else data
        return encodeWsFrame(
            WsFrame.Ping(
                header = buildMaskedHeader(fin = true, rsv1 = false, Opcode.Ping, truncated.remaining()),
                payload = truncated,
            ),
        )
    }

    private suspend fun writePongFrame(payloadData: ReadBuffer) {
        val frameBuffer = encodePongFrame(payloadData)
        writeToTransport(frameBuffer)
        frameBuffer.freeIfNeeded()
    }

    private fun encodePongFrame(data: ReadBuffer = EMPTY_BUFFER): ReadBuffer {
        val truncated = if (data.remaining() > 125) data.readBytes(125) else data
        return encodeWsFrame(
            WsFrame.Pong(
                header = buildMaskedHeader(fin = true, rsv1 = false, Opcode.Pong, truncated.remaining()),
                payload = truncated,
            ),
        )
    }

    private fun encodeDataFrame(
        opcode: Opcode,
        payload: ReadBuffer,
        fin: Boolean = true,
        compress: Boolean = compressionEnabled,
    ): ReadBuffer {
        val shouldCompress = compress && !opcode.isControlFrame() && payload.remaining() > 0

        if (shouldCompress && compressor != null) {
            val originalSize = payload.remaining()
            val chunks = compressSync(payload, compressor)
            val compressedSize = totalRemaining(chunks)

            // With context takeover (!resetCompressorPerMessage), always send compressed
            // even if larger. Falling back to uncompressed would desync the LZ77 windows.
            if (compressedSize < originalSize || !resetCompressorPerMessage) {
                if (resetCompressorPerMessage) compressor.reset()
                val combined = combineChunks(chunks, bufferFactory)
                chunks.freeAll()
                val frame = buildDataWsFrame(opcode, fin, rsv1 = true, combined)
                val encoded = encodeWsFrame(frame)
                combined.freeIfNeeded()
                return encoded
            } else {
                payload.resetForRead()
                compressor.reset()
                chunks.freeAll()
            }
        }

        return encodeWsFrame(buildDataWsFrame(opcode, fin, rsv1 = false, payload))
    }

    private fun buildDataWsFrame(opcode: Opcode, fin: Boolean, rsv1: Boolean, payload: ReadBuffer): WsFrame =
        when (opcode) {
            Opcode.Text -> WsFrame.Text(buildMaskedHeader(fin, rsv1, opcode, payload.remaining()), payload)
            Opcode.Binary -> WsFrame.Binary(buildMaskedHeader(fin, rsv1, opcode, payload.remaining()), payload)
            Opcode.Continuation -> WsFrame.Continuation(buildMaskedHeader(fin, rsv1, opcode, payload.remaining()), payload)
            else -> WsFrame.Binary(buildMaskedHeader(fin, rsv1, opcode, payload.remaining()), payload)
        }

    private fun buildMaskedHeader(fin: Boolean, rsv1: Boolean, opcode: Opcode, payloadSize: Int): WsFrameHeader =
        WsFrameHeader.build(
            byte1 = FrameHeaderByte1.pack(fin, rsv1, rsv2 = false, rsv3 = false, opcode),
            payloadSize = payloadSize.toLong(),
            masked = true,
            maskingKey = WsMaskingKey(MaskingKey.FourByteMaskingKey().packed.toUInt()),
        )

    /**
     * Unified encode: uses [WsFrameCodec.encode] for all frame types (including Close),
     * then XOR-masks the payload region in-place.
     */
    @Suppress("UNCHECKED_CAST")
    private fun encodeWsFrame(frame: WsFrame): ReadBuffer {
        val header = frame.header
        val payloadSize = header.payloadLength.toInt()
        val buffer = bufferFactory.allocate(header.wireSize + payloadSize) as PlatformBuffer

        val writePayload: (com.ditchoom.buffer.WriteBuffer, Any?) -> Unit = { buf, payload ->
            if (payload is ReadBuffer) buf.write(payload)
        }

        WsFrameCodec.encode(
            buffer, frame,
            encodeBinaryPayload = writePayload,
            encodeContinuationPayload = writePayload,
            encodePingPayload = writePayload,
            encodePongPayload = writePayload,
            encodeTextPayload = writePayload,
        )

        // resetForRead sets limit = written position, position = 0
        buffer.resetForRead()

        // Post-encode: XOR mask the payload region in-place
        if (header.maskingKey != null && payloadSize > 0) {
            buffer.position(header.wireSize)
            buffer.xorMask(header.maskingKey!!.raw.toInt())
            buffer.position(0)
        }

        return buffer
    }

    // ──────────────────────── Transport ────────────────────────

    private suspend fun sendCloseFrame(
        code: UShort,
        reason: String = "",
    ) {
        try {
            val frameBuffer = writeCloseFrame(code, reason)
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
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
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
        try {
            transport.close()
        } catch (_: Exception) {}
        try {
            codecJob.cancelAndJoin()
        } catch (_: Exception) {}
        (bufferFactory as? com.ditchoom.buffer.pool.BufferPool)?.clear()
    }
}
