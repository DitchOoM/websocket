package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * Mock [WebSocketTransport] for protocol-level testing without network I/O.
 *
 * Enqueue server responses via [enqueueRead] / [enqueueReadBytes] and
 * inspect client writes via [writtenBuffers].
 */
class MockWebSocketTransport : WebSocketTransport {
    private var open = true
    private val readQueue = Channel<Result<ReadBuffer>>(Channel.UNLIMITED)
    val writtenBuffers = mutableListOf<ReadBuffer>()

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
        readQueue.trySend(Result.failure(Exception("Mock disconnect")))
    }

    override fun isOpen() = open

    override suspend fun read(
        buffer: WriteBuffer,
        timeout: Duration,
    ): Int {
        if (!open) throw Exception("Mock transport is closed")
        val result =
            try {
                withTimeout(timeout) {
                    readQueue.receive()
                }
            } catch (e: ClosedReceiveChannelException) {
                throw Exception("Mock transport channel closed")
            }
        val readBuffer = result.getOrThrow()
        readBuffer.resetForRead()
        val bytesRead = readBuffer.remaining()
        buffer.write(readBuffer)
        return bytesRead
    }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        if (!open) throw Exception("Mock transport is closed")
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
}
