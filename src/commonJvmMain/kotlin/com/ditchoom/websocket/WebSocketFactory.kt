package com.ditchoom.websocket

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions
): WebSocketClient = DefaultWebSocketClient(connectionOptions)
