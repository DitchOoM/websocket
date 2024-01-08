package com.ditchoom.websocket

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.wrap
import kotlin.random.Random

internal sealed class MaskingKey {
    internal data object NoMaskingKey : MaskingKey()
    internal class FourByteMaskingKey(private val underlyingBuffer: ReadBuffer) : MaskingKey() {
        constructor() : this(PlatformBuffer.wrap(Random.nextBytes(4), ByteOrder.BIG_ENDIAN))

        fun write(buffer: WriteBuffer) {
            underlyingBuffer.position(0)
            buffer.write(underlyingBuffer)
        }

        operator fun get(index: Int): Byte {
            underlyingBuffer.position(index)
            return underlyingBuffer.readByte()
        }
    }
}
