package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone

expect fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    zone: AllocationZone = AllocationZone.Direct
): WebSocketClient
