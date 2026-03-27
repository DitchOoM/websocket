package com.ditchoom.websocket

import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.SSLHandshakeFailedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.SocketIOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class WebSocketReconnectionClassifierTests {
    @Test
    fun handshakeRejected_givesUp() =
        runTest {
            val classifier = WebSocketReconnectionClassifier()
            val result =
                classifier.classify(
                    WebSocketException.HandshakeRejected("403 Forbidden", statusCode = 403),
                )
            assertIs<ReconnectDecision.GiveUp>(result)
        }

    @Test
    fun protocolViolation_givesUp() =
        runTest {
            val classifier = WebSocketReconnectionClassifier()
            val result = classifier.classify(WebSocketException.ProtocolViolation("Bad frame"))
            assertIs<ReconnectDecision.GiveUp>(result)
        }

    @Test
    fun transportFailed_connectionRefused_retries() =
        runTest {
            val classifier = WebSocketReconnectionClassifier()
            val result =
                classifier.classify(
                    WebSocketException.TransportFailed(
                        "Connection failed",
                        SocketConnectionException.Refused("localhost", 8080),
                    ),
                )
            assertIs<ReconnectDecision.RetryAfter>(result)
        }

    @Test
    fun transportFailed_sslHandshake_givesUp() =
        runTest {
            val classifier = WebSocketReconnectionClassifier()
            val result =
                classifier.classify(
                    WebSocketException.TransportFailed(
                        "TLS failed",
                        SSLHandshakeFailedException("cert rejected"),
                    ),
                )
            assertIs<ReconnectDecision.GiveUp>(result)
        }

    @Test
    fun normalClose_givesUp() =
        runTest {
            val classifier = WebSocketReconnectionClassifier()
            val result =
                classifier.classify(
                    WebSocketException.ConnectionClosed("Normal close", code = 1000.toUShort()),
                )
            assertIs<ReconnectDecision.GiveUp>(result)
        }

    @Test
    fun abnormalClose_retries() =
        runTest {
            val classifier = WebSocketReconnectionClassifier()
            val result =
                classifier.classify(
                    WebSocketException.ConnectionClosed("Abnormal close", code = 1006.toUShort()),
                )
            assertIs<ReconnectDecision.RetryAfter>(result)
        }

    @Test
    fun backoffProgression() =
        runTest {
            val classifier = WebSocketReconnectionClassifier()
            val error = WebSocketException.TransportFailed("IO", SocketIOException("transient"))

            val first = classifier.classify(error)
            assertIs<ReconnectDecision.RetryAfter>(first)
            assertTrue(first.delay == 100.milliseconds)

            val second = classifier.classify(error)
            assertIs<ReconnectDecision.RetryAfter>(second)
            assertTrue(second.delay == 200.milliseconds)
        }

    @Test
    fun reset_restoresInitialDelay() =
        runTest {
            val classifier = WebSocketReconnectionClassifier()
            val error = WebSocketException.TransportFailed("IO", SocketIOException("transient"))

            repeat(5) { classifier.classify(error) }
            classifier.reset()

            val result = classifier.classify(error)
            assertIs<ReconnectDecision.RetryAfter>(result)
            assertTrue(result.delay == 100.milliseconds)
        }

    @Test
    fun unknownException_retries() =
        runTest {
            val classifier = WebSocketReconnectionClassifier()
            val result = classifier.classify(RuntimeException("unexpected"))
            assertIs<ReconnectDecision.RetryAfter>(result)
        }
}
