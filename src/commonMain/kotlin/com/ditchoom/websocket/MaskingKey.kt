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

        /** Get the mask byte at index 0-3 (big-endian order). */
        operator fun get(index: Int): Byte =
            when (index and 3) {
                0 -> (packed ushr 24).toByte()
                1 -> (packed ushr 16).toByte()
                2 -> (packed ushr 8).toByte()
                else -> packed.toByte()
            }

        fun write(buffer: WriteBuffer) {
            buffer.writeInt(packed)
        }
    }
}
