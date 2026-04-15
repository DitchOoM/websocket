package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.managed
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Full echo round-trip through WebSocketCodec with compression.
 * Isolates the Autobahn cat-12 pattern: server sends compressed message,
 * client decompresses and receives it.
 */
class CompressionEchoTest {
    private fun testCompressedTextEcho(size: Int) = runStrictTest {
        val transport = MockWebSocketTransport()
        val connection = MockAutobahnHelpers.connectWithCompressionHandshake(
            transport,
            bufferFactory = BufferFactory.managed(),
        )

        // Build compressed server frame
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val text = "A".repeat(size)
        val textPayload = BufferFactory.Default.allocate(size)
        textPayload.writeString(text, Charset.UTF8)
        textPayload.resetForRead()
        transport.enqueueRead(
            MockAutobahnHelpers.buildServerCompressedFrame(textPayload, Opcode.Text, compressor),
        )
        transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))
        compressor.close()

        val msg = withTimeout(10.seconds) { connection.receive().first() }
        assertIs<WebSocketMessage.Text>(msg)
        assertEquals(size, msg.payload.length, "Text echo failed at $size bytes: got ${msg.payload.length}")
        connection.close()
    }

    private fun testCompressedBinaryEcho(size: Int) = runStrictTest {
        val transport = MockWebSocketTransport()
        val connection = MockAutobahnHelpers.connectWithCompressionHandshake(
            transport,
            BinaryPassThroughCodec,
            bufferFactory = BufferFactory.managed(),
        )

        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val payload = BufferFactory.Default.allocate(size)
        repeat(size) { payload.writeByte((it % 251).toByte()) }
        payload.resetForRead()
        transport.enqueueRead(
            MockAutobahnHelpers.buildServerCompressedFrame(payload, Opcode.Binary, compressor),
        )
        transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))
        compressor.close()

        val msg = withTimeout(10.seconds) { connection.receive().first() }
        assertIs<WebSocketMessage.Binary<*>>(msg)
        @Suppress("UNCHECKED_CAST")
        val binary = msg as WebSocketMessage.Binary<ReadBuffer>
        assertEquals(size, binary.payload.remaining(), "Binary echo failed at $size bytes: got ${binary.payload.remaining()}")
        connection.close()
    }

    // Size sweep
    @Test fun compressedText1KB() = testCompressedTextEcho(1024)
    @Test fun compressedText16KB() = testCompressedTextEcho(16384)
    @Test fun compressedText32KB() = testCompressedTextEcho(32768)
    @Test fun compressedText64KB() = testCompressedTextEcho(65536)
    @Test fun compressedText128KB() = testCompressedTextEcho(131072)

    @Test fun compressedBinary32KB() = testCompressedBinaryEcho(32768)
    @Test fun compressedBinary64KB() = testCompressedBinaryEcho(65536)
    @Test fun compressedBinary128KB() = testCompressedBinaryEcho(131072)
}
