package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import kotlinx.coroutines.CoroutineScope

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    zone: AllocationZone,
    parentScope: CoroutineScope?
): WebSocketClient = DefaultWebSocketClient(connectionOptions, zone, parentScope)
