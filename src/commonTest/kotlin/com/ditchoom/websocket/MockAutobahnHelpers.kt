package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.compression.BufferAllocator
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.websocket.handshake.computeAcceptKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Shared helpers for mock Autobahn-equivalent tests.
 *
 * All frame builders write directly into buffers — no ByteArray intermediaries.
 * Buffers are left in write mode (position at end) since [MockWebSocketTransport.read]
 * calls `resetForRead()` before copying.
 */
internal object MockAutobahnHelpers {
    private val factory = BufferFactory.Default

    val defaultOptions =
        WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            tls = false,
            websocketEndpoint = "/ws",
        )

    val compressionOptions =
        WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            tls = false,
            websocketEndpoint = "/ws",
            requestCompression = true,
            compressionOptions = CompressionOptions(
                clientNoContextTakeover = true,
                serverNoContextTakeover = true,
            ),
        )

    // ========================================================================
    // Client creation & handshake
    // ========================================================================

    fun createClient(
        transport: MockWebSocketTransport,
        options: WebSocketConnectionOptions = defaultOptions,
        bufferFactory: BufferFactory = BufferFactory.managed(),
        pool: BufferPool? = null,
    ): DefaultWebSocketClient =
        DefaultWebSocketClient(
            transport = transport,
            connectionOptions = options,
            parentScope = null,
            bufferFactory = bufferFactory,
            externalPool = pool,
        )

    suspend fun connectWithHandshake(
        client: DefaultWebSocketClient,
        transport: MockWebSocketTransport,
    ) = coroutineScope {
        val connectJob =
            async {
                client.connect()
            }
        waitForWrite(transport)
        val clientKey = MockHandshakeHelper.extractClientKey(transport.writtenBuffers[0])
        transport.enqueueRead(MockHandshakeHelper.buildHandshakeResponse(clientKey))
        withTimeout(5.seconds) { connectJob.await() }
    }

    suspend fun connectWithCompressionHandshake(
        client: DefaultWebSocketClient,
        transport: MockWebSocketTransport,
    ) = coroutineScope {
        val connectJob =
            async {
                client.connect()
            }
        waitForWrite(transport)
        val clientKey = MockHandshakeHelper.extractClientKey(transport.writtenBuffers[0])
        transport.enqueueRead(buildCompressionHandshakeResponse(clientKey))
        withTimeout(5.seconds) { connectJob.await() }
    }

    suspend fun waitForWrite(
        transport: MockWebSocketTransport,
        count: Int = 1,
    ) {
        withTimeout(5.seconds) {
            while (transport.writtenBuffers.size < count) {
                delay(10)
            }
        }
    }

    // ========================================================================
    // Frame builders (all buffer-based)
    // ========================================================================

    /**
     * Builds a server frame with explicit RSV bit control.
     * Server-to-client frames are NOT masked per RFC 6455.
     */
    fun buildServerFrameWithRsv(
        opcode: Opcode,
        payload: ReadBuffer,
        fin: Boolean,
        rsv1: Boolean = false,
        rsv2: Boolean = false,
        rsv3: Boolean = false,
    ): ReadBuffer {
        val payloadSize = payload.remaining()
        var byte1 = opcode.value.toInt() and 0x0F
        if (fin) byte1 = byte1 or 0x80
        if (rsv1) byte1 = byte1 or 0x40
        if (rsv2) byte1 = byte1 or 0x20
        if (rsv3) byte1 = byte1 or 0x10

        val headerSize =
            when {
                payloadSize <= 125 -> 2
                payloadSize <= 65535 -> 4
                else -> 10
            }
        val buffer = factory.allocate(headerSize + payloadSize)
        buffer.writeByte(byte1.toByte())
        when {
            payloadSize <= 125 -> buffer.writeByte(payloadSize.toByte())
            payloadSize <= 65535 -> {
                buffer.writeByte(126.toByte())
                buffer.writeShort(payloadSize.toShort())
            }
            else -> {
                buffer.writeByte(127.toByte())
                buffer.writeLong(payloadSize.toLong())
            }
        }
        payload.position(0)
        buffer.write(payload)
        return buffer
    }

    /**
     * Builds a server frame from an opcode and payload buffer. No RSV bits set.
     */
    fun buildServerFrame(
        opcode: Opcode,
        payload: ReadBuffer,
        fin: Boolean = true,
    ): ReadBuffer = buildServerFrameWithRsv(opcode, payload, fin)

    /**
     * Builds a server text frame from a string.
     */
    fun buildServerTextFrame(
        text: String,
        fin: Boolean = true,
    ): ReadBuffer {
        val payload = factory.allocate(text.length * 4)
        payload.writeString(text, Charset.UTF8)
        payload.resetForRead()
        return buildServerFrame(Opcode.Text, payload, fin)
    }

    /**
     * Builds a server continuation frame.
     */
    fun buildServerContinuationFrame(
        payload: ReadBuffer,
        fin: Boolean = true,
    ): ReadBuffer = buildServerFrame(Opcode.Continuation, payload, fin)

    /**
     * Builds a server close frame with code and reason written directly into a buffer.
     */
    fun buildServerCloseFrame(
        code: UShort,
        reason: String = "",
    ): ReadBuffer {
        val reasonSize = reason.encodeToByteArray().size // need size for allocation
        val payload = factory.allocate(2 + reasonSize)
        payload.writeShort(code.toShort())
        if (reason.isNotEmpty()) {
            payload.writeString(reason, Charset.UTF8)
        }
        payload.resetForRead()
        return buildServerFrame(Opcode.Close, payload)
    }

    /**
     * Builds a server close frame from a raw payload buffer (for testing invalid close payloads).
     */
    fun buildServerCloseFrameRaw(payload: ReadBuffer): ReadBuffer =
        buildServerFrame(Opcode.Close, payload)

    /**
     * Splits text into a fragmented frame sequence.
     * Returns: Text(FIN=0) + Continuation(FIN=0)... + Continuation(FIN=1)
     */
    fun buildFragmentedTextFrames(
        text: String,
        chunkSize: Int,
    ): List<ReadBuffer> {
        val encoded = factory.allocate(text.length * 4)
        encoded.writeString(text, Charset.UTF8)
        encoded.resetForRead()
        val totalBytes = encoded.remaining()

        val frames = mutableListOf<ReadBuffer>()
        var offset = 0
        var isFirst = true

        while (offset < totalBytes) {
            val end = minOf(offset + chunkSize, totalBytes)
            val chunkBuf = factory.allocate(end - offset)
            val savedPos = encoded.position()
            encoded.position(offset)
            for (i in 0 until (end - offset)) {
                chunkBuf.writeByte(encoded.readByte())
            }
            encoded.position(savedPos)
            chunkBuf.resetForRead()

            val isLast = end >= totalBytes
            if (isFirst) {
                frames.add(buildServerFrame(Opcode.Text, chunkBuf, fin = isLast))
                isFirst = false
            } else {
                frames.add(buildServerContinuationFrame(chunkBuf, fin = isLast))
            }
            offset = end
        }
        return frames
    }

    /**
     * Splits binary data into a fragmented frame sequence.
     */
    fun buildFragmentedBinaryFrames(
        data: ReadBuffer,
        chunkSize: Int,
    ): List<ReadBuffer> {
        data.position(0)
        val totalBytes = data.remaining()

        val frames = mutableListOf<ReadBuffer>()
        var offset = 0
        var isFirst = true

        while (offset < totalBytes) {
            val end = minOf(offset + chunkSize, totalBytes)
            val chunkBuf = factory.allocate(end - offset)
            data.position(offset)
            for (i in 0 until (end - offset)) {
                chunkBuf.writeByte(data.readByte())
            }
            chunkBuf.resetForRead()

            val isLast = end >= totalBytes
            if (isFirst) {
                frames.add(buildServerFrame(Opcode.Binary, chunkBuf, fin = isLast))
                isFirst = false
            } else {
                frames.add(buildServerContinuationFrame(chunkBuf, fin = isLast))
            }
            offset = end
        }
        return frames
    }

    /**
     * Builds a compressed server frame (RSV1=1) using the given compressor.
     * Compresses the payload, strips the sync flush marker, and wraps in a frame.
     */
    fun buildServerCompressedFrame(
        payload: ReadBuffer,
        opcode: Opcode,
        compressor: StreamingCompressor,
    ): ReadBuffer {
        payload.position(0)
        val compressed = compressSync(payload, compressor)
        val combined = combineChunks(compressed)
        return buildServerFrameWithRsv(opcode, combined, fin = true, rsv1 = true)
    }

    /**
     * Builds a 101 handshake response with permessage-deflate negotiated.
     */
    fun buildCompressionHandshakeResponse(clientKey: String): ReadBuffer {
        val acceptKey = computeAcceptKey(clientKey)
        val response =
            buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: $acceptKey\r\n")
                append("Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover; client_no_context_takeover\r\n")
                append("\r\n")
            }
        val buf = factory.allocate(response.length)
        buf.writeString(response, Charset.UTF8)
        return buf
    }

    // ========================================================================
    // Verification helpers
    // ========================================================================

    /**
     * Parses the first close frame from writtenBuffers (skipping handshake at index 0).
     * Unmaskes the payload and extracts close code + reason.
     * Returns null if no close frame found.
     */
    fun parseClientCloseFrame(
        writtenBuffers: List<ReadBuffer>,
        startIndex: Int = 1,
    ): Pair<UInt, String>? {
        for (i in startIndex until writtenBuffers.size) {
            val buf = writtenBuffers[i]
            buf.position(0)
            if (buf.remaining() < 2) continue
            val byte1 = buf.readByte().toInt() and 0xFF
            val opcode = byte1 and 0x0F
            if (opcode != 0x08) continue // not a close frame

            val byte2 = buf.readByte().toInt() and 0xFF
            val masked = (byte2 and 0x80) != 0
            val payloadLen = byte2 and 0x7F
            if (payloadLen == 0) return 0u to ""

            if (!masked || buf.remaining() < 4 + payloadLen) continue

            val mask = BufferFactory.Default.allocate(4)
            for (j in 0 until 4) mask.writeByte(buf.readByte())
            mask.resetForRead()

            val payloadBuf = factory.allocate(payloadLen)
            for (j in 0 until payloadLen) {
                mask.position(j % 4)
                val maskByte = mask.readByte()
                val raw = buf.readByte()
                payloadBuf.writeByte((raw.toInt() xor maskByte.toInt()).toByte())
            }
            payloadBuf.resetForRead()

            if (payloadLen < 2) return 0u to ""
            val code = payloadBuf.readUnsignedShort().toUInt()
            val reason =
                if (payloadBuf.hasRemaining()) {
                    payloadBuf.readString(payloadBuf.remaining(), Charset.UTF8)
                } else {
                    ""
                }
            return code to reason
        }
        return null
    }

    fun assertClientSentClose(
        writtenBuffers: List<ReadBuffer>,
        expectedCode: UInt,
    ) {
        val close = parseClientCloseFrame(writtenBuffers)
        assertTrue(close != null, "Expected client to send a close frame")
        assertTrue(
            close.first == expectedCode,
            "Expected close code $expectedCode but got ${close.first}",
        )
    }

    // ========================================================================
    // UTF-8 test vectors (fresh buffers each call)
    // ========================================================================

    object Utf8Vectors {
        /**
         * Valid UTF-8 test sequences. Each returns a fresh buffer in read mode.
         */
        fun valid(): List<Pair<String, ReadBuffer>> =
            listOf(
                "ASCII" to bufferOf(0x48, 0x65, 0x6C, 0x6C, 0x6F), // "Hello"
                "2-byte Latin e-acute" to bufferOf(0xC3, 0xA9), // é (U+00E9)
                "2-byte boundary U+007F" to bufferOf(0x7F), // DEL
                "2-byte boundary U+0080" to bufferOf(0xC2, 0x80),
                "2-byte boundary U+07FF" to bufferOf(0xDF, 0xBF),
                "3-byte CJK" to bufferOf(0xE4, 0xB8, 0x96), // 世 (U+4E16)
                "3-byte boundary U+0800" to bufferOf(0xE0, 0xA0, 0x80),
                "3-byte boundary U+FFFF" to bufferOf(0xEF, 0xBF, 0xBF),
                "4-byte emoji" to bufferOf(0xF0, 0x9F, 0x98, 0x80), // 😀 (U+1F600)
                "4-byte boundary U+10000" to bufferOf(0xF0, 0x90, 0x80, 0x80),
                "4-byte boundary U+10FFFF" to bufferOf(0xF4, 0x8F, 0xBF, 0xBF),
            )

        /**
         * Invalid UTF-8 test sequences. Each returns a fresh buffer in read mode.
         */
        fun invalid(): List<Pair<String, ReadBuffer>> =
            listOf(
                "lone continuation 0x80" to bufferOf(0x80),
                "lone continuation 0xBF" to bufferOf(0xBF),
                "overlong 2-byte slash" to bufferOf(0xC0, 0xAF),
                "overlong 2-byte C1" to bufferOf(0xC1, 0xBF),
                "overlong 3-byte" to bufferOf(0xE0, 0x80, 0xAF),
                "overlong 4-byte" to bufferOf(0xF0, 0x80, 0x80, 0xAF),
                "surrogate U+D800" to bufferOf(0xED, 0xA0, 0x80),
                "surrogate U+DFFF" to bufferOf(0xED, 0xBF, 0xBF),
                "too high U+110000" to bufferOf(0xF4, 0x90, 0x80, 0x80),
                "truncated 2-byte" to bufferOf(0xC2),
                "truncated 3-byte" to bufferOf(0xE4, 0xB8),
                "truncated 4-byte" to bufferOf(0xF0, 0x9F, 0x98),
                "invalid start byte FE" to bufferOf(0xFE),
                "invalid start byte FF" to bufferOf(0xFF),
                "5-byte sequence F8" to bufferOf(0xF8, 0x80, 0x80, 0x80, 0x80),
            )

        /**
         * Builds a buffer from raw byte values (0x00-0xFF as Int for readability).
         * Writes each byte directly — no ByteArray intermediate.
         */
        private fun bufferOf(vararg bytes: Int): ReadBuffer {
            val buf = BufferFactory.Default.allocate(bytes.size)
            for (b in bytes) buf.writeByte(b.toByte())
            buf.resetForRead()
            return buf
        }
    }

    /**
     * Creates a new streaming compressor for building compressed server frames.
     */
    fun createCompressor(): StreamingCompressor =
        StreamingCompressor.create(
            CompressionAlgorithm.Raw,
            CompressionLevel.Default,
            BufferAllocator.Default,
        )
}
