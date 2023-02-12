package com.ditchoom.websocket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.data.Reader
import com.ditchoom.data.Writer
import com.ditchoom.socket.EMPTY_BUFFER

interface WebSocketClient : Reader, Writer, SuspendCloseable {
    suspend fun connect()
    suspend fun localPort(): Int
    suspend fun remotePort(): Int
    suspend fun read(): DataRead
    suspend fun write(buffer: ReadBuffer)
    suspend fun write(string: String)
    suspend fun ping(payloadData: ReadBuffer = EMPTY_BUFFER)
    suspend fun isPingSupported(): Boolean = true

    companion object
}
