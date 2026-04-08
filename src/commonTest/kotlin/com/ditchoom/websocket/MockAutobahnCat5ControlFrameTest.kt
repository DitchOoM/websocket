package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.shared
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Mock Autobahn Category 5: Control Frame Protocol.
 *
 * RFC 6455 Section 5.5: Tests control frame requirements:
 * - Control frames MUST have FIN set
 * - Control frames MUST NOT have payload > 125 bytes
 * - Control frames MAY be interleaved with fragmented messages
 * - RSV bits must not be set without extension negotiation
 */
abstract class AbstractMockAutobahnCat5Test {
    abstract val bufferFactory: BufferFactory
    open val pool: BufferPool? get() = null

    @Test
    fun fragmentedPingIsProtocolError() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(transport, bufferFactory = bufferFactory, pool = pool)
            MockAutobahnHelpers.connectWithHandshake(client, transport)

            // Ping with FIN=0 (0x09 instead of 0x89), length=0
            transport.enqueueReadBytes(0x09.toByte(), 0x00)

            withTimeout(5.seconds) { client.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1002u)
            client.close()
        }

    @Test
    fun fragmentedCloseIsProtocolError() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(transport, bufferFactory = bufferFactory, pool = pool)
            MockAutobahnHelpers.connectWithHandshake(client, transport)

            // Close with FIN=0 (0x08 instead of 0x88), length=0
            transport.enqueueReadBytes(0x08.toByte(), 0x00)

            withTimeout(5.seconds) { client.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1002u)
            client.close()
        }

    @Test
    fun oversizedPingPayloadIsProtocolError() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(transport, bufferFactory = bufferFactory, pool = pool)
            MockAutobahnHelpers.connectWithHandshake(client, transport)

            // Ping with 126-byte payload (exceeds 125 limit for control frames)
            val payload = BufferFactory.Default.allocate(126)
            for (i in 0 until 126) payload.writeByte(0x41)
            payload.resetForRead()
            transport.enqueueRead(
                MockAutobahnHelpers.buildServerFrame(Opcode.Ping, payload),
            )

            withTimeout(5.seconds) { client.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1002u)
            client.close()
        }

    @Test
    fun pingPayload125BytesIsValid() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(transport, bufferFactory = bufferFactory, pool = pool)
            MockAutobahnHelpers.connectWithHandshake(client, transport)

            val payload = BufferFactory.Default.allocate(125)
            for (i in 0 until 125) payload.writeByte(0x41)
            payload.resetForRead()
            transport.enqueueRead(
                MockAutobahnHelpers.buildServerFrame(Opcode.Ping, payload),
            )
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

            val msg =
                withTimeout(5.seconds) {
                    client.receive().first()
                }
            assertIs<WebSocketMessage.Ping>(msg)
            client.close()
        }

    @Test
    fun continuationWithoutStartIsProtocolError() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(transport, bufferFactory = bufferFactory, pool = pool)
            MockAutobahnHelpers.connectWithHandshake(client, transport)

            // Continuation frame without a preceding start frame
            val payload = BufferFactory.Default.allocate(5)
            for (i in 0 until 5) payload.writeByte(0x41)
            payload.resetForRead()
            transport.enqueueRead(
                MockAutobahnHelpers.buildServerContinuationFrame(payload, fin = true),
            )

            withTimeout(5.seconds) { client.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1002u)
            client.close()
        }

    @Test
    fun newTextDuringFragmentationIsProtocolError() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(transport, bufferFactory = bufferFactory, pool = pool)
            MockAutobahnHelpers.connectWithHandshake(client, transport)

            // Start a fragmented text message, then send a new text frame
            transport.enqueueRead(MockAutobahnHelpers.buildServerTextFrame("start", fin = false))
            transport.enqueueRead(MockAutobahnHelpers.buildServerTextFrame("new message"))

            withTimeout(5.seconds) { client.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1002u)
            client.close()
        }

    @Test
    fun newBinaryDuringTextFragmentationIsProtocolError() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(transport, bufferFactory = bufferFactory, pool = pool)
            MockAutobahnHelpers.connectWithHandshake(client, transport)

            val binPayload = BufferFactory.Default.allocate(3)
            binPayload.writeByte(1); binPayload.writeByte(2); binPayload.writeByte(3)
            binPayload.resetForRead()

            transport.enqueueRead(MockAutobahnHelpers.buildServerTextFrame("start", fin = false))
            transport.enqueueRead(MockAutobahnHelpers.buildServerFrame(Opcode.Binary, binPayload))

            withTimeout(5.seconds) { client.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1002u)
            client.close()
        }

    @Test
    fun rsv2SetWithoutExtensionIsProtocolError() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(transport, bufferFactory = bufferFactory, pool = pool)
            MockAutobahnHelpers.connectWithHandshake(client, transport)

            val payload = BufferFactory.Default.allocate(5)
            for (i in 0 until 5) payload.writeByte(0x41)
            payload.resetForRead()
            transport.enqueueRead(
                MockAutobahnHelpers.buildServerFrameWithRsv(
                    Opcode.Text,
                    payload,
                    fin = true,
                    rsv2 = true,
                ),
            )

            withTimeout(5.seconds) { client.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1002u)
            client.close()
        }

    @Test
    fun rsv3SetWithoutExtensionIsProtocolError() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(transport, bufferFactory = bufferFactory, pool = pool)
            MockAutobahnHelpers.connectWithHandshake(client, transport)

            val payload = BufferFactory.Default.allocate(5)
            for (i in 0 until 5) payload.writeByte(0x41)
            payload.resetForRead()
            transport.enqueueRead(
                MockAutobahnHelpers.buildServerFrameWithRsv(
                    Opcode.Text,
                    payload,
                    fin = true,
                    rsv3 = true,
                ),
            )

            withTimeout(5.seconds) { client.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1002u)
            client.close()
        }

    @Test
    fun rsv1WithoutCompressionIsProtocolError() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(transport, bufferFactory = bufferFactory, pool = pool)
            MockAutobahnHelpers.connectWithHandshake(client, transport)

            val payload = BufferFactory.Default.allocate(5)
            for (i in 0 until 5) payload.writeByte(0x41)
            payload.resetForRead()
            transport.enqueueRead(
                MockAutobahnHelpers.buildServerFrameWithRsv(
                    Opcode.Text,
                    payload,
                    fin = true,
                    rsv1 = true,
                ),
            )

            withTimeout(5.seconds) { client.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1002u)
            client.close()
        }

    @Test
    fun pingInterleavedPreservesFragmentation() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(transport, bufferFactory = bufferFactory, pool = pool)
            MockAutobahnHelpers.connectWithHandshake(client, transport)

            // Text(FIN=0, "AB") + Ping + Cont(FIN=0, "CD") + Ping + Cont(FIN=1, "EF")
            val ab = BufferFactory.Default.allocate(2)
            ab.writeByte(0x41); ab.writeByte(0x42); ab.resetForRead()
            val cd = BufferFactory.Default.allocate(2)
            cd.writeByte(0x43); cd.writeByte(0x44); cd.resetForRead()
            val ef = BufferFactory.Default.allocate(2)
            ef.writeByte(0x45); ef.writeByte(0x46); ef.resetForRead()

            transport.enqueueRead(MockAutobahnHelpers.buildServerFrame(Opcode.Text, ab, fin = false))
            transport.enqueueRead(MockHandshakeHelper.buildServerPingFrame())
            transport.enqueueRead(MockAutobahnHelpers.buildServerContinuationFrame(cd, fin = false))
            transport.enqueueRead(MockHandshakeHelper.buildServerPingFrame())
            transport.enqueueRead(MockAutobahnHelpers.buildServerContinuationFrame(ef, fin = true))
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

            val msg =
                withTimeout(5.seconds) {
                    client.receive().first { it is WebSocketMessage.Text }
                }
            assertIs<WebSocketMessage.Text>(msg)
            assertEquals("ABCDEF", msg.value)
            client.close()
        }
}

class MockAutobahnCat5DefaultTest : AbstractMockAutobahnCat5Test() {
    override val bufferFactory: BufferFactory = BufferFactory.Default
}

class MockAutobahnCat5ManagedTest : AbstractMockAutobahnCat5Test() {
    override val bufferFactory: BufferFactory = BufferFactory.managed()
}

class MockAutobahnCat5DeterministicTest : AbstractMockAutobahnCat5Test() {
    override val bufferFactory: BufferFactory = BufferFactory.deterministic()
}

class MockAutobahnCat5SharedTest : AbstractMockAutobahnCat5Test() {
    override val bufferFactory: BufferFactory = BufferFactory.shared()
}

class MockAutobahnCat5PooledTest : AbstractMockAutobahnCat5Test() {
    override val bufferFactory: BufferFactory = BufferFactory.managed()
    override val pool: BufferPool = BufferPool(factory = bufferFactory)
}
