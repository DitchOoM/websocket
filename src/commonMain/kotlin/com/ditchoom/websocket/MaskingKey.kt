package com.ditchoom.websocket

import com.ditchoom.buffer.WriteBuffer
import kotlin.jvm.JvmInline
import kotlin.random.Random

internal sealed interface MaskingKey {
    data object NoMaskingKey : MaskingKey

    /**
     * Stores the 4-byte masking key packed into an Int for zero-allocation access.
     * Bytes are stored in big-endian order: byte0 is the most significant byte.
     */
    @JvmInline
    value class FourByteMaskingKey(
        val packed: Int,
    ) : MaskingKey {
        constructor() : this(Random.nextInt())

        /** Get the mask byte at index 0-3. */
        operator fun get(index: Int): Byte =
            when (index) {
                0 -> (packed ushr 24).toByte()
                1 -> (packed ushr 16).toByte()
                2 -> (packed ushr 8).toByte()
                3 -> packed.toByte()
                else -> throw IndexOutOfBoundsException("Mask index must be 0-3, got $index")
            }

        /** Get the 4 mask bytes repeated to fill a Long for bulk XOR operations. */
        fun asLong(): Long {
            val p = packed.toLong() and 0xFFFFFFFFL
            return (p shl 32) or p
        }

        fun write(buffer: WriteBuffer) {
            buffer.writeInt(packed)
        }
    }
}
