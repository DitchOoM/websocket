package com.ditchoom.websocket.frame

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.websocket.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [WsFrameCodec].
 *
 * Tests are organized by RFC section for traceability.
 *
 * References:
 * - RFC 6455 Section 5.2: Base Framing Protocol
 *   https://datatracker.ietf.org/doc/html/rfc6455#section-5.2
 * - RFC 6455 Section 5.5: Control Frames
 *   https://datatracker.ietf.org/doc/html/rfc6455#section-5.5
 */
class WsFrameCodecTest {
    // ========================================================================
    // RFC 6455 Section 5.2 - Basic Frame Parsing
    // https://datatracker.ietf.org/doc/html/rfc6455#section-5.2
    // ========================================================================

    @Test
    fun `RFC 6455 Section 5-2 - parse minimal unmasked frame`() {
        // Unmasked text frame with "Hi" payload
        // Byte 1: 0x81 = FIN=1, opcode=1 (text)
        // Byte 2: 0x02 = MASK=0, len=2
        // Payload: "Hi"
        val frameBytes =
            byteArrayOf(
                0x81.toByte(),
                0x02,
                'H'.code.toByte(),
                'i'.code.toByte(),
            )

        val buffer = createBuffer(frameBytes)
        val frame = decodeTestFrame(buffer)

        assertNotNull(frame)
        assertTrue(frame.header.byte1.fin)
        assertEquals(Opcode.Text, frame.header.byte1.opcode)
        assertEquals(2, frame.header.payloadLength.toInt())
        assertTrue(frame is WsFrame.Text<*>)
        assertEquals("Hi", ((frame as WsFrame.Text<*>).payload as TestPayload).text)
    }

    @Test
    fun `RFC 6455 Section 5-2 - parse masked frame with 4-byte key`() {
        // Masked text frame with "Hi" payload
        // Byte 1: 0x81 = FIN=1, opcode=1 (text)
        // Byte 2: 0x82 = MASK=1, len=2
        // Mask: 0x01020304
        // Masked payload: XOR("Hi", mask)
        val maskKey = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val payload = "Hi".encodeToByteArray()
        val maskedPayload = payload.mapIndexed { i, b -> (b.toInt() xor maskKey[i % 4].toInt()).toByte() }.toByteArray()

        val frameBytes =
            byteArrayOf(
                0x81.toByte(),
                0x82.toByte(),
                *maskKey,
                *maskedPayload,
            )

        val buffer = createBuffer(frameBytes)
        val frame = decodeTestFrame(buffer)

        assertNotNull(frame)
        assertTrue(frame is WsFrame.Text<*>)
        // Note: WsFrameCodec.decode does not unmask — the payload is returned as-is.
        // The masking key is available in the header for the caller to unmask if needed.
        assertEquals(2, frame.header.payloadLength.toInt())
    }

    @Test
    fun `RFC 6455 Section 5-2 - parse frame with 16-bit extended length`() {
        // Frame with payload length 126 (uses 16-bit extended length)
        val payloadSize = 200
        val payload = ByteArray(payloadSize) { it.toByte() }

        val frameBytes =
            byteArrayOf(
                0x82.toByte(), // FIN=1, opcode=2 (binary)
                0x7E, // MASK=0, len=126 (16-bit follows)
                (payloadSize shr 8).toByte(), // Extended length high byte
                (payloadSize and 0xFF).toByte(), // Extended length low byte
                *payload,
            )

        val buffer = createBuffer(frameBytes)
        val frame = decodeSkipPayload(buffer)

        assertNotNull(frame)
        assertEquals(Opcode.Binary, frame.header.byte1.opcode)
        assertEquals(payloadSize, frame.header.payloadLength.toInt())
    }

    @Test
    fun `RFC 6455 Section 5-2 - parse frame with 64-bit extended length`() {
        // Frame with payload length > 65535 (uses 64-bit extended length)
        val payloadSize = 70000
        val payload = ByteArray(payloadSize) { (it % 256).toByte() }

        val lengthBytes = ByteArray(8)
        lengthBytes[4] = ((payloadSize shr 24) and 0xFF).toByte()
        lengthBytes[5] = ((payloadSize shr 16) and 0xFF).toByte()
        lengthBytes[6] = ((payloadSize shr 8) and 0xFF).toByte()
        lengthBytes[7] = (payloadSize and 0xFF).toByte()

        val frameBytes =
            byteArrayOf(
                0x82.toByte(), // FIN=1, opcode=2 (binary)
                0x7F, // MASK=0, len=127 (64-bit follows)
                *lengthBytes,
                *payload,
            )

        val buffer = createBuffer(frameBytes)
        val frame = decodeSkipPayload(buffer)

        assertNotNull(frame)
        assertEquals(Opcode.Binary, frame.header.byte1.opcode)
        assertEquals(payloadSize, frame.header.payloadLength.toInt())
    }

