package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * Mock [ByteStream] for protocol-level testing without network I/O.
 *
 * Enqueue server responses via [enqueueRead] / [enqueueReadBytes] and
 * inspect client writes via [writtenBuffers].
 */
class MockWebSocketTransport : ByteStream {
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

    override val isOpen: Boolean get() = open

    override suspend fun read(timeout: Duration): ReadResult {
        if (!open) return ReadResult.End
        val result =
            try {
                withTimeout(timeout) { readQueue.receive() }
            } catch (_: ClosedReceiveChannelException) {
                return ReadResult.End
            }
        val buffer = result.getOrThrow()
        buffer.resetForRead()
        return ReadResult.Data(buffer)
    }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): BytesWritten {
        if (!open) throw Exception("Mock transport is closed")
        val bytes = buffer.remaining()
        val copy = BufferFactory.Default.allocate(bytes)
        copy.write(buffer)
        copy.resetForRead()
        writtenBuffers.add(copy)
        return BytesWritten(bytes)
    }

    override suspend fun close() {
        open = false
        readQueue.close()
    }
}
