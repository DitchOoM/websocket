package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.connect
import kotlin.time.Duration

/**
 * Adapts [ClientToServerSocket] from the socket library to [ByteStream].
 * Used in integration tests to provide real TCP transport.
 */
class SocketTransportAdapter(
    private val socket: ClientToServerSocket,
) : ByteStream {
    override val isOpen: Boolean get() = socket.isOpen()

    override suspend fun read(timeout: Duration): ReadResult {
        val buffer = BufferFactory.Default.allocate(65536)
        val bytesRead = socket.read(buffer, timeout)
        if (bytesRead <= 0) return ReadResult.End
        buffer.setLimit(buffer.position())
        buffer.position(0)
        return ReadResult.Data(buffer)
    }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): BytesWritten = BytesWritten(socket.write(buffer, timeout))

    override suspend fun close() = socket.close()

    companion object {
        suspend fun connect(
            port: Int,
            timeout: Duration,
            hostname: String?,
            socketOptions: SocketOptions = SocketOptions(),
        ): SocketTransportAdapter {
            val socket = ClientSocket.connect(port, hostname, timeout, socketOptions)
            return SocketTransportAdapter(socket)
        }
    }
}
