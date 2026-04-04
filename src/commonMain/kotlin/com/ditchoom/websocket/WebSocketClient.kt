package com.ditchoom.websocket

import com.ditchoom.socket.ConnectionState
import com.ditchoom.socket.transport.MessageConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * WebSocket client as a typed [MessageConnection].
 *
 * Send and receive via [send]/[receive] from [MessageConnection].
 * The sealed [WebSocketMessage] hierarchy carries the opcode.
 */
interface WebSocketClient : MessageConnection<WebSocketMessage> {
    val scope: CoroutineScope

    suspend fun connect(): WebSocketClient

    suspend fun localPort(): Int

    suspend fun remotePort(): Int

    suspend fun isPingSupported(): Boolean = true

    val connectionState: StateFlow<ConnectionState>

    companion object
}

/**
 * Executes [block] with this client, then closes it regardless of outcome.
 *
 * ```kotlin
 * WebSocketClient.allocate(options).use { client ->
 *     client.connect()
 *     client.send(WebSocketMessage.Text("hello"))
 *     val echo = client.receive().first()
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
