package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Diagnostic benchmark for the large-message inbound path — the shape of Autobahn category 9, which
 * echoes 1 MB messages delivered in tiny (64-byte) socket reads, with and without permessage-deflate.
 *
 * Covers the axes that matter for the intermittent multi-second stalls seen on JVM:
 * - text vs binary (text adds the UTF-8 decode / re-encode path),
 * - uncompressed vs compressed (compressed adds inflate + chunked UTF-8 decode),
 * - dribbled (64-byte reads) vs coalesced (one read).
 *
 * Payloads are low-compressibility (seeded RNG) so the compressed frame stays large enough to chop
 * into many reads — a highly-compressible payload would shrink to a handful of chops and hide the
 * cost. Assembly is measured off the network via [MockWebSocketTransport]; the algorithmic cost it
 * reports is independent of JIT/GC/container contention, so an O(n²) shows as ~4× per size doubling.
 *
 * Not a conformance test — run manually:
 *   ./gradlew jvmTest --tests "*ChoppedReadBenchmark*"
 */
class ChoppedReadBenchmark {
    @Test
    fun largeMessageScaling() =
        runStrictTest(timeout = 300.seconds) {
            println("=== large-message assembly (JVM), ms ===")
            println("payloadKB | binChop | txtChop | binZChop | txtZChop | txtWhole | txtZWhole")
            var worstOneMb = 0L
            for (kb in listOf(128, 256, 512, 1024)) {
                val size = kb * 1024
                val binChop = measure(size, chop = 64, text = false, compressed = false)
                val txtChop = measure(size, chop = 64, text = true, compressed = false)
                val binZChop = measure(size, chop = 64, text = false, compressed = true)
                val txtZChop = measure(size, chop = 64, text = true, compressed = true)
                val txtWhole = measure(size, chop = size, text = true, compressed = false)
                val txtZWhole = measure(size, chop = size, text = true, compressed = true)
                println("$kb | $binChop | $txtChop | $binZChop | $txtZChop | $txtWhole | $txtZWhole")
                if (kb == 1024) worstOneMb = maxOf(binChop, txtChop, binZChop, txtZChop)
            }
            // Regression guard for O(n²) in any 1 MB/64-byte-chopped variant. Fixed paths assemble in
            // tens of ms; a quadratic took hundreds of ms locally and 40 s under load. 5 s cleanly
            // separates linear from quadratic without being flaky under CI contention.
            assertTrue(worstOneMb < 5_000, "1MB/64B assembly took ${worstOneMb}ms (expected <5s; O(n^2) regression?)")
        }

    /** Returns assembly time (ms) for a [sizeBytes] frame fed in [chop]-byte reads. */
    private suspend fun measure(
        sizeBytes: Int,
        chop: Int,
        text: Boolean,
        compressed: Boolean,
    ): Long {
        val transport = MockWebSocketTransport()
        val connection =
            if (compressed) {
                MockAutobahnHelpers.connectWithCompressionHandshake(transport, BinaryPassThroughCodec)
            } else {
                MockAutobahnHelpers.connectWithHandshake(transport, BinaryPassThroughCodec)
            }

        val payload = lowCompressibilityPayload(sizeBytes, text)
        val opcode = if (text) Opcode.Text else Opcode.Binary
        val frame =
            if (compressed) {
                val compressor = MockAutobahnHelpers.createCompressor()
                val f = MockAutobahnHelpers.buildServerCompressedFrame(payload, opcode, compressor)
                compressor.close()
                f
            } else {
                MockAutobahnHelpers.buildServerFrame(opcode, payload)
            }
        frame.resetForRead()

        val total = frame.remaining()
        var fed = 0
        while (fed < total) {
            val n = min(chop, total - fed)
            val slice = BufferFactory.Default.allocate(n)
            for (j in 0 until n) slice.writeByte(frame.readByte())
            // Leave slices in write mode — MockWebSocketTransport.read() calls resetForRead() itself;
            // resetting here first would empty them (limit=position=0).
            transport.enqueueRead(slice)
            fed += n
        }
        transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

        val mark = TimeSource.Monotonic.markNow()
        val msg = withTimeout(300.seconds) { connection.receive().first() }
        val elapsed = mark.elapsedNow().inWholeMilliseconds
        val ok = if (text) msg is WebSocketMessage.Text else msg is WebSocketMessage.Binary<*>
        check(ok) { "expected ${if (text) "Text" else "Binary"}, got $msg" }
        connection.close()
        return elapsed
    }

    /** Seeded low-compressibility payload; text = random printable ASCII (valid UTF-8), binary = random bytes. */
    private fun lowCompressibilityPayload(
        sizeBytes: Int,
        text: Boolean,
    ): ReadBuffer {
        val rng = Random(42)
        val buf = BufferFactory.Default.allocate(sizeBytes)
        if (text) {
            // printable ASCII 0x21..0x7E — 94 chars, ~6.5 bits/char, compresses to ~80% (stays large)
            val sb = StringBuilder(sizeBytes)
            for (i in 0 until sizeBytes) sb.append((0x21 + rng.nextInt(0x7E - 0x21)).toChar())
            buf.writeString(sb.toString(), Charset.UTF8)
        } else {
            for (i in 0 until sizeBytes) buf.writeByte(rng.nextInt(256).toByte())
        }
        buf.resetForRead()
        return buf
    }
}
