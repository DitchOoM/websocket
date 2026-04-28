package com.ditchoom.websocket.frame

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.websocket.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Byte-level wire format tests for [WsFrameCodec] encode and decode.
 *
 * References:
 * - RFC 6455 Section 5.2: Base Framing Protocol
 * - RFC 6455 Section 5.7: Examples
 * - RFC 6455 Section 5.5: Control Frames
 */
class WsFrameWireTest {
    // ========================================================================
    // RFC 6455 Section 5.7 — Examples (decode)
    // ========================================================================

    @Test
    fun decodeRFC57Example1UnmaskedTextHi() {
        // 0x81 0x05 0x48 0x65 0x6C 0x6C 0x6F ("Hello")
        val buffer = buf(0x81, 0x05, 0x48, 0x65, 0x6C, 0x6C, 0x6F)
        val frame = decodeTestFrame(buffer)

        assertTrue(frame is WsFrame.Text<*>)
        assertTrue(frame.header.byte1.fin)
        assertEquals(Opcode.Text, frame.header.byte1.opcode)
        assertEquals(5, frame.header.payloadLength.toInt())
        assertEquals("Hello", (frame as WsFrame.Text<*>).payload.let { (it as TestPayload).text })
    }

    @Test
    fun decodeUnmaskedEmptyTextFrame() {
        val buffer = buf(0x81, 0x00)
        val frame = decodeTestFrame(buffer)

        assertTrue(frame is WsFrame.Text<*>)
        assertEquals(0, frame.header.payloadLength.toInt())
        assertEquals("", (frame as WsFrame.Text<*>).payload.let { (it as TestPayload).text })
    }

    @Test
    fun decodeUnmaskedBinaryFrame() {
        val buffer = buf(0x82, 0x03, 0x01, 0x02, 0x03)
        val frame = decodeSkipPayload(buffer)

        assertTrue(frame is WsFrame.Binary<*>)
        assertEquals(3, frame.header.payloadLength.toInt())
    }

    @Test
    fun decodeFIN0TextFragment() {
        val buffer = buf(0x01, 0x02, 0x48, 0x69) // FIN=0, Text, "Hi"
        val frame = decodeTestFrame(buffer)

        assertTrue(frame is WsFrame.Text<*>)
        assertEquals(false, frame.header.byte1.fin)
    }

    @Test
    fun decodeContinuationFrame() {
        val buffer = buf(0x80, 0x02, 0x48, 0x69) // FIN=1, Continuation, "Hi"
        val frame = decodeTestFrame(buffer)

        assertTrue(frame is WsFrame.Continuation<*>)
        assertTrue(frame.header.byte1.fin)
    }

    // ========================================================================
    // Extended payload lengths (decode)
    // ========================================================================

    @Test
    fun decode16BitExtendedLengthBoundary126() {
        val payload = ByteArray(126) { 0x41 }
        val header = byteArrayOf(0x82.toByte(), 0x7E, 0x00, 0x7E)
        val buffer = buf(header + payload)
        val frame = decodeSkipPayload(buffer)

        assertEquals(126, frame.header.payloadLength.toInt())
    }

    @Test
    fun decode16BitExtendedLength300() {
        val payload = ByteArray(300) { 0x42 }
        val header = byteArrayOf(0x82.toByte(), 0x7E, 0x01, 0x2C)
        val buffer = buf(header + payload)
        val frame = decodeSkipPayload(buffer)

        assertEquals(300, frame.header.payloadLength.toInt())
    }

    @Test
    fun decode64BitExtendedLength70000() {
        val payload = ByteArray(70000) { 0x43 }
        val header =
            byteArrayOf(
                0x82.toByte(),
                0x7F,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x01,
                0x11,
                0x70,
            )
        val buffer = buf(header + payload)
        val frame = decodeSkipPayload(buffer)

        assertEquals(70000, frame.header.payloadLength.toInt())
    }

    // ========================================================================
    // RSV bits (decode)
    // ========================================================================