    @Test
    fun `RFC 6455 Section 5-2 - FIN bit parsing`() {
        // FIN=0 (continuation expected)
        val fragmentFrame = byteArrayOf(0x01, 0x02, 'H'.code.toByte(), 'i'.code.toByte())

        val buffer = createBuffer(fragmentFrame)
        val frame = decodeTestFrame(buffer)

        assertNotNull(frame)
        assertFalse(frame.header.byte1.fin, "FIN should be false for fragment")
        assertEquals(Opcode.Text, frame.header.byte1.opcode)
    }

    @Test
    fun `RFC 6455 Section 5-2 - RSV1 bit parsing for compression`() {
        // RSV1=1 indicates compressed frame (permessage-deflate)
        // 0xC1 = 1100 0001 = FIN=1, RSV1=1, opcode=1
        val compressedFrame = byteArrayOf(0xC1.toByte(), 0x02, 'H'.code.toByte(), 'i'.code.toByte())

        val buffer = createBuffer(compressedFrame)
        val frame = decodeTestFrame(buffer)

        assertNotNull(frame)
        assertTrue(frame.header.byte1.rsv1, "RSV1 should be true for compressed frame")
        assertTrue(frame.header.byte1.fin)
    }

    @Test
    fun `RFC 6455 Section 5-2 - all RSV bits parsing`() {
        // All RSV bits set: 0xF1 = 1111 0001 = FIN=1, RSV1=1, RSV2=1, RSV3=1, opcode=1
        val frameBytes = byteArrayOf(0xF1.toByte(), 0x00)

        val buffer = createBuffer(frameBytes)
        val frame = decodeTestFrame(buffer)

        assertNotNull(frame)
        assertTrue(frame.header.byte1.rsv1)
        assertTrue(frame.header.byte1.rsv2)
        assertTrue(frame.header.byte1.rsv3)
    }

    // ========================================================================
    // RFC 6455 Section 5.2 - Opcode Parsing
    // https://datatracker.ietf.org/doc/html/rfc6455#section-5.2
    // ========================================================================

    @Test
    fun `RFC 6455 Section 5-2 - parse continuation frame opcode 0x0`() {
        val frameBytes = byteArrayOf(0x00, 0x02, 'H'.code.toByte(), 'i'.code.toByte())

        val buffer = createBuffer(frameBytes)
        val frame = decodeTestFrame(buffer)

        assertNotNull(frame)
        assertEquals(Opcode.Continuation, frame.header.byte1.opcode)
        assertFalse(frame is WsFrame.Close || frame is WsFrame.Ping<*> || frame is WsFrame.Pong<*>)
        assertTrue(frame is WsFrame.Text<*> || frame is WsFrame.Binary<*> || frame is WsFrame.Continuation<*>)
    }

    @Test
    fun `RFC 6455 Section 5-2 - parse text frame opcode 0x1`() {
        val frameBytes = byteArrayOf(0x81.toByte(), 0x00)

        val buffer = createBuffer(frameBytes)
        val frame = decodeTestFrame(buffer)

        assertNotNull(frame)
        assertEquals(Opcode.Text, frame.header.byte1.opcode)
    }

    @Test
    fun `RFC 6455 Section 5-2 - parse binary frame opcode 0x2`() {
        val frameBytes = byteArrayOf(0x82.toByte(), 0x00)

        val buffer = createBuffer(frameBytes)
        val frame = decodeTestFrame(buffer)

        assertNotNull(frame)
        assertEquals(Opcode.Binary, frame.header.byte1.opcode)
    }

    // ========================================================================
    // RFC 6455 Section 5.5 - Control Frames
    // https://datatracker.ietf.org/doc/html/rfc6455#section-5.5
    // ========================================================================

    @Test
    fun `RFC 6455 Section 5-5 - parse close frame opcode 0x8`() {
        // Close frame with status code 1000 (normal closure)
        val frameBytes =
            byteArrayOf(
                0x88.toByte(), // FIN=1, opcode=8 (close)
                0x02, // len=2
                0x03,
                0xE8.toByte(), // 1000 in big-endian
            )

        val buffer = createBuffer(frameBytes)
        val frame = decodeTestFrame(buffer)

        assertNotNull(frame)
        assertEquals(Opcode.Close, frame.header.byte1.opcode)
        assertTrue(frame is WsFrame.Close || frame is WsFrame.Ping<*> || frame is WsFrame.Pong<*>)
        assertFalse(frame is WsFrame.Text<*> || frame is WsFrame.Binary<*> || frame is WsFrame.Continuation<*>)
        assertEquals(2, frame.header.payloadLength.toInt())
    }

