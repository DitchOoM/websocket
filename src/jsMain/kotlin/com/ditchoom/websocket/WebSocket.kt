package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import kotlinx.coroutines.CoroutineScope

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    zone: AllocationZone,
    parentScope: CoroutineScope?,
): WebSocketClient {
    return try {
        DefaultWebSocketClient(connectionOptions, zone, parentScope)
    } catch (e: UnsupportedOperationException) {
        BrowserWebSocketController(connectionOptions, zone, parentScope)
    }
}
