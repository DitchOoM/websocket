package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

/**
 * Regression guard for the **nested-pool cross-pool release crash** (buffer 6.8.1) that only
 * surfaced on Android CI (#19 run 28836783215, ~case 63/454):
 *
 * ```
 * java.lang.IllegalArgumentException:
 *   Cannot release a buffer to a different pool than the one it was acquired from
 *     LockFreeBufferPool.release(LockFreeBufferPool.kt:107)
 *     PooledBuffer.freeNativeMemory(PooledBuffer.kt)
 *     DefaultStreamProcessor.freeConsumedChunk(BufferStream.kt)
 *     DefaultStreamProcessor.release(BufferStream.kt)
 *     WebSocketCodec.startReadLoop(WebSocketCodec.kt:176)   // finally { stream.release() }
 * ```
 *
 * ## Root cause — nested pooling
 * The socket transport's [com.ditchoom.socket.ReadBufferSource] allocates every receive buffer from
 * `BufferPool(threadingMode = MultiThreaded, factory = config.bufferFactory)`. When a consumer passes
 * a *shared [BufferPool]* as its `bufferFactory` (the Android config, so one pool is reused across
 * every connection), that transport pool used to **nest** the websocket pool:
 *
 * ```
 * PooledBuffer(inner = PooledBuffer(pool = wsPool), pool = transportPool)
 * ```
 *
 * Freeing a consumed chunk then ran `transportPool.release(inner)` where `inner.pool === wsPool ≠
 * transportPool`, tripping `LockFreeBufferPool.release`'s `require(buffer.pool === this)`.
 *
 * ## The fix these tests guard
 * `BufferPool(factory = x)` now **collapses** when `x` is itself a pool — it reuses the existing pool
 * instead of wrapping it (`(factory as? BufferPool) ?: construct`). So the illegal nested topology is
 * unrepresentable: `BufferPool(MultiThreaded, factory = wsPool) === wsPool`, and every buffer is freed
 * to the pool that owns it. These tests originally *asserted the crash* against buffer 6.8.1; they are
 * now inverted to assert the collapse holds and the same read paths complete without a cross-pool throw.
 */
