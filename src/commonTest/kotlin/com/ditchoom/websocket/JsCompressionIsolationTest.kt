package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.freeAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Incremental isolation tests for JS compression failures.
 * Each test narrows the scope until we find the exact boundary.
 */
class JsCompressionIsolationTest {
    // ── Level 1: Does compression round-trip work at all? ──

    @Test
    fun singleCompressedMessage32KB() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithCompressionHandshake(transport)

            val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
            val text = "A".repeat(32768)
            val payload = BufferFactory.Default.allocate(text.length)
            payload.writeString(text, Charset.UTF8)
            payload.resetForRead()
            transport.enqueueRead(MockAutobahnHelpers.buildServerCompressedFrame(payload, Opcode.Text, compressor))
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))
            compressor.close()

            val msg =
                withTimeout(5.seconds) {
                    connection
                        .receive()
                        .filter { it is WebSocketMessage.Text }
                        .take(1)
                        .toList()
                }
            assertEquals(1, msg.size)
            assertEquals(32768, (msg[0] as WebSocketMessage.Text).payload.length)
            connection.close()
        }

    // ── Level 2: Does it work with multiple messages? ──

    @Test
    fun tenCompressedMessages32KB() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithCompressionHandshake(transport)

            val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
            val text = "A".repeat(32768)
            repeat(10) {
                val payload = BufferFactory.Default.allocate(text.length)
                payload.writeString(text, Charset.UTF8)
                payload.resetForRead()
                transport.enqueueRead(MockAutobahnHelpers.buildServerCompressedFrame(payload, Opcode.Text, compressor))
                compressor.reset()
            }
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))
            compressor.close()

            val msgs =
                withTimeout(10.seconds) {
                    connection
                        .receive()
                        .filter { it is WebSocketMessage.Text }
                        .take(10)
                        .toList()
                }
            assertEquals(10, msgs.size)
            msgs.forEachIndexed { i, msg ->
                assertEquals(32768, (msg as WebSocketMessage.Text).payload.length, "Message $i wrong length")
            }
            connection.close()
        }

    // ── Level 3: 100 messages ──

    @Test
    fun hundredCompressedMessages32KB() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithCompressionHandshake(transport)

            val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
            val text = "A".repeat(32768)
            repeat(100) {
                val payload = BufferFactory.Default.allocate(text.length)
                payload.writeString(text, Charset.UTF8)
                payload.resetForRead()
                transport.enqueueRead(MockAutobahnHelpers.buildServerCompressedFrame(payload, Opcode.Text, compressor))
                compressor.reset()
            }
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))
            compressor.close()

            val msgs =
                withTimeout(30.seconds) {
                    connection
                        .receive()
                        .filter { it is WebSocketMessage.Text }
                        .take(100)
                        .toList()
                }
            assertEquals(100, msgs.size)
            msgs.forEachIndexed { i, msg ->
                assertEquals(32768, (msg as WebSocketMessage.Text).payload.length, "Message $i wrong length")
            }
            connection.close()
        }

    // ── Level 4: Non-repetitive data (larger compressed output) ──

    @Test
    fun tenCompressedMessages32KB_nonRepetitive() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithCompressionHandshake(transport)

            val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
            repeat(10) { i ->
                val text =
                    buildString {
                        repeat(32768) { append(('A' + ((it + i * 7) % 26)).code.toChar()) }
                    }
                val payload = BufferFactory.Default.allocate(text.length)
                payload.writeString(text, Charset.UTF8)
                payload.resetForRead()
                transport.enqueueRead(MockAutobahnHelpers.buildServerCompressedFrame(payload, Opcode.Text, compressor))
                compressor.reset()
            }
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))
            compressor.close()

            val msgs =
                withTimeout(10.seconds) {
                    connection
                        .receive()
                        .filter { it is WebSocketMessage.Text }
                        .take(10)
                        .toList()
                }
            assertEquals(10, msgs.size)
            msgs.forEachIndexed { i, msg ->
                assertEquals(32768, (msg as WebSocketMessage.Text).payload.length, "Message $i wrong length")
            }
            connection.close()
        }

    // ── Level 5: Check compressed sizes to verify pool theory ──

    @Test
    fun compressedSizeOfRepetitiveData() {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val text = "A".repeat(32768)
        val payload = BufferFactory.Default.allocate(text.length)
        payload.writeString(text, Charset.UTF8)
        payload.resetForRead()

        val chunks = compressSync(payload, compressor)
        val compressedSize = totalRemaining(chunks)
        println("32KB repetitive 'A' compresses to $compressedSize bytes")
        // If < 8KB, Node.js would pool the TCP buffer
        chunks.freeAll()
        compressor.close()
    }

    // ── Level 6: Echo (compress → send → receive → decompress → recompress → send) ──

    @Test
    fun echoCompressedMessage32KB() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithCompressionHandshake(transport)

            val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
            val text = "A".repeat(32768)
            val payload = BufferFactory.Default.allocate(text.length)
            payload.writeString(text, Charset.UTF8)
            payload.resetForRead()
            // Only enqueue the data frame — no close yet
            transport.enqueueRead(MockAutobahnHelpers.buildServerCompressedFrame(payload, Opcode.Text, compressor))
            compressor.close()

            // Receive
            val msg =
                withTimeout(5.seconds) {
                    connection
                        .receive()
                        .filter { it is WebSocketMessage.Text }
                        .take(1)
                        .toList()
                }
            assertIs<WebSocketMessage.Text>(msg[0])
            val received = msg[0] as WebSocketMessage.Text
            assertEquals(32768, received.payload.length)

            // Echo back while still connected
            connection.send(WebSocketMessage.Text(received.payload))

            // NOW enqueue close
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))
            withTimeout(5.seconds) {
                connection
                    .receive()
                    .filter { it is WebSocketMessage.Close }
                    .take(1)
                    .toList()
            }
            connection.close()
        }

    // ── Level 7: 10 echo round-trips (like Autobahn cat-12) ──

    @Test
    fun tenEchoRoundTrips32KB() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithCompressionHandshake(transport)

            val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
            val text = "A".repeat(32768)

            repeat(10) {
                val payload = BufferFactory.Default.allocate(text.length)
                payload.writeString(text, Charset.UTF8)
                payload.resetForRead()
                transport.enqueueRead(MockAutobahnHelpers.buildServerCompressedFrame(payload, Opcode.Text, compressor))
                compressor.reset()
            }

            // Receive all 10, echo each
            val msgs =
                withTimeout(10.seconds) {
                    connection
                        .receive()
                        .filter { it is WebSocketMessage.Text }
                        .take(10)
                        .toList()
                }
            assertEquals(10, msgs.size)
            msgs.forEachIndexed { i, msg ->
                val t = (msg as WebSocketMessage.Text).payload
                assertEquals(32768, t.length, "Message $i wrong length: ${t.length}")
                connection.send(WebSocketMessage.Text(t))
            }

            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))
            withTimeout(5.seconds) {
                connection
                    .receive()
                    .filter { it is WebSocketMessage.Close }
                    .take(1)
                    .toList()
            }
            connection.close()
            compressor.close()
        }
}
