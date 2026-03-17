package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.compression.BufferAllocator
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for decompressToStringSync with StreamingStringDecoder.
 * Verifies correct round-trip for ASCII, multi-byte UTF-8, and edge cases.
 */
class DecompressToStringTest {
    private fun roundTrip(original: String) {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw, allocator = BufferAllocator.Direct)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferAllocator.Direct)
        val decoder = StreamingStringDecoder()

        try {
            val encoded = original.encodeToByteArray()
            val buf = BufferFactory.Default.allocate(encoded.size)
            buf.writeString(original, Charset.UTF8)
            buf.resetForRead()

            val compressed = compressSync(buf, compressor)
            compressor.reset()
            val combined = combineChunks(compressed, BufferFactory.managed())
            compressed.freeAll()

            val result = decompressToStringSync(combined, decompressor, decoder)
            combined.freeIfNeeded()

            assertEquals(original, result)
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    @Test
    fun asciiSmall() {
        roundTrip("Hello, World!")
    }

    @Test
    fun asciiMedium() {
        roundTrip(buildString { repeat(4096) { append('A' + (it % 26)) } })
    }

    @Test
    fun asciiLarge() {
        roundTrip(buildString { repeat(128 * 1024) { append('A' + (it % 26)) } })
    }

    @Test
    fun chineseText() {
        // 3-byte UTF-8 sequences
        roundTrip("你好世界！这是一个测试。中文字符在UTF-8中占三个字节。")
    }

    @Test
    fun mixedAsciiEmojiChinese() {
        roundTrip("Hello 你好 \uD83D\uDE00\uD83C\uDF0D mixed テスト test!")
    }

    @Test
    fun emptyInput() {
        roundTrip("")
    }

    @Test
    fun singleCharAscii() {
        roundTrip("A")
    }

    @Test
    fun singleChar2Byte() {
        // U+00E9 = é (2-byte UTF-8)
        roundTrip("\u00E9")
    }

    @Test
    fun singleChar3Byte() {
        // U+4E16 = 世 (3-byte UTF-8)
        roundTrip("\u4E16")
    }

    @Test
    fun singleChar4Byte() {
        // U+1F600 = 😀 (4-byte UTF-8, encoded as surrogate pair in Kotlin)
        roundTrip("\uD83D\uDE00")
    }

    @Test
    fun repeatedEmoji() {
        // Many 4-byte sequences that may split across decompressor chunks
        roundTrip(buildString { repeat(1000) { append("\uD83D\uDE00") } })
    }

    @Test
    fun longChinese() {
        // Enough 3-byte sequences to span multiple decompressor output chunks
        val base = "你好世界测试文本数据"
        roundTrip(buildString { repeat(500) { append(base) } })
    }
}
