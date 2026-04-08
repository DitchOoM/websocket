package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
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
 * Mock Autobahn Categories 9 + 12: Compression.
 *
 * Tests permessage-deflate compressed message handling through the full client.
 * Uses [StreamingCompressor] to build compressed server frames (RSV1=1).
 */
abstract class AbstractMockAutobahnCat9Test {
    abstract val bufferFactory: BufferFactory
    open val pool: BufferPool? get() = null

    @Test
    fun compressedTextSmall() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(
                transport,
                options = MockAutobahnHelpers.compressionOptions,
                bufferFactory = bufferFactory,
                pool = pool,
            )
            MockAutobahnHelpers.connectWithCompressionHandshake(client, transport)

            val text = "Hello, compressed WebSocket!"
            val payload = BufferFactory.Default.allocate(text.length * 4)
            payload.writeString(text, Charset.UTF8)
            payload.resetForRead()

            val compressor = MockAutobahnHelpers.createCompressor()
            transport.enqueueRead(
                MockAutobahnHelpers.buildServerCompressedFrame(payload, Opcode.Text, compressor),
            )
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

            val msg =
                withTimeout(5.seconds) {
                    client.receive().first()
                }
            assertIs<WebSocketMessage.Text>(msg)
            assertEquals(text, msg.value)
            compressor.close()
            client.close()
        }

    @Test
    fun compressedTextMedium() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(
                transport,
                options = MockAutobahnHelpers.compressionOptions,
                bufferFactory = bufferFactory,
                pool = pool,
            )
            MockAutobahnHelpers.connectWithCompressionHandshake(client, transport)

            val text = "A".repeat(4096)
            val payload = BufferFactory.Default.allocate(text.length * 4)
            payload.writeString(text, Charset.UTF8)
            payload.resetForRead()

            val compressor = MockAutobahnHelpers.createCompressor()
            transport.enqueueRead(
                MockAutobahnHelpers.buildServerCompressedFrame(payload, Opcode.Text, compressor),
            )
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

            val msg =
                withTimeout(5.seconds) {
                    client.receive().first()
                }
            assertIs<WebSocketMessage.Text>(msg)
            assertEquals(text, msg.value)
            compressor.close()
            client.close()
        }

    @Test
    fun compressedBinary() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(
                transport,
                options = MockAutobahnHelpers.compressionOptions,
                bufferFactory = bufferFactory,
                pool = pool,
            )
            MockAutobahnHelpers.connectWithCompressionHandshake(client, transport)

            val payload = BufferFactory.Default.allocate(256)
            for (i in 0 until 256) payload.writeByte(i.toByte())
            payload.resetForRead()

            val compressor = MockAutobahnHelpers.createCompressor()
            transport.enqueueRead(
                MockAutobahnHelpers.buildServerCompressedFrame(payload, Opcode.Binary, compressor),
            )
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

            val msg =
                withTimeout(5.seconds) {
                    client.receive().first()
                }
            assertIs<WebSocketMessage.Binary>(msg)
            assertEquals(256, msg.value.remaining())
            compressor.close()
            client.close()
        }

    @Test
    fun uncompressedFrameWorksWithCompressionNegotiated() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(
                transport,
                options = MockAutobahnHelpers.compressionOptions,
                bufferFactory = bufferFactory,
                pool = pool,
            )
            MockAutobahnHelpers.connectWithCompressionHandshake(client, transport)

            // RSV1=0 frame should still work even though compression is negotiated
            transport.enqueueRead(MockAutobahnHelpers.buildServerTextFrame("not compressed"))
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

            val msg =
                withTimeout(5.seconds) {
                    client.receive().first()
                }
            assertIs<WebSocketMessage.Text>(msg)
            assertEquals("not compressed", msg.value)
            client.close()
        }

    @Test
    fun multipleCompressedMessages() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(
                transport,
                options = MockAutobahnHelpers.compressionOptions,
                bufferFactory = bufferFactory,
                pool = pool,
            )
            MockAutobahnHelpers.connectWithCompressionHandshake(client, transport)

            val messages = listOf("first message", "second message", "third message")

            // server_no_context_takeover: fresh compressor per message
            for (text in messages) {
                val compressor = MockAutobahnHelpers.createCompressor()
                val payload = BufferFactory.Default.allocate(text.length * 4)
                payload.writeString(text, Charset.UTF8)
                payload.resetForRead()
                transport.enqueueRead(
                    MockAutobahnHelpers.buildServerCompressedFrame(payload, Opcode.Text, compressor),
                )
                compressor.close()
            }
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

            val received =
                withTimeout(5.seconds) {
                    client.receive().toList()
                }
            val textMessages = received.filter { it is WebSocketMessage.Text }
            assertEquals(3, textMessages.size)
            for (i in messages.indices) {
                val txt = textMessages[i] as WebSocketMessage.Text
                assertEquals(messages[i], txt.value)
            }
            client.close()
        }
}

class MockAutobahnCat9DefaultTest : AbstractMockAutobahnCat9Test() {
    override val bufferFactory: BufferFactory = BufferFactory.Default
}

class MockAutobahnCat9ManagedTest : AbstractMockAutobahnCat9Test() {
    override val bufferFactory: BufferFactory = BufferFactory.managed()
}

class MockAutobahnCat9DeterministicTest : AbstractMockAutobahnCat9Test() {
    override val bufferFactory: BufferFactory = BufferFactory.deterministic()
}

class MockAutobahnCat9SharedTest : AbstractMockAutobahnCat9Test() {
    override val bufferFactory: BufferFactory = BufferFactory.shared()
}

class MockAutobahnCat9PooledTest : AbstractMockAutobahnCat9Test() {
    override val bufferFactory: BufferFactory = BufferFactory.managed()
    override val pool: BufferPool = BufferPool(factory = bufferFactory)
}
