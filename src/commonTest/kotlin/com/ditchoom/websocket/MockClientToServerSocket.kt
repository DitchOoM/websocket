package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketOptions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * Mock [ClientToServerSocket] for protocol-level testing without network I/O.
 *
 * Enqueue server responses via [enqueueRead] / [enqueueReadBytes] and
 * inspect client writes via [writtenBuffers].
 */
class MockClientToServerSocket : ClientToServerSocket {
    private var open = false
    private val readQueue = Channel<Result<ReadBuffer>>(Channel.UNLIMITED)
    val writtenBuffers = mutableListOf<ReadBuffer>()
    var openCalled = false
        private set

    fun enqueueRead(buffer: ReadBuffer) {
        readQueue.trySend(Result.success(buffer))
    }

    fun enqueueReadBytes(vararg bytes: Byte) {
        val buffer = BufferFactory.Default.allocate(bytes.size)
        for (b in bytes) buffer.writeByte(b)
        enqueueRead(buffer)
    }

    fun enqueueReadError(exception: Exception) {
        readQueue.trySend(Result.failure(exception))
    }

    fun simulateDisconnect() {
        open = false
        readQueue.trySend(Result.failure(SocketClosedException("Mock disconnect")))
    }

    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
        socketOptions: SocketOptions,
    ) {
        open = true
        openCalled = true
    }

    override fun isOpen() = open

    override suspend fun read(timeout: Duration): ReadBuffer {
        if (!open) throw SocketClosedException("Mock socket is closed")
        val result =
            try {
                withTimeout(timeout) {
                    readQueue.receive()
                }
            } catch (e: ClosedReceiveChannelException) {
                throw SocketClosedException("Mock socket channel closed")
            }
        return result.getOrThrow()
    }

    override suspend fun read(
        buffer: WriteBuffer,
        timeout: Duration,
    ): Int {
        val readBuffer = read(timeout)
        readBuffer.resetForRead()
        val bytesRead = readBuffer.remaining()
        buffer.write(readBuffer)
        return bytesRead
    }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        if (!open) throw SocketClosedException("Mock socket is closed")
        val bytes = buffer.remaining()
        val copy = BufferFactory.Default.allocate(bytes)
        copy.write(buffer)
        copy.resetForRead()
        writtenBuffers.add(copy)
        return bytes
    }

    override suspend fun close() {
        open = false
        readQueue.close()
    }

    override suspend fun localPort() = 12345

    override suspend fun remotePort() = 80
}
