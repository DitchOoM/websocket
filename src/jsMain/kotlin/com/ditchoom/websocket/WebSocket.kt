package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import org.w3c.dom.WebSocket

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    zone: AllocationZone,
    parentScope: CoroutineScope?
): WebSocketClient {
    val networkCapabilities = try {
        WebSocket("ws://localhost")
        NetworkCapabilities.WEBSOCKETS_ONLY
    } catch (e: Throwable) {
        NetworkCapabilities.FULL_SOCKET_ACCESS
    }
    return if (networkCapabilities == NetworkCapabilities.FULL_SOCKET_ACCESS) {
        DefaultWebSocketClient(connectionOptions, zone, parentScope)
    } else {
        BrowserWebSocketController(connectionOptions, zone, parentScope)
    }
}
