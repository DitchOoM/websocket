package com.ditchoom.websocket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.buffer.wrap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FrameSerializationTest {
    // --- Header byte 1: FIN, RSV, and Opcode encoding ---

    @Test
    fun finBitSetForFinalFrame() {
        val frame = Frame(fin = true, opcode = Opcode.Text, maskingKey = MaskingKey.NoMaskingKey, payloadData = EMPTY_BUFFER)
        val buffer = serializeFrame(frame)
        val byte1 = buffer[0].toInt() and 0xFF
        assertEquals(0x81, byte1) // FIN=1, opcode=0x1
    }

    @Test
    fun finBitClearForContinuationFragment() {
        val frame = Frame(fin = false, opcode = Opcode.Text, maskingKey = MaskingKey.NoMaskingKey, payloadData = EMPTY_BUFFER)
        val buffer = serializeFrame(frame)
        val byte1 = buffer[0].toInt() and 0xFF
        assertEquals(0x01, byte1) // FIN=0, opcode=0x1
    }

    @Test
    fun continuationOpcode() {
        val frame = Frame(fin = false, opcode = Opcode.Continuation, maskingKey = MaskingKey.NoMaskingKey, payloadData = EMPTY_BUFFER)
        val buffer = serializeFrame(frame)
        val byte1 = buffer[0].toInt() and 0xFF
        assertEquals(0x00, byte1) // FIN=0, opcode=0x0
    }

    @Test
    fun binaryOpcode() {
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = MaskingKey.NoMaskingKey, payloadData = EMPTY_BUFFER)
        val buffer = serializeFrame(frame)
        val byte1 = buffer[0].toInt() and 0xFF
        assertEquals(0x82, byte1) // FIN=1, opcode=0x2
    }

    @Test
    fun closeOpcode() {
        val frame = Frame(fin = true, opcode = Opcode.Close, maskingKey = MaskingKey.NoMaskingKey, payloadData = EMPTY_BUFFER)
        val buffer = serializeFrame(frame)
        val byte1 = buffer[0].toInt() and 0xFF
        assertEquals(0x88, byte1) // FIN=1, opcode=0x8
    }

    @Test
    fun pingOpcode() {
        val frame = Frame(fin = true, opcode = Opcode.Ping, maskingKey = MaskingKey.NoMaskingKey, payloadData = EMPTY_BUFFER)
        val buffer = serializeFrame(frame)
        val byte1 = buffer[0].toInt() and 0xFF
        assertEquals(0x89, byte1) // FIN=1, opcode=0x9
    }

    @Test
    fun pongOpcode() {
        val frame = Frame(fin = true, opcode = Opcode.Pong, maskingKey = MaskingKey.NoMaskingKey, payloadData = EMPTY_BUFFER)
        val buffer = serializeFrame(frame)
        val byte1 = buffer[0].toInt() and 0xFF
        assertEquals(0x8A, byte1) // FIN=1, opcode=0xA
    }

    @Test
    fun rsv1BitSetForCompression() {
        val frame =
            Frame(
                fin = true,
                rsv1 = true,
                rsv2 = false,
                rsv3 = false,
                opcode = Opcode.Text,
                maskingKey = MaskingKey.NoMaskingKey,
                payloadData = EMPTY_BUFFER,
            )
        val buffer = serializeFrame(frame)
        val byte1 = buffer[0].toInt() and 0xFF
        assertEquals(0xC1, byte1) // FIN=1, RSV1=1, opcode=0x1
    }

    @Test
    fun allRsvBitsSet() {
        val frame =
            Frame(
                fin = true,
                rsv1 = true,
                rsv2 = true,
                rsv3 = true,
                opcode = Opcode.Binary,
                maskingKey = MaskingKey.NoMaskingKey,
                payloadData = EMPTY_BUFFER,
            )
        val buffer = serializeFrame(frame)
        val byte1 = buffer[0].toInt() and 0xFF
        assertEquals(0xF2, byte1) // FIN=1, RSV1=1, RSV2=1, RSV3=1, opcode=0x2
    }

    // --- Payload length encoding ---

    @Test
    fun emptyPayloadLength() {
        val frame = Frame(fin = true, opcode = Opcode.Text, maskingKey = MaskingKey.NoMaskingKey, payloadData = EMPTY_BUFFER)
        val buffer = serializeFrame(frame)
        val byte2 = buffer[1].toInt() and 0xFF
        assertEquals(0, byte2)
        assertEquals(2, frame.size())
    }

    @Test
    fun payloadLength125DirectEncoding() {
        val payload = PlatformBuffer.allocate(125)
        repeat(125) { payload.writeByte(0x41) }
        payload.resetForRead()
        val frame = Frame(fin = true, opcode = Opcode.Text, maskingKey = MaskingKey.NoMaskingKey, payloadData = payload)
        val buffer = serializeFrame(frame)
        val byte2 = buffer[1].toInt() and 0xFF
        assertEquals(125, byte2)
        assertEquals(2 + 125, frame.size())
    }

    @Test
    fun payloadLength126TriggersTwoByteExtended() {
        val payload = PlatformBuffer.allocate(126)
        repeat(126) { payload.writeByte(0x42) }
        payload.resetForRead()
        val frame = Frame(fin = true, opcode = Opcode.Text, maskingKey = MaskingKey.NoMaskingKey, payloadData = payload)
        val buffer = serializeFrame(frame)
        val byte2 = buffer[1].toInt() and 0xFF
        assertEquals(126, byte2) // Signals 2-byte extended length follows
        // Extended length: 126 as unsigned short (big-endian)
        val extLen = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        assertEquals(126, extLen)
        assertEquals(2 + 2 + 126, frame.size()) // header + ext_len + payload
    }

    @Test
    fun payloadLength127TwoByteExtended() {
        val payload = PlatformBuffer.allocate(127)
        repeat(127) { payload.writeByte(0x43) }
        payload.resetForRead()
        val frame = Frame(fin = true, opcode = Opcode.Text, maskingKey = MaskingKey.NoMaskingKey, payloadData = payload)
        val buffer = serializeFrame(frame)
        val byte2 = buffer[1].toInt() and 0xFF
        assertEquals(126, byte2) // Still uses 2-byte extended
        val extLen = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        assertEquals(127, extLen)
    }

    @Test
    fun payloadLength65535MaxTwoByteExtended() {
        val payload = PlatformBuffer.allocate(65535)
        repeat(65535) { payload.writeByte(0x44) }
        payload.resetForRead()
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = MaskingKey.NoMaskingKey, payloadData = payload)
        val buffer = serializeFrame(frame)
        val byte2 = buffer[1].toInt() and 0xFF
        assertEquals(126, byte2)
        val extLen = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        assertEquals(65535, extLen)
        assertEquals(2 + 2 + 65535, frame.size())
    }

    @Test
    fun payloadLength65536TriggersEightByteExtended() {
        val payload = PlatformBuffer.allocate(65536)
        repeat(65536) { payload.writeByte(0x45) }
        payload.resetForRead()
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = MaskingKey.NoMaskingKey, payloadData = payload)
        val buffer = serializeFrame(frame)
        val byte2 = buffer[1].toInt() and 0xFF
        assertEquals(127, byte2) // Signals 8-byte extended length follows
        // Extended length: 65536 as 8-byte big-endian long
        var extLen = 0L
        for (i in 2..9) {
            extLen = (extLen shl 8) or (buffer[i].toLong() and 0xFF)
        }
        assertEquals(65536L, extLen)
        assertEquals(2 + 8 + 65536, frame.size()) // header + ext_len + payload
    }

    // --- Masking ---

    @Test
    fun unmaskBitClearWhenNoMaskingKey() {
        val payload = "Hi".toReadBuffer()
        val frame = Frame(fin = true, opcode = Opcode.Text, maskingKey = MaskingKey.NoMaskingKey, payloadData = payload)
        val buffer = serializeFrame(frame)
        val byte2 = buffer[1].toInt() and 0xFF
        assertEquals(0, byte2 and 0x80) // MASK bit clear
    }

    @Test
    fun maskBitSetWhenMaskingKeyPresent() {
        val payload = "Hi".toReadBuffer()
        val mask = MaskingKey.FourByteMaskingKey(0x12345678)
        val frame = Frame(fin = true, opcode = Opcode.Text, maskingKey = mask, payloadData = payload)
        val buffer = serializeFrame(frame)
        val byte2 = buffer[1].toInt() and 0xFF
        assertEquals(0x80, byte2 and 0x80) // MASK bit set
        assertEquals(2, byte2 and 0x7F) // payload length = 2
    }

    @Test
    fun maskingKeyWrittenAfterPayloadLength() {
        val payload = "AB".toReadBuffer()
        val mask = MaskingKey.FourByteMaskingKey(0x12345678)
        val frame = Frame(fin = true, opcode = Opcode.Text, maskingKey = mask, payloadData = payload)
        val buffer = serializeFrame(frame)
        // Bytes 2-5 are the masking key
        assertEquals(0x12.toByte(), buffer[2])
        assertEquals(0x34.toByte(), buffer[3])
        assertEquals(0x56.toByte(), buffer[4])
        assertEquals(0x78.toByte(), buffer[5])
    }

    @Test
    fun payloadIsMaskedWithXor() {
        val payload = PlatformBuffer.wrap(byteArrayOf(0x48, 0x65, 0x6C, 0x6C, 0x6F)) // "Hello"
        val mask = MaskingKey.FourByteMaskingKey(0x12345678)
        val frame = Frame(fin = true, opcode = Opcode.Text, maskingKey = mask, payloadData = payload)
        val buffer = serializeFrame(frame)
        // Masked payload starts at offset 6 (2 header + 4 mask key)
        val maskedPayload = ByteArray(5) { buffer[6 + it] }
        // Verify each byte is XOR'd with the corresponding mask byte
        val expected =
            byteArrayOf(
                (0x48 xor 0x12).toByte(), // H ^ mask[0]
                (0x65 xor 0x34).toByte(), // e ^ mask[1]
                (0x6C xor 0x56).toByte(), // l ^ mask[2]
                (0x6C xor 0x78).toByte(), // l ^ mask[3]
                (0x6F xor 0x12).toByte(), // o ^ mask[0] (wraps around)
            )
        assertContentEquals(expected, maskedPayload)
    }

    @Test
    fun maskRoundTripRecoverOriginalPayload() {
        val originalBytes = ByteArray(100) { it.toByte() }
        val payload = PlatformBuffer.wrap(originalBytes.copyOf())
        val mask = MaskingKey.FourByteMaskingKey(0xDEADBEEF.toInt())
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = mask, payloadData = payload)
        val buffer = serializeFrame(frame)
        // Extract masked payload (offset: 2 header + 4 mask = 6)
        val maskedPayload = ByteArray(100) { buffer[6 + it] }
        // Unmask by XORing again with the same key
        val unmasked = ByteArray(100) { i -> (maskedPayload[i].toInt() xor mask[i and 3].toInt()).toByte() }
        assertContentEquals(originalBytes, unmasked)
    }

    @Test
    fun maskedFrameWithTwoByteExtendedLength() {
        val payload = PlatformBuffer.allocate(200)
        repeat(200) { payload.writeByte(it.toByte()) }
        payload.resetForRead()
        val mask = MaskingKey.FourByteMaskingKey(0xAABBCCDD.toInt())
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = mask, payloadData = payload)
        val buffer = serializeFrame(frame)
        val byte2 = buffer[1].toInt() and 0xFF
        assertEquals(0x80 or 126, byte2) // MASK=1, length=126
        // Extended length at bytes 2-3
        val extLen = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        assertEquals(200, extLen)
        // Mask key at bytes 4-7
        assertEquals(0xAA.toByte(), buffer[4])
        assertEquals(0xBB.toByte(), buffer[5])
        assertEquals(0xCC.toByte(), buffer[6])
        assertEquals(0xDD.toByte(), buffer[7])
        // Total size: 2 header + 2 ext_len + 4 mask + 200 payload
        assertEquals(2 + 2 + 4 + 200, frame.size())
    }

    // --- Close frame encoding ---

    @Test
    fun closeFrameWithCode() {
        // Close code 1000 (normal closure) encoded as 2-byte big-endian
        val closePayload = PlatformBuffer.allocate(2)
        closePayload.writeByte(0x03.toByte()) // 1000 >> 8
        closePayload.writeByte(0xE8.toByte()) // 1000 & 0xFF
        closePayload.resetForRead()
        val frame = Frame(fin = true, opcode = Opcode.Close, maskingKey = MaskingKey.NoMaskingKey, payloadData = closePayload)
        val buffer = serializeFrame(frame)
        assertEquals(0x88.toByte(), buffer[0]) // FIN=1, opcode=8
        assertEquals(2.toByte(), buffer[1]) // payload length = 2
        assertEquals(0x03.toByte(), buffer[2]) // close code high byte
        assertEquals(0xE8.toByte(), buffer[3]) // close code low byte
    }

    @Test
    fun closeFrameWithCodeAndReason() {
        val reason = "going away"
        val reasonBytes = reason.encodeToByteArray()
        val closePayload = PlatformBuffer.allocate(2 + reasonBytes.size)
        closePayload.writeByte(0x03.toByte()) // 1001 >> 8
        closePayload.writeByte(0xE9.toByte()) // 1001 & 0xFF
        reasonBytes.forEach { closePayload.writeByte(it) }
        closePayload.resetForRead()
        val frame = Frame(fin = true, opcode = Opcode.Close, maskingKey = MaskingKey.NoMaskingKey, payloadData = closePayload)
        val buffer = serializeFrame(frame)
        assertEquals(0x88.toByte(), buffer[0])
        assertEquals((2 + reasonBytes.size).toByte(), buffer[1])
        // Close code
        assertEquals(0x03.toByte(), buffer[2])
        assertEquals(0xE9.toByte(), buffer[3])
        // Reason string
        val extractedReason = ByteArray(reasonBytes.size) { buffer[4 + it] }
        assertContentEquals(reasonBytes, extractedReason)
    }

    // --- Payload content ---

    @Test
    fun unmaskedPayloadWrittenVerbatim() {
        val payloadBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val payload = PlatformBuffer.wrap(payloadBytes.copyOf())
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = MaskingKey.NoMaskingKey, payloadData = payload)
        val buffer = serializeFrame(frame)
        val extracted = ByteArray(5) { buffer[2 + it] }
        assertContentEquals(payloadBytes, extracted)
    }

    @Test
    fun textPayloadEncoding() {
        val text = "Hello, WebSocket!"
        val payload = text.toReadBuffer()
        val frame = Frame(fin = true, opcode = Opcode.Text, maskingKey = MaskingKey.NoMaskingKey, payloadData = payload)
        val buffer = serializeFrame(frame)
        val textBytes = text.encodeToByteArray()
        assertEquals(textBytes.size, buffer[1].toInt() and 0x7F)
        val extracted = ByteArray(textBytes.size) { buffer[2 + it] }
        assertContentEquals(textBytes, extracted)
    }

    // --- Size calculation ---

    @Test
    fun sizeUnmaskedSmallPayload() {
        val payload = PlatformBuffer.wrap(ByteArray(50))
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = MaskingKey.NoMaskingKey, payloadData = payload)
        assertEquals(2 + 50, frame.size()) // 2 header bytes + payload
    }

    @Test
    fun sizeMaskedSmallPayload() {
        val payload = PlatformBuffer.wrap(ByteArray(50))
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = MaskingKey.FourByteMaskingKey(0x11223344), payloadData = payload)
        assertEquals(2 + 4 + 50, frame.size()) // 2 header + 4 mask + payload
    }

    @Test
    fun sizeUnmaskedMediumPayload() {
        val payload = PlatformBuffer.allocate(1000)
        repeat(1000) { payload.writeByte(0) }
        payload.resetForRead()
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = MaskingKey.NoMaskingKey, payloadData = payload)
        assertEquals(2 + 2 + 1000, frame.size()) // 2 header + 2 ext_len + payload
    }

    @Test
    fun sizeMaskedMediumPayload() {
        val payload = PlatformBuffer.allocate(1000)
        repeat(1000) { payload.writeByte(0) }
        payload.resetForRead()
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = MaskingKey.FourByteMaskingKey(0x11223344), payloadData = payload)
        assertEquals(2 + 2 + 4 + 1000, frame.size()) // 2 header + 2 ext_len + 4 mask + payload
    }

    @Test
    fun sizeUnmaskedLargePayload() {
        val payload = PlatformBuffer.allocate(65536)
        repeat(65536) { payload.writeByte(0) }
        payload.resetForRead()
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = MaskingKey.NoMaskingKey, payloadData = payload)
        assertEquals(2 + 8 + 65536, frame.size()) // 2 header + 8 ext_len + payload
    }

    // --- Bulk masking correctness for various payload sizes ---

    @Test
    fun maskingCorrectForPayloadSmallerThan8Bytes() {
        // Tests the byte-by-byte path (no long XOR used)
        val originalBytes = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70)
        val payload = PlatformBuffer.wrap(originalBytes.copyOf())
        val mask = MaskingKey.FourByteMaskingKey(0x01020304)
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = mask, payloadData = payload)
        val buffer = serializeFrame(frame)
        val maskedPayload = ByteArray(7) { buffer[6 + it] }
        val unmasked = ByteArray(7) { i -> (maskedPayload[i].toInt() xor mask[i and 3].toInt()).toByte() }
        assertContentEquals(originalBytes, unmasked)
    }

    @Test
    fun maskingCorrectForPayloadExactly8Bytes() {
        // Tests exactly one long XOR operation
        val originalBytes = ByteArray(8) { (it * 17).toByte() }
        val payload = PlatformBuffer.wrap(originalBytes.copyOf())
        val mask = MaskingKey.FourByteMaskingKey(0xFEDCBA98.toInt())
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = mask, payloadData = payload)
        val buffer = serializeFrame(frame)
        val maskedPayload = ByteArray(8) { buffer[6 + it] }
        val unmasked = ByteArray(8) { i -> (maskedPayload[i].toInt() xor mask[i and 3].toInt()).toByte() }
        assertContentEquals(originalBytes, unmasked)
    }

    @Test
    fun maskingCorrectForPayloadWithRemainder() {
        // 13 bytes = 1 long XOR (8 bytes) + 5 byte-by-byte
        val originalBytes = ByteArray(13) { (it * 7 + 3).toByte() }
        val payload = PlatformBuffer.wrap(originalBytes.copyOf())
        val mask = MaskingKey.FourByteMaskingKey(0x55AA55AA)
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = mask, payloadData = payload)
        val buffer = serializeFrame(frame)
        val maskedPayload = ByteArray(13) { buffer[6 + it] }
        val unmasked = ByteArray(13) { i -> (maskedPayload[i].toInt() xor mask[i and 3].toInt()).toByte() }
        assertContentEquals(originalBytes, unmasked)
    }

    @Test
    fun maskingCorrectForMultipleLongChunks() {
        // 32 bytes = 4 long XOR operations, no remainder
        val originalBytes = ByteArray(32) { (it xor 0xAB).toByte() }
        val payload = PlatformBuffer.wrap(originalBytes.copyOf())
        val mask = MaskingKey.FourByteMaskingKey(0x13579BDF)
        val frame = Frame(fin = true, opcode = Opcode.Binary, maskingKey = mask, payloadData = payload)
        val buffer = serializeFrame(frame)
        val maskedPayload = ByteArray(32) { buffer[6 + it] }
        val unmasked = ByteArray(32) { i -> (maskedPayload[i].toInt() xor mask[i and 3].toInt()).toByte() }
        assertContentEquals(originalBytes, unmasked)
    }

    // --- Helper ---

    private fun serializeFrame(frame: Frame): ByteArray {
        val buffer = PlatformBuffer.allocate(frame.size())
        frame.serialize(buffer)
        buffer.resetForRead()
        return ByteArray(buffer.remaining()) { buffer.readByte() }
    }
}
