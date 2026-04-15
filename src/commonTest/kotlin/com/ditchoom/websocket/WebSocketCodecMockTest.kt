package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.managed
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * Tests for [WebSocketCodec] assembled via [connectWebSocket] using a mock transport.
 *
 * Tests verify the full codec lifecycle (handshake, read, write, close)
 * without any network I/O by injecting a [MockWebSocketTransport].
 */
class WebSocketCodecMockTest {
    private val defaultOptions =
        WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            tls = false,
            websocketEndpoint = "/ws",
        )

    // ========================================================================
    // A. Network Disruption / Transport Errors
    // ========================================================================

    @Test
    fun transportErrorDuringHandshake() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            mockTransport.enqueueReadError(Exception("Connection refused"))

            val exception =
                assertFailsWith<WebSocketException> {
                    connectWebSocket(mockTransport, defaultOptions.copy(bufferFactory = BufferFactory.managed()))
                }
            assertIs<WebSocketException.TransportFailed>(exception)
        }

    @Test
    fun transportDisconnectDuringHandshakeRead() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()

            val connectJob = coroutineScope {
                val job = async {
                    runCatching { connectWebSocket(mockTransport, defaultOptions.copy(bufferFactory = BufferFactory.managed())) }
                }
                MockAutobahnHelpers.waitForWrite(mockTransport)
                mockTransport.simulateDisconnect()
                job
            }

            val result = withTimeout(5.seconds) { connectJob.await() }
            assertTrue(result.isFailure)
        }

    @Test
    fun transportDisconnectDuringMessageRead() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            mockTransport.simulateDisconnect()

            val messages =
                withTimeout(5.seconds) {
                    connection.receive().take(1).toList()
                }
            assertTrue(messages.isEmpty())
            connection.close()
        }

    @Test
    fun transportErrorDuringReadLoop() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            mockTransport.enqueueReadError(Exception("Connection reset"))

            val messages =
                withTimeout(5.seconds) {
                    connection.receive().take(1).toList()
                }
            assertTrue(messages.isEmpty())
            connection.close()
        }

    @Test
    fun transportDisconnectMidFrame() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            mockTransport.enqueueReadBytes(0x81.toByte(), 0x0A)
            mockTransport.enqueueReadError(Exception("Connection lost mid-frame"))

            val messages =
                withTimeout(5.seconds) {
                    connection.receive().take(1).toList()
                }
            assertTrue(messages.isEmpty())
            connection.close()
        }

    @Test
    fun tcpCloseWithoutWsCloseUnblocksCollector() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            mockTransport.enqueueRead(MockHandshakeHelper.buildServerTextFrame("msg1"))
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerTextFrame("msg2"))
            mockTransport.enqueueReadError(Exception("Server TCP close"))

            val messages =
                withTimeout(5.seconds) {
                    connection.receive().take(5).toList()
                }

            assertEquals(2, messages.size)
            assertIs<WebSocketMessage.Text>(messages[0])
            assertEquals("msg1", (messages[0] as WebSocketMessage.Text).payload)
            assertIs<WebSocketMessage.Text>(messages[1])
            assertEquals("msg2", (messages[1] as WebSocketMessage.Text).payload)
            connection.close()
        }

    @Test
    fun tcpCloseImmediatelyAfterConnectUnblocksCollector() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            mockTransport.enqueueReadError(Exception("Server TCP close"))

            val messages =
                withTimeout(5.seconds) {
                    connection.receive().take(1).toList()
                }

            assertTrue(messages.isEmpty())
            connection.close()
        }

    @Test
    fun midFrameTcpCloseUnblocksCollector() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            mockTransport.enqueueRead(MockHandshakeHelper.buildServerTextFrame("before-crash"))
            mockTransport.enqueueReadBytes(0x81.toByte(), 0x0A)
            mockTransport.enqueueReadError(Exception("Connection lost"))

            val messages =
                withTimeout(5.seconds) {
                    connection.receive().take(5).toList()
                }

            assertEquals(1, messages.size)
            assertEquals("before-crash", (messages[0] as WebSocketMessage.Text).payload)
            connection.close()
        }

    // ========================================================================
    // B. Normal Lifecycle
    // ========================================================================

    @Test
    fun successfulHandshakeReturnsConnection() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            // Connection is returned — send succeeds (proves connected state)
            connection.send(WebSocketMessage.Text("hello"))
            connection.close()
        }

    @Test
    fun textMessageReceived() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            mockTransport.enqueueRead(MockHandshakeHelper.buildServerTextFrame("Hello, WebSocket!"))

            val msg =
                withTimeout(5.seconds) {
                    connection.receive().first()
                }

            assertIs<WebSocketMessage.Text>(msg)
            assertEquals("Hello, WebSocket!", msg.payload)
            connection.close()
        }

    @Test
    fun binaryMessageReceived() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport, BinaryPassThroughCodec)

            val testData = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerBinaryFrame(testData))

            val msg =
                withTimeout(5.seconds) {
                    connection.receive().first()
                }

            assertIs<WebSocketMessage.Binary<*>>(msg)
            @Suppress("UNCHECKED_CAST")
            val binary = msg as WebSocketMessage.Binary<ReadBuffer>
            val received = ByteArray(binary.payload.remaining())
            for (i in received.indices) {
                received[i] = binary.payload.readByte()
            }
            assertTrue(testData.contentEquals(received))
            connection.close()
        }

    @Test
    fun clientSendTextWritesMaskedFrame() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            connection.send(WebSocketMessage.Text("hello"))

            MockAutobahnHelpers.waitForWrite(mockTransport, count = 2)

            val frameBuffer = mockTransport.writtenBuffers[1]
            frameBuffer.position(0)

            val byte1 = frameBuffer.readByte().toInt() and 0xFF
            val byte2 = frameBuffer.readByte().toInt() and 0xFF

            assertEquals(0x81, byte1)
            assertTrue((byte2 and 0x80) != 0, "Client frame must have MASK bit set")
            assertEquals(5, byte2 and 0x7F)

            val maskKey = ByteArray(4)
            for (i in 0..3) maskKey[i] = frameBuffer.readByte()

            val expectedPayload = "hello".encodeToByteArray()
            for (i in expectedPayload.indices) {
                val maskedByte = frameBuffer.readByte()
                val unmasked = (maskedByte.toInt() xor maskKey[i % 4].toInt()).toByte()
                assertEquals(expectedPayload[i], unmasked)
            }
            connection.close()
        }

    @Test
    fun serverInitiatedClose() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            mockTransport.enqueueRead(MockHandshakeHelper.buildServerCloseFrame(1000u, "bye"))

            val msg =
                withTimeout(5.seconds) {
                    connection.receive().first()
                }
            assertIs<WebSocketMessage.Close>(msg)
            assertEquals(1000.toUShort(), msg.code)

            MockAutobahnHelpers.waitForWrite(mockTransport, count = 2)

            val remaining =
                withTimeout(5.seconds) {
                    connection.receive().toList()
                }
            assertTrue(remaining.isEmpty())
            connection.close()
        }

    @Test
    fun clientInitiatedClose() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            connection.close()

            assertTrue(mockTransport.writtenBuffers.size >= 2, "Expected at least 2 writes (handshake + close)")

            val closeFrame = mockTransport.writtenBuffers[1]
            closeFrame.position(0)

            val byte1 = closeFrame.readByte().toInt() and 0xFF
            assertEquals(0x88, byte1)
        }

    // ========================================================================
    // C. Ping/Pong
    // ========================================================================

    @Test
    fun pingAutoRespondsWithPong() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            val pingPayload = "ping-data".encodeToByteArray()
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerPingFrame(pingPayload))

            val msg =
                withTimeout(5.seconds) {
                    connection.receive().first()
                }
            assertIs<WebSocketMessage.Ping>(msg)

            MockAutobahnHelpers.waitForWrite(mockTransport, count = 2)

            val pongFrame = mockTransport.writtenBuffers[1]
            pongFrame.position(0)
            val byte1 = pongFrame.readByte().toInt() and 0xFF
            assertEquals(0x8A, byte1)

            val byte2 = pongFrame.readByte().toInt() and 0xFF
            assertTrue((byte2 and 0x80) != 0, "Pong must be masked (client frame)")
            val payloadLen = byte2 and 0x7F
            assertEquals(pingPayload.size, payloadLen)

            val maskKey = ByteArray(4)
            for (i in 0..3) maskKey[i] = pongFrame.readByte()

            for (i in pingPayload.indices) {
                val maskedByte = pongFrame.readByte()
                val unmasked = (maskedByte.toInt() xor maskKey[i % 4].toInt()).toByte()
                assertEquals(pingPayload[i], unmasked, "Pong payload byte $i mismatch")
            }
            connection.close()
        }

    @Test
    fun pongMessageEmitted() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            mockTransport.enqueueRead(MockHandshakeHelper.buildServerPongFrame("pong-data".encodeToByteArray()))

            val msg =
                withTimeout(5.seconds) {
                    connection.receive().first()
                }
            assertIs<WebSocketMessage.Pong>(msg)
            connection.close()
        }

    // ========================================================================
    // D. Handshake Edge Cases
    // ========================================================================

    @Test
    fun invalidStatusCode200() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()

            val result = coroutineScope {
                val job = async {
                    runCatching { connectWebSocket(mockTransport, defaultOptions.copy(bufferFactory = BufferFactory.managed())) }
                }
                MockAutobahnHelpers.waitForWrite(mockTransport)
                mockTransport.enqueueRead(MockHandshakeHelper.buildErrorResponse(200, "OK"))
                withTimeout(5.seconds) { job.await() }
            }

            assertTrue(result.isFailure)
        }

    @Test
    fun missingSecWebSocketAccept() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()

            val result = coroutineScope {
                val job = async {
                    runCatching { connectWebSocket(mockTransport, defaultOptions.copy(bufferFactory = BufferFactory.managed())) }
                }
                MockAutobahnHelpers.waitForWrite(mockTransport)
                mockTransport.enqueueRead(MockHandshakeHelper.buildMissingAcceptResponse())
                withTimeout(5.seconds) { job.await() }
            }

            assertTrue(result.isFailure)
        }

    @Test
    fun wrongAcceptKey() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()

            val result = coroutineScope {
                val job = async {
                    runCatching { connectWebSocket(mockTransport, defaultOptions.copy(bufferFactory = BufferFactory.managed())) }
                }
                MockAutobahnHelpers.waitForWrite(mockTransport)
                mockTransport.enqueueRead(MockHandshakeHelper.buildWrongAcceptResponse())
                withTimeout(5.seconds) { job.await() }
            }

            assertTrue(result.isFailure)
        }

    // ========================================================================
    // G. Exception Consistency
    // ========================================================================

    @Test
    fun handshakeRejected403() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()

            val result = coroutineScope {
                val job = async {
                    runCatching { connectWebSocket(mockTransport, defaultOptions.copy(bufferFactory = BufferFactory.managed())) }
                }
                MockAutobahnHelpers.waitForWrite(mockTransport)
                mockTransport.enqueueRead(MockHandshakeHelper.buildErrorResponse(403, "Forbidden"))
                withTimeout(5.seconds) { job.await() }
            }

            assertTrue(result.isFailure)
            assertIs<WebSocketException.HandshakeRejected>(result.exceptionOrNull())
        }

    @Test
    fun handshakeRejectedWrongAcceptKey() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()

            val result = coroutineScope {
                val job = async {
                    runCatching { connectWebSocket(mockTransport, defaultOptions.copy(bufferFactory = BufferFactory.managed())) }
                }
                MockAutobahnHelpers.waitForWrite(mockTransport)
                mockTransport.enqueueRead(MockHandshakeHelper.buildWrongAcceptResponse())
                withTimeout(5.seconds) { job.await() }
            }

            assertTrue(result.isFailure)
            assertIs<WebSocketException.HandshakeRejected>(result.exceptionOrNull())
        }

    @Test
    fun serverCloseNormal1000FlowCompletes() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            mockTransport.enqueueRead(MockHandshakeHelper.buildServerCloseFrame(1000u, "bye"))

            val messages =
                withTimeout(5.seconds) {
                    connection.receive().toList()
                }

            assertEquals(1, messages.size)
            assertIs<WebSocketMessage.Close>(messages[0])
            assertEquals(1000.toUShort(), (messages[0] as WebSocketMessage.Close).code)
            connection.close()
        }

    @Test
    fun serverCloseAbnormalSurfacesCloseMessage() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            mockTransport.enqueueRead(MockHandshakeHelper.buildServerCloseFrame(1011u, "internal error"))

            val messages =
                withTimeout(5.seconds) {
                    connection.receive().toList()
                }

            assertEquals(1, messages.size)
            assertIs<WebSocketMessage.Close>(messages[0])
            assertEquals(1011.toUShort(), (messages[0] as WebSocketMessage.Close).code)
            connection.close()
        }

    @Test
    fun reservedOpcodeClosesConnection() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            mockTransport.enqueueReadBytes(0x83.toByte(), 0x00)

            val messages =
                withTimeout(5.seconds) {
                    connection.receive().toList()
                }
            assertTrue(messages.isEmpty())
            connection.close()
        }

    @Test
    fun transportErrorDuringReadLoopClosesFlow() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            mockTransport.enqueueReadError(Exception("Connection reset by peer"))

            val messages =
                withTimeout(5.seconds) {
                    connection.receive().toList()
                }
            assertTrue(messages.isEmpty())
            connection.close()
        }

    // ========================================================================
    // H. Write-After-Close and Double-Close Guards
    // ========================================================================

    @Test
    fun sendTextAfterCloseThrowsConnectionClosed() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            connection.close()

            assertFailsWith<WebSocketException.ConnectionClosed> {
                connection.send(WebSocketMessage.Text("should fail"))
            }
        }

    @Test
    fun sendBinaryAfterCloseThrowsConnectionClosed() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport, BinaryPassThroughCodec)

            connection.close()

            val buffer = BufferFactory.managed().allocate(5)
            buffer.writeBytes("hello".encodeToByteArray())
            buffer.resetForRead()

            assertFailsWith<WebSocketException.ConnectionClosed> {
                connection.send(WebSocketMessage.Binary(buffer))
            }
        }

    @Test
    fun sendPingAfterCloseThrowsConnectionClosed() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            connection.close()

            assertFailsWith<WebSocketException.ConnectionClosed> {
                connection.send(WebSocketMessage.Ping(""))
            }
        }

    @Test
    fun doubleCloseIsIdempotent() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            connection.close()
            connection.close()
        }

    @Test
    fun sendAfterServerCloseThrowsConnectionClosed() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            mockTransport.enqueueRead(MockHandshakeHelper.buildServerCloseFrame(1000u, "bye"))

            withTimeout(5.seconds) {
                connection.receive().toList()
            }

            assertFailsWith<WebSocketException.ConnectionClosed> {
                connection.send(WebSocketMessage.Text("should fail after server close"))
            }
            connection.close()
        }

    // ========================================================================
    // I. send() with all WebSocketMessage types
    // ========================================================================

    @Test
    fun sendBinaryWritesMaskedFrame() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport, BinaryPassThroughCodec)

            val payload = BufferFactory.managed().allocate(3)
            payload.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
            payload.resetForRead()
            connection.send(WebSocketMessage.Binary(payload))

            MockAutobahnHelpers.waitForWrite(mockTransport, count = 2)
            val frameBuffer = mockTransport.writtenBuffers[1]
            frameBuffer.position(0)
            val byte1 = frameBuffer.readByte().toInt() and 0xFF
            assertEquals(0x82, byte1)
            val byte2 = frameBuffer.readByte().toInt() and 0xFF
            assertTrue((byte2 and 0x80) != 0, "MASK bit must be set")
            assertEquals(3, byte2 and 0x7F)
            connection.close()
        }

    @Test
    fun sendPingWritesMaskedFrame() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            connection.send(WebSocketMessage.Ping("test"))

            MockAutobahnHelpers.waitForWrite(mockTransport, count = 2)
            val frameBuffer = mockTransport.writtenBuffers[1]
            frameBuffer.position(0)
            val byte1 = frameBuffer.readByte().toInt() and 0xFF
            assertEquals(0x89, byte1)
            val byte2 = frameBuffer.readByte().toInt() and 0xFF
            assertTrue((byte2 and 0x80) != 0, "MASK bit must be set")
            assertEquals(4, byte2 and 0x7F)
            connection.close()
        }

    @Test
    fun sendPongWritesMaskedFrame() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            connection.send(WebSocketMessage.Pong(""))

            MockAutobahnHelpers.waitForWrite(mockTransport, count = 2)
            val frameBuffer = mockTransport.writtenBuffers[1]
            frameBuffer.position(0)
            val byte1 = frameBuffer.readByte().toInt() and 0xFF
            assertEquals(0x8A, byte1)
            connection.close()
        }

    @Test
    fun sendCloseWritesCloseFrame() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            connection.send(WebSocketMessage.Close(1000.toUShort(), "bye"))

            MockAutobahnHelpers.waitForWrite(mockTransport, count = 2)
            val frameBuffer = mockTransport.writtenBuffers[1]
            frameBuffer.position(0)
            val byte1 = frameBuffer.readByte().toInt() and 0xFF
            assertEquals(0x88, byte1)
            connection.close()
        }

    // ========================================================================
    // K. Protocol edge cases
    // ========================================================================

    @Test
    fun invalidUtf8TextMessageSendsClose1007() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            val invalidUtf8 = byteArrayOf(0xC0.toByte(), 0x01)
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerFrame(Opcode.Text, invalidUtf8))
            mockTransport.enqueueReadError(Exception("done"))

            withTimeout(5.seconds) {
                connection.receive().toList()
            }

            val closeSent = mockTransport.writtenBuffers.drop(1).any { buf ->
                buf.position(0)
                val b1 = buf.readByte().toInt() and 0xFF
                (b1 and 0x0F) == 0x08
            }
            assertTrue(closeSent, "Client should send a close frame on invalid UTF-8")
            connection.close()
        }

    @Test
    fun serverCloseWithOneBytePayloadIsProtocolError() =
        runStrictTest {
            val mockTransport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(mockTransport)

            val oneBytePayload = byteArrayOf(0x42)
            mockTransport.enqueueRead(MockHandshakeHelper.buildServerFrame(Opcode.Close, oneBytePayload))

            withTimeout(5.seconds) {
                connection.receive().toList()
            }

            // 1-byte close payload is a protocol error — client sends 1002 close
            MockAutobahnHelpers.waitForWrite(mockTransport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(mockTransport.writtenBuffers, 1002u)
            connection.close()
        }

    // ========================================================================
    // L. Structured concurrency — parent cancellation propagates
    // ========================================================================

    @Test
    fun parentScopeCancellationClosesConnection() =
        runStrictTest {
            val parentJob = kotlinx.coroutines.Job()
            val parentScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + parentJob)

            val mockTransport = MockWebSocketTransport()
            val connection = coroutineScope {
                val job = async {
                    connectWebSocket(mockTransport, defaultOptions.copy(bufferFactory = BufferFactory.managed()), parentScope = parentScope)
                }
                MockAutobahnHelpers.waitForWrite(mockTransport)
                val clientKey = MockHandshakeHelper.extractClientKey(mockTransport.writtenBuffers[0])
                mockTransport.enqueueRead(MockHandshakeHelper.buildHandshakeResponse(clientKey))
                withTimeout(5.seconds) { job.await() }
            }

            // Cancel the PARENT scope — structured concurrency should propagate
            parentJob.cancel()
            delay(100)

            // Send should fail — connection closed by parent cancellation
            assertFailsWith<WebSocketException.ConnectionClosed> {
                connection.send(WebSocketMessage.Text("should fail"))
            }
        }

    @Test
    fun clientCloseDoesNotCancelParent() =
        runStrictTest {
            val parentJob = kotlinx.coroutines.Job()
            val parentScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + parentJob)

            val mockTransport = MockWebSocketTransport()
            val connection = coroutineScope {
                val job = async {
                    connectWebSocket(mockTransport, defaultOptions.copy(bufferFactory = BufferFactory.managed()), parentScope = parentScope)
                }
                MockAutobahnHelpers.waitForWrite(mockTransport)
                val clientKey = MockHandshakeHelper.extractClientKey(mockTransport.writtenBuffers[0])
                mockTransport.enqueueRead(MockHandshakeHelper.buildHandshakeResponse(clientKey))
                withTimeout(5.seconds) { job.await() }
            }

            connection.close()

            assertTrue(parentJob.isActive, "Parent job must remain active after connection.close()")
            parentJob.cancel()
        }
}