    @Test
    fun decodeRSV1SetForCompression() {
        val buffer = buf(0xC1, 0x00) // FIN=1, RSV1=1, Text
        val frame = decodeTestFrame(buffer)

        assertTrue(frame.header.byte1.rsv1)
        assertEquals(false, frame.header.byte1.rsv2)
        assertEquals(false, frame.header.byte1.rsv3)
    }

    @Test
    fun decodeAllRSVBitsSet() {
        val buffer = buf(0xF1, 0x00) // FIN=1, RSV1=1, RSV2=1, RSV3=1, Text
        val frame = decodeTestFrame(buffer)

        assertTrue(frame.header.byte1.rsv1)
        assertTrue(frame.header.byte1.rsv2)
        assertTrue(frame.header.byte1.rsv3)
    }

    // ========================================================================
    // Close frames (decode)
    // ========================================================================

    @Test
    fun decodeCloseFrameWithStatus1000() {
        val buffer = buf(0x88, 0x02, 0x03, 0xE8) // Close, len=2, code=1000
        val frame = decodeTestFrame(buffer)

        assertTrue(frame is WsFrame.Close)
        val close = frame as WsFrame.Close
        assertNotNull(close.body)
        assertEquals(CloseCode.NORMAL, close.body!!.statusCode)
        assertEquals("", close.body!!.reason)
    }

    @Test
    fun decodeCloseFrameWithStatusAndReason() {
        val reason = "bye"
        val reasonBytes = reason.encodeToByteArray()
        val buffer = buf(byteArrayOf(0x88.toByte(), (2 + reasonBytes.size).toByte(), 0x03, 0xE8.toByte()) + reasonBytes)
        val frame = decodeTestFrame(buffer)

        assertTrue(frame is WsFrame.Close)
        val close = frame as WsFrame.Close
        assertEquals(1000u.toUShort(), close.body!!.statusCode.code)
        assertEquals("bye", close.body!!.reason)
    }

    @Test
    fun decodeCloseFrameWithEmptyPayload() {
        val buffer = buf(0x88, 0x00) // Close, len=0
        val frame = decodeTestFrame(buffer)

        assertTrue(frame is WsFrame.Close)
        assertNull((frame as WsFrame.Close).body)
    }

    @Test
    fun decodeCloseFrameWith1BytePayloadHasNoBody() {
        val buffer = buf(0x88, 0x01, 0xFF) // Close, len=1 (invalid per RFC but codec handles it)
        val frame = decodeTestFrame(buffer)

        assertTrue(frame is WsFrame.Close)
        assertNull((frame as WsFrame.Close).body) // @WhenRemaining(2) skips body
    }

    // ========================================================================
    // Ping/Pong (decode)
    // ========================================================================

    @Test
    fun decodePingWithPayload() {
        val buffer = buf(0x89, 0x04, 0x70, 0x69, 0x6E, 0x67) // Ping, "ping"
        val frame = decodeTestFrame(buffer)

        assertTrue(frame is WsFrame.Ping<*>)
        assertEquals("ping", ((frame as WsFrame.Ping<*>).payload as TestPayload).text)
    }

    @Test
    fun decodePongWithPayload() {
        val buffer = buf(0x8A, 0x04, 0x70, 0x6F, 0x6E, 0x67) // Pong, "pong"
        val frame = decodeTestFrame(buffer)

        assertTrue(frame is WsFrame.Pong<*>)
        assertEquals("pong", ((frame as WsFrame.Pong<*>).payload as TestPayload).text)
    }

    @Test
    fun decodeEmptyPing() {
        val buffer = buf(0x89, 0x00)
        val frame = decodeTestFrame(buffer)

        assertTrue(frame is WsFrame.Ping<*>)
        assertEquals("", ((frame as WsFrame.Ping<*>).payload as TestPayload).text)
    }

    // ========================================================================
    // Reserved opcodes (decode)
    // ========================================================================

    @Test
    fun decodeReservedOpcode0x3Throws() {
        val buffer = buf(0x83, 0x00) // FIN=1, opcode=3
        assertFailsWith<IllegalArgumentException> {
            decodeTestFrame(buffer)
        }
    }

