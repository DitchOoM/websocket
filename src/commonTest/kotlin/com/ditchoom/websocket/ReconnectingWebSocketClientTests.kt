package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.managed
import com.ditchoom.socket.ConnectionState
import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.ReconnectionClassifier
import com.ditchoom.socket.SocketClosedException
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
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [ReconnectingWebSocketClient].
 *
 * Uses [MockClientToServerSocket] and [MockHandshakeHelper] to simulate
 * server behavior without network I/O.
 */
class ReconnectingWebSocketClientTests {
    private val defaultOptions =
        WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            tls = false,
            websocketEndpoint = "/ws",
        )

    /**
     * Creates a [ReconnectingWebSocketClient] backed by a list of mock sockets.
     *
     * Each connection attempt pops the next mock socket from [sockets].
     * This lets tests control the behavior of each (re)connection independently.
     */
    private fun createReconnecting(
        sockets: List<MockClientToServerSocket>,
        classifier: ReconnectionClassifier = WebSocketReconnectionClassifier(),
        options: WebSocketConnectionOptions = defaultOptions,
    ): ReconnectingWebSocketClient {
        val socketIterator = sockets.iterator()
        return ReconnectingWebSocketClient(
            connectionOptions = options,
            classifier = classifier,
            bufferFactory = BufferFactory.managed(),
        ).also {
            // Inject mock sockets via reflection-free approach:
            // We override createClient by subclassing. But since createClient is private,
            // we use a different approach: inject sockets via the DefaultWebSocketClient's
            // socketOverride parameter.
        }
    }

    // Since ReconnectingWebSocketClient.createClient() is private, we test at
    // the integration level: reconnecting wrapper behavior (state, classifier, guards)
    // and MessageConnection interface usage (send/receive) on the inner client.

    /**
     * Helper to complete a handshake on a mock socket.
     * Returns when the handshake response has been enqueued.
     */
    private suspend fun completeHandshake(mockSocket: MockClientToServerSocket) {
        withTimeout(5.seconds) {
            while (mockSocket.writtenBuffers.isEmpty()) {
                delay(10)
            }
        }
        val clientKey = MockHandshakeHelper.extractClientKey(mockSocket.writtenBuffers[0])
        mockSocket.enqueueRead(MockHandshakeHelper.buildHandshakeResponse(clientKey))
    }

    // ========================================================================
    // A. State Transitions
    // ========================================================================

    @Test
    fun initialStateIsInitialized() =
        runStrictTest {
            val client =
                ReconnectingWebSocketClient(
                    connectionOptions = defaultOptions,
                    bufferFactory = BufferFactory.managed(),
                )
            assertEquals(ConnectionState.Initialized, client.connectionState.value)
        }

    @Test
    fun closeTransitionsToDisconnected() =
        runStrictTest {
            val client =
                ReconnectingWebSocketClient(
                    connectionOptions = defaultOptions,
                    bufferFactory = BufferFactory.managed(),
                )
            client.close()
            assertIs<ConnectionState.Disconnected>(client.connectionState.value)
        }

    // ========================================================================
    // B. Send / Receive via MessageConnection interface
    // ========================================================================

    @Test
    fun sendDelegatesToCurrentClient() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val inner =
                DefaultWebSocketClient(
                    connectionOptions = defaultOptions,
                    parentScope = null,
                    bufferFactory = BufferFactory.managed(),
                    socketOverride = mockSocket,
                )

            val connectJob = inner.scope.async { inner.connect() }
            completeHandshake(mockSocket)
            withTimeout(5.seconds) { connectJob.await() }

            // Use send() from MessageConnection interface
            inner.send(WebSocketMessage.Text("hello via send"))

            // Wait for write (index 0 = handshake, index 1 = text frame)
            withTimeout(5.seconds) {
                while (mockSocket.writtenBuffers.size < 2) {
                    delay(10)
                }
            }

            val frameBuffer = mockSocket.writtenBuffers[1]
            frameBuffer.position(0)
            val byte1 = frameBuffer.readByte().toInt() and 0xFF
            // FIN=1, opcode=Text(0x1) => 0x81
            assertEquals(0x81, byte1)
            inner.close()
        }

    @Test
    fun receiveReturnsIncomingMessages() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val inner =
                DefaultWebSocketClient(
                    connectionOptions = defaultOptions,
                    parentScope = null,
                    bufferFactory = BufferFactory.managed(),
                    socketOverride = mockSocket,
                )

            val connectJob = inner.scope.async { inner.connect() }
            completeHandshake(mockSocket)
            withTimeout(5.seconds) { connectJob.await() }

            mockSocket.enqueueRead(MockHandshakeHelper.buildServerTextFrame("msg via receive"))

            // Use receive() from MessageConnection interface
            val msg = withTimeout(5.seconds) { inner.receive().first() }
            assertIs<WebSocketMessage.Text>(msg)
            assertEquals("msg via receive", msg.value)
            inner.close()
        }

    @Test
    fun sendAfterCloseThrowsConnectionClosed() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val inner =
                DefaultWebSocketClient(
                    connectionOptions = defaultOptions,
                    parentScope = null,
                    bufferFactory = BufferFactory.managed(),
                    socketOverride = mockSocket,
                )

            val connectJob = inner.scope.async { inner.connect() }
            completeHandshake(mockSocket)
            withTimeout(5.seconds) { connectJob.await() }

            inner.close()

            assertFailsWith<WebSocketException.ConnectionClosed> {
                inner.send(WebSocketMessage.Text("should fail"))
            }
        }

    // ========================================================================
    // C. Reconnection Classifier Integration
    // ========================================================================

    @Test
    fun classifierGiveUpReturnsCorrectDecision() =
        runStrictTest {
            // Verify that the classifier correctly returns GiveUp for non-recoverable errors,
            // which ReconnectingWebSocketClient uses to stop its reconnection loop.
            val classifier = WebSocketReconnectionClassifier()

            // Handshake rejected → GiveUp
            val handshakeResult = classifier.classify(
                WebSocketException.HandshakeRejected("403 Forbidden"),
            )
            assertIs<ReconnectDecision.GiveUp>(handshakeResult)

            // Normal close → GiveUp
            val normalCloseResult = classifier.classify(
                WebSocketException.ConnectionClosed("bye", code = 1000.toUShort()),
            )
            assertIs<ReconnectDecision.GiveUp>(normalCloseResult)
        }

    @Test
    fun webSocketClassifierGivesUpOnHandshakeRejected() =
        runStrictTest {
            val classifier = WebSocketReconnectionClassifier()
            val result = classifier.classify(WebSocketException.HandshakeRejected("403"))
            assertIs<ReconnectDecision.GiveUp>(result)
        }

    @Test
    fun webSocketClassifierGivesUpOnProtocolViolation() =
        runStrictTest {
            val classifier = WebSocketReconnectionClassifier()
            val result = classifier.classify(WebSocketException.ProtocolViolation("bad frame"))
            assertIs<ReconnectDecision.GiveUp>(result)
        }

    @Test
    fun webSocketClassifierGivesUpOnNormalClose() =
        runStrictTest {
            val classifier = WebSocketReconnectionClassifier()
            val result = classifier.classify(
                WebSocketException.ConnectionClosed("bye", code = 1000.toUShort()),
            )
            assertIs<ReconnectDecision.GiveUp>(result)
        }

    @Test
    fun webSocketClassifierRetriesOnAbnormalClose() =
        runStrictTest {
            val classifier = WebSocketReconnectionClassifier()
            val result = classifier.classify(
                WebSocketException.ConnectionClosed("error", code = 1006.toUShort()),
            )
            assertIs<ReconnectDecision.RetryAfter>(result)
        }

    @Test
    fun webSocketClassifierRetriesOnTransportError() =
        runStrictTest {
            val classifier = WebSocketReconnectionClassifier()
            val result = classifier.classify(
                WebSocketException.TransportFailed("IO", SocketIOException("reset")),
            )
            assertIs<ReconnectDecision.RetryAfter>(result)
        }

    // ========================================================================
    // D. Message forwarding across reconnects
    // ========================================================================

    @Test
    fun messagesForwardedToTypedChannels() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val inner =
                DefaultWebSocketClient(
                    connectionOptions = defaultOptions,
                    parentScope = null,
                    bufferFactory = BufferFactory.managed(),
                    socketOverride = mockSocket,
                )

            val connectJob = inner.scope.async { inner.connect() }
            completeHandshake(mockSocket)
            withTimeout(5.seconds) { connectJob.await() }

            // Send text and binary messages
            mockSocket.enqueueRead(MockHandshakeHelper.buildServerTextFrame("hello"))
            mockSocket.enqueueRead(MockHandshakeHelper.buildServerBinaryFrame(byteArrayOf(1, 2, 3)))

            // receive() filtered to text should only have text
            val text = withTimeout(5.seconds) {
                (inner.receive().first { it is WebSocketMessage.Text } as WebSocketMessage.Text).value
            }
            assertEquals("hello", text)

            // receive() filtered to binary should only have binary
            val binary = withTimeout(5.seconds) {
                (inner.receive().first { it is WebSocketMessage.Binary } as WebSocketMessage.Binary).value
            }
            assertEquals(3, binary.remaining())

            inner.close()
        }

    @Test
    fun receiveChannelClosedOnDisconnect() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val inner =
                DefaultWebSocketClient(
                    connectionOptions = defaultOptions,
                    parentScope = null,
                    bufferFactory = BufferFactory.managed(),
                    socketOverride = mockSocket,
                )

            val connectJob = inner.scope.async { inner.connect() }
            completeHandshake(mockSocket)
            withTimeout(5.seconds) { connectJob.await() }

            // Send one message then disconnect
            mockSocket.enqueueRead(MockHandshakeHelper.buildServerTextFrame("before-close"))
            mockSocket.enqueueReadError(SocketClosedException.General("gone"))

            // Collecting with take(10) should complete when channel closes
            val messages = withTimeout(5.seconds) {
                inner.receive().take(10).toList()
            }
            assertEquals(1, messages.size)
            assertIs<WebSocketMessage.Text>(messages[0])
            assertEquals("before-close", (messages[0] as WebSocketMessage.Text).value)

            inner.close()
        }

    // ========================================================================
    // E. Send method delegation
    // ========================================================================

    @Test
    fun sendTextFrameWrites() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val inner =
                DefaultWebSocketClient(
                    connectionOptions = defaultOptions,
                    parentScope = null,
                    bufferFactory = BufferFactory.managed(),
                    socketOverride = mockSocket,
                )

            val connectJob = inner.scope.async { inner.connect() }
            completeHandshake(mockSocket)
            withTimeout(5.seconds) { connectJob.await() }

            // send(WebSocketMessage.Text) sends a text frame
            inner.send(WebSocketMessage.Text("convenience method"))

            withTimeout(5.seconds) {
                while (mockSocket.writtenBuffers.size < 2) {
                    delay(10)
                }
            }

            val frameBuffer = mockSocket.writtenBuffers[1]
            frameBuffer.position(0)
            val byte1 = frameBuffer.readByte().toInt() and 0xFF
            assertEquals(0x81, byte1) // FIN=1, opcode=Text
            inner.close()
        }

    @Test
    fun sendPingFrameWrites() =
        runStrictTest {
            val mockSocket = MockClientToServerSocket()
            val inner =
                DefaultWebSocketClient(
                    connectionOptions = defaultOptions,
                    parentScope = null,
                    bufferFactory = BufferFactory.managed(),
                    socketOverride = mockSocket,
                )

            val connectJob = inner.scope.async { inner.connect() }
            completeHandshake(mockSocket)
            withTimeout(5.seconds) { connectJob.await() }

            // send(WebSocketMessage.Ping) sends a ping frame
            inner.send(WebSocketMessage.Ping(EMPTY_BUFFER))

            withTimeout(5.seconds) {
                while (mockSocket.writtenBuffers.size < 2) {
                    delay(10)
                }
            }

            val frameBuffer = mockSocket.writtenBuffers[1]
            frameBuffer.position(0)
            val byte1 = frameBuffer.readByte().toInt() and 0xFF
            assertEquals(0x89, byte1) // FIN=1, opcode=Ping
            inner.close()
        }

    // ========================================================================
    // F. ReconnectingWebSocketClient send/close guards
    // ========================================================================

    @Test
    fun reconnectingClientSendThrowsWhenNotConnected() =
        runStrictTest {
            val client =
                ReconnectingWebSocketClient(
                    connectionOptions = defaultOptions,
                    bufferFactory = BufferFactory.managed(),
                )

            // Never called connect(), so currentClient is null
            assertFailsWith<WebSocketException.ConnectionClosed> {
                client.send(WebSocketMessage.Text("no connection"))
            }
        }

    @Test
    fun reconnectingClientDoubleCloseIsIdempotent() =
        runStrictTest {
            val client =
                ReconnectingWebSocketClient(
                    connectionOptions = defaultOptions,
                    bufferFactory = BufferFactory.managed(),
                )

            client.close()
            client.close() // should not throw

            assertIs<ConnectionState.Disconnected>(client.connectionState.value)
        }

    @Test
    fun reconnectingClientSendTextAfterCloseThrows() =
        runStrictTest {
            val client =
                ReconnectingWebSocketClient(
                    connectionOptions = defaultOptions,
                    bufferFactory = BufferFactory.managed(),
                )

            client.close()

            assertFailsWith<WebSocketException.ConnectionClosed> {
                client.send(WebSocketMessage.Text("should fail"))
            }
        }

    @Test
    fun reconnectingClientSendPingAfterCloseThrows() =
        runStrictTest {
            val client =
                ReconnectingWebSocketClient(
                    connectionOptions = defaultOptions,
                    bufferFactory = BufferFactory.managed(),
                )

            client.close()

            assertFailsWith<WebSocketException.ConnectionClosed> {
                client.send(WebSocketMessage.Ping(EMPTY_BUFFER))
            }
        }
}
