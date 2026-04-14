package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.shared
import com.ditchoom.websocket.codecs.StringCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Allocator-correctness regression tests for the receive path.
 *
 * These tests run entirely against [MockWebSocketTransport] — no real network, no
 * fuzzingserver, no stress workload. They detect buffer leaks deterministically via
 * [BufferPool.stats] in milliseconds, and parameterize over every [BufferFactory] variant
 * so we catch "this factor variant leaks" as cleanly as "this test class leaks".
 *
 * The scenario: receive N frames of increasing payload size, plus a close. After the run,
 * assert that the pool's reuse rate is high (most acquires hit the pool rather than
 * allocating fresh). A leak — where the library forgets to call `freeIfNeeded()` on a
 * pooled buffer — manifests as `poolHits ≈ 0` because nothing is ever returned for reuse.
 *
 * Regression captured by this file:
 * - `WebSocketCodec.readFrame` used to leak the raw frame buffer returned by
 *   `stream.readBuffer(totalFrameSize)` on every received frame. Under stress this
 *   exhausted the JVM's direct-memory budget even with a deterministic factory. The
 *   try/finally fix closed the leak; these tests lock it in.
 */
abstract class AbstractAllocatorLeakTest {
    /** Underlying allocator the pool wraps. Each variant parameterizes over this. */
    abstract val backingFactory: BufferFactory

    /** Human-readable variant name for test output. */
    abstract val variantName: String

    /**
     * Runs [frameCount] receives through a fresh `MockWebSocketTransport`-backed
     * connection and asserts the pool's hit rate stays high.
     */
    private fun runLeakScenario(
        frameCount: Int,
        payloadSize: Int,
    ) = runStrictTest(timeout = 30.seconds) {
        val pool =
            BufferPool(
                maxPoolSize = 64,
                defaultBufferSize = 8192,
                factory = backingFactory,
            )
        val transport = MockWebSocketTransport()
        val connection = MockAutobahnHelpers.connectWithHandshake(transport, StringCodec, bufferFactory = pool)

        val text = "A".repeat(payloadSize)
        repeat(frameCount) {
            transport.enqueueRead(MockAutobahnHelpers.buildServerTextFrame(text))
        }
        transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

        withTimeout(20.seconds) {
            repeat(frameCount) {
                val msg = connection.receive().first()
                assertIs<WebSocketMessage.Text<String>>(msg, "frame $it not Text on $variantName")
            }
        }
        connection.close()

        val stats = pool.stats()
        // A working receive path produces high pool reuse: most acquires should hit the
        // pool (buffers returned via freeIfNeeded are reused on the next acquire).
        // A leak manifests as poolHits ≪ totalAllocations because the caller never
        // released the buffer, so the pool never gets a chance to offer it back.
        //
        // Tolerance: allow up to 2× maxPoolSize worth of misses to account for startup
        // warm-up, mixed allocation sizes (headers, handshake, payload), and control
        // frames. For frameCount=100, misses>128 indicates a true linear leak.
        val misses = stats.poolMisses
        val totalAllocs = stats.totalAllocations
        val missCeiling = 2L * 64 // 2 × maxPoolSize
        assertTrue(
            misses <= missCeiling,
            "[$variantName] poolMisses=$misses exceeds ceiling $missCeiling " +
                "(totalAllocs=$totalAllocs, poolHits=${stats.poolHits}). " +
                "High miss rate indicates a buffer leak — frames are acquiring fresh " +
                "allocations because freed buffers aren't returning to the pool.",
        )
    }

    @Test fun noLeakSmallFrames() = runLeakScenario(frameCount = 100, payloadSize = 64)

    @Test fun noLeakMediumFrames() = runLeakScenario(frameCount = 100, payloadSize = 4096)

    @Test fun noLeakLargeFrames() = runLeakScenario(frameCount = 50, payloadSize = 65535)

    /**
     * Direct validation of the exact regression that motivated this file:
     * `stream.readBuffer(totalFrameSize)` returning a pooled buffer that the codec
     * never freed. The earlier `noLeak*` tests catch it via high miss rate; this test
     * fails fast with a concrete assertion so the failure message names the bug.
     */
    @Test
    fun readFramePathReturnsBufferToPool() = runStrictTest(timeout = 30.seconds) {
        val pool =
            BufferPool(
                maxPoolSize = 4, // tight pool amplifies leak detection — every few leaks = ceiling exceeded
                defaultBufferSize = 8192,
                factory = backingFactory,
            )
        val transport = MockWebSocketTransport()
        val connection = MockAutobahnHelpers.connectWithHandshake(transport, StringCodec, bufferFactory = pool)

        val frameCount = 50
        val text = "A".repeat(512)
        repeat(frameCount) {
            transport.enqueueRead(MockAutobahnHelpers.buildServerTextFrame(text))
        }
        transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

        withTimeout(20.seconds) {
            repeat(frameCount) { connection.receive().first() }
        }
        connection.close()

        val stats = pool.stats()
        val leaked = stats.poolMisses
        if (leaked > 16) { // generous — handshake + a few control-frame allocations
            fail(
                "[$variantName] frame buffer leak regression: $leaked pool misses over $frameCount frames. " +
                    "WebSocketCodec.readFrame must free the buffer returned by stream.readBuffer(). " +
                    "See the try/finally block around WsFrameCodec.decode.",
            )
        }
    }
}

class AllocatorLeakDefaultTest : AbstractAllocatorLeakTest() {
    override val backingFactory: BufferFactory = BufferFactory.Default
    override val variantName: String = "Default"
}

class AllocatorLeakManagedTest : AbstractAllocatorLeakTest() {
    override val backingFactory: BufferFactory = BufferFactory.managed()
    override val variantName: String = "Managed"
}

class AllocatorLeakDeterministicTest : AbstractAllocatorLeakTest() {
    override val backingFactory: BufferFactory = BufferFactory.deterministic()
    override val variantName: String = "Deterministic"
}

class AllocatorLeakSharedTest : AbstractAllocatorLeakTest() {
    override val backingFactory: BufferFactory = BufferFactory.shared()
    override val variantName: String = "Shared"
}
