package com.ditchoom.websocket.frame

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.toReadBuffer
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
        val result = assembler.addFrame(textFrame("Hello"))

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals(Opcode.Text, result.message.opcode)
        assertEquals("Hello", result.message.payload.readString(5, Charset.UTF8))
        assertFalse(result.message.compressed)
    }

    @Test
    fun `single binary frame returns complete message`() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(binaryFrame(byteCount = 3))

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals(Opcode.Binary, result.message.opcode)
        assertEquals(3, result.message.payload.remaining())
    }

    @Test
    fun `single frame with RSV1 indicates compression`() {
        val assembler = MessageAssembler(compressionEnabled = true)
        val result = assembler.addFrame(textFrame("Hello", rsv1 = true))

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertTrue(result.message.compressed)
    }

    @Test
    fun `empty text frame returns complete message`() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(textFrame(""))

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals(0, result.message.payload.remaining())
    }

    // ========================================================================
    // RFC 6455 Section 5.4 - Fragmented Messages
    // ========================================================================

    @Test
    fun `two fragment message assembles correctly`() {
        val assembler = MessageAssembler()

        val result1 = assembler.addFrame(textFrame("Hello ", fin = false))
        assertIs<AssemblyResult.NeedMoreFrames>(result1)

        val result2 = assembler.addFrame(continuationFrame("World"))
        assertIs<AssemblyResult.CompleteMessage>(result2)
        assertEquals(Opcode.Text, result2.message.opcode)
        assertEquals("Hello World", result2.message.payload.readString(11, Charset.UTF8))
    }

    @Test
    fun `three fragment message assembles correctly`() {
        val assembler = MessageAssembler()

        val result1 = assembler.addFrame(textFrame("One", fin = false))
        assertIs<AssemblyResult.NeedMoreFrames>(result1)

        val result2 = assembler.addFrame(continuationFrame("Two", fin = false))
        assertIs<AssemblyResult.NeedMoreFrames>(result2)

        val result3 = assembler.addFrame(continuationFrame("Three"))
        assertIs<AssemblyResult.CompleteMessage>(result3)
        assertEquals("OneTwoThree", result3.message.payload.readString(11, Charset.UTF8))
    }

    @Test
    fun `fragmented binary message assembles correctly`() {
        val assembler = MessageAssembler()

        val result1 = assembler.addFrame(binaryFrame(byteCount = 2, fin = false))
        assertIs<AssemblyResult.NeedMoreFrames>(result1)

        val result2 = assembler.addFrame(continuationFrame("AB"))
        assertIs<AssemblyResult.CompleteMessage>(result2)

        assertEquals(Opcode.Binary, result2.message.opcode)
        assertEquals(4, result2.message.payload.remaining())
    }

    @Test
    fun `RSV1 from first fragment is preserved`() {
        val assembler = MessageAssembler(compressionEnabled = true)

        assembler.addFrame(textFrame("A", fin = false, rsv1 = true))
        val result = assembler.addFrame(continuationFrame("B"))

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertTrue(result.message.compressed, "Compression flag should come from first frame")
    }

    @Test
    fun `isFragmentInProgress tracks state correctly`() {
        val assembler = MessageAssembler()

        assertFalse(assembler.isFragmentInProgress)

        assembler.addFrame(textFrame("A", fin = false))
        assertTrue(assembler.isFragmentInProgress)

        assembler.addFrame(continuationFrame("B"))
        assertFalse(assembler.isFragmentInProgress)
    }

    // ========================================================================
    // RFC 6455 Section 5.5 - Control Frames
    // ========================================================================

    @Test
    fun `control frame returns immediately`() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(pingFrame("ping"))

        assertIs<AssemblyResult.ControlFrame>(result)
        assertEquals(Opcode.Ping, result.frame.opcode)
    }

    @Test
    fun `control frame during fragmentation returns control frame`() {
        val assembler = MessageAssembler()

        assembler.addFrame(textFrame("Hello", fin = false))

        val pingResult = assembler.addFrame(pingFrame("ping"))
        assertIs<AssemblyResult.ControlFrame>(pingResult)

        val finalResult = assembler.addFrame(continuationFrame(" World"))
        assertIs<AssemblyResult.CompleteMessage>(finalResult)
        assertEquals("Hello World", finalResult.message.payload.readString(11, Charset.UTF8))
    }

    @Test
    fun `pong frame returns immediately`() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(pongFrame("pong"))

        assertIs<AssemblyResult.ControlFrame>(result)
        assertEquals(Opcode.Pong, result.frame.opcode)
    }

    @Test
    fun `close frame returns immediately`() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(closeFrame(CloseCode.NORMAL))

        assertIs<AssemblyResult.ControlFrame>(result)
        assertEquals(Opcode.Close, result.frame.opcode)
    }

    @Test
    fun `control frame without FIN returns error`() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(pingFrame("ping", fin = false))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun `control frame over 125 bytes returns error`() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(oversizedPingFrame(byteCount = 126))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    // ========================================================================
    // Error Cases
    // ========================================================================

    @Test
    fun `continuation without first frame returns error`() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(continuationFrame("data"))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun `new message during fragmentation returns error`() {
        val assembler = MessageAssembler()

        assembler.addFrame(textFrame("Hello", fin = false))
        val result = assembler.addFrame(textFrame("New"))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun `binary frame during text fragmentation returns error`() {
        val assembler = MessageAssembler()

        assembler.addFrame(textFrame("Hello", fin = false))
        val result = assembler.addFrame(binaryFrame(2))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun `RSV2 bit set returns error`() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(textFrame("Hello", rsv2 = true))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun `RSV3 bit set returns error`() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(textFrame("Hello", rsv3 = true))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun `RSV1 without compression enabled returns error`() {
        val assembler = MessageAssembler(compressionEnabled = false)
        val result = assembler.addFrame(textFrame("Hello", rsv1 = true))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    // ========================================================================
    // Fragment Buffer Cleanup (Linux NativeBuffer leak regression tests)
    // ========================================================================

    @Test
    fun `multi-fragment assembly returns fragments for cleanup`() {
        val assembler = MessageAssembler()

        val frag1 = textFrame("Hello ", fin = false)
        val frag2 = continuationFrame("World")

        assembler.addFrame(frag1)
        val result = assembler.addFrame(frag2)

        assertIs<AssemblyResult.CompleteMessage>(result)
        // Multi-fragment: combineBuffers() copies data, originals need cleanup
        assertEquals(2, result.fragmentsToClose.size)
        assertTrue(result.fragmentsToClose[0] === frag1.payload, "Should be same buffer object")
        assertTrue(result.fragmentsToClose[1] === frag2.payload, "Should be same buffer object")
    }

    @Test
    fun `three-fragment assembly returns all fragments for cleanup`() {
        val assembler = MessageAssembler()

        val frag1 = textFrame("A", fin = false)
        val frag2 = continuationFrame("B", fin = false)
        val frag3 = continuationFrame("C")

        assembler.addFrame(frag1)
        assembler.addFrame(frag2)
        val result = assembler.addFrame(frag3)

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals(3, result.fragmentsToClose.size)
    }

    @Test
    fun `single frame message returns empty fragments to close`() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(textFrame("Hello"))

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertTrue(result.fragmentsToClose.isEmpty(), "Single frame should have no fragments to close")
    }

    @Test
    fun `assembled message payload is independent of fragment buffers`() {
        val assembler = MessageAssembler()

        assembler.addFrame(textFrame("Hello ", fin = false))
        val result = assembler.addFrame(continuationFrame("World"))

        assertIs<AssemblyResult.CompleteMessage>(result)
        // The combined payload should be a separate buffer from the fragments
        val payload = result.message.payload
        assertEquals("Hello World", payload.readString(11, Charset.UTF8))
        // Fragments are the originals, not the combined buffer
        for (frag in result.fragmentsToClose) {
            assertTrue(frag !== payload, "Fragment should not be the same object as combined payload")
        }
    }

    // ========================================================================
    // Reset
    // ========================================================================

    @Test
    fun `reset clears fragment state`() {
        val assembler = MessageAssembler()

        assembler.addFrame(textFrame("Hello", fin = false))
        assertTrue(assembler.isFragmentInProgress)

        assembler.reset()

        assertFalse(assembler.isFragmentInProgress)
    }

    @Test
    fun `can start new message after reset`() {
        val assembler = MessageAssembler()

        assembler.addFrame(textFrame("Hello", fin = false))
        assembler.reset()

        val result = assembler.addFrame(textFrame("World"))

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals("World", result.message.payload.readString(5, Charset.UTF8))
    }

    // ========================================================================
    // Multiple Messages
    // ========================================================================

    @Test
    fun `can assemble multiple messages in sequence`() {
        val assembler = MessageAssembler()

        val result1 = assembler.addFrame(textFrame("First"))
        assertIs<AssemblyResult.CompleteMessage>(result1)
        assertEquals("First", result1.message.payload.readString(5, Charset.UTF8))

        val result2 = assembler.addFrame(textFrame("Second"))
        assertIs<AssemblyResult.CompleteMessage>(result2)
        assertEquals("Second", result2.message.payload.readString(6, Charset.UTF8))
    }

    @Test
    fun `can assemble fragmented then single message`() {
        val assembler = MessageAssembler()

        assembler.addFrame(textFrame("A", fin = false))
        val result1 = assembler.addFrame(continuationFrame("B"))
        assertIs<AssemblyResult.CompleteMessage>(result1)

        val result2 = assembler.addFrame(binaryFrame(byteCount = 3))
        assertIs<AssemblyResult.CompleteMessage>(result2)
        assertEquals(Opcode.Binary, result2.message.opcode)
    }

    // ========================================================================
    // Helper Functions - Using Value Classes for Efficient Frame Creation
    // ========================================================================

    /**
     * Creates a text frame. Uses String.toReadBuffer() directly.
     */
    private fun textFrame(
        text: String,
        fin: Boolean = true,
        rsv1: Boolean = false,
        rsv2: Boolean = false,
        rsv3: Boolean = false,
    ): ParsedFrame.DataFrame.Text {
        val buffer = if (text.isEmpty()) ReadBuffer.EMPTY_BUFFER else text.toReadBuffer(Charset.UTF8)
        return ParsedFrame.DataFrame.Text(
            header1 = FrameHeaderByte1.pack(fin, rsv1, rsv2, rsv3, Opcode.Text),
            header2 = FrameHeaderByte2.forPayload(buffer.remaining()),
            payloadLength = buffer.remaining(),
            payload = buffer,
        )
    }

    /**
     * Creates a binary frame with specified byte count.
     */
    private fun binaryFrame(
        byteCount: Int,
        fin: Boolean = true,
        rsv1: Boolean = false,
    ): ParsedFrame.DataFrame.Binary {
        val buffer =
            if (byteCount == 0) {
                ReadBuffer.EMPTY_BUFFER
            } else {
                PlatformBuffer.allocate(byteCount).apply {
                    repeat(byteCount) { writeByte(0xFE.toByte()) }
                    resetForRead()
                }
            }
        return ParsedFrame.DataFrame.Binary(
            header1 = FrameHeaderByte1.pack(fin, rsv1, rsv2 = false, rsv3 = false, Opcode.Binary),
            header2 = FrameHeaderByte2.forPayload(byteCount),
            payloadLength = byteCount,
            payload = buffer,
        )
    }

    /**
     * Creates a continuation frame from text.
     */
    private fun continuationFrame(
        text: String,
        fin: Boolean = true,
    ): ParsedFrame.DataFrame.Continuation {
        val buffer = if (text.isEmpty()) ReadBuffer.EMPTY_BUFFER else text.toReadBuffer(Charset.UTF8)
        return ParsedFrame.DataFrame.Continuation(
            header1 = FrameHeaderByte1.pack(fin, rsv1 = false, rsv2 = false, rsv3 = false, Opcode.Continuation),
            header2 = FrameHeaderByte2.forPayload(buffer.remaining()),
            payloadLength = buffer.remaining(),
            payload = buffer,
        )
    }

    /**
     * Creates a ping frame.
     */
    private fun pingFrame(
        text: String = "",
        fin: Boolean = true,
    ): ParsedFrame.ControlFrame.Ping {
        val buffer = if (text.isEmpty()) ReadBuffer.EMPTY_BUFFER else text.toReadBuffer(Charset.UTF8)
        return ParsedFrame.ControlFrame.Ping(
            header1 = FrameHeaderByte1.pack(fin, rsv1 = false, rsv2 = false, rsv3 = false, Opcode.Ping),
            header2 = FrameHeaderByte2.forPayload(buffer.remaining()),
            payloadLength = buffer.remaining(),
            payload = buffer,
        )
    }

    /**
     * Creates a pong frame.
     */
    private fun pongFrame(text: String = ""): ParsedFrame.ControlFrame.Pong {
        val buffer = if (text.isEmpty()) ReadBuffer.EMPTY_BUFFER else text.toReadBuffer(Charset.UTF8)
        return ParsedFrame.ControlFrame.Pong(
            header1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, Opcode.Pong),
            header2 = FrameHeaderByte2.forPayload(buffer.remaining()),
            payloadLength = buffer.remaining(),
            payload = buffer,
        )
    }

    /**
     * Creates a close frame using CloseCode value class.
     */
    private fun closeFrame(code: CloseCode = CloseCode.NORMAL): ParsedFrame.ControlFrame.Close {
        val buffer =
            PlatformBuffer.allocate(2).apply {
                writeUShort(code.code)
                resetForRead()
            }
        return ParsedFrame.ControlFrame.Close(
            header1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, Opcode.Close),
            header2 = FrameHeaderByte2.forPayload(2),
            payloadLength = 2,
            payload = buffer,
            closeCode = code,
            closeReason = "",
        )
    }

    /**
     * Creates an oversized control frame for error testing.
     */
    private fun oversizedPingFrame(byteCount: Int): ParsedFrame.ControlFrame.Ping {
        val buffer =
            PlatformBuffer.allocate(byteCount).apply {
                repeat(byteCount) { writeByte(0x00) }
                resetForRead()
            }
        return ParsedFrame.ControlFrame.Ping(
            header1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, Opcode.Ping),
            header2 = FrameHeaderByte2.forPayload(byteCount),
            payloadLength = byteCount,
            payload = buffer,
        )
    }
}
