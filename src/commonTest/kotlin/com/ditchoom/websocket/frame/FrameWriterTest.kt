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
    private val pool = BufferPool(allocationZone = AllocationZone.Heap)
    // Use pool-allocated FrameWriter so parseFrame's StreamProcessor doesn't
    // prematurely free NativeBuffers passed to append(). In production, the
    // read loop only appends pool buffers to the StreamProcessor.
    private fun createWriter(clientMode: Boolean = false, compressionEnabled: Boolean = false) =
        FrameWriter(clientMode = clientMode, compressionEnabled = compressionEnabled, pool = pool)

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

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertTrue(frame.masked, "Client frames must be masked")
        }

    @Test
    fun `RFC 6455 Section 5-3 - server frames are not masked`() =
        runTest {
            // "A server MUST NOT mask any frames that it sends to the client"
            val writer = createWriter(clientMode = false)

            val frameBuffer = writer.writeTextFrame("Hello")

            val frame = parseFrame(frameBuffer)
            assertNotNull(frame)
            assertFalse(frame.masked, "Server frames must not be masked")
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
    // Helper Functions
    // ========================================================================

    private fun createBuffer(bytes: ByteArray): com.ditchoom.buffer.ReadBuffer {
        val buffer = PlatformBuffer.allocate(bytes.size)
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
