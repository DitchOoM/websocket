package com.ditchoom.websocket

import com.ditchoom.buffer.flow.Connection

/**
 * Executes [block] with this connection, then closes it regardless of outcome.
 *
 * ```kotlin
 * connectWebSocket(transport, options, StringCodec).use { ws ->
 *     ws.send(WebSocketMessage.Text("hello"))
 *     val echo = ws.receive().first()
 * }
 * ```
 */
suspend inline fun <P, R> Connection<WebSocketMessage<P>>.use(
    block: (Connection<WebSocketMessage<P>>) -> R,
): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
