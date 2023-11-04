package com.ditchoom.websocket

class WebSocketTests {
//
//    @Test
//    fun clientEchoString() = block {
//        val connectionOptions = WebSocketConnectionOptions(name = "localhost", port = 8081, websocketEndpoint = "/echo")
//        val websocket = WebSocketClient.Companion.allocate(connectionOptions, AllocationZone.SharedMemory)
//        websocket.connect()
//        val string1 = "test"
//        launch { websocket.write(string1) }
//        val dataRead = websocket.read() as DataRead.StringDataRead
//        assertEquals(string1, dataRead.string)
//        val string2 = "yolo"
//        websocket.write(string2)
//        val dataRead2 = websocket.read() as DataRead.StringDataRead
//        assertEquals(string2, dataRead2.string)
//        websocket.close()
//    }
//
//    @Test
//    fun clientEchoReadBuffer() = block {
//        val connectionOptions = WebSocketConnectionOptions(name = "127.0.0.1", port = 8081, websocketEndpoint = "/echo")
//        val websocket = WebSocketClient.Companion.allocate(connectionOptions, AllocationZone.SharedMemory)
//        websocket.connect()
//        websocket.write(createPayload())
//        val firstBuffer = (websocket.read() as DataRead.BinaryDataRead).data
//        validatePayload(firstBuffer)
//        websocket.write(createPayloadReverse())
//        val secondBuffer = (websocket.read() as DataRead.BinaryDataRead).data
//        validatePayloadReversed(secondBuffer)
//        websocket.close()
//    }
//
//    @Test
//    fun pingPongWorks() = block {
//        val connectionOptions = WebSocketConnectionOptions(name = "127.0.0.1", port = 8081, websocketEndpoint = "/echo")
//        val websocket = WebSocketClient.Companion.allocate(connectionOptions, AllocationZone.SharedMemory)
//        websocket.connect()
//        if (websocket.isPingSupported()) {
//            val payload = createPayload()
//            websocket.ping(payload)
//            val pong = withTimeout(100.milliseconds) { assertNotNull(websocket.read() as? DataRead.Pong) }
//            validatePayload(pong.buffer)
//        }
//        websocket.close()
//    }
//
//    @Test
//    fun allTypesWork() = block {
//        val connectionOptions = WebSocketConnectionOptions(name = "localhost", port = 8081, websocketEndpoint = "/echo")
//        val websocket = WebSocketClient.Companion.allocate(connectionOptions, AllocationZone.SharedMemory)
//        websocket.connect()
//        websocket.write(createPayload())
//        val firstBuffer = (websocket.read() as DataRead.BinaryDataRead).data
//        validatePayload(firstBuffer)
//        val string1 = "test"
//        websocket.write(string1)
//        val dataRead = websocket.read() as DataRead.StringDataRead
//        assertEquals(string1, dataRead.string)
//
//        if (websocket.isPingSupported()) {
//            val payload = createPayload()
//            websocket.ping(payload)
//            val pong = withTimeout(100.milliseconds) { assertNotNull(websocket.read() as? DataRead.Pong) }
//            validatePayload(pong.buffer)
//        }
//
//        websocket.write(createPayloadReverse())
//        val secondBuffer = (websocket.read() as DataRead.BinaryDataRead).data
//        validatePayloadReversed(secondBuffer)
//
//        val string2 = "yolo"
//        websocket.write(string2)
//        val dataRead2 = websocket.read() as DataRead.StringDataRead
//        assertEquals(string2, dataRead2.string)
//
//        websocket.close()
//    }
//
//    private fun createPayload(): ReadBuffer {
//        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
//        val readBuffer = PlatformBuffer.wrap(bytes)
//        readBuffer.position(readBuffer.limit())
//        return readBuffer
//    }
//
//    private fun createPayloadReverse(): ReadBuffer {
//        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).reversedArray()
//        val readBuffer = PlatformBuffer.wrap(bytes)
//        readBuffer.position(readBuffer.limit())
//        return readBuffer
//    }
//
//    private fun validatePayload(buffer: ReadBuffer) {
//        buffer.resetForRead()
//        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), buffer.readByteArray(buffer.remaining()))
//    }
//
//    private fun validatePayloadReversed(buffer: ReadBuffer) {
//        buffer.resetForRead()
//        assertContentEquals(
//            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).reversedArray(),
//            buffer.readByteArray(buffer.remaining())
//        )
//    }
}
