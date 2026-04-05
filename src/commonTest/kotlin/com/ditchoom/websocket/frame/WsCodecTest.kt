package com.ditchoom.websocket.frame

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    // WsHeaderByte2 value class
    // ========================================================================

    @Test
    fun byte2InlineLength() {
        val byte2 = WsHeaderByte2.pack(payloadSize = 100, masked = false)
        assertEquals(100, byte2.lengthIndicator)
        assertEquals(false, byte2.masked)
        assertEquals(false, byte2.extended16)
        assertEquals(false, byte2.extended64)
    }

    @Test
    fun byte2InlineMasked() {
        val byte2 = WsHeaderByte2.pack(payloadSize = 50, masked = true)
        assertEquals(50, byte2.lengthIndicator)
        assertTrue(byte2.masked)
    }

    @Test
    fun byte2Boundary125() {
        val byte2 = WsHeaderByte2.pack(payloadSize = 125, masked = false)
        assertEquals(125, byte2.lengthIndicator)
        assertEquals(false, byte2.extended16)
        assertEquals(false, byte2.extended64)
    }

    @Test
    fun byte2Extended16() {
        val byte2 = WsHeaderByte2.pack(payloadSize = 256, masked = false)
        assertEquals(126, byte2.lengthIndicator)
        assertTrue(byte2.extended16)
        assertEquals(false, byte2.extended64)
    }

    @Test
    fun byte2Extended64() {
        val byte2 = WsHeaderByte2.pack(payloadSize = 70000, masked = false)
        assertEquals(127, byte2.lengthIndicator)
        assertEquals(false, byte2.extended16)
        assertTrue(byte2.extended64)
    }

    @Test
    fun byte2Extended64Masked() {
        val byte2 = WsHeaderByte2.pack(payloadSize = 70000, masked = true)
        assertTrue(byte2.masked)
        assertTrue(byte2.extended64)
    }

    // ========================================================================
    // WsFrameHeaderCodec (generated) — encode/decode round-trip
    // ========================================================================

    @Test
    fun headerCodecUnmaskedTextFrame() {
        val header = WsFrameHeader.build(
            byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, com.ditchoom.websocket.Opcode.Text),
            payloadSize = 5,
            masked = false,
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
        assertEquals(5L, decoded.payloadLength)
        assertEquals(false, decoded.masked)
        assertNull(decoded.maskingKey)
    }

    @Test
    fun headerCodecMaskedFrame() {
        val maskKey = WsMaskingKey(0xDEADBEEFu)
        val header = WsFrameHeader.build(
            byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, com.ditchoom.websocket.Opcode.Binary),
            payloadSize = 10,
            masked = true,
            maskingKey = maskKey,
        )
        val buf = allocate(6) // byte1 + byte2 + 4-byte mask
        WsFrameHeaderCodec.encode(buf, header)
        buf.resetForRead()

        val decoded = WsFrameHeaderCodec.decode(buf)
        assertEquals(com.ditchoom.websocket.Opcode.Binary, decoded.byte1.opcode)
        assertEquals(10L, decoded.payloadLength)
        assertTrue(decoded.masked)
        assertNotNull(decoded.maskingKey)
        assertEquals(0xDEADBEEFu, decoded.maskingKey!!.raw)
    }

    @Test
    fun headerCodec16bitLength() {
        val header = WsFrameHeader.build(
            byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, com.ditchoom.websocket.Opcode.Text),
            payloadSize = 300,
            masked = false,
        )
        val buf = allocate(4) // byte1 + byte2(126) + 2-byte length
        WsFrameHeaderCodec.encode(buf, header)
        buf.resetForRead()

        val decoded = WsFrameHeaderCodec.decode(buf)
        assertEquals(300L, decoded.payloadLength)
    }

    @Test
    fun headerCodec64bitLength() {
        val header = WsFrameHeader.build(
            byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, com.ditchoom.websocket.Opcode.Binary),
            payloadSize = 70000,
            masked = false,
        )
        val buf = allocate(10) // byte1 + byte2(127) + 8-byte length
        WsFrameHeaderCodec.encode(buf, header)
        buf.resetForRead()

        val decoded = WsFrameHeaderCodec.decode(buf)
        assertEquals(70000L, decoded.payloadLength)
    }

    @Test
    fun headerCodecRsv1Compression() {
        val header = WsFrameHeader.build(
            byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = true, rsv2 = false, rsv3 = false, com.ditchoom.websocket.Opcode.Text),
            payloadSize = 0,
            masked = false,
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
            val header = WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, opcode),
                payloadSize = 0,
                masked = false,
            )
            val buf = allocate(2)
            WsFrameHeaderCodec.encode(buf, header)
            buf.resetForRead()
            val decoded = WsFrameHeaderCodec.decode(buf)
            assertEquals(opcode, decoded.byte1.opcode, "Opcode $opcode round-trip failed")
        }
    }

    // ========================================================================
    // Generated peekFrameSize
    // ========================================================================

    @Test
    fun peekFrameSizeUnmaskedInline() {
        // byte1 + byte2(len=5, no mask) → 2 bytes header
        val buf = allocate(2)
        buf.writeByte(0x81.toByte()) // FIN + Text
        buf.writeByte(0x05.toByte()) // len=5, no mask
        buf.resetForRead()
        val stream = StreamProcessor.create(BufferPool())
        stream.append(buf)

        val result = WsFrameHeaderCodec.peekFrameSize(stream, 0)
        assertIs<PeekResult.Size>(result)
        assertEquals(2, result.bytes)
    }

    @Test
    fun peekFrameSizeMaskedInline() {
        // byte1 + byte2(len=5, masked) + 4-byte mask → 6 bytes header
        val buf = allocate(6)
        buf.writeByte(0x81.toByte()) // FIN + Text
        buf.writeByte(0x85.toByte()) // len=5, masked
        buf.writeInt(0xDEADBEEF.toInt()) // mask key
        buf.resetForRead()
        val stream = StreamProcessor.create(BufferPool())
        stream.append(buf)

        val result = WsFrameHeaderCodec.peekFrameSize(stream, 0)
        assertIs<PeekResult.Size>(result)
        assertEquals(6, result.bytes)
    }

    @Test
    fun peekFrameSize16bit() {
        // byte1 + byte2(126) + 2-byte length → 4 bytes header
        val buf = allocate(4)
        buf.writeByte(0x81.toByte())
        buf.writeByte(126.toByte()) // extended 16-bit
        buf.writeShort(300.toShort())
        buf.resetForRead()
        val stream = StreamProcessor.create(BufferPool())
        stream.append(buf)

        val result = WsFrameHeaderCodec.peekFrameSize(stream, 0)
        assertIs<PeekResult.Size>(result)
        assertEquals(4, result.bytes)
    }

    @Test
    fun peekFrameSize64bitMasked() {
        // byte1 + byte2(127, masked) + 8-byte length + 4-byte mask → 14 bytes header
        val buf = allocate(14)
        buf.writeByte(0x82.toByte())
        buf.writeByte((0x80 or 127).toByte()) // masked + 64-bit
        buf.writeLong(70000L)
        buf.writeInt(0x12345678)
        buf.resetForRead()
        val stream = StreamProcessor.create(BufferPool())
        stream.append(buf)

        val result = WsFrameHeaderCodec.peekFrameSize(stream, 0)
        assertIs<PeekResult.Size>(result)
        assertEquals(14, result.bytes)
    }

    @Test
    fun peekFrameSizeNeedsMoreData() {
        // Only 1 byte available — need at least 2
        val buf = allocate(1)
        buf.writeByte(0x81.toByte())
        buf.resetForRead()
        val stream = StreamProcessor.create(BufferPool())
        stream.append(buf)

        val result = WsFrameHeaderCodec.peekFrameSize(stream, 0)
        assertIs<PeekResult.NeedsMoreData>(result)
    }
}
