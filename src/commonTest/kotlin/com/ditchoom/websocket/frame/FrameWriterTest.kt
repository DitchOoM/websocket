package com.ditchoom.websocket.frame

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.websocket.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [FrameWriter].
 *
 * Tests verify that frames are serialized correctly per RFC 6455.
 * Uses FrameReader to parse and verify the output.
 *
 * References:
 * - RFC 6455 Section 5.2: Base Framing Protocol
 *   https://datatracker.ietf.org/doc/html/rfc6455#section-5.2
 * - RFC 6455 Section 5.3: Client-to-Server Masking
 *   https://datatracker.ietf.org/doc/html/rfc6455#section-5.3
 * - RFC 6455 Section 5.5: Control Frames
 *   https://datatracker.ietf.org/doc/html/rfc6455#section-5.5
 */
class FrameWriterTest {
    private val pool = BufferPool(factory = BufferFactory.managed())

    private fun createWriter(
        clientMode: Boolean = false,
        compressionEnabled: Boolean = false,
    ) = FrameWriter(clientMode = clientMode, compressionEnabled = compressionEnabled)

    // ========================================================================
    // RFC 6455 Section 5.2 - Data Frame Writing
    // https://datatracker.ietf.org/doc/html/rfc6455#section-5.2
    // ========================================================================

    @Test
    fun `RFC 6455 Section 5-2 - write text frame`() =
        runTest {
            val writer = createWriter(clientMode = false)

            val frameBuffer = writer.writeTextFrame("Hello")

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertTrue(frame.fin)
            assertEquals(Opcode.Text, frame.opcode)
            assertEquals("Hello", frame.payload.readString(5, Charset.UTF8))
        }

    @Test
    fun `RFC 6455 Section 5-2 - write binary frame`() =
        runTest {
            val writer = createWriter(clientMode = false)
            val data = byteArrayOf(0x01, 0x02, 0x03, 0x04)

            val frameBuffer = writer.writeBinaryFrame(createBuffer(data))

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertEquals(Opcode.Binary, frame.opcode)
            assertEquals(4, frame.payloadLength)
        }

    @Test
    fun `RFC 6455 Section 5-2 - write continuation frame`() =
        runTest {
            val writer = createWriter(clientMode = false)

            val frameBuffer =
                writer.writeContinuationFrame(
                    createBuffer("more data".encodeToByteArray()),
                    fin = false,
                )

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertEquals(Opcode.Continuation, frame.opcode)
            assertFalse(frame.fin)
        }

    @Test
    fun `RFC 6455 Section 5-2 - write frame with 16-bit length`() =
        runTest {
            val writer = createWriter(clientMode = false)
            val data = ByteArray(200) { it.toByte() }

            val frameBuffer = writer.writeBinaryFrame(createBuffer(data))

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertEquals(200, frame.payloadLength)
        }

    @Test
    fun `RFC 6455 Section 5-2 - write frame with 64-bit length`() =
        runTest {
            val writer = createWriter(clientMode = false)
            val data = ByteArray(70000) { (it % 256).toByte() }

            val frameBuffer = writer.writeBinaryFrame(createBuffer(data))

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertEquals(70000, frame.payloadLength)
        }

    // ========================================================================
    // RFC 6455 Section 5.3 - Client-to-Server Masking
    // https://datatracker.ietf.org/doc/html/rfc6455#section-5.3
    // ========================================================================

    @Test
    fun `RFC 6455 Section 5-3 - client frames are masked`() =
        runTest {
            // "All frames sent from client to server have this bit set to 1"
            val writer = createWriter(clientMode = true)

            val frameBuffer = writer.writeTextFrame("Hello")

            // Check MASK bit directly in raw byte2
            val byte2 = frameBuffer.get(1).toInt() and 0xFF
            assertTrue((byte2 and 0x80) != 0, "Client frames must have MASK bit set")
            // Verify payload is correctly unmasked after parsing
            frameBuffer.position(0)
            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertEquals("Hello", frame.payload.readString(frame.payloadLength, Charset.UTF8))
        }

    @Test
    fun `RFC 6455 Section 5-3 - server frames are not masked`() =
        runTest {
            // "A server MUST NOT mask any frames that it sends to the client"
            val writer = createWriter(clientMode = false)

            val frameBuffer = writer.writeTextFrame("Hello")

            // Check MASK bit directly in raw byte2
            val byte2 = frameBuffer.get(1).toInt() and 0xFF
            assertFalse((byte2 and 0x80) != 0, "Server frames must not have MASK bit set")
        }

