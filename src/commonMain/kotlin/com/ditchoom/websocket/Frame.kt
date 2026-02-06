package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.compression.SuspendingStreamingCompressor

/**
 *
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 * |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 * | |1|2|3|       |K|             |                               |
 * +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 * |     Extended payload length continued, if payload len == 127  |
 * + - - - - - - - - - - - - - - - +-------------------------------+
 * |                               |Masking-key, if MASK set to 1  |
 * +-------------------------------+-------------------------------+
 * | Masking-key (continued)       |          Payload Data         |
 * +-------------------------------- - - - - - - - - - - - - - - - +
 * :                     Payload Data continued ...                :
 * + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 * |                     Payload Data continued ...                |
 * +---------------------------------------------------------------+
 *
 */
internal data class Frame(
    val fin: Boolean,
    val rsv1: Boolean,
    val rsv2: Boolean,
    val rsv3: Boolean,
    val opcode: Opcode,
    val maskingKey: MaskingKey,
    val payloadData: ReadBuffer,
    val payloadLength: Int =
        if (payloadData.limit() <= 125) {
            payloadData.limit()
        } else {
            if (payloadData.limit() <= UShort.MAX_VALUE.toInt()) {
                126
            } else {
                127
            }.also { check(it in 0..127) }
        },
) {
    constructor(fin: Boolean, opcode: Opcode, maskingKey: MaskingKey, payloadData: ReadBuffer) :
        this(fin, false, false, false, opcode, maskingKey, payloadData)

    constructor(opcode: Opcode, payloadData: ReadBuffer = EMPTY_BUFFER) : this(
        false,
        opcode,
        MaskingKey.NoMaskingKey,
        payloadData,
    )

    suspend fun toBuffer(
        attemptDeflate: Boolean = false,
        level: Int = -1,
        compressor: SuspendingStreamingCompressor? = null,
    ): ReadBuffer {
        var didDeflate = false
        val payload =
            if (attemptDeflate && !rsv1) {
                val payloadSize = payloadData.remaining()
                val compressed: ReadBuffer =
                    if (compressor != null) {
                        val chunks = compressWithStreamingCompressor(payloadData, compressor)
                        val compressedSize = totalRemaining(chunks)
                        if (compressedSize >= payloadSize) {
                            // Compression didn't help, use original
                            payloadData.resetForRead()
                            compressor.reset()
                            payloadData
                        } else {
                            didDeflate = true
                            compressor.reset()
                            // Combine chunks for frame serialization (needed for masking)
                            combineChunks(chunks, AllocationZone.Heap)
                        }
                    } else {
                        val compressedBuffer = payloadData.compressWebsocketBuffer(level)
                        if (compressedBuffer.remaining() >= payloadSize) {
                            payloadData.resetForRead()
                            payloadData
                        } else {
                            didDeflate = true
                            compressedBuffer
                        }
                    }
                compressed
            } else {
                payloadData
            }
        if (didDeflate) {
            val buffer =
                Frame(fin, true, rsv2, rsv3, opcode, maskingKey, payload)
                    .toBuffer(attemptDeflate = false)
            return buffer
        }
        val buffer = PlatformBuffer.allocate(size())
        serialize(buffer)
        return buffer
    }

    private val actualPayloadLength =
        if (payloadLength <= 125) {
            payloadLength
        } else if (payloadLength == 126) {
            payloadData.limit() + UShort.SIZE_BYTES
        } else if (payloadLength == 127) {
            payloadData.limit() + ULong.SIZE_BYTES
        } else {
            throw IllegalStateException("Internal payload len size cannot be larger than 127")
        }

    fun size(): Int {
        val ws2ByteOverhead = 2
        return actualPayloadLength + ws2ByteOverhead +
            when (maskingKey) {
                is MaskingKey.FourByteMaskingKey -> 4
                MaskingKey.NoMaskingKey -> 0
            }
    }

    fun serialize(writeBuffer: ReadWriteBuffer) {
        serializeByte1(writeBuffer)
        serializeMaskAndPayloadLength(writeBuffer)
        serializeMaskingKeyAndPayload(writeBuffer)
    }

    /**
     * Encodes fin, rsv1-3, and opcode into a single byte using bitwise operations.
     * Zero allocation - no BooleanArray created.
     */
    private fun serializeByte1(writeBuffer: WriteBuffer) {
        var byte1 = opcode.value.toInt() and 0x0F
        if (fin) byte1 = byte1 or 0x80
        if (rsv1) byte1 = byte1 or 0x40
        if (rsv2) byte1 = byte1 or 0x20
        if (rsv3) byte1 = byte1 or 0x10
        writeBuffer.writeByte(byte1.toByte())
    }

    /**
     * Encodes mask bit and payload length using bitwise operations.
     * Zero allocation - no BooleanArray created.
     */
    private fun serializeMaskAndPayloadLength(writeBuffer: WriteBuffer) {
        var byte2 = payloadLength and 0x7F
        if (maskingKey is MaskingKey.FourByteMaskingKey) {
            byte2 = byte2 or 0x80
        }
        writeBuffer.writeByte(byte2.toByte())
        if (payloadLength == 126) {
            writeBuffer.writeUShort(payloadData.limit().toUShort())
        } else if (payloadLength == 127) {
            writeBuffer.writeLong(payloadData.limit().toLong())
        }
    }

    /**
     * Writes masking key and masked payload using buffer's optimized xorMask.
     * Copies payload data first, then applies XOR mask in-place.
     */
    private fun serializeMaskingKeyAndPayload(writeBuffer: ReadWriteBuffer) {
        if (maskingKey is MaskingKey.FourByteMaskingKey) {
            maskingKey.write(writeBuffer)
            val dataSize = payloadData.limit()
            payloadData.position(0)
            val dstStart = writeBuffer.position()

            // Copy payload data to write buffer
            writeBuffer.write(payloadData)

            // Apply XOR mask in-place on the written region
            val dstEnd = writeBuffer.position()
            writeBuffer.position(dstStart)
            writeBuffer.setLimit(dstEnd)
            writeBuffer.xorMask(maskingKey.packed)
            writeBuffer.position(dstEnd)
            writeBuffer.setLimit(writeBuffer.capacity)
        } else {
            payloadData.position(0)
            writeBuffer.write(payloadData)
        }
    }

    override fun toString(): String {
        val p =
            if (opcode == Opcode.Text) {
                val position = payloadData.position()
                val s = payloadData.readString(payloadData.remaining())
                payloadData.position(position)
                s
            } else {
                payloadData.toString()
            }
        return "Frame(fin=$fin, rsv1=$rsv1, rsv2=$rsv2, rsv3=$rsv3, opcode=$opcode, " +
            "maskingKey=$maskingKey, payloadData=$p, payloadLength=$payloadLength, " +
            "actualPayloadLength=$actualPayloadLength)"
    }
}
