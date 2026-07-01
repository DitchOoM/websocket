package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    override val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds)
    override val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds)

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
        // Model a real server's RFC 6455 §7.1.1 close handshake: when the client sends a Close frame,
        // ack it with a server Close so WebSocketCodec.close()'s bounded wait for the peer's Close
        // completes promptly. Without this, a mock read loop has no peer to close it and would block
        // until the close timeout. Only the (never-masked) FIN+opcode byte is inspected.
        if (bytes >= 2) {
            copy.position(0)
            val isCloseFrame = (copy.readByte().toInt() and 0x0F) == 0x8
            copy.position(0)
            if (isCloseFrame && open) {
                val closeAck = BufferFactory.Default.allocate(4)
                closeAck.writeByte(0x88.toByte()) // FIN + Close opcode
                closeAck.writeByte(0x02) // 2-byte payload: status code only
                closeAck.writeShort(1000) // 1000 = NORMAL_CLOSURE
                readQueue.trySend(Result.success(closeAck))
            }
        }
        return BytesWritten(bytes)
    }

    override suspend fun close() {
        open = false
        readQueue.close()
    }
}