    @Test
    fun `RFC 6455 Section 5-3 - masked data is correctly unmasked`() =
        runTest {
            val writer = createWriter(clientMode = true)
            val originalText = "The quick brown fox jumps over the lazy dog"

            val frameBuffer = writer.writeTextFrame(originalText)

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertEquals(originalText, frame.payload.readString(originalText.length, Charset.UTF8))
        }

    // ========================================================================
    // RFC 6455 Section 5.5 - Control Frames
    // https://datatracker.ietf.org/doc/html/rfc6455#section-5.5
    // ========================================================================

    @Test
    fun `RFC 6455 Section 5-5 - control frames have FIN set`() =
        runTest {
            // "All control frames MUST have the FIN bit set"
            val writer = createWriter(clientMode = false)

            val closeFrame = parseFrame(writer.writeCloseFrame(1000u))
            val pingFrame = parseFrame(writer.writePingFrame())
            val pongFrame = parseFrame(writer.writePongFrame())

            assertNotNull(closeFrame)
            assertNotNull(pingFrame)
            assertNotNull(pongFrame)

            assertTrue(closeFrame.fin, "Close frame must have FIN set")
            assertTrue(pingFrame.fin, "Ping frame must have FIN set")
            assertTrue(pongFrame.fin, "Pong frame must have FIN set")
        }

    @Test
    fun `RFC 6455 Section 5-5-1 - write close frame with status code`() =
        runTest {
            val writer = createWriter(clientMode = false)

            val frameBuffer = writer.writeCloseFrame(1000u, "Normal closure")

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertEquals(Opcode.Close, frame.opcode)

            // First 2 bytes are status code
            val statusCode = frame.payload.readShort().toInt() and 0xFFFF
            assertEquals(1000, statusCode)
        }

    @Test
    fun `RFC 6455 Section 5-5-1 - close frame reason truncated to 123 bytes`() =
        runTest {
            // Control frames max 125 bytes, minus 2 for status code = 123 for reason
            val writer = createWriter(clientMode = false)
            val longReason = "x".repeat(200)

            val frameBuffer = writer.writeCloseFrame(1000u, longReason)

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            // 2 bytes status + 123 bytes reason = 125 max
            assertTrue(frame.payloadLength <= 125, "Close frame payload must be <= 125 bytes")
        }

    @Test
    fun `close frame with no status code produces empty payload`() =
        runTest {
            val writer = createWriter(clientMode = false)

            val frame = parseFrame(writer.writeCloseFrame())
            assertNotNull(frame)
            assertEquals(Opcode.Close, frame.opcode)
            assertEquals(0, frame.payloadLength)
        }

    @Test
    fun `close frame with status code only and no reason`() =
        runTest {
            val writer = createWriter(clientMode = false)

            val frame = parseFrame(writer.writeCloseFrame(1001u))
            assertNotNull(frame)
            assertEquals(2, frame.payloadLength)
            assertEquals(1001, frame.payload.readShort().toInt() and 0xFFFF)
        }

    @Test
    fun `close frame with null reason`() =
        runTest {
            val writer = createWriter(clientMode = false)

            val frame = parseFrame(writer.writeCloseFrame(1000u, null))
            assertNotNull(frame)
            assertEquals(2, frame.payloadLength)
        }

    @Test
    fun `close frame with empty reason`() =
        runTest {
            val writer = createWriter(clientMode = false)

            val frame = parseFrame(writer.writeCloseFrame(1000u, ""))
            assertNotNull(frame)
            assertEquals(2, frame.payloadLength)
        }

    @Test
    fun `close frame preserves short ASCII reason`() =
        runTest {
            val writer = createWriter(clientMode = false)

            val frame = parseFrame(writer.writeCloseFrame(1000u, "Normal closure"))
            assertNotNull(frame)
            val statusCode = frame.payload.readShort().toInt() and 0xFFFF
            assertEquals(1000, statusCode)
            val reason = frame.payload.readString(frame.payloadLength - 2, Charset.UTF8)
            assertEquals("Normal closure", reason)
        }

