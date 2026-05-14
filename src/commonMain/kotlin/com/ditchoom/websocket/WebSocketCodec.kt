package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.stream.AutoFillingSuspendingStreamProcessor
import com.ditchoom.buffer.stream.EndOfStreamException
import com.ditchoom.websocket.codecs.StringCodec
import com.ditchoom.websocket.codecs.WsBufferFactoryKey
import com.ditchoom.websocket.frame.AssembledMessage
import com.ditchoom.websocket.frame.AssemblyResult
import com.ditchoom.websocket.frame.BufferPayload
import com.ditchoom.websocket.frame.BufferPayloadCodec
import com.ditchoom.websocket.frame.CloseCode
import com.ditchoom.websocket.frame.FrameHeaderByte1
import com.ditchoom.websocket.frame.MessageAssembler
import com.ditchoom.websocket.frame.WsCloseBody
import com.ditchoom.websocket.frame.WsFrame
import com.ditchoom.websocket.frame.WsFrameCodec
import com.ditchoom.websocket.frame.WsFrameHeader
import com.ditchoom.websocket.frame.WsFrameHeaderCodec
import com.ditchoom.websocket.frame.WsFraming
import com.ditchoom.websocket.frame.WsMaskingKey
import com.ditchoom.websocket.frame.headerWireSize
import com.ditchoom.websocket.frame.maskingKey
import com.ditchoom.websocket.frame.payloadLength
import com.ditchoom.websocket.frame.toBinaryFrame
import com.ditchoom.websocket.frame.toCloseFrame
import com.ditchoom.websocket.frame.toContinuationFrame
import com.ditchoom.websocket.frame.toPingFrame
import com.ditchoom.websocket.frame.toPongFrame
import com.ditchoom.websocket.frame.toTextFrame
import com.ditchoom.websocket.internal.GrowableWriteBuffer
import com.ditchoom.websocket.internal.truncateUtf8
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

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

    /**
     * Per-frame codec parameterized to the wire-side payload type ([BufferPayload]).
     * The user-supplied [binaryCodec] / [StringCodec] runs *after* fragment reassembly
     * (in [emitMessage]) — fragments may split mid-codepoint or mid-record, so user
     * decode happens against the assembled message, not per-frame.
     */
    private val frameCodec: WsFrameCodec<BufferPayload> = WsFrameCodec(BufferPayloadCodec)

    /**
     * Decode context used for user-codec invocations inside [emitMessage]. Carries
     * the connection's [bufferFactory] under [WsBufferFactoryKey] so codecs like
     * [com.ditchoom.websocket.codecs.BinaryPassThroughCodec] can allocate consumer-owned
     * destination buffers from the same allocator the wire path uses.
     */
    private val userDecodeContext: DecodeContext = DecodeContext.Empty.with(WsBufferFactoryKey, bufferFactory)

    private val incomingMessageChannel = Channel<WebSocketMessage<B>>(Channel.UNLIMITED)

    /**
     * Emits received messages. The library runs the user-supplied [binaryCodec] inside
     * the read loop and frees the raw payload buffer immediately after decode — emitted
     * values are fully decoupled from library-owned buffers, so the consumer never has
     * to worry about buffer lifetimes.
     */
    override fun receive(): Flow<WebSocketMessage<B>> = incomingMessageChannel.receiveAsFlow()

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
                        is AssemblyResult.CompleteMessage -> emitMessage(result.message)
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
     * Frame size is peeked via [WsFraming.peekFrameSize], which delegates to the
     * variable-length header walk and adds the payload length encoded in byte 2 (or its
     * 16/64-bit extension). Once the full frame is buffered we hand the bounded slice to
     * [frameCodec] (a `WsFrameCodec(BufferPayloadCodec)`) — the generated codec reads
     * the header, dispatches on opcode, and aliases the payload window via
     * [BufferPayloadCodec.decode] for data / ping / pong variants (zero-copy). The
     * structured close body is decoded by the generated [com.ditchoom.websocket.frame.CloseCodec].
     *
     * **Ownership:** for data / ping / pong frames the returned frame's
     * `payload.buffer` aliases the raw frame buffer (position = payload start,
     * limit = payload end). Ownership transfers to the caller (MessageAssembler or
     * handleControlFrame), which must eventually free it. On decode failure or
     * close-frame path the buffer is freed here via the `owned` flag.
     */
    private suspend fun readNextFrame(): WsFrame<BufferPayload>? {
        while (coroutineContext.isActive) {
            if (stream.available() < 2) {
                stream.peekByte(stream.available())
                continue
            }

            val totalFrameSize =
                when (val peek = WsFraming.peekFrameSize(stream, 0)) {
                    is PeekResult.NeedsMoreData -> {
                        stream.peekByte(stream.available())
                        continue
                    }
                    is PeekResult.Complete -> peek.bytes
                    PeekResult.NoFraming ->
                        error("WsFraming.peekFrameSize must not return NoFraming")
                }

            if (stream.available() < totalFrameSize) {
                stream.peekByte(stream.available())
                continue
            }

            val buffer = stream.readBuffer(totalFrameSize)
            var owned = true
            val frame =
                try {
                    val decoded = frameCodec.decode(buffer, DecodeContext.Empty)
                    if (decoded !is WsFrame.Close) {
                        // Data / Ping / Pong: payload aliases the frame buffer; transfer
                        // ownership downstream so MessageAssembler / handleControlFrame
                        // can read from it before freeing.
                        owned = false
                    }
                    decoded
                } catch (e: com.ditchoom.buffer.codec.DecodeException) {
                    val isReservedOpcode = e.fieldPath == "WsFrame.discriminator"
                    val (code, reason) =
                        if (isReservedOpcode) {
                            CloseCode.PROTOCOL_ERROR to "Reserved opcode"
                        } else {
                            CloseCode.INVALID_PAYLOAD to "Invalid payload data"
                        }
                    sendCloseFrame(code.code, reason)
                    closed = true
                    return null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") _: Throwable,
                ) {
                    sendCloseFrame(CloseCode.INVALID_PAYLOAD.code, "Invalid payload data")
                    closed = true
                    return null
                } finally {
                    if (owned) buffer.freeIfNeeded()
                }

            // Malformed UTF-8 in the close reason is rejected at the codec layer
            // by Charset.UTF8 strict decode — the catch block above turns that
            // into a CloseCode.INVALID_PAYLOAD frame. No second post-decode
            // round-trip check is needed (and it would walk the string a third
            // time, after decode and before re-emit).

            return frame
        }
        return null
    }

    private suspend fun handleControlFrame(frame: WsFrame<BufferPayload>) {
        when (frame) {
            is WsFrame.Ping<BufferPayload> -> {
                val payload = frame.payload.buffer
                val payloadStart = payload.position()
                // Echo payload bytes back as Pong (RFC 6455 requires identical application data).
                writePongFrameBytes(if (frame.payloadLength > 0) payload else EMPTY_BUFFER)
                // Decode payload as UTF-8 String for the user-facing Ping message.
                // RFC 6455 §5.5.2 treats control-frame application data as opaque bytes — Autobahn
                // category 2 probes binary (non-UTF-8) ping payloads. JVM's readString is strict
                // (CodingErrorAction.REPORT), so wrap in try/catch and fall through with "" rather
                // than killing the read loop mid-close-handshake.
                payload.position(payloadStart)
                val appData =
                    if (frame.payloadLength > 0) {
                        try {
                            payload.readString(payload.remaining(), Charset.UTF8)
                        } catch (_: Throwable) {
                            ""
                        }
                    } else {
                        ""
                    }
                payload.freeIfNeeded()
                incomingMessageChannel.trySend(WebSocketMessage.Ping(appData))
            }
            is WsFrame.Pong<BufferPayload> -> {
                val payload = frame.payload.buffer
                val appData =
                    if (frame.payloadLength > 0) {
                        try {
                            payload.readString(payload.remaining(), Charset.UTF8)
                        } catch (_: Throwable) {
                            ""
                        }
                    } else {
                        ""
                    }
                payload.freeIfNeeded()
                incomingMessageChannel.trySend(WebSocketMessage.Pong(appData))
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
                    WebSocketMessage.Close(closeCode.code, reason),
                )
                closed = true
            }
            else -> {}
        }
    }

    /**
     * Decodes an assembled data message, frees the raw payload buffer, and enqueues
     * the typed [WebSocketMessage] for delivery. Text frames go through the internal
     * [StringCodec] (RFC 6455 §5.6 guarantees UTF-8); binary frames use the user-supplied
     * [binaryCodec]. The buffer is freed immediately after decode — consumer codecs MUST
     * produce an independent value (the standard `Decoder.decode` no-retain contract), so
     * no raw payload ever crosses the consumer boundary in the emitted [WebSocketMessage].
     */
    private suspend fun emitMessage(message: AssembledMessage) {
        val opcode = message.opcode
        if (opcode != Opcode.Text && opcode != Opcode.Binary) {
            message.payload.freeIfNeeded()
            return
        }

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
                    WebSocketMessage.Text(StringCodec.decode(rawPayload, userDecodeContext))
                } else {
                    WebSocketMessage.Binary(binaryCodec.decode(rawPayload, userDecodeContext))
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Throwable,
            ) {
                sendCloseFrame(CloseCode.INVALID_PAYLOAD.code, "Payload decode failed")
                rawPayload.freeIfNeeded()
                return
            }
        rawPayload.freeIfNeeded()
        incomingMessageChannel.trySend(wsMessage)
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
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun decompressPayloadToString(payload: ReadBuffer): String {
        val config =
            compression as? CompressionConfig.Enabled
                ?: return payload.readString(payload.remaining(), Charset.UTF8)
        return try {
            decompressToStringSync(payload, config.decompressor, stringDecoder)
        } catch (e: Throwable) {
            throw e
        } finally {
            if (config.serverNoContextTakeover) {
                try {
                    config.decompressor.reset()
                } catch (_: Exception) {
                }
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
        // RFC 6455 §5.5: control-frame payload ≤ 125 bytes; minus the 2-byte status
        // code ⇒ ≤ 123 bytes for the reason. truncateUtf8 walks the reason once,
        // returns a codepoint-aligned prefix that fits in 123 UTF-8 bytes, and
        // surfaces that prefix's exact byte count — feeding both the WsCloseBody
        // and the wire-header sizing without a second walk.
        val truncation = (reason ?: "").truncateUtf8(123)
        val body =
            if (statusCode != null) {
                WsCloseBody(CloseCode(statusCode), truncation.text)
            } else {
                null
            }
        val payloadSize = if (body != null) 2 + truncation.byteSize else 0
        return encodeWsFrame(
            buildMaskedHeader(fin = true, rsv1 = false, Opcode.Close, payloadSize)
                .toCloseFrame(body),
        )
    }

    private fun writePingFrame(appData: String = ""): ReadBuffer =
        encodeControlFrameWithAppData(Opcode.Ping, appData) { header, payload ->
            header.toPingFrame(payload)
        }

    /** Echoes raw payload bytes (used by ping handler where the RFC requires identical app data). */
    private suspend fun writePongFrameBytes(payloadData: ReadBuffer) {
        val truncated = if (payloadData.remaining() > 125) payloadData.readBytes(125) else payloadData
        val frame =
            encodeWsFrame(
                buildMaskedHeader(fin = true, rsv1 = false, Opcode.Pong, truncated.remaining())
                    .toPongFrame(BufferPayload(truncated)),
            )
        writeToTransport(frame)
        frame.freeIfNeeded()
    }

    private fun encodePongFrame(appData: String = ""): ReadBuffer =
        encodeControlFrameWithAppData(Opcode.Pong, appData) { header, payload ->
            header.toPongFrame(payload)
        }

    private inline fun encodeControlFrameWithAppData(
        opcode: Opcode,
        appData: String,
        build: (WsFrameHeader, BufferPayload) -> WsFrame<BufferPayload>,
    ): ReadBuffer {
        if (appData.isEmpty()) {
            return encodeWsFrame(
                build(buildMaskedHeader(fin = true, rsv1 = false, opcode, 0), BufferPayload(EMPTY_BUFFER)),
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
                build(buildMaskedHeader(fin = true, rsv1 = false, opcode, truncated.remaining()), BufferPayload(truncated)),
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

    private fun buildDataWsFrame(
        opcode: Opcode,
        fin: Boolean,
        rsv1: Boolean,
        payload: ReadBuffer,
    ): WsFrame<BufferPayload> {
        val header = buildMaskedHeader(fin, rsv1, opcode, payload.remaining())
        val wrapped = BufferPayload(payload)
        return when (opcode) {
            Opcode.Text -> header.toTextFrame(wrapped)
            Opcode.Binary -> header.toBinaryFrame(wrapped)
            Opcode.Continuation -> header.toContinuationFrame(wrapped)
            else -> header.toBinaryFrame(wrapped)
        }
    }

    private fun buildMaskedHeader(
        fin: Boolean,
        rsv1: Boolean,
        opcode: Opcode,
        payloadSize: Int,
    ): WsFrameHeader =
        WsFrameHeader.build(
            byte1 = FrameHeaderByte1.pack(fin, rsv1, rsv2 = false, rsv3 = false, opcode),
            payloadSize = payloadSize.toLong(),
            masked = true,
            maskingKey = WsMaskingKey(MaskingKey.FourByteMaskingKey().packed.toUInt()),
        )

    /**
     * Unified encode: lets [frameCodec] (`WsFrameCodec(BufferPayloadCodec)`) write
     * header + payload bytes for all frame types (including Close, whose CloseCodec
     * doesn't consult the payload codec), then XOR-masks the payload region in-place.
     */
    private fun encodeWsFrame(frame: WsFrame<BufferPayload>): ReadBuffer {
        val payloadSize = frame.payloadLength.toInt()
        val headerSize = frame.headerWireSize
        val buffer = bufferFactory.allocate(headerSize + payloadSize) as PlatformBuffer

        frameCodec.encode(buffer, frame, EncodeContext.Empty)

        // resetForRead sets limit = written position, position = 0
        buffer.resetForRead()

        // Post-encode: XOR mask the payload region in-place
        val maskingKey = frame.maskingKey
        if (maskingKey != null && payloadSize > 0) {
            buffer.position(headerSize)
            buffer.xorMask(maskingKey.raw.toInt())
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
            } catch (_: Exception) {
            }
        }
        incomingMessageChannel.close()
        // Clean up compression resources
        val config = compression as? CompressionConfig.Enabled
        config?.compressor?.close()
        config?.decompressor?.close()
        // Close transport first to break pending I/O, then cancelAndJoin.
        try {
            transport.close()
        } catch (_: Exception) {
        }
        try {
            codecJob.cancelAndJoin()
        } catch (_: Exception) {
        }
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
