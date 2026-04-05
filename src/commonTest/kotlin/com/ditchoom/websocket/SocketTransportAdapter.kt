package com.ditchoom.websocket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.connect
import kotlin.time.Duration

/**
 * Adapts [ClientToServerSocket] from the socket library to [WebSocketTransport].
 * Used in integration tests to provide real TCP transport.
 */
class SocketTransportAdapter(
    private val socket: ClientToServerSocket,
) : WebSocketTransport {
    override fun isOpen(): Boolean = socket.isOpen()

    override suspend fun read(
        buffer: WriteBuffer,
        timeout: Duration,
    ): Int = socket.read(buffer, timeout)

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int = socket.write(buffer, timeout)

    override suspend fun close() = socket.close()

    companion object {
        /**
         * Creates a [SocketTransportAdapter] by opening a TCP connection.
         */
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
