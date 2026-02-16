package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import kotlinx.coroutines.CoroutineScope

expect fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope? = null,
    allocationZone: AllocationZone = AllocationZone.Direct,
): WebSocketClient
