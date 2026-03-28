package com.ditchoom.websocket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface WebSocketClient {
    val scope: CoroutineScope

    suspend fun connect(): WebSocketClient

    suspend fun localPort(): Int

    suspend fun remotePort(): Int

    suspend fun write(buffer: ReadBuffer)

    suspend fun write(string: String)

    suspend fun ping(payloadData: ReadBuffer = EMPTY_BUFFER)

    suspend fun isPingSupported(): Boolean = true

    companion object

    val connectionState: StateFlow<ConnectionState>
    val incomingMessages: Flow<WebSocketMessage>

    /** Text messages only — avoids filterIsInstance overhead for typed consumers. */
    val incomingTextMessages: Flow<String>

    /** Binary messages only — avoids filterIsInstance overhead for typed consumers. */
    val incomingBinaryMessages: Flow<ReadBuffer>

    suspend fun close()
}

/**
 * Executes [block] with this client, then closes it regardless of outcome.
 *
 * ```kotlin
 * WebSocketClient.allocate(options).use { client ->
 *     client.connect()
 *     client.write("hello")
 *     val echo = client.incomingTextMessages.first()
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
