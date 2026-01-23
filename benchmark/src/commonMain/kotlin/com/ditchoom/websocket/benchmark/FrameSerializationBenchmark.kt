package com.ditchoom.websocket.benchmark

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.wrap
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlin.experimental.xor
import kotlin.random.Random

/**
 * Benchmarks for WebSocket frame serialization operations.
 *
 * Tests the key operations that are in the hot path:
 * 1. Byte1 serialization (fin/rsv/opcode encoding) - BooleanArray vs bitwise
 * 2. Masking (XOR loop) - buffer position-based vs direct byte array
 * 3. Full frame serialization - current approach vs optimized
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class FrameSerializationBenchmark {
    private lateinit var smallPayload: ByteArray
    private lateinit var mediumPayload: ByteArray
    private lateinit var largePayload: ByteArray
    private lateinit var maskBytes: ByteArray

    @Setup
    fun setup() {
        smallPayload = Random.nextBytes(10) // typical control frame
        mediumPayload = Random.nextBytes(1024) // typical text message
        largePayload = Random.nextBytes(65536) // large binary payload
        maskBytes = Random.nextBytes(4)
    }

    // ===== Byte1 Encoding Benchmarks =====

    /**
     * Current approach: Allocates BooleanArray(8) and converts to byte.
     * This is called ONCE per frame.
     */
    @Benchmark
    fun byte1_booleanArrayApproach(bh: Blackhole) {
        val fin = true
        val rsv1 = false
        val rsv2 = false
        val rsv3 = false
        val opcodeValue: Byte = 0x01 // Text

        val byte1Array = BooleanArray(8)
        byte1Array[0] = fin
        byte1Array[1] = rsv1
        byte1Array[2] = rsv2
        byte1Array[3] = rsv3
        byte1Array[4] = getBit(opcodeValue, 4)
        byte1Array[5] = getBit(opcodeValue, 5)
        byte1Array[6] = getBit(opcodeValue, 6)
        byte1Array[7] = getBit(opcodeValue, 7)
        val result = booleanArrayToByte(byte1Array)
        bh.consume(result)
    }

    /**
     * Optimized approach: Direct bitwise operations, zero allocation.
     */
    @Benchmark
    fun byte1_bitwiseApproach(bh: Blackhole) {
        val fin = true
        val rsv1 = false
        val rsv2 = false
        val rsv3 = false
        val opcodeValue: Int = 0x01 // Text

        var byte1 = opcodeValue and 0x0F
        if (fin) byte1 = byte1 or 0x80
        if (rsv1) byte1 = byte1 or 0x40
        if (rsv2) byte1 = byte1 or 0x20
        if (rsv3) byte1 = byte1 or 0x10
        bh.consume(byte1.toByte())
    }

    // ===== Mask+PayloadLength Encoding Benchmarks =====

    /**
     * Current approach: toBooleanArray() on the payload length byte, then modify mask bit.
     */
    @Benchmark
    fun maskAndLength_booleanArrayApproach(bh: Blackhole) {
        val payloadLength = 100 // fits in 7 bits
        val hasMask = true

        val maskedBitAndPayloadLengthArray = byteToBooleanArray(payloadLength.toByte())
        maskedBitAndPayloadLengthArray[0] = hasMask
        val byte = booleanArrayToByte(maskedBitAndPayloadLengthArray)
        bh.consume(byte)
    }

    /**
     * Optimized approach: Direct bitwise.
     */
    @Benchmark
    fun maskAndLength_bitwiseApproach(bh: Blackhole) {
        val payloadLength = 100
        val hasMask = true

        var byte2 = payloadLength and 0x7F
        if (hasMask) byte2 = byte2 or 0x80
        bh.consume(byte2.toByte())
    }

    // ===== Masking Benchmarks (Hot Path for large payloads) =====

    /**
     * Current approach: ReadBuffer position-based access per byte via TransformedReadBuffer pattern.
     * maskingKey[i.mod(4)] calls position(index) then readByte() for each byte.
     */
    @Benchmark
    fun masking_positionBasedSmall(bh: Blackhole) {
        val maskBuffer = PlatformBuffer.wrap(maskBytes)
        val result = ByteArray(smallPayload.size)
        for (i in smallPayload.indices) {
            maskBuffer.position(i.mod(4))
            val maskByte = maskBuffer.readByte()
            result[i] = smallPayload[i] xor maskByte
        }
        bh.consume(result)
    }

    @Benchmark
    fun masking_positionBasedMedium(bh: Blackhole) {
        val maskBuffer = PlatformBuffer.wrap(maskBytes)
        val result = ByteArray(mediumPayload.size)
        for (i in mediumPayload.indices) {
            maskBuffer.position(i.mod(4))
            val maskByte = maskBuffer.readByte()
            result[i] = mediumPayload[i] xor maskByte
        }
        bh.consume(result)
    }

    @Benchmark
    fun masking_positionBasedLarge(bh: Blackhole) {
        val maskBuffer = PlatformBuffer.wrap(maskBytes)
        val result = ByteArray(largePayload.size)
        for (i in largePayload.indices) {
            maskBuffer.position(i.mod(4))
            val maskByte = maskBuffer.readByte()
            result[i] = largePayload[i] xor maskByte
        }
        bh.consume(result)
    }

    /**
     * Optimized approach: Direct byte array access for mask bytes.
     */
    @Benchmark
    fun masking_directByteArraySmall(bh: Blackhole) {
        val result = ByteArray(smallPayload.size)
        for (i in smallPayload.indices) {
            result[i] = smallPayload[i] xor maskBytes[i and 3]
        }
        bh.consume(result)
    }

    @Benchmark
    fun masking_directByteArrayMedium(bh: Blackhole) {
        val result = ByteArray(mediumPayload.size)
        for (i in mediumPayload.indices) {
            result[i] = mediumPayload[i] xor maskBytes[i and 3]
        }
        bh.consume(result)
    }

    @Benchmark
    fun masking_directByteArrayLarge(bh: Blackhole) {
        val result = ByteArray(largePayload.size)
        for (i in largePayload.indices) {
            result[i] = largePayload[i] xor maskBytes[i and 3]
        }
        bh.consume(result)
    }

    /**
     * Further optimized: Process 4 bytes at a time using Int XOR.
     */
    @Benchmark
    fun masking_intChunkedLarge(bh: Blackhole) {
        val result = ByteArray(largePayload.size)
        val maskInt = (maskBytes[0].toInt() and 0xFF shl 24) or
            (maskBytes[1].toInt() and 0xFF shl 16) or
            (maskBytes[2].toInt() and 0xFF shl 8) or
            (maskBytes[3].toInt() and 0xFF)

        var i = 0
        // Process 4 bytes at a time
        val limit = largePayload.size - 3
        while (i < limit) {
            val payloadInt = (largePayload[i].toInt() and 0xFF shl 24) or
                (largePayload[i + 1].toInt() and 0xFF shl 16) or
                (largePayload[i + 2].toInt() and 0xFF shl 8) or
                (largePayload[i + 3].toInt() and 0xFF)
            val masked = payloadInt xor maskInt
            result[i] = (masked shr 24).toByte()
            result[i + 1] = (masked shr 16).toByte()
            result[i + 2] = (masked shr 8).toByte()
            result[i + 3] = masked.toByte()
            i += 4
        }
        // Handle remaining bytes
        while (i < largePayload.size) {
            result[i] = largePayload[i] xor maskBytes[i and 3]
            i++
        }
        bh.consume(result)
    }

    /**
     * Long-based bulk XOR (8 bytes at a time) matching the production code.
     * Uses PlatformBuffer.readLong/writeLong for realistic I/O.
     */
    @Benchmark
    fun masking_longBulkLarge(bh: Blackhole) {
        val maskInt = (maskBytes[0].toInt() and 0xFF shl 24) or
            (maskBytes[1].toInt() and 0xFF shl 16) or
            (maskBytes[2].toInt() and 0xFF shl 8) or
            (maskBytes[3].toInt() and 0xFF)
        val maskLong = (maskInt.toLong() and 0xFFFFFFFFL shl 32) or
            (maskInt.toLong() and 0xFFFFFFFFL)

        val payloadBuffer = PlatformBuffer.wrap(largePayload)
        val outputBuffer = PlatformBuffer.allocate(largePayload.size)
        val dataSize = largePayload.size
        var i = 0
        val longLimit = dataSize - 7

        while (i < longLimit) {
            val payloadLong = payloadBuffer.readLong()
            outputBuffer.writeLong(payloadLong xor maskLong)
            i += 8
        }
        while (i < dataSize) {
            val original = payloadBuffer.readByte()
            outputBuffer.writeByte((original.toInt() xor maskBytes[i and 3].toInt()).toByte())
            i++
        }
        bh.consume(outputBuffer)
    }

    @Benchmark
    fun masking_longBulkMedium(bh: Blackhole) {
        val maskInt = (maskBytes[0].toInt() and 0xFF shl 24) or
            (maskBytes[1].toInt() and 0xFF shl 16) or
            (maskBytes[2].toInt() and 0xFF shl 8) or
            (maskBytes[3].toInt() and 0xFF)
        val maskLong = (maskInt.toLong() and 0xFFFFFFFFL shl 32) or
            (maskInt.toLong() and 0xFFFFFFFFL)

        val payloadBuffer = PlatformBuffer.wrap(mediumPayload)
        val outputBuffer = PlatformBuffer.allocate(mediumPayload.size)
        val dataSize = mediumPayload.size
        var i = 0
        val longLimit = dataSize - 7

        while (i < longLimit) {
            val payloadLong = payloadBuffer.readLong()
            outputBuffer.writeLong(payloadLong xor maskLong)
            i += 8
        }
        while (i < dataSize) {
            val original = payloadBuffer.readByte()
            outputBuffer.writeByte((original.toInt() xor maskBytes[i and 3].toInt()).toByte())
            i++
        }
        bh.consume(outputBuffer)
    }

    // ===== Full Frame Serialization Benchmarks =====

    /**
     * Current approach: Full frame serialization with BooleanArray + position-based masking.
     */
    @Benchmark
    fun fullFrame_currentApproachMedium(bh: Blackhole) {
        val payloadSize = mediumPayload.size
        val frameSize = 2 + 4 + payloadSize // header + mask + payload
        val buffer = PlatformBuffer.allocate(frameSize)

        // Byte 1: BooleanArray approach
        val byte1Array = BooleanArray(8)
        byte1Array[0] = true // fin
        byte1Array[4] = false // opcode bit 4
        byte1Array[5] = false
        byte1Array[6] = false
        byte1Array[7] = true // Text = 0x01
        buffer.writeByte(booleanArrayToByte(byte1Array))

        // Byte 2: BooleanArray approach
        val byte2Array = byteToBooleanArray(payloadSize.toByte())
        byte2Array[0] = true // mask
        buffer.writeByte(booleanArrayToByte(byte2Array))

        // Write mask
        buffer.write(maskBytes)

        // Write masked payload (position-based)
        val maskBuffer = PlatformBuffer.wrap(maskBytes)
        for (i in mediumPayload.indices) {
            maskBuffer.position(i.mod(4))
            buffer.writeByte(mediumPayload[i] xor maskBuffer.readByte())
        }

        bh.consume(buffer)
    }

    /**
     * Optimized approach: Bitwise header + direct masking.
     */
    @Benchmark
    fun fullFrame_optimizedApproachMedium(bh: Blackhole) {
        val payloadSize = mediumPayload.size
        val frameSize = 2 + 4 + payloadSize
        val buffer = PlatformBuffer.allocate(frameSize)

        // Byte 1: Direct bitwise
        val byte1 = 0x81 // fin=1, opcode=1 (Text)
        buffer.writeByte(byte1.toByte())

        // Byte 2: Direct bitwise
        val byte2 = 0x80 or (payloadSize and 0x7F) // mask=1, len
        buffer.writeByte(byte2.toByte())

        // Write mask
        buffer.write(maskBytes)

        // Write masked payload (direct byte array access)
        for (i in mediumPayload.indices) {
            buffer.writeByte(mediumPayload[i] xor maskBytes[i and 3])
        }

        bh.consume(buffer)
    }

    // ===== Buffer Allocation Benchmark =====

    @Benchmark
    fun bufferAllocation_perFrame(bh: Blackhole) {
        // Simulates allocating a new buffer per frame (current approach)
        val buffer = PlatformBuffer.allocate(1024 + 6) // medium frame
        bh.consume(buffer)
    }

    // ===== Helper Functions (replicating internal behavior) =====

    private fun getBit(byte: Byte, position: Int): Boolean =
        (byte.toInt() shr (7 - position)) and 1 == 1

    private fun booleanArrayToByte(array: BooleanArray): Byte {
        var result = 0
        for (i in 0..7) {
            if (array[i]) {
                result = result or (1 shl (7 - i))
            }
        }
        return result.toByte()
    }

    private fun byteToBooleanArray(byte: Byte): BooleanArray {
        val array = BooleanArray(8)
        for (i in 0..7) {
            array[i] = (byte.toInt() shr (7 - i)) and 1 == 1
        }
        return array
    }
}
