package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    zone: AllocationZone
): WebSocketClient = DefaultWebSocketClient(connectionOptions, zone)
