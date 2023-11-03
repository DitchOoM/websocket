package com.ditchoom.websocket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.data.Reader
import com.ditchoom.data.Writer
import com.ditchoom.socket.EMPTY_BUFFER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface WebSocketClient : SuspendCloseable {
    fun connect(scope: CoroutineScope, tag: String = "")
    suspend fun localPort(): Int
    suspend fun remotePort(): Int
    suspend fun write(buffer: ReadBuffer)
    suspend fun write(string: String)
    suspend fun ping(payloadData: ReadBuffer = EMPTY_BUFFER)
    suspend fun isPingSupported(): Boolean = true

    companion object

    val connectionState: StateFlow<ConnectionState>
    val incomingMessages: SharedFlow<WebSocketMessage>
}
