package com.ditchoom.websocket

expect fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions
): WebSocketClient
