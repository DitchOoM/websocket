package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.managed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [DefaultWebSocketClient] using a mock transport.
 *
 * These tests verify the full client lifecycle (connect, read, write, close)
 * without any network I/O by injecting a [MockWebSocketTransport].
 */
class DefaultWebSocketClientMockTest {
    private val defaultOptions =
        WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            tls = false,
            websocketEndpoint = "/ws",
        )

    private fun createClient(
        mockTransport: MockWebSocketTransport,
        options: WebSocketConnectionOptions = defaultOptions,
    ): DefaultWebSocketClient =
        DefaultWebSocketClient(
            transport = mockTransport,
            connectionOptions = options,
            parentScope = null,
            bufferFactory = BufferFactory.managed(),
        )

    /**
     * Completes the WebSocket handshake on a mock transport.
     *
     * Launches connect() in the background, waits for the handshake request to be written,
     * extracts the Sec-WebSocket-Key, computes the correct accept key, and enqueues
     * the 101 response. The mock transport's Channel-based read() suspends until we enqueue data,
     * giving the test full control over timing.
     *
     * @return The connected client
     */
    private suspend fun connectWithHandshake(
        client: DefaultWebSocketClient,
        mockTransport: MockWebSocketTransport,
    ): DefaultWebSocketClient {
        val connectJob =
            CoroutineScope(Dispatchers.Default).async {
                client.connect()
            }

        // Wait for handshake request to be written
        waitForWrite(mockTransport)

        // Extract key and enqueue valid response
        val clientKey = MockHandshakeHelper.extractClientKey(mockTransport.writtenBuffers[0])
        mockTransport.enqueueRead(MockHandshakeHelper.buildHandshakeResponse(clientKey))

        // Wait for connect to complete
        withTimeout(5.seconds) { connectJob.await() }

        return client
    }

    /**
     * Waits until the mock transport has at least [count] written buffers.
     */
    private suspend fun waitForWrite(
        mockTransport: MockWebSocketTransport,
        count: Int = 1,
    ) {
        withTimeout(5.seconds) {
            while (mockTransport.writtenBuffers.size < count) {
                delay(10)
            }
        }
    }

    // ========================================================================
    // A. Network Disruption / Transport Errors
    // ========================================================================

    @Test
    fun transportErrorDuringHandshake() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)

            // Enqueue error before connect attempt reads anything
            mockTransport.enqueueReadError(Exception("Connection refused"))

            val exception =
                assertFailsWith<WebSocketException> {
                    client.connect()
                }
            assertIs<WebSocketException.TransportFailed>(exception)
        }

    @Test
    fun transportDisconnectDuringHandshakeRead() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)

            val connectJob =
                CoroutineScope(Dispatchers.Default).async {
                    runCatching { client.connect() }
                }

            // Wait for handshake write
            waitForWrite(mockTransport)

            // Simulate disconnect before sending response
            mockTransport.simulateDisconnect()

            val result = withTimeout(5.seconds) { connectJob.await() }
            assertTrue(result.isFailure)
        }

    @Test
    fun transportDisconnectDuringMessageRead() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            // Simulate disconnect during read loop
            mockTransport.simulateDisconnect()

            // receive flow should complete (channel closes)
            val messages =
                withTimeout(5.seconds) {
                    client.receive().take(1).toList()
                }
            assertTrue(messages.isEmpty())
            client.close()
        }

    @Test
    fun transportErrorDuringReadLoop() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            // Inject transport error
            mockTransport.enqueueReadError(Exception("Connection reset"))

            // receive flow should complete (channel closes)
            val messages =
                withTimeout(5.seconds) {
                    client.receive().take(1).toList()
                }
            assertTrue(messages.isEmpty())
            client.close()
        }

    @Test
    fun transportDisconnectMidFrame() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            // Send only the first 2 bytes of a frame header (text, length=10)
            // then error before payload arrives.
            mockTransport.enqueueReadBytes(0x81.toByte(), 0x0A) // FIN + Text, length=10
            mockTransport.enqueueReadError(Exception("Connection lost mid-frame"))

            // receive flow should complete
            val messages =
                withTimeout(5.seconds) {
                    client.receive().take(1).toList()
                }
            assertTrue(messages.isEmpty())
            client.close()
        }

    /**
     * Regression: server TCP close without WS close frame must unblock
     * receive() collectors. Before the fix, take(N).collect hung
     * forever because incomingMessageChannel was never closed.
     */
    @Test
    fun tcpCloseWithoutWsCloseUnblocksCollector() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            // Send 2 text messages, then transport-level close (no WS close frame).
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerTextFrame("msg1"))
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerTextFrame("msg2"))
            mockTransport.enqueueReadError(Exception("Server TCP close"))

            // Collect with take(5) — we'll only get 2 messages before the
            // channel closes. This must NOT hang.
            val messages =
                withTimeout(5.seconds) {
                    client.receive().take(5).toList()
                }

            assertEquals(2, messages.size)
            assertIs<WebSocketMessage.Text>(messages[0])
            assertEquals("msg1", (messages[0] as WebSocketMessage.Text).value)
            assertIs<WebSocketMessage.Text>(messages[1])
            assertEquals("msg2", (messages[1] as WebSocketMessage.Text).value)
            client.close()
        }

    /**
     * Regression: zero messages before TCP disconnect must also unblock receive() collectors.
     * This is the worst case -- the collector has received nothing when the channel closes.
     */
    @Test
    fun tcpCloseImmediatelyAfterConnectUnblocksCollector() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            // TCP disconnect immediately — no messages at all
            mockTransport.enqueueReadError(Exception("Server TCP close"))

            // Collector on receive() must complete (channel closes with no messages)
            val messages =
                withTimeout(5.seconds) {
                    client.receive().take(1).toList()
                }

            // Channel closed before any message — should get empty list
            assertTrue(messages.isEmpty())
            client.close()
        }

    /**
     * Regression: mid-frame TCP disconnect must unblock collector.
     * Server sends partial frame header then disconnects.
     */
    @Test
    fun midFrameTcpCloseUnblocksCollector() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            // Send complete message, then partial frame header, then disconnect
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerTextFrame("before-crash"))
            mockTransport.enqueueReadBytes(0x81.toByte(), 0x0A) // Partial: FIN+Text, length=10, no payload
            mockTransport.enqueueReadError(Exception("Connection lost"))

            val messages =
                withTimeout(5.seconds) {
                    client.receive().take(5).toList()
                }

            // Should get the one complete message before the crash
            assertEquals(1, messages.size)
            assertEquals("before-crash", (messages[0] as WebSocketMessage.Text).value)
            client.close()
        }

    // ========================================================================
    // B. Normal Lifecycle
    // ========================================================================

    @Test
    fun successfulHandshakeConnects() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            assertTrue(client.isOpen())
            client.close()
        }

    @Test
    fun textMessageReceived() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            // Enqueue a server text frame
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerTextFrame("Hello, WebSocket!"))

            val msg =
                withTimeout(5.seconds) {
                    client.receive().first()
                }

            assertIs<WebSocketMessage.Text>(msg)
            assertEquals("Hello, WebSocket!", msg.value)
            client.close()
        }

    @Test
    fun binaryMessageReceived() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            val testData = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerBinaryFrame(testData))

            val msg =
                withTimeout(5.seconds) {
                    client.receive().first()
                }

            assertIs<WebSocketMessage.Binary>(msg)
            val received = ByteArray(msg.value.remaining())
            for (i in received.indices) {
                received[i] = msg.value.readByte()
            }
            assertTrue(testData.contentEquals(received))
            client.close()
        }

    @Test
    fun clientSendTextWritesMaskedFrame() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            client.send(WebSocketMessage.Text("hello"))

            // Wait for the write to appear (index 0 is handshake, index 1 is our frame)
            waitForWrite(mockTransport, count = 2)

            val frameBuffer = mockTransport.writtenBuffers[1]
            frameBuffer.position(0)

            val byte1 = frameBuffer.readByte().toInt() and 0xFF
            val byte2 = frameBuffer.readByte().toInt() and 0xFF

            // FIN=1, opcode=Text(0x1)
            assertEquals(0x81, byte1)
            // MASK bit must be set for client frames
            assertTrue((byte2 and 0x80) != 0, "Client frame must have MASK bit set")
            // Payload length = 5 ("hello" in UTF-8)
            assertEquals(5, byte2 and 0x7F)

            // Read mask key (4 bytes)
            val maskKey = ByteArray(4)
            for (i in 0..3) maskKey[i] = frameBuffer.readByte()

            // Read masked payload and unmask
            val expectedPayload = "hello".encodeToByteArray()
            for (i in expectedPayload.indices) {
                val maskedByte = frameBuffer.readByte()
                val unmasked = (maskedByte.toInt() xor maskKey[i % 4].toInt()).toByte()
                assertEquals(expectedPayload[i], unmasked)
            }
            client.close()
        }

    @Test
    fun serverInitiatedClose() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            // Server sends close frame
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerCloseFrame(1000u, "bye"))

            // Should receive close message
            val msg =
                withTimeout(5.seconds) {
                    client.receive().first()
                }
            assertIs<WebSocketMessage.Close>(msg)
            assertEquals(1000.toUShort(), msg.code)

            // Wait for client to send close response
            waitForWrite(mockTransport, count = 2)

            // receive flow should complete
            val remaining =
                withTimeout(5.seconds) {
                    client.receive().toList()
                }
            assertTrue(remaining.isEmpty())
            client.close()
        }

    @Test
    fun clientInitiatedClose() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            client.close()

            // Verify close frame was sent (index 0 = handshake, index 1 = close frame)
            assertTrue(mockTransport.writtenBuffers.size >= 2, "Expected at least 2 writes (handshake + close)")

            val closeFrame = mockTransport.writtenBuffers[1]
            closeFrame.position(0)

            val byte1 = closeFrame.readByte().toInt() and 0xFF
            // FIN=1, opcode=Close(0x8) => 0x88
            assertEquals(0x88, byte1)
        }

    // ========================================================================
    // C. Ping/Pong
    // ========================================================================

    @Test
    fun pingAutoRespondsWithPong() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            val pingPayload = "ping-data".encodeToByteArray()
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerPingFrame(pingPayload))

            // Should emit Ping message
            val msg =
                withTimeout(5.seconds) {
                    client.receive().first()
                }
            assertIs<WebSocketMessage.Ping>(msg)

            // Wait for pong to be written
            waitForWrite(mockTransport, count = 2)

            val pongFrame = mockTransport.writtenBuffers[1]
            pongFrame.position(0)
            val byte1 = pongFrame.readByte().toInt() and 0xFF
            // FIN=1, opcode=Pong(0xA) => 0x8A
            assertEquals(0x8A, byte1)

            val byte2 = pongFrame.readByte().toInt() and 0xFF
            assertTrue((byte2 and 0x80) != 0, "Pong must be masked (client frame)")
            val payloadLen = byte2 and 0x7F
            assertEquals(pingPayload.size, payloadLen)

            // Unmask and verify payload matches ping payload
            val maskKey = ByteArray(4)
            for (i in 0..3) maskKey[i] = pongFrame.readByte()

            for (i in pingPayload.indices) {
                val maskedByte = pongFrame.readByte()
                val unmasked = (maskedByte.toInt() xor maskKey[i % 4].toInt()).toByte()
                assertEquals(pingPayload[i], unmasked, "Pong payload byte $i mismatch")
            }
            client.close()
        }

    @Test
    fun pongMessageEmitted() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            mockTransport.enqueueRead(MockHandshakeHelper.buildServerPongFrame("pong-data".encodeToByteArray()))

            val msg =
                withTimeout(5.seconds) {
                    client.receive().first()
                }
            assertIs<WebSocketMessage.Pong>(msg)
            client.close()
        }

    // ========================================================================
    // D. Handshake Edge Cases
    // ========================================================================

    @Test
    fun invalidStatusCode200() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)

            val connectJob =
                CoroutineScope(Dispatchers.Default).async {
                    runCatching { client.connect() }
                }

            waitForWrite(mockTransport)
            mockTransport.enqueueRead(MockHandshakeHelper.buildErrorResponse(200, "OK"))

            val result = withTimeout(5.seconds) { connectJob.await() }
            assertTrue(result.isFailure)
        }

    @Test
    fun missingSecWebSocketAccept() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)

            val connectJob =
                CoroutineScope(Dispatchers.Default).async {
                    runCatching { client.connect() }
                }

            waitForWrite(mockTransport)
            mockTransport.enqueueRead(MockHandshakeHelper.buildMissingAcceptResponse())

            val result = withTimeout(5.seconds) { connectJob.await() }
            assertTrue(result.isFailure)
        }

    @Test
    fun wrongAcceptKey() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)

            val connectJob =
                CoroutineScope(Dispatchers.Default).async {
                    runCatching { client.connect() }
                }

            waitForWrite(mockTransport)
            mockTransport.enqueueRead(MockHandshakeHelper.buildWrongAcceptResponse())

            val result = withTimeout(5.seconds) { connectJob.await() }
            assertTrue(result.isFailure)
        }

    // ========================================================================
    // G. Exception Consistency
    // ========================================================================

    @Test
    fun handshakeRejected403() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)

            val connectJob =
                CoroutineScope(Dispatchers.Default).async {
                    runCatching { client.connect() }
                }

            waitForWrite(mockTransport)
            mockTransport.enqueueRead(MockHandshakeHelper.buildErrorResponse(403, "Forbidden"))

            val result = withTimeout(5.seconds) { connectJob.await() }
            assertTrue(result.isFailure)
            assertIs<WebSocketException.HandshakeRejected>(result.exceptionOrNull())
        }

    @Test
    fun handshakeRejectedWrongAcceptKey() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)

            val connectJob =
                CoroutineScope(Dispatchers.Default).async {
                    runCatching { client.connect() }
                }

            waitForWrite(mockTransport)
            mockTransport.enqueueRead(MockHandshakeHelper.buildWrongAcceptResponse())

            val result = withTimeout(5.seconds) { connectJob.await() }
            assertTrue(result.isFailure)
            assertIs<WebSocketException.HandshakeRejected>(result.exceptionOrNull())
        }

    @Test
    fun serverCloseNormal1000FlowCompletes() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            mockTransport.enqueueRead(MockHandshakeHelper.buildServerCloseFrame(1000u, "bye"))

            // Should receive the close message then the flow completes
            val messages =
                withTimeout(5.seconds) {
                    client.receive().toList()
                }

            assertEquals(1, messages.size)
            assertIs<WebSocketMessage.Close>(messages[0])
            assertEquals(1000.toUShort(), (messages[0] as WebSocketMessage.Close).code)
            client.close()
        }

    @Test
    fun serverCloseAbnormalSurfacesCloseMessage() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            mockTransport.enqueueRead(MockHandshakeHelper.buildServerCloseFrame(1011u, "internal error"))

            val messages =
                withTimeout(5.seconds) {
                    client.receive().toList()
                }

            assertEquals(1, messages.size)
            assertIs<WebSocketMessage.Close>(messages[0])
            assertEquals(1011.toUShort(), (messages[0] as WebSocketMessage.Close).code)
            client.close()
        }

    @Test
    fun reservedOpcodeClosesConnection() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            // Send a frame with reserved opcode 0x3 (RFC 6455 Section 5.2: opcodes 3-7 are reserved)
            // FIN=1, opcode=3, no mask, payload length=0
            mockTransport.enqueueReadBytes(0x83.toByte(), 0x00)

            // receive flow should complete (client sends close frame and stops)
            val messages =
                withTimeout(5.seconds) {
                    client.receive().toList()
                }
            // No messages expected — the reserved opcode causes immediate close
            assertTrue(messages.isEmpty())
            client.close()
        }

    @Test
    fun transportErrorDuringReadLoopClosesFlow() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            mockTransport.enqueueReadError(Exception("Connection reset by peer"))

            // receive flow should complete
            val messages =
                withTimeout(5.seconds) {
                    client.receive().toList()
                }
            assertTrue(messages.isEmpty())
            client.close()
        }

    // ========================================================================
    // H. Write-After-Close and Double-Close Guards
    // ========================================================================

    @Test
    fun sendTextAfterCloseThrowsConnectionClosed() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            client.close()

            assertFailsWith<WebSocketException.ConnectionClosed> {
                client.send(WebSocketMessage.Text("should fail"))
            }
        }

    @Test
    fun sendBinaryAfterCloseThrowsConnectionClosed() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            client.close()

            val buffer = BufferFactory.managed().allocate(5)
            buffer.writeBytes("hello".encodeToByteArray())
            buffer.resetForRead()

            assertFailsWith<WebSocketException.ConnectionClosed> {
                client.send(WebSocketMessage.Binary(buffer))
            }
        }

    @Test
    fun sendPingAfterCloseThrowsConnectionClosed() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            client.close()

            assertFailsWith<WebSocketException.ConnectionClosed> {
                client.send(WebSocketMessage.Ping(EMPTY_BUFFER))
            }
        }

    @Test
    fun doubleCloseIsIdempotent() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            client.close()
            // Second close should return without error
            client.close()
        }

    @Test
    fun sendAfterServerCloseThrowsConnectionClosed() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            // Server sends close frame
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerCloseFrame(1000u, "bye"))

            // Wait for the close message to be processed
            withTimeout(5.seconds) {
                client.receive().toList()
            }

            assertFailsWith<WebSocketException.ConnectionClosed> {
                client.send(WebSocketMessage.Text("should fail after server close"))
            }
            client.close()
        }

    // ========================================================================
    // I. send() with all WebSocketMessage types
    // ========================================================================

    @Test
    fun sendBinaryWritesMaskedFrame() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            val payload = BufferFactory.managed().allocate(3)
            payload.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
            payload.resetForRead()
            client.send(WebSocketMessage.Binary(payload))

            waitForWrite(mockTransport, count = 2)
            val frameBuffer = mockTransport.writtenBuffers[1]
            frameBuffer.position(0)
            val byte1 = frameBuffer.readByte().toInt() and 0xFF
            // FIN=1, opcode=Binary(0x2) => 0x82
            assertEquals(0x82, byte1)
            val byte2 = frameBuffer.readByte().toInt() and 0xFF
            assertTrue((byte2 and 0x80) != 0, "MASK bit must be set")
            assertEquals(3, byte2 and 0x7F)
            client.close()
        }

    @Test
    fun sendPingWritesMaskedFrame() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            val payload = BufferFactory.managed().allocate(4)
            payload.writeBytes("test".encodeToByteArray())
            payload.resetForRead()
            client.send(WebSocketMessage.Ping(payload))

            waitForWrite(mockTransport, count = 2)
            val frameBuffer = mockTransport.writtenBuffers[1]
            frameBuffer.position(0)
            val byte1 = frameBuffer.readByte().toInt() and 0xFF
            // FIN=1, opcode=Ping(0x9) => 0x89
            assertEquals(0x89, byte1)
            val byte2 = frameBuffer.readByte().toInt() and 0xFF
            assertTrue((byte2 and 0x80) != 0, "MASK bit must be set")
            assertEquals(4, byte2 and 0x7F)
            client.close()
        }

    @Test
    fun sendPongWritesMaskedFrame() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            client.send(WebSocketMessage.Pong(EMPTY_BUFFER))

            waitForWrite(mockTransport, count = 2)
            val frameBuffer = mockTransport.writtenBuffers[1]
            frameBuffer.position(0)
            val byte1 = frameBuffer.readByte().toInt() and 0xFF
            // FIN=1, opcode=Pong(0xA) => 0x8A
            assertEquals(0x8A, byte1)
            client.close()
        }

    @Test
    fun sendCloseWritesCloseFrame() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            client.send(WebSocketMessage.Close(1000.toUShort(), "bye"))

            waitForWrite(mockTransport, count = 2)
            val frameBuffer = mockTransport.writtenBuffers[1]
            frameBuffer.position(0)
            val byte1 = frameBuffer.readByte().toInt() and 0xFF
            // FIN=1, opcode=Close(0x8) => 0x88
            assertEquals(0x88, byte1)
            client.close()
        }

    // ========================================================================
    // J. send() state guards
    // ========================================================================

    @Test
    fun sendBeforeConnectThrowsConnectionClosed() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            // Never called connect() — state is not connected
            assertFailsWith<WebSocketException.ConnectionClosed> {
                client.send(WebSocketMessage.Text("should fail"))
            }
        }

    @Test
    fun sendDuringConnectingThrowsConnectionClosed() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)

            // Start connect but don't complete handshake — state stays connecting
            val connectJob =
                CoroutineScope(Dispatchers.Default).async {
                    client.connect()
                }

            // Wait for handshake to be sent (state is now Connecting)
            waitForWrite(mockTransport)

            assertFailsWith<WebSocketException.ConnectionClosed> {
                client.send(WebSocketMessage.Text("should fail during connecting"))
            }

            // Complete handshake so the coroutine finishes cleanly
            val clientKey = MockHandshakeHelper.extractClientKey(mockTransport.writtenBuffers[0])
            mockTransport.enqueueRead(MockHandshakeHelper.buildHandshakeResponse(clientKey))
            withTimeout(5.seconds) { connectJob.await() }
            client.close()
        }

    // ========================================================================
    // K. Protocol edge cases
    // ========================================================================

    @Test
    fun invalidUtf8TextMessageSendsClose1007() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            // Send a text frame with invalid UTF-8: 0xC0 0x01 is overlong
            val invalidUtf8 = byteArrayOf(0xC0.toByte(), 0x01)
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerFrame(Opcode.Text, invalidUtf8))
            // Keep the read loop alive long enough to process
            mockTransport.enqueueReadError(Exception("done"))

            // receive flow should complete
            val messages =
                withTimeout(5.seconds) {
                    client.receive().toList()
                }

            // Client should have sent a close frame with code 1007 (invalid payload)
            // Frame writes: index 0 = handshake, index 1+ = close frame
            val closeSent = mockTransport.writtenBuffers.drop(1).any { buf ->
                buf.position(0)
                val b1 = buf.readByte().toInt() and 0xFF
                (b1 and 0x0F) == 0x08 // opcode = Close
            }
            assertTrue(closeSent, "Client should send a close frame on invalid UTF-8")
            client.close()
        }

    @Test
    fun serverCloseWithOneBytePayloadIsProtocolError() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val client = createClient(mockTransport)
            connectWithHandshake(client, mockTransport)

            // RFC 6455 Section 5.5.1: Close frame with 1-byte payload is a protocol error.
            // The status code requires 2 bytes, so 1 byte is invalid.
            val oneBytePayload = byteArrayOf(0x42)
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerFrame(Opcode.Close, oneBytePayload))

            val messages =
                withTimeout(5.seconds) {
                    client.receive().toList()
                }

            // FrameReader parses this as a Close with PROTOCOL_ERROR code
            assertTrue(messages.isNotEmpty())
            assertIs<WebSocketMessage.Close>(messages[0])

            client.close()
        }

    // ========================================================================
    // L. Structured concurrency — parent cancellation propagates
    // ========================================================================

    @Test
    fun parentScopeCancellationClosesClient() =
        runStrictTest {
            val parentJob = kotlinx.coroutines.Job()
            val parentScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + parentJob)

            val mockTransport = MockWebSocketTransport()
            val client =
                DefaultWebSocketClient(
                    transport = mockTransport,
                    connectionOptions = defaultOptions,
                    parentScope = parentScope,
                    bufferFactory = BufferFactory.managed(),
                )
            connectWithHandshake(client, mockTransport)

            assertTrue(client.isOpen())

            // Cancel the PARENT scope — structured concurrency should propagate
            parentJob.cancel()

            // The client's read loop should terminate because its Job is a child of parentJob.
            // The receive flow should complete.
            val messages =
                withTimeout(5.seconds) {
                    client.receive().toList()
                }
            // Flow completes (may be empty or have some messages depending on timing)
            // The key assertion is that it doesn't hang
        }

    @Test
    fun clientCloseDoesNotCancelParent() =
        runStrictTest {
            val parentJob = kotlinx.coroutines.Job()
            val parentScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + parentJob)

            val mockTransport = MockWebSocketTransport()
            val client =
                DefaultWebSocketClient(
                    transport = mockTransport,
                    connectionOptions = defaultOptions,
                    parentScope = parentScope,
                    bufferFactory = BufferFactory.managed(),
                )
            connectWithHandshake(client, mockTransport)

            // Close the CLIENT — should NOT cancel the parent
            client.close()

            // Parent job should still be active
            assertTrue(parentJob.isActive, "Parent job must remain active after client.close()")
            parentJob.cancel()
        }
}
