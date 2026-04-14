package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec
import com.ditchoom.websocket.codecs.StringCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
 *
 */
class PublicWssValidationTest {
    // --- HiveMQ WSS (DigiCert/Amazon Trust CA) ---

    @Test
    fun hivemqWssConnect() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val ws =
                connectForTest(
                    WebSocketConnectionOptions(
                        name = "broker.hivemq.com",
                        port = 8884,
                        tls = true,
                        websocketEndpoint = "/mqtt",
                        protocols = listOf("mqtt"),
                        connectionTimeout = 15.seconds,
                    ),
                    StringCodec,
                )
            try {
                // connectForTest() already performed the handshake — WSS connection is established
            } finally {
                ws.close()
            }
        }

    // --- Mosquitto WSS (Let's Encrypt CA) ---

    @Test
    fun mosquittoWssConnect() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val ws =
                connectForTest(
                    WebSocketConnectionOptions(
                        name = "test.mosquitto.org",
                        port = 8081,
                        tls = true,
                        websocketEndpoint = "/",
                        protocols = listOf("mqtt"),
                        connectionTimeout = 15.seconds,
                    ),
                    StringCodec,
                )
            try {
                // connectForTest() already performed the handshake — WSS connection is established
            } finally {
                ws.close()
            }
        }

    // --- echo.websocket.org (note: endpoint is /.ws, not /) ---

    @Test
    fun echoWebsocketOrgText() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val ws =
                connectForTest(
                    WebSocketConnectionOptions(
                        name = "echo.websocket.org",
                        port = 443,
                        tls = true,
                        websocketEndpoint = "/.ws",
                        connectionTimeout = 15.seconds,
                    ),
                    StringCodec,
                )
            try {
                val message = "ditchoom-wss-test"
                // Server may send a welcome message first, so send then filter for our echo
                ws.send(WebSocketMessage.Text(message))
                val echo =
                    withTimeout(10.seconds) {
                        ws.receive().filterIsInstance<WebSocketMessage.Text<String>>().map { it.payload }.first { it == message }
                    }
                assertEquals(message, echo)
            } finally {
                ws.close()
            }
        }

    @Test
    fun echoWebsocketOrgBinary() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val ws =
                connectForTest(
                    WebSocketConnectionOptions(
                        name = "echo.websocket.org",
                        port = 443,
                        tls = true,
                        websocketEndpoint = "/.ws",
                        connectionTimeout = 15.seconds,
                    ),
                    BinaryPassThroughCodec,
                )
            try {
                val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0xF0.toByte(), 0xFF.toByte())
                launch(Dispatchers.Default) { ws.send(WebSocketMessage.Binary(BufferFactory.Default.wrap(payload))) }
                val echo =
                    withTimeout(10.seconds) {
                        ws.receive().filterIsInstance<WebSocketMessage.Binary<ReadBuffer>>().map { it.payload }.first()
                    }
                val received = echo.readByteArray(echo.remaining())
                assertTrue(payload.contentEquals(received), "Binary echo mismatch")
            } finally {
                ws.close()
            }
        }

    // --- websocket-echo.com ---

    @Test
    fun websocketEchoDotComText() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val ws =
                connectForTest(
                    WebSocketConnectionOptions(
                        name = "websocket-echo.com",
                        port = 443,
                        tls = true,
                        connectionTimeout = 15.seconds,
                    ),
                    StringCodec,
                )
            try {
                val message = "ditchoom-echo-test"
                launch(Dispatchers.Default) { ws.send(WebSocketMessage.Text(message)) }
                val echo =
                    withTimeout(10.seconds) {
                        ws.receive().first() as WebSocketMessage.Text<String>
                    }
                assertEquals(message, echo.payload)
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
    fun wssMultipleMessagesEcho() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val ws =
                connectForTest(
                    WebSocketConnectionOptions(
                        name = "echo.websocket.org",
                        port = 443,
                        tls = true,
                        websocketEndpoint = "/.ws",
                        connectionTimeout = 15.seconds,
                    ),
                    StringCodec,
                )
            try {
                val messageCount = 5
                val messages = (1..messageCount).map { "ditchoom-multi-$it" }
                // Send all messages
                for (msg in messages) {
                    ws.send(WebSocketMessage.Text(msg))
                }
                // Collect echoes (skip any server welcome messages)
                val echoes =
                    withTimeout(15.seconds) {
                        ws.receive()
                            .filterIsInstance<WebSocketMessage.Text<String>>()
                            .map { it.payload }
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
    fun wssInterleavedTextAndBinary() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val ws =
                connectForTest(
                    WebSocketConnectionOptions(
                        name = "echo.websocket.org",
                        port = 443,
                        tls = true,
                        websocketEndpoint = "/.ws",
                        connectionTimeout = 15.seconds,
                    ),
                    BinaryPassThroughCodec,
                )
            try {
                // Send text then binary. BinaryPassThroughCodec surfaces both as ReadBuffer,
                // distinguished only by message class (Text vs Binary) — correct match for
                // frame-type interleave testing.
                val textMsg = "ditchoom-interleave-text"
                val textBytes = textMsg.encodeToByteArray()
                val binaryPayload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

                ws.send(WebSocketMessage.Text(BufferFactory.Default.wrap(textBytes)))
                ws.send(WebSocketMessage.Binary(BufferFactory.Default.wrap(binaryPayload)))

                // Collect both echoes
                var gotText = false
                var gotBinary = false
                withTimeout(10.seconds) {
                    ws.receive()
                        .takeWhile {
                            when (it) {
                                is WebSocketMessage.Text<ReadBuffer> -> {
                                    val bytes = it.payload.readByteArray(it.payload.remaining())
                                    if (textBytes.contentEquals(bytes)) gotText = true
                                }
                                is WebSocketMessage.Binary<ReadBuffer> -> {
                                    val received = it.payload.readByteArray(it.payload.remaining())
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
}
