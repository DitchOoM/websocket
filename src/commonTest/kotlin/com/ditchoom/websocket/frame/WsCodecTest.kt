package com.ditchoom.websocket.frame

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct unit tests for the WebSocket codec models.
 *
 * These test the codec layer in isolation — no streaming processor,
 * no socket, just encode/decode on plain buffers.
 */
class WsCodecTest {
    private fun allocate(size: Int) = BufferFactory.Default.allocate(size)

    // ========================================================================
    // WsFrameLengthCodec
    // ========================================================================

    @Test
    fun lengthCodecInline() {
        val buf = allocate(1)
        val length = WsFrameLength(100, masked = false)
        WsFrameLengthCodec.encode(buf, length)
        buf.resetForRead()
        val decoded = WsFrameLengthCodec.decode(buf)
        assertEquals(100L, decoded.payloadLength)
        assertEquals(false, decoded.masked)
    }

    @Test
    fun lengthCodecInlineMasked() {
        val buf = allocate(1)
        val length = WsFrameLength(50, masked = true)
        WsFrameLengthCodec.encode(buf, length)
        buf.resetForRead()
        val decoded = WsFrameLengthCodec.decode(buf)
        assertEquals(50L, decoded.payloadLength)
        assertEquals(true, decoded.masked)
    }

    @Test
    fun lengthCodecInlineBoundary125() {
        val buf = allocate(1)
        WsFrameLengthCodec.encode(buf, WsFrameLength(125, masked = false))
        buf.resetForRead()
        assertEquals(125L, WsFrameLengthCodec.decode(buf).payloadLength)
    }

    @Test
    fun lengthCodec16bit() {
        val buf = allocate(3)
        val length = WsFrameLength(256, masked = false)
        WsFrameLengthCodec.encode(buf, length)
        buf.resetForRead()

        // byte2 should have len7=126
        val byte2 = buf.get(0).toInt() and 0xFF
        assertEquals(126, byte2 and 0x7F)

        val decoded = WsFrameLengthCodec.decode(buf)
        assertEquals(256L, decoded.payloadLength)
    }

    @Test
    fun lengthCodec16bitBoundary() {
        val buf = allocate(3)
        WsFrameLengthCodec.encode(buf, WsFrameLength(65535, masked = false))
        buf.resetForRead()
        assertEquals(65535L, WsFrameLengthCodec.decode(buf).payloadLength)
    }

    @Test
    fun lengthCodec64bit() {
        val buf = allocate(9)
        val length = WsFrameLength(70000, masked = false)
        WsFrameLengthCodec.encode(buf, length)
        buf.resetForRead()

        // byte2 should have len7=127
        val byte2 = buf.get(0).toInt() and 0xFF
        assertEquals(127, byte2 and 0x7F)

        val decoded = WsFrameLengthCodec.decode(buf)
        assertEquals(70000L, decoded.payloadLength)
    }

    @Test
    fun lengthCodec64bitMasked() {
        val buf = allocate(9)
        WsFrameLengthCodec.encode(buf, WsFrameLength(70000, masked = true))
        buf.resetForRead()

        val byte2 = buf.get(0).toInt() and 0xFF
        assertTrue((byte2 and 0x80) != 0, "MASK bit must be set")
        assertEquals(127, byte2 and 0x7F)

        val decoded = WsFrameLengthCodec.decode(buf)
        assertEquals(70000L, decoded.payloadLength)
        assertTrue(decoded.masked)
    }

    @Test
    fun lengthCodecSizeOf() {
        assertEquals(1, WsFrameLengthCodec.sizeOf(WsFrameLength(0, false)))
        assertEquals(1, WsFrameLengthCodec.sizeOf(WsFrameLength(125, false)))
        assertEquals(3, WsFrameLengthCodec.sizeOf(WsFrameLength(126, false)))
        assertEquals(3, WsFrameLengthCodec.sizeOf(WsFrameLength(65535, false)))
        assertEquals(9, WsFrameLengthCodec.sizeOf(WsFrameLength(65536, false)))
    }

    @Test
    fun lengthCodecWireSize() {
        assertEquals(1, WsFrameLengthCodec.wireSize(0))
        assertEquals(1, WsFrameLengthCodec.wireSize(125))
        assertEquals(3, WsFrameLengthCodec.wireSize(126))
        assertEquals(9, WsFrameLengthCodec.wireSize(127))
    }

    @Test
    fun lengthCodecRejectsNegative() {
        assertFailsWith<IllegalArgumentException> {
            WsFrameLength(-1, masked = false)
        }
    }

    @Test
    fun lengthCodecDecodesNegative64bitAsMsbError() {
        // 64-bit length with MSB set (negative Long) is invalid per RFC 6455
        val buf = allocate(9)
        buf.writeByte(127.toByte()) // len7=127, unmasked
        buf.writeLong(-1L) // all bits set = MSB is 1
        buf.resetForRead()
        assertFailsWith<FrameParseException> {
            WsFrameLengthCodec.decode(buf)
        }
    }

