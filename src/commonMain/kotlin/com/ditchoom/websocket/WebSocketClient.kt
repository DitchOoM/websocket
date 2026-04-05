package com.ditchoom.websocket

import com.ditchoom.buffer.flow.Connection

/**
 * WebSocket client as a typed [Connection].
 *
 * Send and receive via [send]/[receive] from [Connection].
 * The sealed [WebSocketMessage] hierarchy carries the opcode.
 */
interface WebSocketClient : Connection<WebSocketMessage> {
    suspend fun connect(): WebSocketClient

    suspend fun isPingSupported(): Boolean = true

    companion object
}

/**
 * Executes [block] with this client, then closes it regardless of outcome.
 *
 * ```kotlin
 * client.use { ws ->
 *     ws.connect()
 *     ws.send(WebSocketMessage.Text("hello"))
 *     val echo = ws.receive().first()
 * }
 * ```
 */
suspend inline fun <R> WebSocketClient.use(block: (WebSocketClient) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
