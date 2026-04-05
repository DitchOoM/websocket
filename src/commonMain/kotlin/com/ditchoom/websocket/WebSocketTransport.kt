package com.ditchoom.websocket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlin.time.Duration

/**
 * A connected byte transport for WebSocket protocol I/O.
 *
 * The consumer provides a pre-connected transport (e.g., TCP socket).
 * The WebSocket client handles the HTTP upgrade handshake and framing on top.
 */
interface WebSocketTransport {
    fun isOpen(): Boolean

    suspend fun read(
        buffer: WriteBuffer,
        timeout: Duration,
    ): Int

    suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int

    suspend fun close()
}
