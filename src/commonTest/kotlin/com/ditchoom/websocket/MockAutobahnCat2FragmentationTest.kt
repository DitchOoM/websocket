package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Mock Autobahn Category 2: Fragmentation.
 *
 * RFC 6455 Section 5.4: Tests fragmented message reassembly through the full client.
 */
class MockAutobahnCat2FragmentationTest {
    private fun testFragmentedText(
        text: String,
        chunkSize: Int,
    ) = runStrictTest {
        val transport = MockWebSocketTransport()
        val connection = MockAutobahnHelpers.connectWithHandshake(transport)

        val frames = MockAutobahnHelpers.buildFragmentedTextFrames(text, chunkSize)
        for (frame in frames) transport.enqueueRead(frame)
        transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

        val msg =
            withTimeout(5.seconds) {
                connection.receive().first()
            }
        assertIs<WebSocketMessage.Text>(msg)
        assertEquals(text, msg.payload)
        connection.close()
    }

    @Test
    fun textTwoFragments() = testFragmentedText("Hello, World!", 7)

    @Test
    fun textThreeFragments() = testFragmentedText("Hello, World!", 5)

    @Test
    fun textManyFragments() = testFragmentedText("Hello, World!", 2)

    @Test
    fun textOctetWise() = testFragmentedText("Hello", 1)

    @Test
    fun textLargeFragmented() = testFragmentedText("A".repeat(4096), 512)

    @Test
    fun binaryTwoFragments() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(transport, BinaryPassThroughCodec)

            val data = BufferFactory.Default.allocate(100)
            for (i in 0 until 100) data.writeByte(i.toByte())
            data.resetForRead()
            val frames = MockAutobahnHelpers.buildFragmentedBinaryFrames(data, 50)
            for (frame in frames) transport.enqueueRead(frame)
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

            val msg =
                withTimeout(5.seconds) {
                    connection.receive().first()
                }
            assertIs<WebSocketMessage.Binary<*>>(msg)
            @Suppress("UNCHECKED_CAST")
            val binary = msg as WebSocketMessage.Binary<ReadBuffer>
            assertEquals(100, binary.payload.remaining())
            connection.close()
        }

    @Test
    fun emptyIntermediateFragments() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(transport)

            // Text(FIN=0, "He") + Cont(FIN=0, "") + Cont(FIN=0, "ll") + Cont(FIN=1, "o")
            val he = BufferFactory.Default.allocate(2)
            he.writeByte(0x48)
            he.writeByte(0x65)
            he.resetForRead()
            val empty = BufferFactory.Default.allocate(0)
            empty.resetForRead()
            val ll = BufferFactory.Default.allocate(2)
            ll.writeByte(0x6C)
            ll.writeByte(0x6C)
            ll.resetForRead()
            val o = BufferFactory.Default.allocate(1)
            o.writeByte(0x6F)
            o.resetForRead()

            transport.enqueueRead(MockAutobahnHelpers.buildServerFrame(Opcode.Text, he, fin = false))
            transport.enqueueRead(MockAutobahnHelpers.buildServerContinuationFrame(empty, fin = false))
            transport.enqueueRead(MockAutobahnHelpers.buildServerContinuationFrame(ll, fin = false))
            transport.enqueueRead(MockAutobahnHelpers.buildServerContinuationFrame(o, fin = true))
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

            val msg =
                withTimeout(5.seconds) {
                    connection.receive().first()
                }
            assertIs<WebSocketMessage.Text>(msg)
            assertEquals("Hello", msg.payload)
            connection.close()
        }

    @Test
    fun pingBetweenFragments() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(transport)

            // Text(FIN=0, "Hel") + Ping + Cont(FIN=1, "lo")
            val hel = BufferFactory.Default.allocate(3)
            hel.writeByte(0x48)
            hel.writeByte(0x65)
            hel.writeByte(0x6C)
            hel.resetForRead()
            val lo = BufferFactory.Default.allocate(2)
            lo.writeByte(0x6C)
            lo.writeByte(0x6F)
            lo.resetForRead()

            transport.enqueueRead(MockAutobahnHelpers.buildServerFrame(Opcode.Text, hel, fin = false))
            transport.enqueueRead(MockHandshakeHelper.buildServerPingFrame())
            transport.enqueueRead(MockAutobahnHelpers.buildServerContinuationFrame(lo, fin = true))
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

            val msg =
                withTimeout(5.seconds) {
                    connection.receive().first { it is WebSocketMessage.Text }
                }
            assertIs<WebSocketMessage.Text>(msg)
            assertEquals("Hello", msg.payload)
            connection.close()
        }
}
