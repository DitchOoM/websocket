package com.ditchoom.websocket

import com.ditchoom.buffer.flow.Connection

/**
 * Executes [block] with this connection, then closes it regardless of outcome.
 *
 * ```kotlin
 * connectWebSocket(transport, options).use { ws ->
 *     ws.send(WebSocketMessage.Text("hello"))
 *     val echo = ws.receive().first()
 * }
 * ```
 */
suspend inline fun <R> Connection<WebSocketMessage>.use(block: (Connection<WebSocketMessage>) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
