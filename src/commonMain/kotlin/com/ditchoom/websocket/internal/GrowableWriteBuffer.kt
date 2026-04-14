package com.ditchoom.websocket.internal

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * A [WriteBuffer] wrapper that automatically grows when writes exceed capacity.
 *
 * Allows a [PayloadEncoder][com.ditchoom.websocket.PayloadEncoder] to write an
 * unknown payload size into a single buffer; once the codec returns we know
 * [position], and the [WebSocketCodec][com.ditchoom.websocket.WebSocketCodec]
 * backpatches the RFC 6455 header into a reserved prefix region.
 *
 * On each growth event the old buffer is freed via
 * [PlatformBuffer.freeNativeMemory] and a new one is allocated through the same
 * [BufferFactory], so pooled and deterministic factories work correctly and
 * intermediate buffers are never leaked.
 *
 * Adapted from `com.ditchoom.buffer.codec.GrowableWriteBuffer` (internal to
 * buffer-codec); duplicated here to keep the websocket module self-contained.
 */
internal class GrowableWriteBuffer(
    private val factory: BufferFactory,
    initialSize: Int = 256,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
) : WriteBuffer {
    private var inner: PlatformBuffer = factory.allocate(initialSize, byteOrder)

    /** Exposes the underlying buffer for in-place operations (header backpatch, masking). */
    val underlying: PlatformBuffer get() = inner

    override val byteOrder: ByteOrder get() = inner.byteOrder

    override fun limit(): Int = inner.limit()

    override fun setLimit(limit: Int) {
        inner.setLimit(limit)
    }

    override fun position(): Int = inner.position()

    override fun position(newPosition: Int) {
        if (newPosition > inner.limit()) {
            ensureCapacity(newPosition - inner.position())
        }
        inner.position(newPosition)
    }

    override fun resetForWrite() {
        inner.resetForWrite()
    }

    private fun ensureCapacity(additionalBytes: Int) {
        if (inner.remaining() >= additionalBytes) return
        val needed = inner.position() + additionalBytes
        var newCapacity = inner.capacity * 2
        while (newCapacity < needed) newCapacity *= 2
        val old = inner
        val pos = old.position()
        old.resetForRead() // position=0, limit=pos
        val newBuffer = factory.allocate(newCapacity, byteOrder)
        newBuffer.write(old) // copies `pos` bytes; newBuffer.position == pos
        old.freeNativeMemory()
        inner = newBuffer
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        ensureCapacity(1)
        inner.writeByte(byte)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        ensureCapacity(2)
        inner.writeShort(short)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        ensureCapacity(4)
        inner.writeInt(int)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        ensureCapacity(8)
        inner.writeLong(long)
        return this
    }

    override fun writeFloat(float: Float): WriteBuffer {
        ensureCapacity(4)
        inner.writeFloat(float)
        return this
    }

    override fun writeDouble(double: Double): WriteBuffer {
        ensureCapacity(8)
        inner.writeDouble(double)
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        ensureCapacity(length)
        inner.writeBytes(bytes, offset, length)
        return this
    }

    override fun write(buffer: ReadBuffer) {
        ensureCapacity(buffer.remaining())
        inner.write(buffer)
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        inner.set(index, byte)
        return this
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        // UTF-8 is at most 3 bytes/char for the BMP. Reserve pessimistically.
        val pessimistic = text.length * 3
        ensureCapacity(pessimistic.coerceAtLeast(1))
        // Write into a fresh slice so we can measure exact bytes written.
        // Simpler: just call underlying; if it overflows, the ensureCapacity above
        // should have prevented it for BMP chars.
        inner.writeString(text, charset)
        return this
    }
}
