package com.ditchoom.websocket

sealed interface ConnectionState {
    data object Initialized : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Disconnected(val t: Throwable? = null, val code: UShort? = null, val reason: String? = null) : ConnectionState
}
