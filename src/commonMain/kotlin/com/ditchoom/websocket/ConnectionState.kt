package com.ditchoom.websocket

import com.ditchoom.socket.ConnectionState

/**
 * WebSocket-specific [ConnectionState.Disconnected] that carries close code and reason.
 *
 * Normal `ConnectionState.Disconnected` checks still work (`is ConnectionState.Disconnected`).
 * Downcast to [WebSocketDisconnected] when close code/reason are needed.
 */
class WebSocketDisconnected(
    t: Throwable? = null,
    val code: UShort? = null,
    val reason: String? = null,
) : ConnectionState.Disconnected(t)
