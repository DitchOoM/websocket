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
import com.ditchoom.buffer.utf8ByteCount
import com.ditchoom.buffer.stream.AutoFillingSuspendingStreamProcessor
import com.ditchoom.buffer.stream.EndOfStreamException
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.websocket.codecs.StringCodec
import com.ditchoom.websocket.frame.AssembledMessage
import com.ditchoom.websocket.internal.GrowableWriteBuffer
import com.ditchoom.websocket.frame.AssemblyResult
import com.ditchoom.websocket.frame.CloseCode
import com.ditchoom.websocket.frame.FrameHeaderByte1
import com.ditchoom.websocket.frame.MessageAssembler
import com.ditchoom.websocket.frame.WsCloseBody
import com.ditchoom.websocket.frame.WsCloseBodyCodec
import com.ditchoom.websocket.frame.WsFrame
import com.ditchoom.websocket.frame.WsFrameCodec
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
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Couples a decoded [WebSocketMessage] with the library-owned raw payload buffer
 * that backs it (if any). The Flow emitted from [WebSocketCodec.receive] frees
 * [cleanup] after the collector's lambda returns — so zero-copy payload decoders
 * (e.g. `BinaryPassThroughCodec.decode = this`) can return a view of the raw frame
 * buffer without burdening the user with manual free().
 */
private data class MessageEnvelope<B>(
    val message: WebSocketMessage<B>,
    val cleanup: ReadBuffer? = null,
)

/**
 * WebSocket codec connection over a [ByteStream].
 *
 * Uses [WsFrameHeaderCodec] to parse frame headers, then constructs [WsFrame]
 * variants directly from the raw buffer (zero-copy payload views). Fragment
 * assembly via [MessageAssembler].
 */
