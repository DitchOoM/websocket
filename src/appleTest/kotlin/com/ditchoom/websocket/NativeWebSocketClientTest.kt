package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests the Apple-native WebSocket client (NWConnection / Network.framework)
 * via [preferNative] = true against public echo servers.
 *
 * Tests are consolidated per-server to avoid triggering rate limits from
 * too many rapid reconnections to the same endpoint.
 */
class NativeWebSocketClientTest {
    /**
     * Comprehensive test against echo.websocket.org:
     * text echo, binary echo, multiple messages, interleaved types, and ping/pong.
     * All operations share a single connection.
     */
    @Test
    fun nativeWssComprehensiveEcho() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val ws =
                WebSocketClient.allocate(
                    WebSocketConnectionOptions(
                        name = "echo.websocket.org",
                        port = 443,
                        tls = true,
                        websocketEndpoint = "/.ws",
                        connectionTimeout = 15.seconds,
                    ),
                )
            try {
                ws.connect()
                // connect() succeeded without throwing — native WSS connection is established

                // 1. Text echo
                val textMsg = "ditchoom-native-text"
                ws.send(WebSocketMessage.Text(textMsg))
                val textEcho =
                    withTimeout(10.seconds) {
                        ws.receive().filterIsInstance<WebSocketMessage.Text>().map { it.value }.first { it == textMsg }
                    }
                assertEquals(textMsg, textEcho)

                // 2. Binary echo
                val binaryPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0xF0.toByte(), 0xFF.toByte())
                launch(Dispatchers.Default) { ws.send(WebSocketMessage.Binary(BufferFactory.Default.wrap(binaryPayload))) }
                val binaryEcho =
                    withTimeout(10.seconds) {
                        ws.receive().filterIsInstance<WebSocketMessage.Binary>().map { it.value }.first()
                    }
                val received = binaryEcho.readByteArray(binaryEcho.remaining())
                assertTrue(binaryPayload.contentEquals(received), "Binary echo mismatch")

                // 3. Multiple sequential text messages
                val messages = (1..3).map { "ditchoom-native-seq-$it" }
                for (msg in messages) {
                    ws.send(WebSocketMessage.Text(msg))
                }
                val seqEchoes =
                    withTimeout(10.seconds) {
                        val collected = mutableListOf<String>()
                        ws.receive()
                            .filterIsInstance<WebSocketMessage.Text>()
                            .map { it.value }
                            .takeWhile { text ->
                                if (text.startsWith("ditchoom-native-seq-")) {
                                    collected.add(text)
                                }
                                collected.size < messages.size
                            }.collect {}
                        collected
                    }
                assertEquals(messages, seqEchoes, "Sequential messages should echo in order")

                // 4. Interleaved text + binary
                val interleaveText = "ditchoom-native-interleave"
                val interleaveBinary = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
                ws.send(WebSocketMessage.Text(interleaveText))
                ws.send(WebSocketMessage.Binary(BufferFactory.Default.wrap(interleaveBinary)))
                var gotText = false
                var gotBinary = false
                withTimeout(10.seconds) {
                    ws.receive()
                        .takeWhile {
                            when (it) {
                                is WebSocketMessage.Text -> if (it.value == interleaveText) gotText = true
                                is WebSocketMessage.Binary -> {
                                    val r = it.value.readByteArray(it.value.remaining())
                                    if (interleaveBinary.contentEquals(r)) gotBinary = true
                                }
                                else -> {}
                            }
                            !(gotText && gotBinary)
                        }.collect {}
                }
                assertTrue(gotText, "Should receive interleaved text echo")
                assertTrue(gotBinary, "Should receive interleaved binary echo")

                // 5. Ping/pong
                assertTrue(ws.isPingSupported(), "Native client should support ping")
                val pingPayload = BufferFactory.Default.wrap(byteArrayOf(1, 2, 3, 4))
                launch(Dispatchers.Default) { ws.send(WebSocketMessage.Ping(pingPayload)) }
                val pong =
                    withTimeout(10.seconds) {
                        ws.receive().first { it is WebSocketMessage.Pong } as WebSocketMessage.Pong
                    }
                assertTrue(pong.value.remaining() > 0, "Pong should have payload")
            } finally {
                ws.close()
            }
        }

    /**
     * Separate server test: websocket-echo.com (different CA and server stack).
     */
    @Test
    fun nativeWebsocketEchoDotComText() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val ws =
                WebSocketClient.allocate(
                    WebSocketConnectionOptions(
                        name = "websocket-echo.com",
                        port = 443,
                        tls = true,
                        connectionTimeout = 15.seconds,
                    ),
                )
            try {
                ws.connect()
                val message = "ditchoom-native-echo-test"
                launch(Dispatchers.Default) { ws.send(WebSocketMessage.Text(message)) }
                val echo =
                    withTimeout(10.seconds) {
                        ws.receive().first() as WebSocketMessage.Text
                    }
                assertEquals(message, echo.value)
            } finally {
                ws.close()
            }
        }
}
