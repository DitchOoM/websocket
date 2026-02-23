package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.isNodeJs
import kotlinx.coroutines.CoroutineScope

internal actual val supportsCustomDeflateWindowBits: Boolean = false
internal actual val supportsDeflateContextTakeover: Boolean = false

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope?,
    allocationZone: AllocationZone,
    bufferPool: BufferPool?,
): WebSocketClient =
    if (isNodeJs) {
        DefaultWebSocketClient(connectionOptions, parentScope, allocationZone, externalPool = bufferPool)
    } else {
        BrowserWebSocketController(connectionOptions, bufferPool ?: BufferPool(), parentScope, allocationZone)
    }