    @Test
    fun `RFC 6455 Section 5-5-2 - parse ping frame opcode 0x9`() {
        val pingData = "ping".encodeToByteArray()
        val frameBytes =
            byteArrayOf(
                0x89.toByte(), // FIN=1, opcode=9 (ping)
                pingData.size.toByte(),
                *pingData,
            )

        val buffer = createBuffer(frameBytes)
        val frame = decodeTestFrame(buffer)

        assertNotNull(frame)
        assertEquals(Opcode.Ping, frame.header.byte1.opcode)
        assertTrue(frame is WsFrame.Close || frame is WsFrame.Ping<*> || frame is WsFrame.Pong<*>)
        assertTrue(frame is WsFrame.Ping<*>)
        assertEquals("ping", ((frame as WsFrame.Ping<*>).payload as TestPayload).text)
    }

    @Test
    fun `RFC 6455 Section 5-5-3 - parse pong frame opcode 0xA`() {
        val pongData = "pong".encodeToByteArray()
        val frameBytes =
            byteArrayOf(
                0x8A.toByte(), // FIN=1, opcode=10 (pong)
                pongData.size.toByte(),
                *pongData,
            )

        val buffer = createBuffer(frameBytes)
        val frame = decodeTestFrame(buffer)

        assertNotNull(frame)
        assertEquals(Opcode.Pong, frame.header.byte1.opcode)
        assertTrue(frame is WsFrame.Close || frame is WsFrame.Ping<*> || frame is WsFrame.Pong<*>)
    }

    // ========================================================================
    // Multiple Frames
    // ========================================================================

    @Test
    fun `parse multiple frames sequentially`() {
        val frame1Bytes = byteArrayOf(0x81.toByte(), 0x02, 'H'.code.toByte(), 'i'.code.toByte())
        val frame2Bytes = byteArrayOf(0x82.toByte(), 0x03, 0x01, 0x02, 0x03)

        val buffer1 = createBuffer(frame1Bytes)
        val parsed1 = decodeTestFrame(buffer1)

        assertNotNull(parsed1)
        assertEquals(Opcode.Text, parsed1.header.byte1.opcode)
        assertTrue(parsed1 is WsFrame.Text<*>)
        assertEquals("Hi", ((parsed1 as WsFrame.Text<*>).payload as TestPayload).text)

        val buffer2 = createBuffer(frame2Bytes)
        val parsed2 = decodeTestFrame(buffer2)

        assertNotNull(parsed2)
        assertEquals(Opcode.Binary, parsed2.header.byte1.opcode)
        assertEquals(3, parsed2.header.payloadLength.toInt())
    }

    // ========================================================================
    // Sliced Buffer Regression Tests (position > 0)
    //
    // These tests verify WsFrameCodec.decode handles buffers correctly
    // when the payload follows header bytes in the same buffer.
    // ========================================================================

    @Test
    fun `text frame payload readable after decode`() {
        // Build a text frame: FIN=1, opcode=Text, payload="Hello"
        val text = "Hello"
        val frameBytes =
            byteArrayOf(
                0x81.toByte(), // FIN + Text
                text.length.toByte(),
                *text.encodeToByteArray(),
            )

        val buffer = createBuffer(frameBytes)
        val frame = decodeTestFrame(buffer)

        assertNotNull(frame)
        assertEquals(Opcode.Text, frame.header.byte1.opcode)
        assertTrue(frame is WsFrame.Text<*>)
        val payload = (frame as WsFrame.Text<*>).payload as TestPayload
        assertEquals(text, payload.text)
    }

    @Test
    fun `close frame payload parsed correctly`() {
        // Close frame: FIN=1, opcode=Close, status=1000, reason="bye"
        val reason = "bye"
        val reasonBytes = reason.encodeToByteArray()
        val frameBytes =
            byteArrayOf(
                0x88.toByte(), // FIN + Close
                (2 + reasonBytes.size).toByte(), // status (2) + reason
                0x03,
                0xE8.toByte(), // 1000 in big-endian
                *reasonBytes,
            )

        val buffer = createBuffer(frameBytes)
        val frame = decodeTestFrame(buffer)

        assertNotNull(frame)
        assertIs<WsFrame.Close>(frame)
        assertEquals(Opcode.Close, frame.header.byte1.opcode)
        val close = frame as WsFrame.Close
        assertEquals(CloseCode.NORMAL, close.body?.statusCode)
        assertEquals("bye", close.body?.reason)
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun createBuffer(bytes: ByteArray): com.ditchoom.buffer.ReadBuffer {
        val buffer = BufferFactory.Default.allocate(bytes.size, ByteOrder.BIG_ENDIAN)
        buffer.writeBytes(bytes)
        buffer.resetForRead()
        return buffer
    }
}
