package com.ditchoom.websocket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.socket.EMPTY_BUFFER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

interface WebSocketClient : SuspendCloseable {

    val scope: CoroutineScope

    suspend fun connect(): WebSocketClient

    suspend fun localPort(): Int
    suspend fun remotePort(): Int
    suspend fun write(buffer: ReadBuffer)
    suspend fun write(string: String)
    suspend fun ping(payloadData: ReadBuffer = EMPTY_BUFFER)
    suspend fun isPingSupported(): Boolean = true

    fun onIncomingWebsocketMessage(cb: (WebSocketMessage) -> Unit) = scope.launch {
        incomingMessages.collect { cb(it) }
    }

    fun onConnectionStateChange(cb: (ConnectionState) -> Unit) = scope.launch {
        connectionState.collect { cb(it) }
    }

    companion object

    val connectionState: StateFlow<ConnectionState>
    val incomingMessages: SharedFlow<WebSocketMessage>
}
