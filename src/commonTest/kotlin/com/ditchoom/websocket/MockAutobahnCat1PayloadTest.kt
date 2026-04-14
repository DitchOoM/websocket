package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec
import com.ditchoom.websocket.codecs.StringCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Mock Autobahn Category 1: Payload sizes.
 *
 * Tests text and binary message reception at length-encoding boundaries:
 * - 0 bytes (empty)
 * - 125 bytes (max 1-byte length)
 * - 126 bytes (min 2-byte/16-bit length)
 * - 65535 bytes (max 16-bit length)
 * - 65536 bytes (min 8-byte/64-bit length)
 *
 * In v2 the payload allocator is picked by the codec / connection options, no longer a
 * user parameter; the per-factory variants (Default/Managed/Deterministic/Shared/Pooled)
 * that existed in v1 retired — factory-matrix coverage lives in [AllocatorLeakTests] now.
 */
class MockAutobahnCat1PayloadTest {
    private fun testTextPayload(
        size: Int,
    ) = runStrictTest {
        val transport = MockWebSocketTransport()
        val connection = MockAutobahnHelpers.connectWithHandshake(transport, StringCodec)

        val text = "A".repeat(size)
        transport.enqueueRead(MockAutobahnHelpers.buildServerTextFrame(text))
        transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

        val msg =
            withTimeout(5.seconds) {
                connection.receive().first()
            }
        assertIs<WebSocketMessage.Text<String>>(msg)
        assertEquals(size, msg.payload.length)
        if (size <= 1024) assertEquals(text, msg.payload)
        connection.close()
    }

    private fun testBinaryPayload(
        size: Int,
    ) = runStrictTest {
        val transport = MockWebSocketTransport()
        val connection = MockAutobahnHelpers.connectWithHandshake(transport, BinaryPassThroughCodec)

        val payload = BufferFactory.Default.allocate(size)
        for (i in 0 until size) payload.writeByte((i % 256).toByte())
        payload.resetForRead()
        transport.enqueueRead(MockAutobahnHelpers.buildServerFrame(Opcode.Binary, payload))
        transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

        val msg =
            withTimeout(5.seconds) {
                connection.receive().first()
            }
        assertIs<WebSocketMessage.Binary<*>>(msg)
        @Suppress("UNCHECKED_CAST")
        val binary = msg as WebSocketMessage.Binary<com.ditchoom.buffer.ReadBuffer>
        assertEquals(size, binary.payload.remaining())
        connection.close()
    }

    // Text payloads at encoding boundaries
    @Test fun textEmpty() = testTextPayload(0)

    @Test fun text125Bytes() = testTextPayload(125)

    @Test fun text126Bytes() = testTextPayload(126)

    @Test fun text127Bytes() = testTextPayload(127)

    @Test fun text128Bytes() = testTextPayload(128)

    @Test fun text65535Bytes() = testTextPayload(65535)

    @Test fun text65536Bytes() = testTextPayload(65536)

    // Binary payloads at encoding boundaries
    @Test fun binaryEmpty() = testBinaryPayload(0)

    @Test fun binary125Bytes() = testBinaryPayload(125)

    @Test fun binary126Bytes() = testBinaryPayload(126)

    @Test fun binary65535Bytes() = testBinaryPayload(65535)

    @Test fun binary65536Bytes() = testBinaryPayload(65536)
}
