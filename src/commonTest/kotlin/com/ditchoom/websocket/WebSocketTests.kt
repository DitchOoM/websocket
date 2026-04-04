package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.shared
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class WebSocketTests {
    @Test
    fun clientEchoString() =
        runTestNoTimeSkipping {
            val connectionOptions = WebSocketConnectionOptions(name = "127.0.0.1", port = 8081, websocketEndpoint = "/echo")
            val websocket =
                WebSocketClient.Companion.allocate(
                    connectionOptions,
                    parentScope = this,
                    bufferFactory = BufferFactory.shared(),
                )
            websocket.connect()
            websocket.awaitConnected()
            val string1 = "test"
            launch(Dispatchers.Default) { websocket.send(WebSocketMessage.Text(string1)) }
            val dataRead = websocket.receive().first() as WebSocketMessage.Text
            assertEquals(string1, dataRead.value)
            val string2 = "yolo"
            launch(Dispatchers.Default) { websocket.send(WebSocketMessage.Text(string2)) }
            val dataRead2 = websocket.receive().first() as WebSocketMessage.Text
            assertEquals(string2, dataRead2.value)
            websocket.close()
        }

    @Test
    fun clientEchoReadBuffer() =
        runTestNoTimeSkipping {
            val connectionOptions = WebSocketConnectionOptions(name = "127.0.0.1", port = 8081, websocketEndpoint = "/echo")
            val websocket =
                WebSocketClient.Companion.allocate(
                    connectionOptions,
                    parentScope = this,
                    bufferFactory = BufferFactory.shared(),
                )
            websocket.connect()
            launch(Dispatchers.Default) { websocket.send(WebSocketMessage.Binary(createPayload())) }
            val firstBuffer = websocket.receive().first() as WebSocketMessage.Binary
            validatePayload(firstBuffer.value)
            launch(Dispatchers.Default) { websocket.send(WebSocketMessage.Binary(createPayloadReverse())) }
            val secondBuffer = websocket.receive().first() as WebSocketMessage.Binary
            validatePayloadReversed(secondBuffer.value)
            websocket.close()
        }

    @Test
    fun pingPongWorks() =
        runTestNoTimeSkipping {
            val connectionOptions = WebSocketConnectionOptions(name = "127.0.0.1", port = 8081, websocketEndpoint = "/echo")
            val websocket =
                WebSocketClient.Companion.allocate(
                    connectionOptions,
                    parentScope = this,
                    bufferFactory = BufferFactory.shared(),
                )
            websocket.connect()
            if (websocket.isPingSupported()) {
                val payload = createPayload()
                launch(Dispatchers.Default) { websocket.send(WebSocketMessage.Ping(payload)) }
                val pong =
                    withTimeout(10.seconds) {
                        val p = websocket.receive().first() as? WebSocketMessage.Pong
                        assertNotNull(p)
                    }
                validatePayload(pong.value)
            }
            websocket.close()
        }

    @Test
    fun allTypesWork() =
        runTestNoTimeSkipping {
            val connectionOptions = WebSocketConnectionOptions(name = "127.0.0.1", port = 8081, websocketEndpoint = "/echo")
            val websocket =
                WebSocketClient.Companion.allocate(
                    connectionOptions,
                    parentScope = this,
                    bufferFactory = BufferFactory.shared(),
                )
            websocket.connect()
            launch(Dispatchers.Default) { websocket.send(WebSocketMessage.Binary(createPayload())) }
            val firstBuffer = websocket.receive().first() as WebSocketMessage.Binary
            validatePayload(firstBuffer.value)
            val string1 = "test"
            launch(Dispatchers.Default) { websocket.send(WebSocketMessage.Text(string1)) }
            val dataRead = websocket.receive().first() as WebSocketMessage.Text
            assertEquals(string1, dataRead.value)

            if (websocket.isPingSupported()) {
                val payload = createPayload()
                launch(Dispatchers.Default) { websocket.send(WebSocketMessage.Ping(payload)) }
                val pong =
                    withTimeout(10.seconds) {
                        val p = websocket.receive().first() as? WebSocketMessage.Pong
                        assertNotNull(p)
                    }
                validatePayload(pong.value)
            }

            launch(Dispatchers.Default) { websocket.send(WebSocketMessage.Binary(createPayloadReverse())) }
            val secondBuffer = websocket.receive().first() as WebSocketMessage.Binary
            validatePayloadReversed(secondBuffer.value)

            val string2 = "yolo"
            launch(Dispatchers.Default) { websocket.send(WebSocketMessage.Text(string2)) }
            val dataRead2 = websocket.receive().first() as WebSocketMessage.Text
            assertEquals(string2, dataRead2.value)

            websocket.close()
        }

    private fun createPayload(): ReadBuffer {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        // wrap() returns buffer with position=0, limit=length, ready to read
        return BufferFactory.Default.wrap(bytes)
    }

    private fun createPayloadReverse(): ReadBuffer {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).reversedArray()
        // wrap() returns buffer with position=0, limit=length, ready to read
        return BufferFactory.Default.wrap(bytes)
    }

    private fun validatePayload(buffer: ReadBuffer) {
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), buffer.readByteArray(buffer.remaining()))
    }

    private fun validatePayloadReversed(buffer: ReadBuffer) {
        assertContentEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).reversedArray(),
            buffer.readByteArray(buffer.remaining()),
        )
    }
}
