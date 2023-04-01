package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.NetworkCapabilities
import org.w3c.dom.WebSocket

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    zone: AllocationZone
): WebSocketClient {
    val networkCapabilities = try {
        WebSocket("ws://localhost")
        NetworkCapabilities.WEBSOCKETS_ONLY
    } catch (e: Throwable) {
        NetworkCapabilities.FULL_SOCKET_ACCESS
    }
    return if (networkCapabilities == NetworkCapabilities.FULL_SOCKET_ACCESS) {
        DefaultWebSocketClient(connectionOptions, zone)
    } else {
        BrowserWebSocketController(connectionOptions, zone)
    }
}
