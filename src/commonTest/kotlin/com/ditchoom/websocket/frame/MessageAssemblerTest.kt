package com.ditchoom.websocket.frame

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.websocket.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [MessageAssembler].
 *
 * Tests verify correct assembly of fragmented messages per RFC 6455 Section 5.4.
 *
 * References:
 * - RFC 6455 Section 5.4: Fragmentation
 *   https://datatracker.ietf.org/doc/html/rfc6455#section-5.4
 * - RFC 6455 Section 5.5: Control Frames
 *   https://datatracker.ietf.org/doc/html/rfc6455#section-5.5
 */
class MessageAssemblerTest {

    // ========================================================================
    // RFC 6455 Section 5.4 - Single Frame Messages
    // ========================================================================

    @Test
    fun `single text frame returns complete message`() {
        val assembler = MessageAssembler()
        val frame = createFrame(Opcode.Text, "Hello", fin = true)

        val result = assembler.addFrame(frame)

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals(Opcode.Text, result.message.opcode)
        assertEquals("Hello", result.message.payload.readString(5, Charset.UTF8))
        assertFalse(result.message.compressed)
    }

    @Test
    fun `single binary frame returns complete message`() {
        val assembler = MessageAssembler()
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val frame = createFrame(Opcode.Binary, data, fin = true)

        val result = assembler.addFrame(frame)

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals(Opcode.Binary, result.message.opcode)
        assertEquals(3, result.message.payload.remaining())
    }

    @Test
    fun `single frame with RSV1 indicates compression`() {
        val assembler = MessageAssembler()
        val frame = createFrame(Opcode.Text, "Hello", fin = true, rsv1 = true)

        val result = assembler.addFrame(frame)

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertTrue(result.message.compressed)
    }

    @Test
    fun `empty text frame returns complete message`() {
        val assembler = MessageAssembler()
        val frame = createFrame(Opcode.Text, "", fin = true)

        val result = assembler.addFrame(frame)

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals(0, result.message.payload.remaining())
    }

    // ========================================================================
    // RFC 6455 Section 5.4 - Fragmented Messages
    // ========================================================================

    @Test
    fun `two fragment message assembles correctly`() {
        val assembler = MessageAssembler()

        // First fragment
        val frame1 = createFrame(Opcode.Text, "Hello ", fin = false)
        val result1 = assembler.addFrame(frame1)
        assertIs<AssemblyResult.NeedMoreFrames>(result1)

        // Final fragment
        val frame2 = createFrame(Opcode.Continuation, "World", fin = true)
        val result2 = assembler.addFrame(frame2)

        assertIs<AssemblyResult.CompleteMessage>(result2)
        assertEquals(Opcode.Text, result2.message.opcode)
        assertEquals("Hello World", result2.message.payload.readString(11, Charset.UTF8))
    }

    @Test
    fun `three fragment message assembles correctly`() {
        val assembler = MessageAssembler()

        val result1 = assembler.addFrame(createFrame(Opcode.Text, "One", fin = false))
        assertIs<AssemblyResult.NeedMoreFrames>(result1)

        val result2 = assembler.addFrame(createFrame(Opcode.Continuation, "Two", fin = false))
        assertIs<AssemblyResult.NeedMoreFrames>(result2)

        val result3 = assembler.addFrame(createFrame(Opcode.Continuation, "Three", fin = true))
        assertIs<AssemblyResult.CompleteMessage>(result3)
        assertEquals("OneTwoThree", result3.message.payload.readString(11, Charset.UTF8))
    }

    @Test
    fun `fragmented binary message assembles correctly`() {
        val assembler = MessageAssembler()

        val result1 = assembler.addFrame(createFrame(Opcode.Binary, byteArrayOf(1, 2), fin = false))
        assertIs<AssemblyResult.NeedMoreFrames>(result1)

        val result2 = assembler.addFrame(createFrame(Opcode.Continuation, byteArrayOf(3, 4), fin = true))
        assertIs<AssemblyResult.CompleteMessage>(result2)

        assertEquals(Opcode.Binary, result2.message.opcode)
        assertEquals(4, result2.message.payload.remaining())
    }

