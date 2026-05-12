package com.ditchoom.websocket.frame

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
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
    fun singleTextFrameReturnsCompleteMessage() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(textFrame("Hello"))

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals(Opcode.Text, result.message.opcode)
        assertEquals("Hello", result.message.payload.readString(5, Charset.UTF8))
        assertFalse(result.message.compressed)
    }

    @Test
    fun singleBinaryFrameReturnsCompleteMessage() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(binaryFrame(byteCount = 3))

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals(Opcode.Binary, result.message.opcode)
        assertEquals(3, result.message.payload.remaining())
    }

    @Test
    fun singleFrameWithRSV1IndicatesCompression() {
        val assembler = MessageAssembler(compressionEnabled = true)
        val result = assembler.addFrame(textFrame("Hello", rsv1 = true))

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertTrue(result.message.compressed)
    }

    @Test
    fun emptyTextFrameReturnsCompleteMessage() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(textFrame(""))

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals(0, result.message.payload.remaining())
    }

    // ========================================================================
    // RFC 6455 Section 5.4 - Fragmented Messages
    // ========================================================================

    @Test
    fun twoFragmentMessageAssemblesCorrectly() {
        val assembler = MessageAssembler()

        val result1 = assembler.addFrame(textFrame("Hello ", fin = false))
        assertIs<AssemblyResult.NeedMoreFrames>(result1)

        val result2 = assembler.addFrame(continuationFrame("World"))
        assertIs<AssemblyResult.CompleteMessage>(result2)
        assertEquals(Opcode.Text, result2.message.opcode)
        assertEquals("Hello World", result2.message.payload.readString(11, Charset.UTF8))
    }

    @Test
    fun threeFragmentMessageAssemblesCorrectly() {
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
    fun fragmentedBinaryMessageAssemblesCorrectly() {
        val assembler = MessageAssembler()

        val result1 = assembler.addFrame(binaryFrame(byteCount = 2, fin = false))
        assertIs<AssemblyResult.NeedMoreFrames>(result1)

        val result2 = assembler.addFrame(continuationFrame("AB"))
        assertIs<AssemblyResult.CompleteMessage>(result2)

        assertEquals(Opcode.Binary, result2.message.opcode)
        assertEquals(4, result2.message.payload.remaining())
    }

    @Test
    fun rsv1FromFirstFragmentIsPreserved() {
        val assembler = MessageAssembler(compressionEnabled = true)

        assembler.addFrame(textFrame("A", fin = false, rsv1 = true))
        val result = assembler.addFrame(continuationFrame("B"))

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertTrue(result.message.compressed, "Compression flag should come from first frame")
    }

    @Test
    fun isFragmentInProgressTracksStateCorrectly() {
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
    fun controlFrameReturnsImmediately() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(pingFrame("ping"))

        assertIs<AssemblyResult.ControlFrame>(result)
        assertEquals(Opcode.Ping, result.frame.byte1.opcode)
    }

    @Test
    fun controlFrameDuringFragmentationReturnsControlFrame() {
        val assembler = MessageAssembler()

        assembler.addFrame(textFrame("Hello", fin = false))

        val pingResult = assembler.addFrame(pingFrame("ping"))
        assertIs<AssemblyResult.ControlFrame>(pingResult)

        val finalResult = assembler.addFrame(continuationFrame(" World"))
        assertIs<AssemblyResult.CompleteMessage>(finalResult)
        assertEquals("Hello World", finalResult.message.payload.readString(11, Charset.UTF8))
    }

    @Test
    fun pongFrameReturnsImmediately() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(pongFrame("pong"))

        assertIs<AssemblyResult.ControlFrame>(result)
        assertEquals(Opcode.Pong, result.frame.byte1.opcode)
    }

    @Test
    fun closeFrameReturnsImmediately() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(closeFrame(CloseCode.NORMAL))

        assertIs<AssemblyResult.ControlFrame>(result)
        assertEquals(Opcode.Close, result.frame.byte1.opcode)
    }

    @Test
    fun controlFrameWithoutFINReturnsError() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(pingFrame("ping", fin = false))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun controlFrameOver125BytesReturnsError() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(oversizedPingFrame(byteCount = 126))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    // ========================================================================
    // Error Cases
    // ========================================================================

    @Test
    fun continuationWithoutFirstFrameReturnsError() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(continuationFrame("data"))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun newMessageDuringFragmentationReturnsError() {
        val assembler = MessageAssembler()

        assembler.addFrame(textFrame("Hello", fin = false))
        val result = assembler.addFrame(textFrame("New"))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun binaryFrameDuringTextFragmentationReturnsError() {
        val assembler = MessageAssembler()

        assembler.addFrame(textFrame("Hello", fin = false))
        val result = assembler.addFrame(binaryFrame(2))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun rsv2BitSetReturnsError() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(textFrame("Hello", rsv2 = true))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun rsv3BitSetReturnsError() {
        val assembler = MessageAssembler()
        val result = assembler.addFrame(textFrame("Hello", rsv3 = true))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    @Test
    fun rsv1WithoutCompressionEnabledReturnsError() {
        val assembler = MessageAssembler(compressionEnabled = false)
        val result = assembler.addFrame(textFrame("Hello", rsv1 = true))

        assertIs<AssemblyResult.Error>(result)
        assertEquals(CloseCode.PROTOCOL_ERROR, result.code)
    }

    // ========================================================================
    // Assembly ownership contract (post buffer-codec lockdown v1)
    //
    // The assembler frees per-frame source buffers internally; CompleteMessage
    // surfaces only the assembled payload that the caller owns. These tests
    // pin the new ownership invariants.
    // ========================================================================

    @Test
    fun singleFrameMessagePayloadAliasesFrameBuffer() {
        // Single-frame path: assembler returns the wire-buffer view directly as
        // message.payload. Caller owns and frees it after running the user codec.
        val assembler = MessageAssembler()
        val frame = textFrame("Hello")
        val result = assembler.addFrame(frame)

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertTrue(
            result.message.payload === frame.payload.buffer,
            "Single-frame payload should alias the original wire buffer",
        )
    }

    @Test
    fun multiFragmentMessagePayloadIsCombinedBuffer() {
        // Multi-fragment path: combineBuffers() allocates a fresh buffer and
        // frees the source fragments before returning. The assembled payload
        // is independent of any of the source wire buffers.
        val assembler = MessageAssembler()
        val frag1 = textFrame("Hello ", fin = false)
        val frag2 = continuationFrame("World")

        assembler.addFrame(frag1)
        val result = assembler.addFrame(frag2)

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals("Hello World", result.message.payload.readString(11, Charset.UTF8))
        val combined = result.message.payload
        assertTrue(combined !== frag1.payload.buffer)
        assertTrue(combined !== frag2.payload.buffer)
    }

    @Test
    fun threeFragmentMessageProducesSingleAssembledBuffer() {
        val assembler = MessageAssembler()
        val frag1 = textFrame("A", fin = false)
        val frag2 = continuationFrame("B", fin = false)
        val frag3 = continuationFrame("C")

        assembler.addFrame(frag1)
        assembler.addFrame(frag2)
        val result = assembler.addFrame(frag3)

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals("ABC", result.message.payload.readString(3, Charset.UTF8))
    }

    // ========================================================================
    // Reset
    // ========================================================================

    @Test
    fun resetClearsFragmentState() {
        val assembler = MessageAssembler()

        assembler.addFrame(textFrame("Hello", fin = false))
        assertTrue(assembler.isFragmentInProgress)

        assembler.reset()

        assertFalse(assembler.isFragmentInProgress)
    }

    @Test
    fun canStartNewMessageAfterReset() {
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
    fun canAssembleMultipleMessagesInSequence() {
        val assembler = MessageAssembler()

        val result1 = assembler.addFrame(textFrame("First"))
        assertIs<AssemblyResult.CompleteMessage>(result1)
        assertEquals("First", result1.message.payload.readString(5, Charset.UTF8))

        val result2 = assembler.addFrame(textFrame("Second"))
        assertIs<AssemblyResult.CompleteMessage>(result2)
        assertEquals("Second", result2.message.payload.readString(6, Charset.UTF8))
    }

    @Test
    fun canAssembleFragmentedThenSingleMessage() {
        val assembler = MessageAssembler()

        assembler.addFrame(textFrame("A", fin = false))
        val result1 = assembler.addFrame(continuationFrame("B"))
        assertIs<AssemblyResult.CompleteMessage>(result1)

        val result2 = assembler.addFrame(binaryFrame(byteCount = 3))
        assertIs<AssemblyResult.CompleteMessage>(result2)
        assertEquals(Opcode.Binary, result2.message.opcode)
    }

    // ========================================================================
    // Non-Zero Position Fragment Regression Tests
    //
    // Verifies that MessageAssembler correctly combines fragments where
    // the payload buffers have position > 0 (sliced/transferred chunks).
    // ========================================================================

    @Test
    fun combineFragmentsWithNonZeroPositionPayloads() {
        val assembler = MessageAssembler()

        // Create fragment payloads with position > 0 (simulates sliced buffers)
        val buf1 = BufferFactory.Default.allocate(10)
        buf1.writeBytes("xxHello ".encodeToByteArray()) // 2 prefix + "Hello "
        buf1.resetForRead()
        buf1.readByte() // skip prefix
        buf1.readByte() // skip prefix — position=2, remaining=8 ("Hello XX")
        // Trim to just "Hello "
        buf1.setLimit(buf1.position() + 6)

        val buf2 = BufferFactory.Default.allocate(10)
        buf2.writeBytes("yyyWorld".encodeToByteArray()) // 3 prefix + "World"
        buf2.resetForRead()
        buf2.readByte() // skip
        buf2.readByte() // skip
        buf2.readByte() // skip — position=3, remaining=5 ("World")
        // Trim to just "World"
        buf2.setLimit(buf2.position() + 5)

        val frag1 =
            header(Opcode.Text, buf1.remaining().toLong(), fin = false)
                .toTextFrame(BufferPayload(buf1))
        val frag2 =
            header(Opcode.Continuation, buf2.remaining().toLong())
                .toContinuationFrame(BufferPayload(buf2))

        assembler.addFrame(frag1)
        val result = assembler.addFrame(frag2)

        assertIs<AssemblyResult.CompleteMessage>(result)
        assertEquals(Opcode.Text, result.message.opcode)
        assertEquals("Hello World", result.message.payload.readString(11, Charset.UTF8))
    }

    // ========================================================================
    // Helper Functions - Using Value Classes for Efficient Frame Creation
    // ========================================================================

    private fun header(
        opcode: Opcode,
        payloadSize: Long,
        fin: Boolean = true,
        rsv1: Boolean = false,
        rsv2: Boolean = false,
        rsv3: Boolean = false,
    ): WsFrameHeader =
        WsFrameHeader.build(
            byte1 = FrameHeaderByte1.pack(fin, rsv1, rsv2, rsv3, opcode),
            payloadSize = payloadSize,
            masked = false,
        )

    /**
     * Creates a text frame. Uses String.toReadBuffer() directly.
     */
    private fun textFrame(
        text: String,
        fin: Boolean = true,
        rsv1: Boolean = false,
        rsv2: Boolean = false,
        rsv3: Boolean = false,
    ): WsFrame.Text<BufferPayload> {
        val buffer = if (text.isEmpty()) ReadBuffer.EMPTY_BUFFER else text.toReadBuffer(Charset.UTF8)
        return header(Opcode.Text, buffer.remaining().toLong(), fin, rsv1, rsv2, rsv3)
            .toTextFrame(BufferPayload(buffer))
    }

    /**
     * Creates a binary frame with specified byte count.
     */
    private fun binaryFrame(
        byteCount: Int,
        fin: Boolean = true,
        rsv1: Boolean = false,
    ): WsFrame.Binary<BufferPayload> {
        val buffer =
            if (byteCount == 0) {
                ReadBuffer.EMPTY_BUFFER
            } else {
                BufferFactory.Default.allocate(byteCount).apply {
                    repeat(byteCount) { writeByte(0xFE.toByte()) }
                    resetForRead()
                }
            }
        return header(Opcode.Binary, byteCount.toLong(), fin, rsv1)
            .toBinaryFrame(BufferPayload(buffer))
    }

    /**
     * Creates a continuation frame from text.
     */
    private fun continuationFrame(
        text: String,
        fin: Boolean = true,
    ): WsFrame.Continuation<BufferPayload> {
        val buffer = if (text.isEmpty()) ReadBuffer.EMPTY_BUFFER else text.toReadBuffer(Charset.UTF8)
        return header(Opcode.Continuation, buffer.remaining().toLong(), fin)
            .toContinuationFrame(BufferPayload(buffer))
    }

    /**
     * Creates a ping frame.
     */
    private fun pingFrame(
        text: String = "",
        fin: Boolean = true,
    ): WsFrame.Ping<BufferPayload> {
        val buffer = if (text.isEmpty()) ReadBuffer.EMPTY_BUFFER else text.toReadBuffer(Charset.UTF8)
        return header(Opcode.Ping, buffer.remaining().toLong(), fin)
            .toPingFrame(BufferPayload(buffer))
    }

    /**
     * Creates a pong frame.
     */
    private fun pongFrame(text: String = ""): WsFrame.Pong<BufferPayload> {
        val buffer = if (text.isEmpty()) ReadBuffer.EMPTY_BUFFER else text.toReadBuffer(Charset.UTF8)
        return header(Opcode.Pong, buffer.remaining().toLong())
            .toPongFrame(BufferPayload(buffer))
    }

    /**
     * Creates a close frame using CloseCode value class.
     */
    private fun closeFrame(code: CloseCode = CloseCode.NORMAL): WsFrame.Close =
        header(Opcode.Close, payloadSize = 2)
            .toCloseFrame(WsCloseBody(code, ""))

    /**
     * Creates an oversized control frame for error testing.
     */
    private fun oversizedPingFrame(byteCount: Int): WsFrame.Ping<BufferPayload> {
        val buffer =
            BufferFactory.Default.allocate(byteCount).apply {
                repeat(byteCount) { writeByte(0x00) }
                resetForRead()
            }
        return header(Opcode.Ping, byteCount.toLong())
            .toPingFrame(BufferPayload(buffer))
    }
}
