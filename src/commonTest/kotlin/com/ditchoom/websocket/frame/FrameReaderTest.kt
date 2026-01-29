package com.ditchoom.websocket.frame

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.websocket.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [FrameReader].
 *
 * Tests are organized by RFC section for traceability.
 *
 * References:
 * - RFC 6455 Section 5.2: Base Framing Protocol
 *   https://datatracker.ietf.org/doc/html/rfc6455#section-5.2
 * - RFC 6455 Section 5.5: Control Frames
 *   https://datatracker.ietf.org/doc/html/rfc6455#section-5.5
 */
class FrameReaderTest {

    private val pool = BufferPool(allocationZone = AllocationZone.Heap)

    // ========================================================================
    // RFC 6455 Section 5.2 - Basic Frame Parsing
    // https://datatracker.ietf.org/doc/html/rfc6455#section-5.2
    // ========================================================================

    @Test
    fun `RFC 6455 Section 5-2 - parse minimal unmasked frame`() = runTest {
        // Unmasked text frame with "Hi" payload
        // Byte 1: 0x81 = FIN=1, opcode=1 (text)
        // Byte 2: 0x02 = MASK=0, len=2
        // Payload: "Hi"
        val frameBytes = byteArrayOf(
            0x81.toByte(), 0x02, 'H'.code.toByte(), 'i'.code.toByte()
        )

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frameBytes))

        val reader = FrameReader(processor)
        val frame = reader.readFrame()

        assertNotNull(frame)
        assertTrue(frame.fin)
        assertEquals(Opcode.Text, frame.opcode)
        assertFalse(frame.masked)
        assertEquals(2, frame.payloadLength)
        assertEquals("Hi", frame.payload.readString(2, Charset.UTF8))
    }

    @Test
    fun `RFC 6455 Section 5-2 - parse masked frame with 4-byte key`() = runTest {
        // Masked text frame with "Hi" payload
        // Byte 1: 0x81 = FIN=1, opcode=1 (text)
        // Byte 2: 0x82 = MASK=1, len=2
        // Mask: 0x01020304
        // Masked payload: XOR("Hi", mask)
        val maskKey = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val payload = "Hi".encodeToByteArray()
        val maskedPayload = payload.mapIndexed { i, b -> (b.toInt() xor maskKey[i % 4].toInt()).toByte() }.toByteArray()

        val frameBytes = byteArrayOf(
            0x81.toByte(), 0x82.toByte(),
            *maskKey,
            *maskedPayload
        )

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frameBytes))

        val reader = FrameReader(processor)
        val frame = reader.readFrame()

        assertNotNull(frame)
        assertTrue(frame.masked)
        assertEquals("Hi", frame.payload.readString(2, Charset.UTF8))
    }

    @Test
    fun `RFC 6455 Section 5-2 - parse frame with 16-bit extended length`() = runTest {
        // Frame with payload length 126 (uses 16-bit extended length)
        val payloadSize = 200
        val payload = ByteArray(payloadSize) { it.toByte() }

        val frameBytes = byteArrayOf(
            0x82.toByte(), // FIN=1, opcode=2 (binary)
            0x7E, // MASK=0, len=126 (16-bit follows)
            (payloadSize shr 8).toByte(), // Extended length high byte
            (payloadSize and 0xFF).toByte(), // Extended length low byte
            *payload
        )

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frameBytes))

        val reader = FrameReader(processor)
        val frame = reader.readFrame()

        assertNotNull(frame)
        assertEquals(Opcode.Binary, frame.opcode)
        assertEquals(payloadSize, frame.payloadLength)
    }

    @Test
    fun `RFC 6455 Section 5-2 - parse frame with 64-bit extended length`() = runTest {
        // Frame with payload length > 65535 (uses 64-bit extended length)
        val payloadSize = 70000
        val payload = ByteArray(payloadSize) { (it % 256).toByte() }

        val lengthBytes = ByteArray(8)
        lengthBytes[4] = ((payloadSize shr 24) and 0xFF).toByte()
        lengthBytes[5] = ((payloadSize shr 16) and 0xFF).toByte()
        lengthBytes[6] = ((payloadSize shr 8) and 0xFF).toByte()
        lengthBytes[7] = (payloadSize and 0xFF).toByte()

        val frameBytes = byteArrayOf(
            0x82.toByte(), // FIN=1, opcode=2 (binary)
            0x7F, // MASK=0, len=127 (64-bit follows)
            *lengthBytes,
            *payload
        )

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frameBytes))

        val reader = FrameReader(processor)
        val frame = reader.readFrame()

        assertNotNull(frame)
        assertEquals(Opcode.Binary, frame.opcode)
        assertEquals(payloadSize, frame.payloadLength)
    }

    @Test
    fun `RFC 6455 Section 5-2 - FIN bit parsing`() = runTest {
        // FIN=0 (continuation expected)
        val fragmentFrame = byteArrayOf(0x01, 0x02, 'H'.code.toByte(), 'i'.code.toByte())

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(fragmentFrame))

        val reader = FrameReader(processor)
        val frame = reader.readFrame()

        assertNotNull(frame)
        assertFalse(frame.fin, "FIN should be false for fragment")
        assertEquals(Opcode.Text, frame.opcode)
    }

    @Test
    fun `RFC 6455 Section 5-2 - RSV1 bit parsing for compression`() = runTest {
        // RSV1=1 indicates compressed frame (permessage-deflate)
        // 0xC1 = 1100 0001 = FIN=1, RSV1=1, opcode=1
        val compressedFrame = byteArrayOf(0xC1.toByte(), 0x02, 'H'.code.toByte(), 'i'.code.toByte())

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(compressedFrame))

        val reader = FrameReader(processor)
        val frame = reader.readFrame()

        assertNotNull(frame)
        assertTrue(frame.rsv1, "RSV1 should be true for compressed frame")
        assertTrue(frame.fin)
    }

    @Test
    fun `RFC 6455 Section 5-2 - all RSV bits parsing`() = runTest {
        // All RSV bits set: 0xF1 = 1111 0001 = FIN=1, RSV1=1, RSV2=1, RSV3=1, opcode=1
        val frameBytes = byteArrayOf(0xF1.toByte(), 0x00)

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frameBytes))

        val reader = FrameReader(processor)
        val frame = reader.readFrame()

        assertNotNull(frame)
        assertTrue(frame.rsv1)
        assertTrue(frame.rsv2)
        assertTrue(frame.rsv3)
    }

    // ========================================================================
    // RFC 6455 Section 5.2 - Opcode Parsing
    // https://datatracker.ietf.org/doc/html/rfc6455#section-5.2
    // ========================================================================

    @Test
    fun `RFC 6455 Section 5-2 - parse continuation frame opcode 0x0`() = runTest {
        val frameBytes = byteArrayOf(0x00, 0x02, 'H'.code.toByte(), 'i'.code.toByte())

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frameBytes))

        val frame = FrameReader(processor).readFrame()

        assertNotNull(frame)
        assertEquals(Opcode.Continuation, frame.opcode)
        assertFalse(frame.isControlFrame)
        assertTrue(frame.isDataFrame)
    }

    @Test
    fun `RFC 6455 Section 5-2 - parse text frame opcode 0x1`() = runTest {
        val frameBytes = byteArrayOf(0x81.toByte(), 0x00)

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frameBytes))

        val frame = FrameReader(processor).readFrame()

        assertNotNull(frame)
        assertEquals(Opcode.Text, frame.opcode)
    }

    @Test
    fun `RFC 6455 Section 5-2 - parse binary frame opcode 0x2`() = runTest {
        val frameBytes = byteArrayOf(0x82.toByte(), 0x00)

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frameBytes))

        val frame = FrameReader(processor).readFrame()

        assertNotNull(frame)
        assertEquals(Opcode.Binary, frame.opcode)
    }

    // ========================================================================
    // RFC 6455 Section 5.5 - Control Frames
    // https://datatracker.ietf.org/doc/html/rfc6455#section-5.5
    // ========================================================================

    @Test
    fun `RFC 6455 Section 5-5 - parse close frame opcode 0x8`() = runTest {
        // Close frame with status code 1000 (normal closure)
        val frameBytes = byteArrayOf(
            0x88.toByte(), // FIN=1, opcode=8 (close)
            0x02, // len=2
            0x03, 0xE8.toByte() // 1000 in big-endian
        )

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frameBytes))

        val frame = FrameReader(processor).readFrame()

        assertNotNull(frame)
        assertEquals(Opcode.Close, frame.opcode)
        assertTrue(frame.isControlFrame)
        assertFalse(frame.isDataFrame)
        assertEquals(2, frame.payloadLength)
    }

    @Test
    fun `RFC 6455 Section 5-5-2 - parse ping frame opcode 0x9`() = runTest {
        val pingData = "ping".encodeToByteArray()
        val frameBytes = byteArrayOf(
            0x89.toByte(), // FIN=1, opcode=9 (ping)
            pingData.size.toByte(),
            *pingData
        )

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frameBytes))

        val frame = FrameReader(processor).readFrame()

        assertNotNull(frame)
        assertEquals(Opcode.Ping, frame.opcode)
        assertTrue(frame.isControlFrame)
        assertEquals("ping", frame.payload.readString(4, Charset.UTF8))
    }

    @Test
    fun `RFC 6455 Section 5-5-3 - parse pong frame opcode 0xA`() = runTest {
        val pongData = "pong".encodeToByteArray()
        val frameBytes = byteArrayOf(
            0x8A.toByte(), // FIN=1, opcode=10 (pong)
            pongData.size.toByte(),
            *pongData
        )

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frameBytes))

        val frame = FrameReader(processor).readFrame()

        assertNotNull(frame)
        assertEquals(Opcode.Pong, frame.opcode)
        assertTrue(frame.isControlFrame)
    }

    // ========================================================================
    // Incremental Parsing (not enough data)
    // ========================================================================

    @Test
    fun `incremental parsing - returns null when no data`() = runTest {
        val processor = StreamProcessor.builder(pool).buildSuspending()
        val reader = FrameReader(processor)

        assertNull(reader.readFrame())
        assertFalse(reader.hasMinimumData())
    }

    @Test
    fun `incremental parsing - returns null with only 1 byte`() = runTest {
        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(byteArrayOf(0x81.toByte())))

        val reader = FrameReader(processor)

        assertNull(reader.readFrame())
    }

    @Test
    fun `incremental parsing - returns null when header complete but payload missing`() = runTest {
        // Header says 10 bytes but only 2 provided
        val frameBytes = byteArrayOf(0x81.toByte(), 0x0A, 'H'.code.toByte(), 'i'.code.toByte())

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frameBytes))

        val reader = FrameReader(processor)

        assertNull(reader.readFrame())
    }

    @Test
    fun `incremental parsing - returns null when extended length incomplete`() = runTest {
        // Frame says 16-bit length but only 1 byte provided
        val frameBytes = byteArrayOf(0x82.toByte(), 0x7E, 0x00)

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frameBytes))

        val reader = FrameReader(processor)

        assertNull(reader.readFrame())
    }

    @Test
    fun `incremental parsing - completes when more data arrives`() = runTest {
        val processor = StreamProcessor.builder(pool).buildSuspending()
        val reader = FrameReader(processor)

        // Send partial frame
        processor.append(createBuffer(byteArrayOf(0x81.toByte(), 0x05)))
        assertNull(reader.readFrame())

        // Send rest of payload
        processor.append(createBuffer("Hello".encodeToByteArray()))
        val frame = reader.readFrame()

        assertNotNull(frame)
        assertEquals("Hello", frame.payload.readString(5, Charset.UTF8))
    }

    // ========================================================================
    // Error Cases
    // ========================================================================

    @Test
    fun `error - 64-bit length with MSB set throws exception`() = runTest {
        // RFC 6455: "the most significant bit MUST be 0"
        val frameBytes = byteArrayOf(
            0x82.toByte(),
            0x7F, // 64-bit length
            0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01 // MSB set
        )

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frameBytes))

        assertFailsWith<FrameParseException> {
            FrameReader(processor).readFrame()
        }
    }

    // ========================================================================
    // Multiple Frames
    // ========================================================================

    @Test
    fun `parse multiple frames sequentially`() = runTest {
        val frame1 = byteArrayOf(0x81.toByte(), 0x02, 'H'.code.toByte(), 'i'.code.toByte())
        val frame2 = byteArrayOf(0x82.toByte(), 0x03, 0x01, 0x02, 0x03)

        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(createBuffer(frame1 + frame2))

        val reader = FrameReader(processor)

        val parsed1 = reader.readFrame()
        assertNotNull(parsed1)
        assertEquals(Opcode.Text, parsed1.opcode)
        assertEquals("Hi", parsed1.payload.readString(2, Charset.UTF8))

        val parsed2 = reader.readFrame()
        assertNotNull(parsed2)
        assertEquals(Opcode.Binary, parsed2.opcode)
        assertEquals(3, parsed2.payloadLength)
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun createBuffer(bytes: ByteArray): com.ditchoom.buffer.ReadBuffer {
        val buffer = PlatformBuffer.allocate(bytes.size)
        buffer.writeBytes(bytes)
        buffer.resetForRead()
        return buffer
    }
}
