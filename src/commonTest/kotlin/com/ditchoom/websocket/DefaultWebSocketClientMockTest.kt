package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
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
            allocationZone = AllocationZone.Heap,
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
            mockSocket.enqueueReadError(SocketException("Connection refused"))

            client.connect()

            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
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
            mockSocket.enqueueReadError(SocketException("Connection reset"))

            // Wait for state to transition
            withTimeout(5.seconds) {
                while (client.connectionState.value !is ConnectionState.Disconnected) {
                    delay(10)
                }
            }

            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            assertNotNull(state.t)
            assertIs<SocketException>(state.t)
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
            mockSocket.enqueueReadError(SocketClosedException("Connection lost mid-frame"))

            // Wait for disconnection
            withTimeout(5.seconds) {
                while (client.connectionState.value !is ConnectionState.Disconnected) {
                    delay(10)
                }
            }

            assertIs<ConnectionState.Disconnected>(client.connectionState.value)
            client.close()
        }

    @Test
    fun writeAfterCloseReturnsGracefully() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            client.close()

            // Writing after close should not throw
            client.write("hello after close")
            client.write("another message")
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
                    client.incomingMessages.first()
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
                    client.incomingMessages.first()
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
    fun clientWriteSendsMaskedFrame() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val client = createClient(mockSocket)
            connectWithHandshake(client, mockSocket)

            client.write("hello")

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
                    client.incomingMessages.first()
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
                    client.incomingMessages.first()
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
                    client.incomingMessages.first()
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
}