internal class WebSocketCodec<B>(
    private val transport: ByteStream,
    private val stream: AutoFillingSuspendingStreamProcessor,
    private val compression: CompressionConfig,
    private val bufferFactory: BufferFactory,
    private val binaryCodec: Codec<B>,
    private val readTimeout: Duration,
    private val writeTimeout: Duration,
    parentScope: CoroutineScope?,
    private val compressor: StreamingCompressor? = null,
    private val compressionEnabled: Boolean = false,
    private val resetCompressorPerMessage: Boolean = true,
) : Connection<WebSocketMessage<B>> {
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

    private val incomingMessageChannel = Channel<MessageEnvelope<B>>(Channel.UNLIMITED)

    /**
     * Emits received messages. For messages whose `payload` aliases a library-owned
     * frame buffer (e.g. [com.ditchoom.websocket.codecs.BinaryPassThroughCodec]), the
     * buffer is valid only within the collector's `emit` block — the library frees it
     * as soon as the collector's lambda returns. Copy bytes out if you need them later.
     */
    override fun receive(): Flow<WebSocketMessage<B>> =
        incomingMessageChannel.receiveAsFlow()
            .transform { env ->
                try {
                    emit(env.message)
                } finally {
                    env.cleanup?.freeIfNeeded()
                }
            }

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
     * Reads the next complete frame from the stream.
     *
     * Manual peekFrameSize: determines header size via [WsFrameHeaderCodec.peekFrameSize],
     * peeks payload length from byte2, waits for all bytes, then parses.
     *
     * **Ownership:** for data / ping / pong frames, the returned frame's `payload` is the
     * raw frame buffer itself, positioned at payload start with `limit()` set to payload
     * end. Ownership transfers to the caller (MessageAssembler or handleControlFrame),
     * which must eventually free it. On decode failure or close-frame path the buffer is
     * freed here via the `owned` flag.
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

            // Zero-copy receive: decode the header, then construct the WsFrame with the
            // raw frame buffer itself as the payload (position at payload start, limit
            // at payload end). Ownership moves out via the returned frame.
            val buffer = stream.readBuffer(totalFrameSize)
            var owned = true
            val frame = try {
                val header = WsFrameHeaderCodec.decode(buffer)
                // buffer.position() is now at payload start; buffer.limit() is at frame end.
                val payloadStart = buffer.position()
                val payloadEnd = payloadStart + header.payloadLength.toInt()
                buffer.setLimit(payloadEnd)

                when (val op = header.byte1.opcode) {
                    Opcode.Text -> {
                        owned = false
                        WsFrame.Text(header, buffer)
                    }
                    Opcode.Binary -> {
                        owned = false
                        WsFrame.Binary(header, buffer)
                    }
                    Opcode.Continuation -> {
                        owned = false
                        WsFrame.Continuation(header, buffer)
                    }
                    Opcode.Ping -> {
                        owned = false
                        WsFrame.Ping(header, buffer)
                    }
                    Opcode.Pong -> {
                        owned = false
                        WsFrame.Pong(header, buffer)
                    }
                    Opcode.Close -> {
                        val body =
                            if (buffer.remaining() >= 2) {
                                WsCloseBodyCodec.decode(buffer, DecodeContext.Empty)
                            } else {
                                null
                            }
                        // Close body was fully read; buffer can be freed in the finally.
                        WsFrame.Close(header, body)
                    }
                    else -> throw IllegalArgumentException("Reserved opcode: $op")
                }
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
            } finally {
                if (owned) buffer.freeIfNeeded()
            }

            // Post-decode validation: close frame reason must be valid UTF-8.
            // If readString replaced invalid bytes, re-encoding produces different bytes
            // than the original wire payload (payloadLength - 2 bytes for status code).
            @Suppress("USELESS_IS_CHECK")
            if (frame is WsFrame.Close && frame.body != null && frame.header.payloadLength > 2) {
                val reasonByteCount = frame.body.reason.utf8ByteCount()
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
                val payloadStart = payload.position()
                // Echo payload bytes back as Pong (RFC 6455 requires identical application data).
                writePongFrameBytes(if (frame.header.payloadLength > 0) payload else EMPTY_BUFFER)
                // Decode payload as UTF-8 String for the user-facing Ping message.
                // RFC 6455 caps control frame app data at 125 bytes; it's usually empty or a small
                // identifier, so a best-effort UTF-8 decode is fine. If bytes aren't UTF-8, we
                // still pass through without throwing (readString replaces invalid sequences).
                payload.position(payloadStart)
                val appData =
                    if (frame.header.payloadLength > 0) {
                        payload.readString(payload.remaining(), Charset.UTF8)
                    } else {
                        ""
                    }
                payload.freeIfNeeded()
                incomingMessageChannel.trySend(MessageEnvelope(WebSocketMessage.Ping(appData)))
            }
            is WsFrame.Pong<*> -> {
                val payload = frame.payload as ReadBuffer
                val appData =
                    if (frame.header.payloadLength > 0) {
                        payload.readString(payload.remaining(), Charset.UTF8)
                    } else {
                        ""
                    }
                payload.freeIfNeeded()
                incomingMessageChannel.trySend(MessageEnvelope(WebSocketMessage.Pong(appData)))
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

                incomingMessageChannel.trySend(
                    MessageEnvelope(WebSocketMessage.Close(closeCode.code, reason)),
                )
                closed = true
            }
            else -> {}
        }
    }

    /**
     * Decodes an assembled data message and enqueues it with the raw payload buffer
     * as the cleanup target. Text frames are decoded via the internal [StringCodec]
     * (RFC 6455 §5.6 guarantees UTF-8). Binary frames use the user-supplied
     * [binaryCodec]. The Flow.transform in [receive] frees `cleanup` after the user's
     * collector lambda returns — letting `BinaryPassThroughCodec` alias the raw buffer
     * without the user ever needing to manage its lifetime.
     */
    private suspend fun emitMessage(message: AssembledMessage) {
        val opcode = message.opcode
        if (opcode != Opcode.Text && opcode != Opcode.Binary) return

        val rawPayload =
            try {
                if (message.compressed && compression is CompressionConfig.Enabled) {
                    val decompressed = decompressPayload(message.payload)
                    if (decompressed !== message.payload) message.payload.freeIfNeeded()
                    decompressed
                } else {
                    message.payload
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Throwable,
            ) {
                sendCloseFrame(CloseCode.INVALID_PAYLOAD.code, "Decompression failed")
                message.payload.freeIfNeeded()
                return
            }

        val wsMessage: WebSocketMessage<B> =
            try {
                if (opcode == Opcode.Text) {
                    WebSocketMessage.Text(StringCodec.decode(rawPayload, DecodeContext.Empty))
                } else {
                    WebSocketMessage.Binary(binaryCodec.decode(rawPayload, DecodeContext.Empty))
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Throwable,
            ) {
                sendCloseFrame(CloseCode.INVALID_PAYLOAD.code, "Payload decode failed")
                rawPayload.freeIfNeeded()
                return
            }
        // rawPayload is freed after the user's `.collect { }` lambda returns (see receive()).
        // For value-returning decoders (e.g. StringCodec), this just defers the free by one
        // coroutine hop — harmless. For BinaryPassThroughCodec.decode-returns-`this`, this
        // is what keeps the aliased buffer valid during the collector's handler.
        incomingMessageChannel.trySend(MessageEnvelope(wsMessage, cleanup = rawPayload))
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

    override suspend fun send(message: WebSocketMessage<B>) {
        if (closed) {
            throw WebSocketException.ConnectionClosed("Cannot send: not connected")
        }
        val frameBuffer =
            when (message) {
                is WebSocketMessage.Text -> writeDataFrameFromCodec(Opcode.Text, message.payload, StringCodec)
                is WebSocketMessage.Binary -> writeDataFrameFromCodec(Opcode.Binary, message.payload, binaryCodec)
                is WebSocketMessage.Ping -> writePingFrame(message.appData)
                is WebSocketMessage.Pong -> encodePongFrame(message.appData)
                is WebSocketMessage.Close -> writeCloseFrame(message.code, message.reason)
            }
        writeToTransport(frameBuffer)
        frameBuffer.freeIfNeeded()
    }

    /**
     * Encodes a user-supplied payload [value] via [codec] into a scratch buffer and
     * returns the wire frame bytes ready for transmission.
     *
     * **Uncompressed fast path** (default): reserves 14 bytes at offset 0 for the worst-case
     * RFC 6455 header (byte1 + byte2 + 8-byte extended length + 4-byte mask), lets the codec
     * write the payload starting at offset 14, then backpatches the actual header bytes into
     * the reserved prefix (right-aligned at offset `14 - headerSize`) and XOR-masks the
     * payload in place. This eliminates the scratch→frame copy — a 1MB × 1000 msg batch
     * saves ~1GB of memcpy per connection.
     *
     * **Compressed path**: compressed size isn't known ahead of time, so we stage payload
     * bytes into a scratch buffer, compress, and go through [encodeDataFrame] (which allocates
     * a fresh frame buffer and writes header + compressed payload). The scratch→compressed
     * copy is unavoidable without a custom streaming compressor.
     */
    private fun <T> writeDataFrameFromCodec(
        opcode: Opcode,
        value: T,
        codec: Codec<T>,
        fin: Boolean = true,
    ): ReadBuffer {
        val shouldCompress = compressionEnabled && !opcode.isControlFrame()

        if (shouldCompress && compressor != null) {
            // Compressed path: keep the existing staging flow.
            val scratch = GrowableWriteBuffer(bufferFactory, initialSize = 256)
            codec.encode(scratch, value, EncodeContext.Empty)
            val inner = scratch.underlying
            val size = inner.position()
            if (size == 0) {
                inner.freeIfNeeded()
                return encodeDataFrame(opcode, EMPTY_BUFFER, fin)
            }
            inner.position(0)
            inner.setLimit(size)
            val frame = encodeDataFrame(opcode, inner, fin)
            inner.freeIfNeeded()
            return frame
        }

        // Uncompressed fast path: reserved prefix + backpatch.
        val scratch = GrowableWriteBuffer(bufferFactory, initialSize = 256)
        scratch.position(HEADER_PREFIX_BYTES)
        codec.encode(scratch, value, EncodeContext.Empty)
        val payloadEnd = scratch.position()
        val payloadSize = payloadEnd - HEADER_PREFIX_BYTES
        val inner = scratch.underlying

        val header = buildMaskedHeader(fin, rsv1 = false, opcode, payloadSize)
        val headerSize = header.wireSize
        val headerStart = HEADER_PREFIX_BYTES - headerSize

        // Write header bytes so they end exactly at offset HEADER_PREFIX_BYTES.
        inner.position(headerStart)
        WsFrameHeaderCodec.encode(inner, header, EncodeContext.Empty)

        // XOR-mask the payload in place. xorMask uses [position, limit) and does not
        // advance position, so we set them to the payload window.
        if (payloadSize > 0) {
            inner.position(HEADER_PREFIX_BYTES)
            inner.setLimit(payloadEnd)
            inner.xorMask(header.maskingKey!!.raw.toInt())
        }

        // Expose the wire window: position = header start, limit = payload end.
        inner.position(headerStart)
        inner.setLimit(payloadEnd)
        return inner
    }

    private fun writeCloseFrame(
        statusCode: UShort? = null,
        reason: String? = null,
    ): ReadBuffer {
        val body = if (statusCode != null) {
            val truncated = if (reason != null && reason.length > 123) reason.substring(0, 123) else reason
            // Cap reason bytes at 123 (RFC 6455: control payload ≤ 125, minus 2 for status code).
            val reasonStr = truncated ?: ""
            val cappedReason = if (reasonStr.utf8ByteCount() > 123) reasonStr.substring(0, 123) else reasonStr
            WsCloseBody(CloseCode(statusCode), cappedReason)
        } else {
            null
        }
        val payloadSize = if (body != null) 2 + body.reason.utf8ByteCount() else 0
        return encodeWsFrame(
            WsFrame.Close(
                header = buildMaskedHeader(fin = true, rsv1 = false, Opcode.Close, payloadSize),
                body = body,
            ),
        )
    }

    private fun writePingFrame(appData: String = ""): ReadBuffer =
        encodeControlFrameWithAppData(Opcode.Ping, appData) { header, payload ->
            WsFrame.Ping(header = header, payload = payload)
        }

    /** Echoes raw payload bytes (used by ping handler where the RFC requires identical app data). */
    private suspend fun writePongFrameBytes(payloadData: ReadBuffer) {
        val truncated = if (payloadData.remaining() > 125) payloadData.readBytes(125) else payloadData
        val frame =
            encodeWsFrame(
                WsFrame.Pong(
                    header = buildMaskedHeader(fin = true, rsv1 = false, Opcode.Pong, truncated.remaining()),
                    payload = truncated,
                ),
            )
        writeToTransport(frame)
        frame.freeIfNeeded()
    }

    private fun encodePongFrame(appData: String = ""): ReadBuffer =
        encodeControlFrameWithAppData(Opcode.Pong, appData) { header, payload ->
            WsFrame.Pong(header = header, payload = payload)
        }

    private inline fun encodeControlFrameWithAppData(
        opcode: Opcode,
        appData: String,
        build: (WsFrameHeader, ReadBuffer) -> WsFrame,
    ): ReadBuffer {
        if (appData.isEmpty()) {
            return encodeWsFrame(
                build(buildMaskedHeader(fin = true, rsv1 = false, opcode, 0), EMPTY_BUFFER),
            )
        }
        // Pessimistic UTF-8 allocation (3 bytes/char BMP); real size measured after write.
        val scratch = bufferFactory.allocate(appData.length * 3) as PlatformBuffer
        scratch.writeString(appData, Charset.UTF8)
        scratch.resetForRead()
        // RFC 6455 caps control-frame application data at 125 bytes.
        val truncated = if (scratch.remaining() > 125) scratch.readBytes(125) else scratch
        val frame =
            encodeWsFrame(
                build(buildMaskedHeader(fin = true, rsv1 = false, opcode, truncated.remaining()), truncated),
            )
        scratch.freeIfNeeded()
        return frame
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
            try {
                val compressedSize = totalRemaining(chunks)

                // With context takeover (!resetCompressorPerMessage), always send compressed
                // even if larger. Falling back to uncompressed would desync the LZ77 windows.
                if (compressedSize < originalSize || !resetCompressorPerMessage) {
                    if (resetCompressorPerMessage) compressor.reset()
                    val combined = combineChunks(chunks, bufferFactory)
                    try {
                        val frame = buildDataWsFrame(opcode, fin, rsv1 = true, combined)
                        return encodeWsFrame(frame)
                    } finally {
                        combined.freeIfNeeded()
                    }
                } else {
                    payload.resetForRead()
                    compressor.reset()
                }
            } finally {
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

    private companion object {
        /**
         * Maximum RFC 6455 client header size: byte1(1) + byte2(1) + 8-byte extended
         * length + 4-byte mask = 14 bytes. The uncompressed send path reserves this
         * prefix in a scratch buffer so the real header (6, 8, or 14 bytes) can be
         * right-aligned into the prefix after the payload is written.
         */
        const val HEADER_PREFIX_BYTES = 14
    }
}
