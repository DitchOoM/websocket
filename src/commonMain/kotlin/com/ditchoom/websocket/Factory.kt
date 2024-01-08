package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import kotlinx.coroutines.CoroutineScope

expect fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    zone: AllocationZone = AllocationZone.Direct,
    parentScope: CoroutineScope? = null
): WebSocketClient
