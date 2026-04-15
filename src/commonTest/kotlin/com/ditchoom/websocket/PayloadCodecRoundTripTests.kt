package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec
import com.ditchoom.websocket.codecs.EmptyCodec
import com.ditchoom.websocket.codecs.StringCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip tests for the v2 built-in payload codecs. These exercise encode/decode
 * without going through a `Connection` — the codecs are pure `Codec<T>` implementations
 * and should be testable in isolation.
 *
 * Each codec encodes a known value, rewinds, and decodes back. The result must equal
 * the original.
 */
class PayloadCodecRoundTripTests {
    @Test
    fun stringCodecRoundTripEmpty() = roundTripString("")

    @Test
    fun stringCodecRoundTripAscii() = roundTripString("hello world")

    @Test
    fun stringCodecRoundTripUtf8MultiByte() = roundTripString("héllo, 世界! 🎉")

    @Test
    fun stringCodecRoundTrip1KB() = roundTripString("A".repeat(1024))

    @Test
    fun stringCodecRoundTrip64KB() = roundTripString("A".repeat(65_536))

    @Test
    fun emptyCodecRoundTrip() {
        val buffer = BufferFactory.Default.allocate(16)
        EmptyCodec.encode(buffer, Unit)
        assertEquals(0, buffer.position(), "EmptyCodec should write zero bytes")
        buffer.resetForRead()
        EmptyCodec.decode(buffer)
        // decode on empty buffer is a no-op; nothing to assert beyond not throwing
    }

    @Test
    fun emptyCodecDecodeDiscardsBytes() {
        // If a caller sends EmptyCodec but receives a non-empty payload, decode
        // must still complete cleanly (the bytes are discarded, not retained).
        val buffer = BufferFactory.Default.allocate(64)
        StringCodec.encode(buffer, "surprise")
        buffer.resetForRead()
        EmptyCodec.decode(buffer)
        assertEquals(0, buffer.remaining())
    }

    @Test
    fun binaryPassThroughRoundTrip() {
        val originalBytes = BufferFactory.Default.allocate(256)
        repeat(256) { originalBytes.writeByte((it and 0xFF).toByte()) }
        originalBytes.resetForRead()

        val wire = BufferFactory.Default.allocate(512)
        BinaryPassThroughCodec.encode(wire, originalBytes)
        assertEquals(256, wire.position())

        // BinaryPassThroughCodec.decode returns the same buffer aliased at payload range.
        wire.resetForRead()
        val decoded = BinaryPassThroughCodec.decode(wire)

        assertEquals(256, decoded.remaining())
        repeat(256) { i ->
            assertEquals(
                (i and 0xFF).toByte(),
                decoded.readByte(),
                "byte at index $i differs after round-trip",
            )
        }
    }

    @Test
    fun binaryPassThroughRoundTripLargePayload() {
        // Exercise the 2-byte-length branch of the underlying buffer encoding
        val size = 4096
        val originalBytes = BufferFactory.Default.allocate(size)
        repeat(size) { originalBytes.writeByte(((it * 31) and 0xFF).toByte()) }
        originalBytes.resetForRead()

        val wire = BufferFactory.Default.allocate(size + 64)
        BinaryPassThroughCodec.encode(wire, originalBytes)
        wire.resetForRead()
        val decoded = BinaryPassThroughCodec.decode(wire)

        assertEquals(size, decoded.remaining())
        // Spot-check a few positions
        decoded.position(0)
        assertEquals(0.toByte(), decoded.readByte())
        decoded.position(size / 2)
        assertEquals((((size / 2) * 31) and 0xFF).toByte(), decoded.readByte())
        decoded.position(size - 1)
        assertEquals((((size - 1) * 31) and 0xFF).toByte(), decoded.readByte())
    }

    // Helper for String round-trip tests with size-dependent buffer allocation.
    private fun roundTripString(value: String) {
        val estimated = (value.length * 3).coerceAtLeast(16)
        val wire = BufferFactory.Default.allocate(estimated)
        StringCodec.encode(wire, value)
        val encodedSize = wire.position()
        assertTrue(encodedSize >= 0, "encode should advance the buffer position")

        wire.resetForRead()
        val decoded = StringCodec.decode(wire)
        assertEquals(value, decoded)
    }
}