    @Test
    fun decodeReservedOpcode0xBThrows() {
        val buffer = buf(0x8B, 0x00) // FIN=1, opcode=0xB
        assertFailsWith<IllegalArgumentException> {
            decodeTestFrame(buffer)
        }
    }

    // ========================================================================
    // Encode — wire byte verification
    // ========================================================================

    @Test
    fun encodeUnmaskedTextHiWireBytes() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(true, false, false, false, Opcode.Text),
                payloadSize = 2,
                masked = false,
            )
        val buffer = allocEncode(header, 2)
        WsFrameHeaderCodec.encode(buffer, header, EncodeContext.Empty)
        buffer.writeBytes("Hi".encodeToByteArray())
        buffer.resetForRead()

        assertEquals(0x81.toByte(), buffer.get(0)) // FIN + Text
        assertEquals(0x02.toByte(), buffer.get(1)) // len=2
        assertEquals(0x48.toByte(), buffer.get(2)) // 'H'
        assertEquals(0x69.toByte(), buffer.get(3)) // 'i'
    }

    @Test
    fun encodeCloseFrame1000WireBytes() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(true, false, false, false, Opcode.Close),
                payloadSize = 2,
                masked = false,
            )
        val buffer = allocEncode(header, 2)
        WsFrameHeaderCodec.encode(buffer, header, EncodeContext.Empty)
        buffer.writeShort(1000.toShort())
        buffer.resetForRead()

        assertEquals(0x88.toByte(), buffer.get(0)) // FIN + Close
        assertEquals(0x02.toByte(), buffer.get(1)) // len=2
        assertEquals(0x03.toByte(), buffer.get(2)) // 1000 >> 8
        assertEquals(0xE8.toByte(), buffer.get(3)) // 1000 & 0xFF
    }

    @Test
    fun encodeRSV1SetProduces0xC1() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(true, rsv1 = true, false, false, Opcode.Text),
                payloadSize = 0,
                masked = false,
            )
        val buffer = allocEncode(header, 0)
        WsFrameHeaderCodec.encode(buffer, header, EncodeContext.Empty)
        buffer.resetForRead()

        assertEquals(0xC1.toByte(), buffer.get(0)) // FIN + RSV1 + Text
    }

    @Test
    fun encodeMaskedFrameHasMASKBitAnd4ByteKey() {
        val mask = WsMaskingKey(0xDEADBEEFu)
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(true, false, false, false, Opcode.Text),
                payloadSize = 2,
                masked = true,
                maskingKey = mask,
            )
        val buffer = allocEncode(header, 2)
        WsFrameHeaderCodec.encode(buffer, header, EncodeContext.Empty)
        buffer.writeBytes("Hi".encodeToByteArray())
        buffer.resetForRead()

        assertEquals(0x81.toByte(), buffer.get(0)) // FIN + Text
        assertEquals(0x82.toByte(), buffer.get(1)) // MASK + len=2
        // bytes 2-5: masking key 0xDEADBEEF
        assertEquals(0xDE.toByte(), buffer.get(2))
        assertEquals(0xAD.toByte(), buffer.get(3))
        assertEquals(0xBE.toByte(), buffer.get(4))
        assertEquals(0xEF.toByte(), buffer.get(5))
        // total: 2 header + 4 mask + 2 payload = 8
        assertEquals(8, buffer.remaining())
    }

    @Test
    fun encode16BitExtendedLengthWireFormat() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(true, false, false, false, Opcode.Binary),
                payloadSize = 256,
                masked = false,
            )
        val buffer = allocEncode(header, 0) // don't write payload, just header
        WsFrameHeaderCodec.encode(buffer, header, EncodeContext.Empty)
        buffer.resetForRead()

        assertEquals(0x82.toByte(), buffer.get(0)) // FIN + Binary
        assertEquals(0x7E.toByte(), buffer.get(1)) // 126 → 16-bit follows
        assertEquals(0x01.toByte(), buffer.get(2)) // 256 >> 8
        assertEquals(0x00.toByte(), buffer.get(3)) // 256 & 0xFF
    }

    @Test
    fun encode64BitExtendedLengthWireFormat() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(true, false, false, false, Opcode.Binary),
                payloadSize = 70000,
                masked = false,
            )
        val buffer = allocEncode(header, 0)
        WsFrameHeaderCodec.encode(buffer, header, EncodeContext.Empty)
        buffer.resetForRead()

        assertEquals(0x82.toByte(), buffer.get(0)) // FIN + Binary
        assertEquals(0x7F.toByte(), buffer.get(1)) // 127 → 64-bit follows
        // 70000 = 0x00_00_00_00_00_01_11_70
        assertEquals(0x00.toByte(), buffer.get(2))
        assertEquals(0x00.toByte(), buffer.get(3))
        assertEquals(0x00.toByte(), buffer.get(4))
        assertEquals(0x00.toByte(), buffer.get(5))
        assertEquals(0x00.toByte(), buffer.get(6))
        assertEquals(0x01.toByte(), buffer.get(7))
        assertEquals(0x11.toByte(), buffer.get(8))
        assertEquals(0x70.toByte(), buffer.get(9))
    }

    @Test
    fun encodeFIN0FragmentWireFormat() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(fin = false, false, false, false, Opcode.Text),
                payloadSize = 0,
                masked = false,
            )
        val buffer = allocEncode(header, 0)
        WsFrameHeaderCodec.encode(buffer, header, EncodeContext.Empty)
        buffer.resetForRead()

        assertEquals(0x01.toByte(), buffer.get(0)) // FIN=0, Text
    }

    // ========================================================================
    // Encode/decode round-trip
    // ========================================================================

    @Test
    fun roundTripUnmaskedTextFrame() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(true, false, false, false, Opcode.Text),
                payloadSize = 5,
                masked = false,
            )
        val buffer = allocEncode(header, 5)
        WsFrameHeaderCodec.encode(buffer, header, EncodeContext.Empty)
        buffer.writeBytes("Hello".encodeToByteArray())
        buffer.resetForRead()

        val frame = decodeTestFrame(buffer)
        assertTrue(frame is WsFrame.Text<*>)
        assertEquals("Hello", (frame as WsFrame.Text<*>).payload.let { (it as TestPayload).text })
    }

    @Test
    fun roundTripCloseFrameWithReason() {
        val reason = "goodbye"
        val reasonBytes = reason.encodeToByteArray()
        val payloadSize = 2 + reasonBytes.size
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(true, false, false, false, Opcode.Close),
                payloadSize = payloadSize.toLong(),
                masked = false,
            )
        val buffer = allocEncode(header, payloadSize)
        WsFrameHeaderCodec.encode(buffer, header, EncodeContext.Empty)
        buffer.writeShort(1001.toShort())
        buffer.writeBytes(reasonBytes)
        buffer.resetForRead()

        val frame = decodeTestFrame(buffer)
        assertTrue(frame is WsFrame.Close)
        val close = frame as WsFrame.Close
        assertEquals(1001u.toUShort(), close.body!!.statusCode.code)
        assertEquals("goodbye", close.body!!.reason)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun buf(vararg bytes: Int): ReadBuffer {
        val b = BufferFactory.Default.allocate(bytes.size, ByteOrder.BIG_ENDIAN)
        for (v in bytes) b.writeByte(v.toByte())
        b.resetForRead()
        return b
    }

    private fun buf(bytes: ByteArray): ReadBuffer {
        val b = BufferFactory.Default.allocate(bytes.size, ByteOrder.BIG_ENDIAN)
        b.writeBytes(bytes)
        b.resetForRead()
        return b
    }

    private fun allocEncode(
        header: WsFrameHeader,
        payloadSize: Int,
    ): PlatformBuffer = BufferFactory.Default.allocate(header.wireSize + payloadSize, ByteOrder.BIG_ENDIAN) as PlatformBuffer
}