    @Test
    fun `close frame with exactly 123 ASCII bytes`() =
        runTest {
            val writer = createWriter(clientMode = false)
            val reason = "x".repeat(123)

            val frame = parseFrame(writer.writeCloseFrame(1000u, reason))
            assertNotNull(frame)
            assertEquals(125, frame.payloadLength, "2 status + 123 reason = 125")
            frame.payload.readShort() // skip status
            assertEquals(reason, frame.payload.readString(123, Charset.UTF8))
        }

    @Test
    fun `close frame with 124 ASCII bytes truncates to 123`() =
        runTest {
            val writer = createWriter(clientMode = false)
            val reason = "x".repeat(124)

            val frame = parseFrame(writer.writeCloseFrame(1000u, reason))
            assertNotNull(frame)
            assertTrue(frame.payloadLength <= 125)
            frame.payload.readShort() // skip status
            val readReason = frame.payload.readString(frame.payloadLength - 2, Charset.UTF8)
            assertEquals(123, readReason.length)
        }

    @Test
    fun `close frame with multibyte chars stays within 125 bytes`() =
        runTest {
            // CJK chars are 3 bytes each in UTF-8
            val writer = createWriter(clientMode = false)
            val reason = "\u4F60\u597D".repeat(50) // 100 CJK chars = 300 UTF-8 bytes

            val frame = parseFrame(writer.writeCloseFrame(1000u, reason))
            assertNotNull(frame)
            assertTrue(frame.payloadLength <= 125, "Payload was ${frame.payloadLength}, must be <= 125")
        }

    @Test
    fun `close frame with emoji stays within 125 bytes`() =
        runTest {
            // Emoji (surrogate pairs) are 4 bytes each in UTF-8
            val writer = createWriter(clientMode = false)
            val reason = "\uD83D\uDE00".repeat(50) // 50 emoji = 200 UTF-8 bytes

            val frame = parseFrame(writer.writeCloseFrame(1000u, reason))
            assertNotNull(frame)
            assertTrue(frame.payloadLength <= 125, "Payload was ${frame.payloadLength}, must be <= 125")
        }

    @Test
    fun `close frame with masked client mode`() =
        runTest {
            val writer = createWriter(clientMode = true)

            val closeBuffer = writer.writeCloseFrame(1000u, "Going away")
            val byte2 = closeBuffer.get(1).toInt() and 0xFF
            assertTrue((byte2 and 0x80) != 0, "Client close frames must be masked")
            closeBuffer.position(0)
            val frame = parseFrame(closeBuffer)
            assertNotNull(frame)
            val statusCode = frame.payload.readShort().toInt() and 0xFFFF
            assertEquals(1000, statusCode)
            val reason = frame.payload.readString(frame.payloadLength - 2, Charset.UTF8)
            assertEquals("Going away", reason)
        }

    @Test
    fun `RFC 6455 Section 5-5-2 - write ping frame`() =
        runTest {
            val writer = createWriter(clientMode = false)
            val pingData = "ping".encodeToByteArray()

            val frameBuffer = writer.writePingFrame(createBuffer(pingData))

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertEquals(Opcode.Ping, frame.opcode)
            assertEquals("ping", frame.payload.readString(4, Charset.UTF8))
        }

    @Test
    fun `RFC 6455 Section 5-5-2 - ping payload truncated to 125 bytes`() =
        runTest {
            val writer = createWriter(clientMode = false)
            val longData = ByteArray(200) { it.toByte() }

            val frameBuffer = writer.writePingFrame(createBuffer(longData))

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertTrue(frame.payloadLength <= 125, "Ping payload must be <= 125 bytes")
        }

    @Test
    fun `RFC 6455 Section 5-5-3 - write pong frame`() =
        runTest {
            val writer = createWriter(clientMode = false)
            val pongData = "pong".encodeToByteArray()

            val frameBuffer = writer.writePongFrame(createBuffer(pongData))

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertEquals(Opcode.Pong, frame.opcode)
        }

    // ========================================================================
    // Fragmentation
    // ========================================================================