    @Test
    fun `RSV1 from first fragment is preserved`() {
        val assembler = MessageAssembler()

        assembler.addFrame(createFrame(Opcode.Text, "A", fin = false, rsv1 = true))
        val result = assembler.addFrame(createFrame(Opcode.Continuation, "B", fin = true, rsv1 = false))

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertTrue(result.message.compressed, "Compression flag should come from first frame")
    }

    @Test
    fun `isFragmentInProgress tracks state correctly`() {
        val assembler = MessageAssembler()

        assertFalse(assembler.isFragmentInProgress)

        assembler.addFrame(createFrame(Opcode.Text, "A", fin = false))
        assertTrue(assembler.isFragmentInProgress)

        assembler.addFrame(createFrame(Opcode.Continuation, "B", fin = true))
        assertFalse(assembler.isFragmentInProgress)
    }

    // ========================================================================
    // RFC 6455 Section 5.5 - Control Frames
    // ========================================================================

    @Test
    fun `control frame returns immediately`() {
        val assembler = MessageAssembler()
        val frame = createControlFrame(Opcode.Ping, "ping")

        val result = assembler.addFrame(frame)

        assertIs<AssemblyResult.ControlFrame>(result)
        assertEquals(Opcode.Ping, result.frame.opcode)
    }

    @Test
    fun `control frame during fragmentation returns control frame`() {
        val assembler = MessageAssembler()

        // Start fragmented message
        assembler.addFrame(createFrame(Opcode.Text, "Hello", fin = false))

        // Control frame interspersed
        val pingResult = assembler.addFrame(createControlFrame(Opcode.Ping, "ping"))
        assertIs<AssemblyResult.ControlFrame>(pingResult)

        // Continue fragmentation
        val finalResult = assembler.addFrame(createFrame(Opcode.Continuation, " World", fin = true))
        assertIs<AssemblyResult.CompleteMessage>(finalResult)
        assertEquals("Hello World", finalResult.message.payload.readString(11, Charset.UTF8))
    }

    @Test
    fun `pong frame returns immediately`() {
        val assembler = MessageAssembler()
        val frame = createControlFrame(Opcode.Pong, "pong")

        val result = assembler.addFrame(frame)

        assertIs<AssemblyResult.ControlFrame>(result)
        assertEquals(Opcode.Pong, result.frame.opcode)
    }

    @Test
    fun `close frame returns immediately`() {
        val assembler = MessageAssembler()
        val frame = createControlFrame(Opcode.Close, byteArrayOf(0x03, 0xE8.toByte())) // 1000

        val result = assembler.addFrame(frame)

        assertIs<AssemblyResult.ControlFrame>(result)
        assertEquals(Opcode.Close, result.frame.opcode)
    }

