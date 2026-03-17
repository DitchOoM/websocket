package com.ditchoom.websocket.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlin.random.Random

/**
 * Benchmarks for WebSocket frame serialization.
 *
 * Measures the complete frame encoding process including:
 * - Header encoding (FIN, opcode, mask bit, payload length)
 * - Extended payload length handling (16-bit and 64-bit)
 * - Masking key generation and XOR masking
 *
 * Uses BufferFactory.Default directly to avoid ByteArray allocations and memory copies.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class FrameSerializationBenchmark {
    private lateinit var smallPayload: PlatformBuffer
    private lateinit var mediumPayload: PlatformBuffer
    private lateinit var largePayload: PlatformBuffer
    private var maskInt: Int = 0

    companion object {
        private const val SMALL_SIZE = 64 // Control frame size (ping/pong)
        private const val MEDIUM_SIZE = 1024 // Typical text message
        private const val LARGE_SIZE = 65536 // Large binary payload
    }

    @Setup
    fun setup() {
        val random = Random.Default
        maskInt = random.nextInt()

        // Pre-allocate buffers with random data
        smallPayload = BufferFactory.Default.allocate(SMALL_SIZE).apply {
            repeat(SMALL_SIZE) { writeByte(random.nextInt().toByte()) }
            resetForRead()
        }
        mediumPayload = BufferFactory.Default.allocate(MEDIUM_SIZE).apply {
            repeat(MEDIUM_SIZE) { writeByte(random.nextInt().toByte()) }
            resetForRead()
        }
        largePayload = BufferFactory.Default.allocate(LARGE_SIZE).apply {
            repeat(LARGE_SIZE) { writeByte(random.nextInt().toByte()) }
            resetForRead()
        }
    }

    @Benchmark
    fun frame_serialize_small(bh: Blackhole) {
        val payloadSize = SMALL_SIZE
        val frameSize = 2 + 4 + payloadSize // header + mask + payload
        val buffer = BufferFactory.Default.allocate(frameSize)

        // Byte 1: FIN=1, opcode=1 (Text)
        buffer.writeByte(0x81.toByte())

        // Byte 2: MASK=1, len (7-bit for small payloads)
        buffer.writeByte((0x80 or (payloadSize and 0x7F)).toByte())

        // Write mask key as 4 bytes from int
        buffer.writeInt(maskInt)

        // Write payload from source buffer
        smallPayload.resetForRead()
        buffer.write(smallPayload)

        // Apply mask in-place
        val payloadStart = 2 + 4
        buffer.position(payloadStart)
        buffer.setLimit(payloadStart + payloadSize)
        buffer.xorMask(maskInt)

        buffer.resetForRead()
        bh.consume(buffer)
    }

    @Benchmark
    fun frame_serialize_medium(bh: Blackhole) {
        val payloadSize = MEDIUM_SIZE
        // 16-bit extended length for payloads 126-65535
        val frameSize = 2 + 2 + 4 + payloadSize // header + ext len + mask + payload
        val buffer = BufferFactory.Default.allocate(frameSize)

        buffer.writeByte(0x81.toByte()) // FIN=1, opcode=1 (Text)
        buffer.writeByte((0x80 or 126).toByte()) // MASK=1, len=126 (16-bit follows)
        buffer.writeShort(payloadSize.toShort()) // Extended length
        buffer.writeInt(maskInt)

        mediumPayload.resetForRead()
        buffer.write(mediumPayload)

        val payloadStart = 2 + 2 + 4
        buffer.position(payloadStart)
        buffer.setLimit(payloadStart + payloadSize)
        buffer.xorMask(maskInt)

        buffer.resetForRead()
        bh.consume(buffer)
    }

    @Benchmark
    fun frame_serialize_large(bh: Blackhole) {
        val payloadSize = LARGE_SIZE
        // 16-bit extended length (65536 fits in 16 bits)
        val frameSize = 2 + 2 + 4 + payloadSize // header + ext len + mask + payload
        val buffer = BufferFactory.Default.allocate(frameSize)

        buffer.writeByte(0x82.toByte()) // FIN=1, opcode=2 (Binary)
        buffer.writeByte((0x80 or 126).toByte()) // MASK=1, len=126 (16-bit follows)
        buffer.writeShort(payloadSize.toShort()) // Extended length
        buffer.writeInt(maskInt)

        largePayload.resetForRead()
        buffer.write(largePayload)

        val payloadStart = 2 + 2 + 4
        buffer.position(payloadStart)
        buffer.setLimit(payloadStart + payloadSize)
        buffer.xorMask(maskInt)

        buffer.resetForRead()
        bh.consume(buffer)
    }
}