    // ========================================================================
    // WsFrameHeaderCodec
    // ========================================================================

    @Test
    fun headerCodecUnmaskedTextFrame() {
        val header = WsFrameHeader(
            byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, com.ditchoom.websocket.Opcode.Text),
            length = WsFrameLength(5, masked = false),
        )
        val buf = allocate(2)
        WsFrameHeaderCodec.encode(buf, header)
        buf.resetForRead()

        // byte1: FIN=1, opcode=0x1 → 0x81
        assertEquals(0x81.toByte(), buf.get(0))
        // byte2: MASK=0, len=5 → 0x05
        assertEquals(0x05.toByte(), buf.get(1))

        buf.position(0)
        val decoded = WsFrameHeaderCodec.decode(buf)
        assertTrue(decoded.byte1.fin)
        assertEquals(com.ditchoom.websocket.Opcode.Text, decoded.byte1.opcode)
        assertEquals(5L, decoded.length.payloadLength)
        assertEquals(false, decoded.length.masked)
        assertNull(decoded.maskingKey)
    }

    @Test
    fun headerCodecMaskedFrame() {
        val maskKey = WsMaskingKey(0xDEADBEEFu)
        val header = WsFrameHeader(
            byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, com.ditchoom.websocket.Opcode.Binary),
            length = WsFrameLength(10, masked = true),
            maskingKey = maskKey,
        )
        val buf = allocate(6) // byte1 + byte2 + 4-byte mask
        WsFrameHeaderCodec.encode(buf, header)
        buf.resetForRead()

        val decoded = WsFrameHeaderCodec.decode(buf)
        assertEquals(com.ditchoom.websocket.Opcode.Binary, decoded.byte1.opcode)
        assertEquals(10L, decoded.length.payloadLength)
        assertTrue(decoded.length.masked)
        assertNotNull(decoded.maskingKey)
        assertEquals(0xDEADBEEFu, decoded.maskingKey!!.raw)
    }

    @Test
    fun headerCodec16bitLength() {
        val header = WsFrameHeader(
            byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, com.ditchoom.websocket.Opcode.Text),
            length = WsFrameLength(300, masked = false),
        )
        val buf = allocate(4) // byte1 + byte2(126) + 2-byte length
        WsFrameHeaderCodec.encode(buf, header)
        buf.resetForRead()

        val decoded = WsFrameHeaderCodec.decode(buf)
        assertEquals(300L, decoded.length.payloadLength)
    }

    @Test
    fun headerCodecRsv1Compression() {
        val header = WsFrameHeader(
            byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = true, rsv2 = false, rsv3 = false, com.ditchoom.websocket.Opcode.Text),
            length = WsFrameLength(0, masked = false),
        )
        val buf = allocate(2)
        WsFrameHeaderCodec.encode(buf, header)
        buf.resetForRead()

        // byte1: FIN=1, RSV1=1, opcode=0x1 → 0xC1
        assertEquals(0xC1.toByte(), buf.get(0))

        buf.position(0)
        val decoded = WsFrameHeaderCodec.decode(buf)
        assertTrue(decoded.byte1.rsv1)
    }

    @Test
    fun headerCodecRoundTripAllOpcodes() {
        for (opcode in com.ditchoom.websocket.Opcode.entries) {
            val header = WsFrameHeader(
                byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, opcode),
                length = WsFrameLength(0, masked = false),
            )
            val buf = allocate(2)
            WsFrameHeaderCodec.encode(buf, header)
            buf.resetForRead()
            val decoded = WsFrameHeaderCodec.decode(buf)
            assertEquals(opcode, decoded.byte1.opcode, "Opcode $opcode round-trip failed")
        }
    }

    // ========================================================================
    // Impossible state enforcement
    // ========================================================================

    @Test
    fun headerRejectsMaskedWithoutKey() {
        assertFailsWith<IllegalArgumentException> {
            WsFrameHeader(
                byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, com.ditchoom.websocket.Opcode.Text),
                length = WsFrameLength(0, masked = true),
                maskingKey = null,
            )
        }
    }

    @Test
    fun headerRejectsUnmaskedWithKey() {
        assertFailsWith<IllegalArgumentException> {
            WsFrameHeader(
                byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, com.ditchoom.websocket.Opcode.Text),
                length = WsFrameLength(0, masked = false),
                maskingKey = WsMaskingKey(0u),
            )
        }
    }

    @Test
    fun lengthRejectsNegativePayload() {
        assertFailsWith<IllegalArgumentException> {
            WsFrameLength(-1, masked = false)
        }
    }
}
