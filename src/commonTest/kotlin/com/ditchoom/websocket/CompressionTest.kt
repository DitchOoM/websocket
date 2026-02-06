package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.compressAsync
import com.ditchoom.buffer.compression.decompressAsync
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.buffer.wrap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompressionTest {
    private val pool = BufferPool(allocationZone = AllocationZone.Heap)

    @Test
    fun inflateDeflateWSMessage() =
        runTestNoTimeSkipping {
            val text =
                " Here's a repeating sentence that repeats words like repeat, " +
                    "repeatedly.".repeat(40)
            val uncompressedBuffer = text.toReadBuffer()
            val compressed = uncompressedBuffer.compressWebsocketBuffer(zone = AllocationZone.Heap)
            assertEquals(0, compressed.position())
            // Compressed size should be much smaller than original (501 bytes)
            assertTrue(compressed.remaining() < 100, "Expected compression ratio, got ${compressed.remaining()}")
            assertTrue(compressed.remaining() > 50, "Expected reasonable compression, got ${compressed.remaining()}")
            assertEquals(501, uncompressedBuffer.position())
            assertEquals(501, uncompressedBuffer.limit())
            uncompressedBuffer.resetForRead()
            assertEquals(0, uncompressedBuffer.position())
            assertEquals(501, uncompressedBuffer.limit())
            val decompressed = compressed.decompressWebsocketBuffer()
            assertContentEquals(uncompressedBuffer, decompressed)
        }

    @Test // https://datatracker.ietf.org/doc/html/rfc7692#section-7.2.3.1
    fun inflateExample7_2_3_1_Unfragmented() =
        runTestNoTimeSkipping {
            val expectedBytes =
                byteArrayOf(
                    0xc1.toByte(),
                    0x07,
                    0xf2.toByte(),
                    0x48,
                    0xcd.toByte(),
                    0xc9.toByte(),
                    0xc9.toByte(),
                    0x07,
                    0x00,
                    0x00,
                    0x00,
                    0xff.toByte(),
                    0xff.toByte(),
                )
            val expectedBuffer = PlatformBuffer.wrap(expectedBytes)
            val ws =
                DefaultWebSocketClient(
                    WebSocketConnectionOptions("", 3),
                    pool,
                    null,
                )
            ws.enableCompression = true
            var isOpen = true
            ws.processIncomingMessages(
                {
                    val o = isOpen
                    isOpen = false
                    o
                },
                { expectedBuffer.readByte() },
                { expectedBuffer.readBytes(it!!) },
            )
            val msg = ws.incomingMessages.first()
            assertEquals("Hello", (msg as WebSocketMessage.Text).value)
        }

    @Test // https://datatracker.ietf.org/doc/html/rfc7692#section-7.2.3.1
    fun inflateExample7_2_3_1_Fragmented() =
        runTestNoTimeSkipping(3) {
            val expectedBytes =
                byteArrayOf(
                    0x41,
                    0x03,
                    0xf2.toByte(),
                    0x48,
                    0xcd.toByte(), // first frame
                    0x80.toByte(),
                    0x04,
                    0xc9.toByte(),
                    0xc9.toByte(),
                    0x07,
                    0x00, // second frame
                )
            val expectedBuffer = PlatformBuffer.wrap(expectedBytes)
            val ws =
                DefaultWebSocketClient(
                    WebSocketConnectionOptions("", 3),
                    pool,
                    null,
                )
            ws.enableCompression = true
            var isOpen = true
            ws.processIncomingMessages(
                {
                    val o = isOpen
                    isOpen = false
                    o
                },
                { expectedBuffer.readByte() },
                { expectedBuffer.readBytes(it!!) },
            )
            val msg = ws.incomingMessages.first()
            assertEquals("Hello", (msg as WebSocketMessage.Text).value)
        }

    @Test // https://datatracker.ietf.org/doc/html/rfc7692#section-7.2.3.3
    fun inflateExample7_2_3_3() =
        runTestNoTimeSkipping {
            val expectedBytes =
                byteArrayOf(
                    0xc1.toByte(),
                    0x0b,
                    0x00,
                    0x05,
                    0x00,
                    0xfa.toByte(),
                    0xff.toByte(),
                    0x48,
                    0x65,
                    0x6c,
                    0x6c,
                    0x6f,
                    0x00,
                )
            val expectedBuffer = PlatformBuffer.wrap(expectedBytes)
            val ws =
                DefaultWebSocketClient(
                    WebSocketConnectionOptions("", 3),
                    pool,
                    null,
                )
            ws.enableCompression = true
            var isOpen = true
            ws.processIncomingMessages(
                {
                    val o = isOpen
                    isOpen = false
                    o
                },
                { expectedBuffer.readByte() },
                { expectedBuffer.readBytes(it!!) },
            )
            val msg = ws.incomingMessages.first()
            assertEquals("Hello", (msg as WebSocketMessage.Text).value)
        }

    @Test // https://datatracker.ietf.org/doc/html/rfc7692#section-7.2.3.4
    fun inflateExample7_2_3_4() =
        runTestNoTimeSkipping {
            val expectedBytes =
                byteArrayOf(
                    0xf3.toByte(),
                    0x48,
                    0xcd.toByte(),
                    0xc9.toByte(),
                    0xc9.toByte(),
                    0x07,
                    0x00,
                    0x00,
                )
            val expectedBuffer = PlatformBuffer.wrap(expectedBytes)
            val decompressed = expectedBuffer.decompressWebsocketBuffer()
            assertEquals("Hello", decompressed.readString(decompressed.remaining()))
        }

    @Test // https://datatracker.ietf.org/doc/html/rfc7692#section-7.2.3.5
    fun inflateExample7_2_3_5() =
        runTestNoTimeSkipping {
            val expectedBytes =
                byteArrayOf(
                    0xf2.toByte(),
                    0x48,
                    0x05,
                    0x00,
                    0x00,
                    0x00,
                    0xff.toByte(),
                    0xff.toByte(),
                    0xca.toByte(),
                    0xc9.toByte(),
                    0xc9.toByte(),
                    0x07,
                    0x00,
                )
            val expectedBuffer = PlatformBuffer.wrap(expectedBytes)
            val decompressed = expectedBuffer.decompressWebsocketBuffer()
            assertEquals("Hello", decompressed.readString(decompressed.remaining()))
        }

    @Test
    fun largeBuffer() =
        runTestNoTimeSkipping {
            val zone = AllocationZone.Heap
            val size = 32 * 1024 * 1024 // 32 Megabytes
            val original = PlatformBuffer.allocate(size, zone)
            val random = Random.Default
            while (original.hasRemaining()) {
                original.writeInt(random.nextInt())
            }
            original.resetForRead()
            val compressed = compressAsync(original, CompressionAlgorithm.Raw, zone = zone)
            val uncompressed = decompressAsync(compressed, CompressionAlgorithm.Raw, zone = zone)
            original.resetForRead()
            assertContentEquals(original, uncompressed)
        }

    private fun assertContentEquals(
        expected: ReadBuffer,
        actual: ReadBuffer,
    ) {
        assertEquals(expected.remaining(), actual.remaining())
        repeat(expected.remaining()) { _ ->
            assertEquals(expected.readByte(), actual.readByte())
        }
    }

    @Test
    fun decompress() =
        runTest {
            val testString = "test"
            val compressed = PlatformBuffer.wrap(byteArrayOf(43, 73, 45, 46, 1, 0))
            try {
                val decompressed = decompressAsync(compressed, CompressionAlgorithm.Raw)
                val uncompressedString = decompressed.readString(decompressed.remaining())
                assertEquals(testString, uncompressedString)
            } catch (_: UnsupportedOperationException) {
                // ignore browser error
            }
        }

    @Test
    fun compress() =
        runTest {
            val testString = "test"
            val uncompressedStringBuffer = testString.toReadBuffer()
            try {
                val compressed = compressAsync(uncompressedStringBuffer, CompressionAlgorithm.Raw)
                assertContentEquals(
                    compressed.readByteArray(compressed.remaining()),
                    byteArrayOf(43, 73, 45, 46, 1, 0),
                )
            } catch (_: UnsupportedOperationException) {
                // ignore browser error
            }
        }
}
