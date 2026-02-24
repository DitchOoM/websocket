package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.isNodeJs
import kotlinx.coroutines.CoroutineScope

internal actual val supportsCustomDeflateWindowBits: Boolean = false
internal actual val supportsDeflateContextTakeover: Boolean = false

private val wsModule: dynamic = js("try { require('ws') } catch(e) { null }")

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope?,
    allocationZone: AllocationZone,
): WebSocketClient =
    if (isNodeJs) {
        if (connectionOptions.useNativePlatformClient && wsModule != null) {
            NodeJsWebSocketClient(connectionOptions, parentScope, wsModule)
        } else {
            DefaultWebSocketClient(connectionOptions, parentScope, allocationZone)
        }
    } else {
        BrowserWebSocketController(connectionOptions, BufferPool(), parentScope, allocationZone)
    }