    @Test
    fun `control frame without FIN returns error`() {
        val assembler = MessageAssembler()
        val frame = createFrame(Opcode.Ping, "ping", fin = false)

        val result = assembler.addFrame(frame)

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun `control frame over 125 bytes returns error`() {
        val assembler = MessageAssembler()
        val frame = createControlFrame(Opcode.Ping, ByteArray(126))

        val result = assembler.addFrame(frame)

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    // ========================================================================
    // Error Cases
    // ========================================================================

    @Test
    fun `continuation without first frame returns error`() {
        val assembler = MessageAssembler()
        val frame = createFrame(Opcode.Continuation, "data", fin = true)

        val result = assembler.addFrame(frame)

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun `new message during fragmentation returns error`() {
        val assembler = MessageAssembler()

        // Start fragmented message
        assembler.addFrame(createFrame(Opcode.Text, "Hello", fin = false))

        // Try to start new message
        val result = assembler.addFrame(createFrame(Opcode.Text, "New", fin = true))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun `binary frame during text fragmentation returns error`() {
        val assembler = MessageAssembler()

        assembler.addFrame(createFrame(Opcode.Text, "Hello", fin = false))
        val result = assembler.addFrame(createFrame(Opcode.Binary, byteArrayOf(1, 2), fin = true))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    // ========================================================================
    // Reset
    // ========================================================================

    @Test
    fun `reset clears fragment state`() {
        val assembler = MessageAssembler()

        assembler.addFrame(createFrame(Opcode.Text, "Hello", fin = false))
        assertTrue(assembler.isFragmentInProgress)

        assembler.reset()

        assertFalse(assembler.isFragmentInProgress)
    }

    @Test
    fun `can start new message after reset`() {
        val assembler = MessageAssembler()

        assembler.addFrame(createFrame(Opcode.Text, "Hello", fin = false))
        assembler.reset()

        val result = assembler.addFrame(createFrame(Opcode.Text, "World", fin = true))

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals("World", result.message.payload.readString(5, Charset.UTF8))
    }

    // ========================================================================
    // Multiple Messages
    // ========================================================================

    @Test
    fun `can assemble multiple messages in sequence`() {
        val assembler = MessageAssembler()

        // First message
        val result1 = assembler.addFrame(createFrame(Opcode.Text, "First", fin = true))
        assertIs<AssemblyResult.CompleteMessage>(result1)
        assertEquals("First", result1.message.payload.readString(5, Charset.UTF8))

        // Second message
        val result2 = assembler.addFrame(createFrame(Opcode.Text, "Second", fin = true))
        assertIs<AssemblyResult.CompleteMessage>(result2)
        assertEquals("Second", result2.message.payload.readString(6, Charset.UTF8))
    }

    @Test
    fun `can assemble fragmented then single message`() {
        val assembler = MessageAssembler()

        // Fragmented message
        assembler.addFrame(createFrame(Opcode.Text, "A", fin = false))
        val result1 = assembler.addFrame(createFrame(Opcode.Continuation, "B", fin = true))
        assertIs<AssemblyResult.CompleteMessage>(result1)

        // Single frame message
        val result2 = assembler.addFrame(createFrame(Opcode.Binary, byteArrayOf(1, 2, 3), fin = true))
        assertIs<AssemblyResult.CompleteMessage>(result2)
        assertEquals(Opcode.Binary, result2.message.opcode)
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun createFrame(
        opcode: Opcode,
        text: String,
        fin: Boolean,
        rsv1: Boolean = false,
    ): ParsedFrame {
        val bytes = text.encodeToByteArray()
        return createFrame(opcode, bytes, fin, rsv1)
    }

    private fun createFrame(
        opcode: Opcode,
        data: ByteArray,
        fin: Boolean,
        rsv1: Boolean = false,
    ): ParsedFrame {
        val buffer = if (data.isEmpty()) {
            ReadBuffer.EMPTY_BUFFER
        } else {
            PlatformBuffer.allocate(data.size).apply {
                writeBytes(data)
                resetForRead()
            }
        }
        return ParsedFrame(
            fin = fin,
            rsv1 = rsv1,
            rsv2 = false,
            rsv3 = false,
            opcode = opcode,
            masked = false,
            payloadLength = data.size,
            payload = buffer,
        )
    }

    private fun createControlFrame(opcode: Opcode, text: String): ParsedFrame {
        return createControlFrame(opcode, text.encodeToByteArray())
    }

    private fun createControlFrame(opcode: Opcode, data: ByteArray): ParsedFrame {
        val buffer = if (data.isEmpty()) {
            ReadBuffer.EMPTY_BUFFER
        } else {
            PlatformBuffer.allocate(data.size).apply {
                writeBytes(data)
                resetForRead()
            }
        }
        return ParsedFrame(
            fin = true,
            rsv1 = false,
            rsv2 = false,
            rsv3 = false,
            opcode = opcode,
            masked = false,
            payloadLength = data.size,
            payload = buffer,
        )
    }
}
