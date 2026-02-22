package com.ditchoom.websocket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.wrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Validates WebSocket TLS (WSS) connectivity against public endpoints.
 *
 * Tests verify the full TLS + WebSocket pipeline: TCP connect, TLS handshake
 * (certificate validation, hostname verification), HTTP/1.1 upgrade to WebSocket,
 * and bidirectional framed messaging.
 *
 * Endpoints tested (different CAs and server stacks):
 * - broker.hivemq.com:8884        (WSS, DigiCert/Amazon CA, HiveMQ MQTT broker)
 * - test.mosquitto.org:8081       (WSS, Let's Encrypt CA, Mosquitto MQTT broker)
 * - echo.websocket.org/.ws        (WSS, standard echo server)
 * - websocket-echo.com            (WSS, echo server, different CA)
 * - socketsbay.com                (WSS, echo server, yet another CA)
 *
 * Tests use [connectOrSkip] to gracefully handle flaky public servers.
 */
class PublicWssValidationTest {

    // --- HiveMQ WSS (DigiCert/Amazon Trust CA) ---

    @Test
    fun hivemqWssConnect() = runTestNoTimeSkipping(timeout = 30.seconds) {
        val ws = connectOrSkip(
            WebSocketConnectionOptions(
                name = "broker.hivemq.com",
                port = 8884,
                tls = true,
                websocketEndpoint = "/mqtt",
                protocols = listOf("mqtt"),
                connectionTimeout = 15.seconds,
            ),
        ) ?: return@runTestNoTimeSkipping
        try {
            assertTrue(
                ws.connectionState.value == ConnectionState.Connected,
                "Should be connected via WSS to HiveMQ",
            )
        } finally {
            ws.close()
        }
    }

    // --- Mosquitto WSS (Let's Encrypt CA) ---

    @Test
    fun mosquittoWssConnect() = runTestNoTimeSkipping(timeout = 30.seconds) {
        val ws = connectOrSkip(
            WebSocketConnectionOptions(
                name = "test.mosquitto.org",
                port = 8081,
                tls = true,
                websocketEndpoint = "/",
                protocols = listOf("mqtt"),
                connectionTimeout = 15.seconds,
            ),
        ) ?: return@runTestNoTimeSkipping
        try {
            assertTrue(
                ws.connectionState.value == ConnectionState.Connected,
                "Should be connected via WSS to Mosquitto",
            )
        } finally {
            ws.close()
        }
    }

    // --- echo.websocket.org (note: endpoint is /.ws, not /) ---

    @Test
    fun echoWebsocketOrgText() = runTestNoTimeSkipping(timeout = 30.seconds) {
        val ws = connectOrSkip(
            WebSocketConnectionOptions(
                name = "echo.websocket.org",
                port = 443,
                tls = true,
                websocketEndpoint = "/.ws",
                connectionTimeout = 15.seconds,
            ),
        ) ?: return@runTestNoTimeSkipping
        try {
            val message = "ditchoom-wss-test"
            // Server may send a welcome message first, so send then filter for our echo
            ws.write(message)
            val echo = withTimeout(10.seconds) {
                ws.incomingTextMessages.first { it == message }
            }
            assertEquals(message, echo)
        } finally {
            ws.close()
        }
    }

    @Test
    fun echoWebsocketOrgBinary() = runTestNoTimeSkipping(timeout = 30.seconds) {
        val ws = connectOrSkip(
            WebSocketConnectionOptions(
                name = "echo.websocket.org",
                port = 443,
                tls = true,
                websocketEndpoint = "/.ws",
                connectionTimeout = 15.seconds,
            ),
        ) ?: return@runTestNoTimeSkipping
        try {
            val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0xF0.toByte(), 0xFF.toByte())
            launch(Dispatchers.Default) { ws.write(PlatformBuffer.wrap(payload)) }
            val echo = withTimeout(10.seconds) {
                ws.incomingBinaryMessages.first()
            }
            val received = echo.readByteArray(echo.remaining())
            assertTrue(payload.contentEquals(received), "Binary echo mismatch")
        } finally {
            ws.close()
        }
    }

    // --- websocket-echo.com ---

    @Test
    fun websocketEchoDotComText() = runTestNoTimeSkipping(timeout = 30.seconds) {
        val ws = connectOrSkip(
            WebSocketConnectionOptions(
                name = "websocket-echo.com",
                port = 443,
                tls = true,
                connectionTimeout = 15.seconds,
            ),
        ) ?: return@runTestNoTimeSkipping
        try {
            val message = "ditchoom-echo-test"
            launch(Dispatchers.Default) { ws.write(message) }
            val echo = withTimeout(10.seconds) {
                ws.incomingMessages.take(1).first() as WebSocketMessage.Text
            }
            assertEquals(message, echo.value)
        } finally {
            ws.close()
        }
    }

    // --- socketsbay.com ---

    @Test
    fun socketsbayWssEcho() = runTestNoTimeSkipping(timeout = 30.seconds) {
        val ws = connectOrSkip(
            WebSocketConnectionOptions(
                name = "socketsbay.com",
                port = 443,
                tls = true,
                websocketEndpoint = "/wss/v2/1/demo/",
                connectionTimeout = 15.seconds,
            ),
        ) ?: return@runTestNoTimeSkipping
        try {
            val message = "ditchoom-socketsbay-test"
            launch(Dispatchers.Default) { ws.write(message) }
            val echo = withTimeout(10.seconds) {
                ws.incomingMessages.take(1).first() as WebSocketMessage.Text
            }
            assertEquals(message, echo.value)
        } finally {
            ws.close()
        }
    }

    // --- Sustained TLS communication tests ---

    /**
     * Tests multiple sequential messages over WSS to verify TLS session
     * state is maintained correctly across reads/writes.
     */
    @Test
    fun wssMultipleMessagesEcho() = runTestNoTimeSkipping(timeout = 30.seconds) {
        val ws = connectOrSkip(
            WebSocketConnectionOptions(
                name = "echo.websocket.org",
                port = 443,
                tls = true,
                websocketEndpoint = "/.ws",
                connectionTimeout = 15.seconds,
            ),
        ) ?: return@runTestNoTimeSkipping
        try {
            val messageCount = 5
            val messages = (1..messageCount).map { "ditchoom-multi-$it" }
            // Send all messages
            for (msg in messages) {
                ws.write(msg)
            }
            // Collect echoes (skip any server welcome messages)
            val echoes = withTimeout(15.seconds) {
                ws.incomingTextMessages
                    .filter { it.startsWith("ditchoom-multi-") }
                    .take(messageCount)
                    .toList()
            }
            assertEquals(messages, echoes, "All messages should be echoed in order")
        } finally {
            ws.close()
        }
    }

    /**
     * Tests interleaved binary and text messages over WSS.
     * Stresses the TLS read path with different frame types.
     */
    @Test
    fun wssInterleavedTextAndBinary() = runTestNoTimeSkipping(timeout = 30.seconds) {
        val ws = connectOrSkip(
            WebSocketConnectionOptions(
                name = "echo.websocket.org",
                port = 443,
                tls = true,
                websocketEndpoint = "/.ws",
                connectionTimeout = 15.seconds,
            ),
        ) ?: return@runTestNoTimeSkipping
        try {
            // Send text then binary
            val textMsg = "ditchoom-interleave-text"
            val binaryPayload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

            ws.write(textMsg)
            ws.write(PlatformBuffer.wrap(binaryPayload))

            // Collect both echoes
            var gotText = false
            var gotBinary = false
            withTimeout(10.seconds) {
                ws.incomingMessages.takeWhile {
                    when (it) {
                        is WebSocketMessage.Text -> if (it.value == textMsg) gotText = true
                        is WebSocketMessage.Binary -> {
                            val received = it.value.readByteArray(it.value.remaining())
                            if (binaryPayload.contentEquals(received)) gotBinary = true
                        }
                        else -> {}
                    }
                    !(gotText && gotBinary) // continue while we haven't got both
                }.collect {}
            }
            assertTrue(gotText, "Should receive text echo over WSS")
            assertTrue(gotBinary, "Should receive binary echo over WSS")
        } finally {
            ws.close()
        }
    }

    /**
     * Attempts WSS connect. Returns connected client, or null if endpoint
     * is unreachable (public servers can be flaky).
     */
    private suspend fun connectOrSkip(options: WebSocketConnectionOptions): WebSocketClient? {
        val ws = WebSocketClient.allocate(options)
        return try {
            ws.connect()
            ws.awaitConnected()
            ws
        } catch (e: Exception) {
            println("Skipping ${options.name}:${options.port}${options.websocketEndpoint} - ${e::class.simpleName}: ${e.message}")
            try {
                ws.close()
            } catch (_: Exception) {}
            null
        }
    }
}
