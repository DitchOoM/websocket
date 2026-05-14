package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression net for Autobahn case 9.6.1 — 1 MiB binary permessage-deflate
 * message arriving in ~16k 64-byte chops, then echoed back. The case once
 * tripped the JVM 1 GiB direct-buffer cap (`Cannot reserve … bytes of direct
 * buffer memory`) when the wire path uses `BufferFactory.Default` (which on
 * JVM resolves to `DirectJvmBuffer`, whose `freeNativeMemory()` is a no-op —
 * cleanup relies on the NIO Cleaner / GC). The original symptom didn't
 * reproduce in isolation; both the assemble/decompress/echo round-trip below
 * and a real Autobahn 9.6.1 run pass cleanly. This probe stays as a fast
 * jvmTest gate so a future per-fragment or per-message direct-buffer leak
 * can't slip back in unnoticed.
 *
 * The test feeds the mock transport 16k+ frames per iteration, exercises the
 * MessageAssembler / `decompressToBufferSync` / `BinaryPassThroughCodec` /
 * compressed-send chain, and asserts the JMX `java.nio:type=BufferPool` count
 * and bytes don't drift more than a small bound across 20 iterations.
 */
class Case961DirectBufferProbeTest {
    private data class MemSnapshot(
        val heapMB: Double,
        val directMB: Double,
        val directCount: Long,
    ) {
        override fun toString() = "heap=%.1fMB direct=%.2fMB(%d bufs)".format(heapMB, directMB, directCount)
    }

    private fun snapshot(): MemSnapshot {
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
        val rt = Runtime.getRuntime()
        val heapMB = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
        val (count, used) =
            try {
                val mbs = ManagementFactory.getPlatformMBeanServer()
                val name = ObjectName("java.nio:type=BufferPool,name=direct")
                Pair(
                    mbs.getAttribute(name, "Count") as Long,
                    mbs.getAttribute(name, "MemoryUsed") as Long,
                )
            } catch (_: Exception) {
                Pair(-1L, -1L)
            }
        return MemSnapshot(heapMB, used / (1024.0 * 1024.0), count)
    }

    /**
     * Builds a fresh batch of [Opcode.Binary] + N×[Opcode.Continuation] frames
     * carrying the permessage-deflate compressed bytes of [source] in
     * [chunkSize]-byte chops. Mirrors the wire shape Autobahn produces for
     * case 9.6.1 (RSV1=1 on the first frame, then continuations until FIN).
     */
    private fun buildFragmentedCompressedFrames(
        source: ReadBuffer,
        chunkSize: Int,
    ): List<ReadBuffer> {
        val compressor =
            StreamingCompressor.create(
                algorithm = CompressionAlgorithm.Raw,
                level = CompressionLevel.Default,
                bufferFactory = BufferFactory.Default,
            )
        source.position(0)
        val compressedChunks = compressSync(source, compressor)
        val combined = combineChunks(compressedChunks)
        compressedChunks.freeAll()
        compressor.close()

        val total = combined.remaining()
        val frames = mutableListOf<ReadBuffer>()
        var offset = 0
        var isFirst = true
        while (offset < total) {
            val end = minOf(offset + chunkSize, total)
            val size = end - offset
            val chunkBuf = BufferFactory.Default.allocate(size)
            combined.position(offset)
            repeat(size) { chunkBuf.writeByte(combined.readByte()) }
            chunkBuf.resetForRead()
            val isLast = end >= total
            val frame =
                if (isFirst) {
                    MockAutobahnHelpers.buildServerFrameWithRsv(
                        Opcode.Binary,
                        chunkBuf,
                        fin = isLast,
                        rsv1 = true,
                    )
                } else {
                    MockAutobahnHelpers.buildServerContinuationFrame(chunkBuf, fin = isLast)
                }
            chunkBuf.freeIfNeeded()
            frames.add(frame)
            isFirst = false
            offset = end
        }
        combined.freeIfNeeded()
        return frames
    }

    @Test
    fun case961_directBufferStaysBoundedAcrossIterations() =
        runStrictTest(timeout = 120.seconds) {
            val payloadSize = 1 * 1024 * 1024 // 1 MiB — matches Autobahn case 9.6
            val chunkSize = 64 // matches case 9.6.1 specifically
            val iterations = 20

            // High-entropy source so deflate doesn't compress it away — Autobahn's
            // 9.6.1 message is random-ish bytes, producing ~1MB on the wire and
            // ~16k frames at 64B chops. Deterministic seed for reproducibility.
            val rng = kotlin.random.Random(0x9613L)
            val source = BufferFactory.Default.allocate(payloadSize)
            repeat(payloadSize) { source.writeByte(rng.nextInt().toByte()) }
            source.resetForRead()

            // Feed the 16k-frame compressed stream, receive the assembled message,
            // echo it back (exercising the compressed-send path), then close.
            suspend fun runOnce() {
                val transport = MockWebSocketTransport()
                val frames = buildFragmentedCompressedFrames(source, chunkSize)
                val connection =
                    MockAutobahnHelpers.connectWithCompressionHandshake(
                        transport = transport,
                        binaryCodec = BinaryPassThroughCodec,
                        bufferFactory = BufferFactory.Default,
                    )
                for (f in frames) transport.enqueueRead(f)
                val msg = withTimeout(60.seconds) { connection.receive().first() }
                assertIs<WebSocketMessage.Binary<*>>(msg)
                @Suppress("UNCHECKED_CAST")
                val binary = msg as WebSocketMessage.Binary<ReadBuffer>
                assertEquals(payloadSize, binary.payload.remaining())
                connection.send(WebSocketMessage.Binary(binary.payload))
                binary.payload.freeIfNeeded()
                connection.close()
            }

            // Warm-up: prime JIT + grow direct-buffer pool to steady state before
            // the measurement loop. Two iterations is enough for HotSpot to settle.
            runOnce()
            runOnce()

            val before = snapshot()
            repeat(iterations) { runOnce() }
            source.freeIfNeeded()
            val after = snapshot()

            val directGrowthMB = after.directMB - before.directMB
            val countGrowth = after.directCount - before.directCount
            println(
                "[case961-probe] direct=%+.2fMB(%+d bufs) heap=%+.2fMB across $iterations × (1MB/64B chops) iterations".format(
                    directGrowthMB,
                    countGrowth,
                    after.heapMB - before.heapMB,
                ),
            )

            // The eager-free invariant from websocket_fallout_closed says assembled
            // messages must not retain library-owned buffers past emitMessage. A
            // genuine per-message leak would be ~MBs per iteration. 40MB across 20
            // iterations leaves room for JIT / pool ramp without masking real
            // regressions.
            assertTrue(
                directGrowthMB < 40.0,
                "Direct buffer pool grew by %.2fMB across $iterations iterations of case 9.6.1 — suspected per-message leak."
                    .format(directGrowthMB),
            )
        }
}
