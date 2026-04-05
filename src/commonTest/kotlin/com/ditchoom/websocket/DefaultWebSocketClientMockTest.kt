package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.managed
import com.ditchoom.socket.ConnectionState
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.SocketIOException
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [DefaultWebSocketClient] using a mock socket.
 *
 * These tests verify the full client lifecycle (connect, read, write, close)
 * without any network I/O by injecting a [MockClientToServerSocket].
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
        mockSocket: MockClientToServerSocket,
        options: WebSocketConnectionOptions = defaultOptions,
    ): DefaultWebSocketClient =
        DefaultWebSocketClient(
            connectionOptions = options,
            parentScope = null,
            bufferFactory = BufferFactory.managed(),
            socketOverride = mockSocket,
        )

    /**
     * Completes the WebSocket handshake on a mock socket.
     *
     * Launches connect() in the background, waits for the handshake request to be written,
     * extracts the Sec-WebSocket-Key, computes the correct accept key, and enqueues
     * the 101 response. The mock socket's Channel-based read() suspends until we enqueue data,
     * giving the test full control over timing.
     *
     * @return The connected client
     */
    private suspend fun connectWithHandshake(
        client: DefaultWebSocketClient,
        mockSocket: MockClientToServerSocket,
    ): DefaultWebSocketClient {
        val connectJob =
            client.scope.async {
                client.connect()
            }

        // Wait for handshake request to be written
        waitForWrite(mockSocket)

        // Extract key and enqueue valid response
        val clientKey = MockHandshakeHelper.extractClientKey(mockSocket.writtenBuffers[0])
        mockSocket.enqueueRead(MockHandshakeHelper.buildHandshakeResponse(clientKey))

        // Wait for connect to complete
        withTimeout(5.seconds) { connectJob.await() }

        return client
    }

    /**
     * Waits until the mock socket has at least [count] written buffers.
     */
    private suspend fun waitForWrite(
        mockSocket: MockClientToServerSocket,
        count: Int = 1,
    ) {
        withTimeout(5.seconds) {
            while (mockSocket.writtenBuffers.size < count) {
                delay(10)
            }
        }
    }

    // ========================================================================
    // A. Network Disruption / Socket Exceptions
    // ========================================================================

    @Test
    fun socketErrorDuringOpen() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)

            // Enqueue error before connect attempt reads anything
            mockSocket.enqueueReadError(SocketConnectionException.Refused("localhost", 9001))

            client.connect()

            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            assertNotNull(state.t)
            assertIs<WebSocketException.TransportFailed>(state.t)
        }

    @Test
    fun socketDisconnectDuringHandshakeRead() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)

            val connectJob =
                client.scope.async {
                    client.connect()
                }

            // Wait for handshake write
            waitForWrite(mockSocket)

            // Simulate disconnect before sending response
            mockSocket.simulateDisconnect()

            withTimeout(5.seconds) { connectJob.await() }

            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
        }

    @Test
    fun socketDisconnectDuringMessageRead() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            assertIs<ConnectionState.Connected>(client.connectionState.value)

            // Simulate disconnect during read loop
            mockSocket.simulateDisconnect()

            // Wait for state to transition
            withTimeout(5.seconds) {
                while (client.connectionState.value !is ConnectionState.Disconnected) {
                    delay(10)
                }
            }

            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            client.close()
        }

    @Test
    fun socketErrorDuringReadLoop() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            assertIs<ConnectionState.Connected>(client.connectionState.value)

            // Inject socket error
            mockSocket.enqueueReadError(SocketIOException("Connection reset"))

            // Wait for state to transition
            withTimeout(5.seconds) {
                while (client.connectionState.value !is ConnectionState.Disconnected) {
                    delay(10)
                }
            }

            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            assertNotNull(state.t)
            assertIs<WebSocketException.TransportFailed>(state.t)
            client.close()
        }

    @Test
    fun socketDisconnectMidFrame() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            // Send only the first 2 bytes of a frame header (text, length=10)
            // then error before payload arrives. Use enqueueReadError instead of
            // simulateDisconnect to avoid race where isOpen() returns false
            // (from open=false) before the read loop hits the exception path.
            mockSocket.enqueueReadBytes(0x81.toByte(), 0x0A) // FIN + Text, length=10
            mockSocket.enqueueReadError(SocketClosedException.General("Connection lost mid-frame"))

            // Wait for disconnection
            withTimeout(5.seconds) {
                while (client.connectionState.value !is ConnectionState.Disconnected) {
                    delay(10)
                }
            }

            assertIs<ConnectionState.Disconnected>(client.connectionState.value)
            client.close()
        }

    /**
     * Regression: server TCP close without WS close frame must unblock
     * receive() collectors. Before the fix, take(N).collect hung
     * forever because incomingMessageChannel was never closed.
     *
     * Uses enqueueReadError (not simulateDisconnect) because simulateDisconnect
     * sets open=false synchronously, which causes the read loop to exit at the
     * isOpen() check before processing queued frames.
     */
    @Test
    fun tcpCloseWithoutWsCloseUnblocksCollector() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            // Send 2 text messages, then TCP-level close (no WS close frame).
            // enqueueReadError queues the error inline so the read loop processes
            // frames in order: text1 → text2 → SocketClosedException.
            mockSocket.enqueueRead(MockHandshakeHelper.buildServerTextFrame("msg1"))
            mockSocket.enqueueRead(MockHandshakeHelper.buildServerTextFrame("msg2"))
            mockSocket.enqueueReadError(SocketClosedException.General("Server TCP close"))

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
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            // TCP disconnect immediately — no messages at all
            mockSocket.enqueueReadError(SocketClosedException.General("Server TCP close"))

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
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            // Send complete message, then partial frame header, then disconnect
            mockSocket.enqueueRead(MockHandshakeHelper.buildServerTextFrame("before-crash"))
            mockSocket.enqueueReadBytes(0x81.toByte(), 0x0A) // Partial: FIN+Text, length=10, no payload
            mockSocket.enqueueReadError(SocketClosedException.General("Connection lost"))

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
    fun successfulHandshakeTransitionsToConnected() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            assertEquals(ConnectionState.Connected, client.connectionState.value)
            assertTrue(mockSocket.openCalled)
            client.close()
        }

    @Test
    fun textMessageReceived() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            // Enqueue a server text frame
            mockSocket.enqueueRead(MockHandshakeHelper.buildServerTextFrame("Hello, WebSocket!"))

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
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            val testData = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
            mockSocket.enqueueRead(MockHandshakeHelper.buildServerBinaryFrame(testData))

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
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            client.send(WebSocketMessage.Text("hello"))

            // Wait for the write to appear (index 0 is handshake, index 1 is our frame)
            waitForWrite(mockSocket, count = 2)

            val frameBuffer = mockSocket.writtenBuffers[1]
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
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            // Server sends close frame
            mockSocket.enqueueRead(MockHandshakeHelper.buildServerCloseFrame(1000u, "bye"))

            // Should receive close message
            val msg =
                withTimeout(5.seconds) {
                    client.receive().first()
                }
            assertIs<WebSocketMessage.Close>(msg)
            assertEquals(1000.toUShort(), msg.code)

            // Wait for client to send close response
            waitForWrite(mockSocket, count = 2)

            // Client should transition to Disconnected
            withTimeout(5.seconds) {
                while (client.connectionState.value !is ConnectionState.Disconnected) {
                    delay(10)
                }
            }
            assertIs<ConnectionState.Disconnected>(client.connectionState.value)
            client.close()
        }

    @Test
    fun clientInitiatedClose() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            client.close()

            // Verify close frame was sent (index 0 = handshake, index 1 = close frame)
            assertTrue(mockSocket.writtenBuffers.size >= 2, "Expected at least 2 writes (handshake + close)")

            val closeFrame = mockSocket.writtenBuffers[1]
            closeFrame.position(0)

            val byte1 = closeFrame.readByte().toInt() and 0xFF
            // FIN=1, opcode=Close(0x8) => 0x88
            assertEquals(0x88, byte1)

            assertIs<ConnectionState.Disconnected>(client.connectionState.value)
        }

    // ========================================================================
    // C. Ping/Pong
    // ========================================================================

    @Test
    fun pingAutoRespondsWithPong() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            val pingPayload = "ping-data".encodeToByteArray()
            mockSocket.enqueueRead(MockHandshakeHelper.buildServerPingFrame(pingPayload))

            // Should emit Ping message
            val msg =
                withTimeout(5.seconds) {
                    client.receive().first()
                }
            assertIs<WebSocketMessage.Ping>(msg)

            // Wait for pong to be written
            waitForWrite(mockSocket, count = 2)

            val pongFrame = mockSocket.writtenBuffers[1]
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
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            mockSocket.enqueueRead(MockHandshakeHelper.buildServerPongFrame("pong-data".encodeToByteArray()))

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
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)

            val connectJob =
                client.scope.async {
                    client.connect()
                }

            waitForWrite(mockSocket)
            mockSocket.enqueueRead(MockHandshakeHelper.buildErrorResponse(200, "OK"))

            withTimeout(5.seconds) { connectJob.await() }

            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            assertNotNull(state.t)
        }

    @Test
    fun missingSecWebSocketAccept() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)

            val connectJob =
                client.scope.async {
                    client.connect()
                }

            waitForWrite(mockSocket)
            mockSocket.enqueueRead(MockHandshakeHelper.buildMissingAcceptResponse())

            withTimeout(5.seconds) { connectJob.await() }

            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            assertNotNull(state.t)
        }

    @Test
    fun wrongAcceptKey() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)

            val connectJob =
                client.scope.async {
                    client.connect()
                }

            waitForWrite(mockSocket)
            mockSocket.enqueueRead(MockHandshakeHelper.buildWrongAcceptResponse())

            withTimeout(5.seconds) { connectJob.await() }

            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            assertNotNull(state.t)
        }

    // ========================================================================
    // G. Exception Consistency
    // ========================================================================

    @Test
    fun handshakeRejected403() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)

            val connectJob =
                client.scope.async {
                    client.connect()
                }

            waitForWrite(mockSocket)
            mockSocket.enqueueRead(MockHandshakeHelper.buildErrorResponse(403, "Forbidden"))

            withTimeout(5.seconds) { connectJob.await() }

            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            assertNotNull(state.t)
            assertIs<WebSocketException.HandshakeRejected>(state.t)
        }

    @Test
    fun handshakeRejectedWrongAcceptKey() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)

            val connectJob =
                client.scope.async {
                    client.connect()
                }

            waitForWrite(mockSocket)
            mockSocket.enqueueRead(MockHandshakeHelper.buildWrongAcceptResponse())

            withTimeout(5.seconds) { connectJob.await() }

            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            assertNotNull(state.t)
            assertIs<WebSocketException.HandshakeRejected>(state.t)
        }

    @Test
    fun serverCloseNormal1000HasNoException() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            mockSocket.enqueueRead(MockHandshakeHelper.buildServerCloseFrame(1000u, "bye"))

            withTimeout(5.seconds) {
                while (client.connectionState.value !is ConnectionState.Disconnected) {
                    delay(10)
                }
            }

            val state = client.connectionState.value
            assertIs<WebSocketDisconnected>(state)
            assertEquals(1000.toUShort(), state.code)
            // Normal close should NOT carry an exception
            assertEquals(null, state.t)
            client.close()
        }

    @Test
    fun serverCloseAbnormalSurfacesConnectionClosed() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            mockSocket.enqueueRead(MockHandshakeHelper.buildServerCloseFrame(1011u, "internal error"))

            withTimeout(5.seconds) {
                while (client.connectionState.value !is ConnectionState.Disconnected) {
                    delay(10)
                }
            }

            val state = client.connectionState.value
            assertIs<WebSocketDisconnected>(state)
            assertEquals(1011.toUShort(), state.code)
            assertNotNull(state.t)
            assertIs<WebSocketException.ConnectionClosed>(state.t)
            assertEquals(1011.toUShort(), (state.t as WebSocketException.ConnectionClosed).code)
            client.close()
        }

    @Test
    fun reservedOpcodeSurfacesProtocolViolation() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            // Send a frame with reserved opcode 0x3 (RFC 6455 Section 5.2: opcodes 3-7 are reserved)
            // FIN=1, opcode=3, no mask, payload length=0
            mockSocket.enqueueReadBytes(0x83.toByte(), 0x00)

            withTimeout(5.seconds) {
                while (client.connectionState.value !is ConnectionState.Disconnected) {
                    delay(10)
                }
            }

            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            assertNotNull(state.t)
            assertIs<WebSocketException.ProtocolViolation>(state.t)
            client.close()
        }

    @Test
    fun socketErrorSurfacesTransportFailed() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            mockSocket.enqueueReadError(SocketIOException("Connection reset by peer"))

            withTimeout(5.seconds) {
                while (client.connectionState.value !is ConnectionState.Disconnected) {
                    delay(10)
                }
            }

            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            assertNotNull(state.t)
            assertIs<WebSocketException.TransportFailed>(state.t)
            assertIs<SocketIOException>((state.t as WebSocketException.TransportFailed).cause)
            client.close()
        }

    // ========================================================================
    // H. Write-After-Close and Double-Close Guards
    // ========================================================================

    @Test
    fun sendTextAfterCloseThrowsConnectionClosed() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            client.close()

            assertFailsWith<WebSocketException.ConnectionClosed> {
                client.send(WebSocketMessage.Text("should fail"))
            }
        }

    @Test
    fun sendBinaryAfterCloseThrowsConnectionClosed() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

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
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            client.close()

            assertFailsWith<WebSocketException.ConnectionClosed> {
                client.send(WebSocketMessage.Ping(EMPTY_BUFFER))
            }
        }

    @Test
    fun doubleCloseIsIdempotent() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            client.close()
            // Second close should return without error
            client.close()

            assertIs<ConnectionState.Disconnected>(client.connectionState.value)
        }

    @Test
    fun sendAfterServerCloseThrowsConnectionClosed() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            // Server sends close frame
            mockSocket.enqueueRead(MockHandshakeHelper.buildServerCloseFrame(1000u, "bye"))

            withTimeout(5.seconds) {
                while (client.connectionState.value !is ConnectionState.Disconnected) {
                    delay(10)
                }
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
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            val payload = BufferFactory.managed().allocate(3)
            payload.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
            payload.resetForRead()
            client.send(WebSocketMessage.Binary(payload))

            waitForWrite(mockSocket, count = 2)
            val frameBuffer = mockSocket.writtenBuffers[1]
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
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            val payload = BufferFactory.managed().allocate(4)
            payload.writeBytes("test".encodeToByteArray())
            payload.resetForRead()
            client.send(WebSocketMessage.Ping(payload))

            waitForWrite(mockSocket, count = 2)
            val frameBuffer = mockSocket.writtenBuffers[1]
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
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            client.send(WebSocketMessage.Pong(EMPTY_BUFFER))

            waitForWrite(mockSocket, count = 2)
            val frameBuffer = mockSocket.writtenBuffers[1]
            frameBuffer.position(0)
            val byte1 = frameBuffer.readByte().toInt() and 0xFF
            // FIN=1, opcode=Pong(0xA) => 0x8A
            assertEquals(0x8A, byte1)
            client.close()
        }

    @Test
    fun sendCloseWritesCloseFrame() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            client.send(WebSocketMessage.Close(1000.toUShort(), "bye"))

            waitForWrite(mockSocket, count = 2)
            val frameBuffer = mockSocket.writtenBuffers[1]
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
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            // Never called connect() — state is Initialized
            assertFailsWith<WebSocketException.ConnectionClosed> {
                client.send(WebSocketMessage.Text("should fail"))
            }
        }

    @Test
    fun sendDuringConnectingThrowsConnectionClosed() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)

            // Start connect but don't complete handshake — state stays Connecting
            val connectJob = client.scope.async { client.connect() }

            // Wait for handshake to be sent (state is now Connecting)
            waitForWrite(mockSocket)

            assertFailsWith<WebSocketException.ConnectionClosed> {
                client.send(WebSocketMessage.Text("should fail during connecting"))
            }

            // Complete handshake so the coroutine finishes cleanly
            val clientKey = MockHandshakeHelper.extractClientKey(mockSocket.writtenBuffers[0])
            mockSocket.enqueueRead(MockHandshakeHelper.buildHandshakeResponse(clientKey))
            withTimeout(5.seconds) { connectJob.await() }
            client.close()
        }

    // ========================================================================
    // K. Protocol edge cases
    // ========================================================================

    @Test
    fun invalidUtf8TextMessageSendsClose1007() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            // Send a text frame with invalid UTF-8: 0xC0 0x01 is overlong
            val invalidUtf8 = byteArrayOf(0xC0.toByte(), 0x01)
            mockSocket.enqueueRead(MockHandshakeHelper.buildServerFrame(Opcode.Text, invalidUtf8))
            // Keep the read loop alive long enough to process
            mockSocket.enqueueReadError(SocketClosedException.General("done"))

            withTimeout(5.seconds) {
                while (client.connectionState.value !is ConnectionState.Disconnected) {
                    delay(10)
                }
            }

            // Client should have sent a close frame with code 1007 (invalid payload)
            // Frame writes: index 0 = handshake, index 1+ = close frame
            val closeSent = mockSocket.writtenBuffers.drop(1).any { buf ->
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
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            // RFC 6455 Section 5.5.1: Close frame with 1-byte payload is a protocol error.
            // The status code requires 2 bytes, so 1 byte is invalid.
            val oneBytePayload = byteArrayOf(0x42)
            mockSocket.enqueueRead(MockHandshakeHelper.buildServerFrame(Opcode.Close, oneBytePayload))

            val msg = withTimeout(5.seconds) {
                client.receive().first()
            }

            // FrameReader parses this as a Close with PROTOCOL_ERROR code
            assertIs<WebSocketMessage.Close>(msg)

            withTimeout(5.seconds) {
                while (client.connectionState.value !is ConnectionState.Disconnected) {
                    delay(10)
                }
            }

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

            val mockSocket = MockClientToServerSocket()
            val client =
                DefaultWebSocketClient(
                    connectionOptions = defaultOptions,
                    parentScope = parentScope,
                    bufferFactory = BufferFactory.managed(),
                    socketOverride = mockSocket,
                )
            connectWithHandshake(client, mockSocket)

            assertIs<ConnectionState.Connected>(client.connectionState.value)

            // Cancel the PARENT scope — structured concurrency should propagate
            parentJob.cancel()

            // The client's read loop should terminate because its Job is a child of parentJob
            withTimeout(5.seconds) {
                while (client.connectionState.value is ConnectionState.Connected) {
                    delay(10)
                }
            }

            // Client should be disconnected (not stuck in Connected forever)
            assertIs<ConnectionState.Disconnected>(client.connectionState.value)
        }

    @Test
    fun clientCloseDoesNotCancelParent() =
        runStrictTest {
            val parentJob = kotlinx.coroutines.Job()
            val parentScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + parentJob)

            val mockSocket = MockClientToServerSocket()
            val client =
                DefaultWebSocketClient(
                    connectionOptions = defaultOptions,
                    parentScope = parentScope,
                    bufferFactory = BufferFactory.managed(),
                    socketOverride = mockSocket,
                )
            connectWithHandshake(client, mockSocket)

            // Close the CLIENT — should NOT cancel the parent
            client.close()

            // Parent job should still be active
            assertTrue(parentJob.isActive, "Parent job must remain active after client.close()")
            parentJob.cancel()
        }
}
