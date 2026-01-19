package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.toReadBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertEquals


class CompressionByteBufferTests {
    @Test
    fun testDeflate() {
        val testString = "testingString123|testingString123|testingString123|testingString123"
        val buffer = testString
            .toReadBuffer(zone = AllocationZone.Direct) as JvmBuffer
        val byteBuffer = buffer.byteBuffer
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(byteBuffer.array(), byteBuffer.position(), byteBuffer.remaining())
        deflater.finish()

        val bytes = deflater.deflate(byteBuffer.array(), byteBuffer.position(), byteBuffer.remaining())
        deflater.end()
        byteBuffer.position(0)
        byteBuffer.limit(bytes)
        val compressedString = try {
            JvmBuffer(byteBuffer).readString(bytes)
        } catch (e: Exception) {
            "Unreadable string"
        }
        println("before ${buffer.capacity} after: $bytes, compressed $compressedString")
        byteBuffer.position(0)
        val decompressor = Inflater()
        decompressor.setInput(byteBuffer.array(), byteBuffer.position(), byteBuffer.remaining())
        val bytesInflated = decompressor.inflate(byteBuffer.array())
        decompressor.end()

        byteBuffer.position(0)
        byteBuffer.limit(bytesInflated)
        val string = JvmBuffer(byteBuffer).readString(bytesInflated)
        assertEquals(string, testString)
    }
}