    @Test
    fun `write fragmented message`() =
        runTest {
            val writer = createWriter(clientMode = false)

            // First fragment
            val frame1 = parseFrame(writer.writeFrame(Opcode.Text, createBuffer("Hello ".encodeToByteArray()), fin = false))
            assertNotNull(frame1)
            assertEquals(Opcode.Text, frame1.opcode)
            assertFalse(frame1.fin)

            // Continuation
            val frame2 = parseFrame(writer.writeContinuationFrame(createBuffer("World".encodeToByteArray()), fin = true))
            assertNotNull(frame2)
            assertEquals(Opcode.Continuation, frame2.opcode)
            assertTrue(frame2.fin)
        }

    // ========================================================================
    // Empty Payloads
    // ========================================================================

    @Test
    fun `write empty text frame`() =
        runTest {
            val writer = createWriter(clientMode = false)

            val frameBuffer = writer.writeTextFrame("")

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertEquals(Opcode.Text, frame.opcode)
            assertEquals(0, frame.payloadLength)
        }

    @Test
    fun `write empty close frame`() =
        runTest {
            val writer = createWriter(clientMode = false)

            val frameBuffer = writer.writeCloseFrame()

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertEquals(Opcode.Close, frame.opcode)
            assertEquals(0, frame.payloadLength)
        }

    // ========================================================================
    // Round-Trip Tests
    // ========================================================================

    @Test
    fun `round-trip - text frame preserves content`() =
        runTest {
            val writer = createWriter(clientMode = true)
            val originalText = "Hello, WebSocket! 你好世界 🌍"

            val frameBuffer = writer.writeTextFrame(originalText)
            val frame = parseFrame(frameBuffer)

            assertNotNull(frame)
            assertEquals(originalText, frame.payload.readString(frame.payloadLength, Charset.UTF8))
        }

    @Test
    fun `round-trip - binary frame preserves content`() =
        runTest {
            val writer = createWriter(clientMode = true)
            val originalData = ByteArray(256) { it.toByte() }

            val frameBuffer = writer.writeBinaryFrame(createBuffer(originalData))
            val frame = parseFrame(frameBuffer)

            assertNotNull(frame)
            val readData = ByteArray(256)
            for (i in 0 until 256) {
                readData[i] = frame.payload.readByte()
            }
            assertTrue(originalData.contentEquals(readData))
        }

    // ========================================================================
    // Non-Zero Position Payload Tests
    //
    // Verifies that writeFrame handles payloads where position > 0,
    // writing only the remaining bytes (not the prefix before position).
    // ========================================================================

    @Test
    fun `write frame with non-zero position payload`() =
        runTest {
            val writer = createWriter(clientMode = false)

            // Create a buffer with prefix bytes, then advance position past them
            val buffer = BufferFactory.Default.allocate(10)
            buffer.writeBytes(byteArrayOf(0xAA.toByte(), 0xBB.toByte())) // prefix (should be skipped)
            buffer.writeBytes("Hello".encodeToByteArray()) // actual payload
            buffer.resetForRead()
            // Advance past the 2-byte prefix
            buffer.readByte()
            buffer.readByte()
            // Now position=2, remaining=5 ("Hello")

            val frameBuffer = writer.writeBinaryFrame(buffer)

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertEquals(Opcode.Binary, frame.opcode)
            assertEquals(5, frame.payloadLength)
            // Verify the payload is "Hello", not the prefix bytes
            assertEquals("Hello", frame.payload.readString(5, Charset.UTF8))
        }

    // ========================================================================
    // Wire-Format Byte Verification
    // ========================================================================

    @Test
    fun `wire format - unmasked text frame raw bytes`() =
        runTest {
            val writer = createWriter(clientMode = false)
            val frameBuffer = writer.writeTextFrame("Hi")

            // RFC 6455 Section 5.7 Example 1: unmasked text "Hi"
            // byte1: FIN=1, opcode=0x1 → 0x81
            // byte2: MASK=0, length=2 → 0x02
            // payload: 0x48 0x69 ("Hi")
            assertEquals(0x81.toByte(), frameBuffer.get(0))
            assertEquals(0x02.toByte(), frameBuffer.get(1))
            assertEquals(0x48.toByte(), frameBuffer.get(2)) // 'H'
            assertEquals(0x69.toByte(), frameBuffer.get(3)) // 'i'
        }

