package com.ditchoom.websocket.apple

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketMessage
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NativeWebSocketClientTest {
    @Test
    fun nativeWssComprehensiveEcho() =
        runBlocking {
            withTimeout(60.seconds) {
                val ws =
                    connectAppleNativeWebSocket(
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
                    val textMsg = "ditchoom-native-text"
                    ws.send(WebSocketMessage.Text(textMsg))
                    val textEcho =
                        withTimeout(10.seconds) {
                            ws.receive()
                                .filterIsInstance<WebSocketMessage.Text>()
                                .map { it.payload }
                                .first { it == textMsg }
                        }
                    assertEquals(textMsg, textEcho)

                    val binaryPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0xF0.toByte(), 0xFF.toByte())
                    launch(Dispatchers.Default) {
                        ws.send(WebSocketMessage.Binary(BufferFactory.Default.wrap(binaryPayload)))
                    }
                    val binaryEcho =
                        withTimeout(10.seconds) {
                            ws.receive().filterIsInstance<WebSocketMessage.Binary<ReadBuffer>>().map { it.payload }.first()
                        }
                    val received = binaryEcho.readByteArray(binaryEcho.remaining())
                    assertTrue(binaryPayload.contentEquals(received), "Binary echo mismatch")

                    val messages = (1..3).map { "ditchoom-native-seq-$it" }
                    for (msg in messages) {
                        ws.send(WebSocketMessage.Text(msg))
                    }
                    val seqEchoes =
                        withTimeout(10.seconds) {
                            val collected = mutableListOf<String>()
                            ws.receive()
                                .filterIsInstance<WebSocketMessage.Text>()
                                .map { it.payload }
                                .takeWhile { text ->
                                    if (text.startsWith("ditchoom-native-seq-")) {
                                        collected.add(text)
                                    }
                                    collected.size < messages.size
                                }.collect {}
                            collected
                        }
                    assertEquals(messages, seqEchoes, "Sequential messages should echo in order")
                } finally {
                    ws.close()
                }
            }
        }

    @Test
    fun nativeWebsocketEchoDotComText() =
        runBlocking {
            withTimeout(30.seconds) {
                val ws =
                    connectAppleNativeWebSocket(
                        WebSocketConnectionOptions(
                            name = "websocket-echo.com",
                            port = 443,
                            tls = true,
                            connectionTimeout = 15.seconds,
                        ),
                    )
                try {
                    val message = "ditchoom-native-echo-test"
                    launch(Dispatchers.Default) { ws.send(WebSocketMessage.Text(message)) }
                    val echo =
                        withTimeout(10.seconds) {
                            ws.receive().first() as WebSocketMessage.Text
                        }
                    assertEquals(message, echo.payload)
                } finally {
                    ws.close()
                }
            }
        }
}
