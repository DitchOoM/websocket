package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import kotlinx.coroutines.CoroutineScope

internal actual val supportsCustomDeflateWindowBits: Boolean = false
internal actual val supportsDeflateContextTakeover: Boolean = true

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope?,
    allocationZone: AllocationZone,
): WebSocketClient =
    if (connectionOptions.useNativePlatformClient) {
        NWWebSocketClient(connectionOptions, parentScope)
    } else {
        DefaultWebSocketClient(connectionOptions, parentScope, allocationZone)
    }