    @Test
    fun `wire format - 16-bit extended length byte order`() =
        runTest {
            val writer = createWriter(clientMode = false)
            val data = ByteArray(256) { 0 }
            val frameBuffer = writer.writeBinaryFrame(createBuffer(data))

            // byte1: FIN=1, opcode=0x2 → 0x82
            // byte2: MASK=0, length=126 (16-bit follows) → 0x7E
            // extended length: 256 in big-endian → 0x01 0x00
            assertEquals(0x82.toByte(), frameBuffer.get(0))
            assertEquals(0x7E.toByte(), frameBuffer.get(1))
            assertEquals(0x01.toByte(), frameBuffer.get(2)) // 256 >> 8
            assertEquals(0x00.toByte(), frameBuffer.get(3)) // 256 & 0xFF
        }

    @Test
    fun `wire format - 64-bit extended length byte order`() =
        runTest {
            val writer = createWriter(clientMode = false)
            val data = ByteArray(70000) { 0 }
            val frameBuffer = writer.writeBinaryFrame(createBuffer(data))

            // byte1: FIN=1, opcode=0x2 → 0x82
            // byte2: MASK=0, length=127 (64-bit follows) → 0x7F
            // extended length: 70000 in big-endian (8 bytes)
            assertEquals(0x82.toByte(), frameBuffer.get(0))
            assertEquals(0x7F.toByte(), frameBuffer.get(1))
            // 70000 = 0x00_00_00_00_00_01_11_70
            assertEquals(0x00.toByte(), frameBuffer.get(2))
            assertEquals(0x00.toByte(), frameBuffer.get(3))
            assertEquals(0x00.toByte(), frameBuffer.get(4))
            assertEquals(0x00.toByte(), frameBuffer.get(5))
            assertEquals(0x00.toByte(), frameBuffer.get(6))
            assertEquals(0x01.toByte(), frameBuffer.get(7))
            assertEquals(0x11.toByte(), frameBuffer.get(8))
            assertEquals(0x70.toByte(), frameBuffer.get(9))
        }

    @Test
    fun `wire format - masked frame has MASK bit and 4-byte key`() =
        runTest {
            val writer = createWriter(clientMode = true)
            val frameBuffer = writer.writeTextFrame("Hi")

            // byte1: FIN=1, opcode=0x1 → 0x81
            assertEquals(0x81.toByte(), frameBuffer.get(0))
            // byte2: MASK=1, length=2 → 0x82
            assertEquals(0x82.toByte(), frameBuffer.get(1))
            // bytes 2-5: masking key (4 bytes, any value)
            // bytes 6-7: masked payload (2 bytes)
            // Total: 8 bytes
            assertEquals(8, frameBuffer.remaining())
        }

    @Test
    fun `wire format - close frame status code big-endian`() =
        runTest {
            val writer = createWriter(clientMode = false)
            val frameBuffer = writer.writeCloseFrame(1000u, "")

            // byte1: FIN=1, opcode=0x8 → 0x88
            // byte2: MASK=0, length=2 → 0x02
            // status code: 1000 in big-endian → 0x03 0xE8
            assertEquals(0x88.toByte(), frameBuffer.get(0))
            assertEquals(0x02.toByte(), frameBuffer.get(1))
            assertEquals(0x03.toByte(), frameBuffer.get(2)) // 1000 >> 8
            assertEquals(0xE8.toByte(), frameBuffer.get(3)) // 1000 & 0xFF
        }

    @Test
    fun `wire format - RSV1 bit set for compressed frame`() =
        runTest {
            // RSV1=1 indicates permessage-deflate per RFC 7692
            val writer = createWriter(clientMode = false)
            val frameBuffer = writer.writeFrame(Opcode.Text, createBuffer("test".encodeToByteArray()), fin = true, compress = false)

            // byte1: FIN=1, RSV1=0, opcode=0x1 → 0x81
            assertEquals(0x81.toByte(), frameBuffer.get(0))
            // RSV1 would be 0xC1 (0x81 | 0x40) if compression were active
        }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun createBuffer(bytes: ByteArray): com.ditchoom.buffer.ReadBuffer {
        val buffer = BufferFactory.Default.allocate(bytes.size)
        buffer.writeBytes(bytes)
        buffer.resetForRead()
        return buffer
    }

    private suspend fun parseFrame(buffer: com.ditchoom.buffer.ReadBuffer): ParsedFrame? {
        val processor = StreamProcessor.builder(pool).buildSuspending()
        processor.append(buffer)
        return FrameReader(processor).readFrame()
    }
}
