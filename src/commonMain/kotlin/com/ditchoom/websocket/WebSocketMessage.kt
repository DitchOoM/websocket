package com.ditchoom.websocket

import com.ditchoom.buffer.ReadBuffer

sealed interface WebSocketMessage {
    data class Ping(val value: ReadBuffer) : WebSocketMessage
    data class Pong(val value: ReadBuffer) : WebSocketMessage
    data class Binary(val value: ReadBuffer) : WebSocketMessage
    data class Text(val value: String) : WebSocketMessage
    data class Close(val code: UShort, val reason: String) : WebSocketMessage
}