class CrossPoolReleaseReproTest {
    private val options =
        WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            tls = false,
            websocketEndpoint = "/ws",
        )

    /** Allocates a buffer from [pool] and writes [bytes] into it (left in write mode; the mock
     *  transport's `read()` calls `resetForRead()` before delivery). Modelling a real socket
     *  `readRaw` filling a [com.ditchoom.socket.ReadBufferSource] buffer with wire bytes. */
    private fun poolBuffer(
        pool: BufferPool,
        bytes: ByteArray,
    ): PlatformBuffer {
        val b = pool.acquire(bytes.size) as PlatformBuffer
        b.writeBytes(bytes)
        return b
    }

    private fun ReadBuffer.snapshotBytes(): ByteArray {
        resetForRead()
        return ByteArray(remaining()) { readByte() }
    }

    /** True if [t] or any of its causes is the cross-pool `IllegalArgumentException`. */
    private fun isCrossPoolCause(t: Throwable?): Boolean =
        generateSequence(t) { it.cause }
            .any { it is IllegalArgumentException && (it.message ?: "").contains("different pool") }

    /**
     * The transport read pool built from a consumer's shared pool collapses to that same pool, so the
     * 101 handshake response delivered "through the transport pool" is freed to its owner during
     * `connectWebSocket`'s sub-4 KiB read compaction — no cross-pool crash — and the connect succeeds.
     * (Against buffer 6.8.1 this surfaced as `WebSocketException.TransportFailed`.)
     */
    @Test
    fun nestedTransportPoolCollapsesSoRealConnectSucceeds() =
        runStrictTest(timeout = 20.seconds) {
            val wsPool =
                BufferPool(
                    threadingMode = ThreadingMode.MultiThreaded,
                    maxPoolSize = 64,
                    defaultBufferSize = 8192,
                    factory = BufferFactory.deterministic(),
                )
            // Models socket ReadBufferSource: BufferPool(MultiThreaded, factory = config.bufferFactory).
            // The collapse fix makes this reuse wsPool rather than nest it.
            val transportPool =
                BufferPool(
                    threadingMode = ThreadingMode.MultiThreaded,
                    maxPoolSize = 64,
                    defaultBufferSize = 8192,
                    factory = wsPool,
                )
            assertSame(
                wsPool,
                transportPool,
                "BufferPool(factory = pool) must collapse to the existing pool, not nest it",
            )
            val transport = MockWebSocketTransport()

            val connection =
                coroutineScope {
                    val job =
                        async {
                            connectWebSocket(transport, options.copy(bufferFactory = wsPool))
                        }
                    MockAutobahnHelpers.waitForWrite(transport)
                    val clientKey = MockHandshakeHelper.extractClientKey(transport.writtenBuffers[0])
                    val respBytes = MockHandshakeHelper.buildHandshakeResponse(clientKey).snapshotBytes()
                    // Deliver the handshake response via the (collapsed) transport pool.
                    transport.enqueueRead(poolBuffer(transportPool, respBytes))
                    withTimeout(10.seconds) { job.await() }
                }

            assertNotNull(connection, "connectWebSocket should establish once nested pools collapse")
            runCatching { connection.close() }
        }

    /**
     * Full read-loop guard hitting the exact former crash site (`stream.release()` in
     * `startReadLoop`'s `finally`). The handshake succeeds, then a large (> 4 KiB, bypassing compaction)
     * *incomplete* frame is delivered from the collapsed transport pool and left in the stream's chunk
     * deque. `close()` cancels the read loop; the `finally` runs `stream.release()`, which frees that
     * chunk to its owning pool — no cross-pool throw. A concurrent send loop runs the shared pool from a
     * second thread, mirroring Android's live traffic.
     *
     * The read-loop coroutine's exceptions are captured via a [CoroutineExceptionHandler] on the parent
     * scope; the test asserts none of them is the cross-pool `IllegalArgumentException`.
     */
    @Test
    fun streamReleaseUnderConcurrentSendReadDoesNotThrowCrossPool() =
        runStrictTest(timeout = 20.seconds) {
            val wsPool =
                BufferPool(
                    threadingMode = ThreadingMode.MultiThreaded,
                    maxPoolSize = 64,
                    defaultBufferSize = 8192,
                    factory = BufferFactory.deterministic(),
                )
            val transportPool =
                BufferPool(
                    threadingMode = ThreadingMode.MultiThreaded,
                    maxPoolSize = 64,
                    defaultBufferSize = 8192,
                    factory = wsPool,
                )
            assertSame(
                wsPool,
                transportPool,
                "BufferPool(factory = pool) must collapse to the existing pool, not nest it",
            )
            val transport = MockWebSocketTransport()

            val readLoopFailure = CompletableDeferred<Throwable>()
            val handler =
                CoroutineExceptionHandler { _, e ->
                    readLoopFailure.complete(e)
                }
            val parentJob = Job()
            val parentScope = CoroutineScope(Dispatchers.Default + parentJob + handler)

            val connection =
                coroutineScope {
                    val job =
                        async {
                            connectWebSocket(
                                transport,
                                options.copy(bufferFactory = wsPool),
                                parentScope = parentScope,
                            )
                        }
                    MockAutobahnHelpers.waitForWrite(transport)
                    val clientKey = MockHandshakeHelper.extractClientKey(transport.writtenBuffers[0])
                    // Handshake plain (Default) so the connection establishes successfully.
                    transport.enqueueRead(MockHandshakeHelper.buildHandshakeResponse(clientKey))
                    withTimeout(10.seconds) { job.await() }
                }

            // Concurrent send traffic on the shared pool (mirrors Android's live echo load).
            val sender =
                parentScope.launch {
                    repeat(50) {
                        runCatching { connection.send(WebSocketMessage.Text("x".repeat(200))) }
                    }
                }

            // A large (> COMPACT_READ_THRESHOLD = 4096) *incomplete* frame: header declares a 65 535-byte
            // payload but only ~5 000 bytes follow, so readNextFrame appends the whole chunk to the stream
            // and then waits for more data that never arrives. The chunk stays in the deque until
            // stream.release() — the former cross-pool crash site.
            val header = byteArrayOf(0x82.toByte(), 0x7E, 0xFF.toByte(), 0xFF.toByte())
            val body = ByteArray(5000) { 0x61 }
            transport.enqueueRead(poolBuffer(transportPool, header + body))

            // Give the read loop a moment to append the chunk, then close (cancels the loop → finally →
            // stream.release()).
            withTimeout(5.seconds) {
                while (transport.writtenBuffers.size < 1) kotlinx.coroutines.delay(5)
            }
            kotlinx.coroutines.delay(100)
            sender.cancel()
            runCatching { connection.close() }

            // With the collapse fix the read loop unwinds cleanly: no cross-pool throwable should reach the
            // handler. Wait a bounded window; if anything surfaces, it must not be the cross-pool error.
            val failure = withTimeoutOrNull(3.seconds) { readLoopFailure.await() }
            parentJob.cancel()

            assertFalse(
                isCrossPoolCause(failure),
                "stream.release() must not throw cross-pool after the nested-pool collapse, got: $failure",
            )
        }
}
