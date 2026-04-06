package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.CoroutineScope

/**
 * Connects a WebSocket over the given [ByteStream] and returns a ready-to-use
 * [Connection]. The connection is guaranteed to be established when this returns —
 * impossible to send on an unconnected client.
 *
 * ```kotlin
 * val ws: Connection<WebSocketMessage> = connectWebSocket(transport, options)
 * ws.send(WebSocketMessage.Text("hello")) // guaranteed connected
 * ```
 *
 * @param transport A pre-connected byte transport (e.g. TCP socket).
 * @param connectionOptions Connection configuration (host, compression, etc.)
 * @param parentScope Optional parent coroutine scope for structured concurrency.
 * @param bufferFactory Buffer factory for frame I/O allocations.
 * @param bufferPool Optional buffer pool for memory reuse.
 * @throws WebSocketException.HandshakeRejected if the server rejects the upgrade.
 * @throws WebSocketException.TransportFailed if the transport fails during handshake.
 */
suspend fun connectWebSocket(
    transport: ByteStream,
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope? = null,
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
    bufferPool: BufferPool? = null,
): Connection<WebSocketMessage> {
    val client =
        DefaultWebSocketClient(
            transport = transport,
            connectionOptions = connectionOptions,
            parentScope = parentScope,
            bufferFactory = bufferFactory,
            externalPool = bufferPool,
        )
    client.connect()
    return client
}

/**
 * Create a platform-native WebSocket connection.
 *
 * On platforms with native WebSocket support (browser), this creates a
 * native client that handles transport internally. On other platforms
 * (JVM, Linux, Apple native), this throws [UnsupportedOperationException] —
 * use [connectWebSocket] with a [ByteStream] instead.
 */
expect suspend fun connectNativeWebSocket(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope? = null,
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
    bufferPool: BufferPool? = null,
): Connection<WebSocketMessage>
