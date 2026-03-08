package com.ditchoom.websocket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies buffer type assumptions that compression and I/O code depends on.
 * These tests catch regressions where buffer wrappers (TrackedSlice, PooledBuffer)
 * break pointer access needed by the zlib decompressor.
 */
class BufferTypeAssumptionTests {
    @Test
    fun directAllocateHasNativeMemoryAccess() {
        val buffer = PlatformBuffer.allocate(64)
        assertNotNull(
            buffer.nativeMemoryAccess,
            "Direct allocation should have nativeMemoryAccess",
        )
    }

    @Test
    fun poolBufferHasNativeMemoryAccess() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 4)
        val buffer = pool.acquire(64)
        assertNotNull(
            (buffer as ReadBuffer).nativeMemoryAccess,
            "Pool-acquired buffer should have nativeMemoryAccess",
        )
        pool.release(buffer)
        pool.clear()
    }

    @Test
    fun poolBufferSliceHasNativeMemoryAccess() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 4)
        val buffer = pool.acquire(64)
        buffer.writeInt(0x12345678)
        buffer.resetForRead()
        val slice = buffer.slice()
        assertNotNull(
            slice.nativeMemoryAccess,
            "Slice of pool buffer should have nativeMemoryAccess (TrackedSlice delegates to inner)",
        )
        pool.release(buffer)
        pool.clear()
    }

    @Test
    fun streamProcessorSingleChunkSliceHasNativeMemoryAccess() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 4)
        val processor = StreamProcessor.builder(pool).build()

        // Append a small amount of data (fits in one chunk)
        val data = pool.acquire(64)
        data.writeInt(42)
        data.setLimit(data.position())
        data.position(0)
        processor.append(data)

        // readBuffer for a single-chunk case returns a slice (TrackedSlice)
        val available = processor.available()
        assertTrue(available > 0, "Should have data available")
        val readBuf = processor.readBuffer(available)
        assertNotNull(
            readBuf.nativeMemoryAccess,
            "StreamProcessor single-chunk readBuffer should have nativeMemoryAccess",
        )

        processor.release()
        pool.clear()
    }

    @Test
    fun syncFlushMarkerBufferIsDirect() {
        // Verify the SYNC_FLUSH_MARKER_BUFFER is Direct (not Heap) to avoid futex overhead
        val buffer = PlatformBuffer.allocate(4)
        buffer.writeInt(0x0000FFFF)
        buffer.resetForRead()
        assertNotNull(
            buffer.nativeMemoryAccess,
            "Direct 4-byte buffer should have nativeMemoryAccess (same as SYNC_FLUSH_MARKER_BUFFER)",
        )
    }
}
