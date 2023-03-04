package com.ditchoom.websocket

import com.ditchoom.socket.NetworkCapabilities
import com.ditchoom.socket.getNetworkCapabilities

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions
): WebSocketClient {
    return if (getNetworkCapabilities() == NetworkCapabilities.FULL_SOCKET_ACCESS) {
        DefaultWebSocketClient(connectionOptions)
    } else {
        BrowserWebSocketController(connectionOptions)
    }
}
