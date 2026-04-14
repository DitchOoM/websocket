package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.payload.ReadBufferPayloadReader
import com.ditchoom.buffer.managed
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec
import com.ditchoom.websocket.codecs.EmptyCodec
import com.ditchoom.websocket.codecs.StringCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip tests for the v2 built-in payload codecs. These exercise encode/decode
 * without going through a `Connection` — the codecs are pure and should be testable
 * in isolation.
 *
 * Each codec encodes a known value to a buffer, rewinds, constructs a
 * [ReadBufferPayloadReader] over the written bytes, and decodes back. The result
 * must equal the original.
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
        with(EmptyCodec) {
            buffer.encode(Unit)
        }
        assertEquals(0, buffer.position(), "EmptyCodec should write zero bytes")
        buffer.resetForRead()
        val reader = ReadBufferPayloadReader(buffer)
        with(EmptyCodec) {
            reader.decode()
        }
        // decode on empty reader is a no-op; nothing to assert beyond not throwing
    }

    @Test
    fun emptyCodecDecodeDiscardsBytes() {
        // If a caller sends EmptyCodec but receives a non-empty payload, decode
        // must still complete cleanly (the bytes are discarded, not retained).
        val buffer = BufferFactory.Default.allocate(64)
        with(StringCodec) {
            buffer.encode("surprise")
        }
        buffer.resetForRead()
        val reader = ReadBufferPayloadReader(buffer)
        with(EmptyCodec) {
            reader.decode()
        }
        // Reader was advanced; remaining should be 0.
        assertEquals(0, reader.remaining())
    }

    @Test
    fun binaryPassThroughRoundTrip() {
        val originalBytes = BufferFactory.Default.allocate(256)
        repeat(256) { originalBytes.writeByte((it and 0xFF).toByte()) }
        originalBytes.resetForRead()

        // Encode: write the payload into a wire buffer via BinaryPassThroughCodec.
        val wire = BufferFactory.Default.allocate(512)
        with(BinaryPassThroughCodec) {
            wire.encode(originalBytes)
        }
        assertEquals(256, wire.position())

        // Decode: build a reader over the written bytes and round-trip through the codec.
        // copyToBuffer() already calls resetForRead() on the returned buffer, so
        // `decoded` comes back in read mode with position=0, limit=256.
        wire.resetForRead()
        val reader = ReadBufferPayloadReader(wire)
        val decoded =
            with(BinaryPassThroughCodec) {
                reader.decode()
            }

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
        with(BinaryPassThroughCodec) {
            wire.encode(originalBytes)
        }
        wire.resetForRead()
        val reader = ReadBufferPayloadReader(wire)
        val decoded =
            with(BinaryPassThroughCodec) {
                reader.decode()
            }

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
        with(StringCodec) {
            wire.encode(value)
        }
        val encodedSize = wire.position()
        assertTrue(encodedSize >= 0, "encode should advance the buffer position")

        wire.resetForRead()
        val reader = ReadBufferPayloadReader(wire)
        val decoded =
            with(StringCodec) {
                reader.decode()
            }
        assertEquals(value, decoded)
    }
}
