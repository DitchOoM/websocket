package com.ditchoom.websocket

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions
): WebSocketClient {
    return try {
        val s = DefaultWebSocketClient(connectionOptions)
//        println("NodeJS = true")
        s
    } catch (e: Exception) {
//        println("NodeJS = false")
        BrowserWebSocketController(connectionOptions)
    }
}
