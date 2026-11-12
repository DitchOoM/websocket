package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.EndOfStreamException
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.websocket.codecs.EmptyCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Every way a [WebSocketCodec] stops being usable releases its transport and compression
 * resources exactly once, without the caller having to reach `close()` on a live connection.
 */
@OptIn(ExperimentalAtomicApi::class)
class WebSocketCodecReleaseTest {
    /** Counts closes, and counts every use that overlaps a close or comes after one. */
    private class CountingCompressor(
        private val inner: StreamingCompressor,
        private val closes: AtomicInt,
        private val violations: AtomicInt,
    ) : StreamingCompressor by inner {
        private val running = AtomicInt(0)

        private fun <R> using(block: () -> R): R {
            running.addAndFetch(1)
            try {
                if (closes.load() > 0) violations.addAndFetch(1)
                return block()
            } finally {
                running.addAndFetch(-1)
            }
        }

        override fun compress(
            input: ReadBuffer,
            onOutput: (ReadBuffer) -> Unit,
        ) = using { inner.compress(input, onOutput) }

        override fun flush(onOutput: (ReadBuffer) -> Unit) = using { inner.flush(onOutput) }

        override fun finish(onOutput: (ReadBuffer) -> Unit) = using { inner.finish(onOutput) }

        override fun reset() = using { inner.reset() }

        override fun close() {
            if (running.load() > 0) violations.addAndFetch(1)
            closes.addAndFetch(1)
            inner.close()
        }
    }

    private class CountingDecompressor(
        private val inner: StreamingDecompressor,
        private val closes: AtomicInt,
    ) : StreamingDecompressor by inner {
        override fun close() {
            closes.addAndFetch(1)
            inner.close()
        }
    }

    private class Fixture(
        val transport: MockWebSocketTransport,
        val codec: WebSocketCodec<Unit>,
        private val compressorCloses: AtomicInt,
        private val decompressorCloses: AtomicInt,
        private val compressorViolations: AtomicInt,
    ) {
        /** Uses of the compressor that overlapped its close or followed it. */
        val compressorViolationCount: Int get() = compressorViolations.load()
        val compressorCloseCount: Int get() = compressorCloses.load()
        val decompressorCloseCount: Int get() = decompressorCloses.load()
    }

    private fun fixture(
        parentScope: CoroutineScope? = null,
        readTimeout: Duration = 15.seconds,
        peerAfterClose: PeerAfterClose = PeerAfterClose.AcksAndHangsUp,
    ): Fixture {
        val transport = MockWebSocketTransport(peerAfterClose)
        val compressorViolations = AtomicInt(0)
        val compressorCloses = AtomicInt(0)
        val decompressorCloses = AtomicInt(0)
        val compression =
            CompressionConfig.Enabled(
                decompressor = CountingDecompressor(StreamingDecompressor.create(CompressionAlgorithm.Raw), decompressorCloses),
                compressor =
                    CountingCompressor(
                        StreamingCompressor.create(CompressionAlgorithm.Raw),
                        compressorCloses,
                        compressorViolations,
                    ),
                serverNoContextTakeover = true,
                clientNoContextTakeover = true,
            )
        val pool = BufferPool(threadingMode = ThreadingMode.MultiThreaded, factory = BufferFactory.managed())
        val stream =
            StreamProcessor.builder(pool).buildSuspendingWithAutoFill { processor ->
                when (val result = transport.read(15.seconds)) {
                    is ReadResult.Data -> processor.append(result.buffer)
                    else -> throw EndOfStreamException()
                }
            }
        val codec =
            WebSocketCodec(
                transport = transport,
                stream = stream,
                compression = compression,
                bufferFactory = pool,
                binaryCodec = EmptyCodec,
                readTimeout = readTimeout,
                writeTimeout = 15.seconds,
                parentScope = parentScope,
                compressor = compression.compressor,
                compressionEnabled = true,
            )
        codec.startReadLoop()
        return Fixture(transport, codec, compressorCloses, decompressorCloses, compressorViolations)
    }

    /** Waits for the transport to be closed without anyone calling `close()` on the codec. */
    private suspend fun Fixture.awaitTransportClosed() {
        withTimeout(5.seconds) {
            while (transport.closeCount == 0) delay(10)
        }
    }

    private fun Fixture.assertReleasedOnce() {
        assertEquals(1, transport.closeCount, "transport.close() calls")
        assertEquals(1, compressorCloseCount, "compressor.close() calls")
        assertEquals(1, decompressorCloseCount, "decompressor.close() calls")
    }

    /** The connection ends by itself; then `close()` is a no-op that may not release a second time. */
    private suspend fun Fixture.assertReleasedByItself() {
        awaitTransportClosed()
        codec.close()
        codec.close()
        assertReleasedOnce()
    }

    @Test
    fun serverInitiatedCloseReleasesTransport() =
        runStrictTest {
            val f = fixture()
            f.transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u, "bye"))
            f.assertReleasedByItself()
        }

    @Test
    fun clientInitiatedCloseReleasesTransport() =
        runStrictTest {
            val f = fixture()
            f.codec.close()
            f.assertReleasedOnce()
        }

    @Test
    fun reservedOpcodeFrameReleasesTransport() =
        runStrictTest {
            val f = fixture()
            f.transport.enqueueReadBytes(0x83.toByte(), 0x00)
            f.assertReleasedByItself()
        }

    @Test
    fun messageAssemblyErrorReleasesTransport() =
        runStrictTest {
            val f = fixture()
            // A continuation frame with no message in progress.
            f.transport.enqueueRead(MockAutobahnHelpers.buildServerContinuationFrame(textPayload("orphan")))
            f.assertReleasedByItself()
        }

    @Test
    fun invalidUtf8TextMessageReleasesTransport() =
        runStrictTest {
            val f = fixture()
            f.transport.enqueueRead(MockAutobahnHelpers.buildServerFrame(Opcode.Text, rawPayload(0xFF, 0xFE, 0xFD)))
            f.assertReleasedByItself()
        }

    @Test
    fun invalidUtf8CloseReasonReleasesTransport() =
        runStrictTest {
            val f = fixture()
            f.transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrameRaw(rawPayload(0x03, 0xE8, 0xFF, 0xFE)))
            f.assertReleasedByItself()
        }

    @Test
    fun transportReadFailureReleasesTransport() =
        runStrictTest {
            val f = fixture()
            f.transport.enqueueReadError(Exception("connection reset"))
            f.assertReleasedByItself()
        }

    @Test
    fun transportEndOfStreamReleasesTransport() =
        runStrictTest {
            val f = fixture()
            f.transport.simulateDisconnect()
            f.assertReleasedByItself()
        }

    @Test
    fun transportWriteFailureReleasesTransport() =
        runStrictTest {
            val f = fixture()
            f.transport.failWrites = true
            f.codec.send(WebSocketMessage.Text("hello"))
            f.assertReleasedByItself()
        }

    @Test
    fun cancellingTheParentScopeReleasesTransport() =
        runStrictTest {
            val parent = CoroutineScope(Job())
            val f = fixture(parent)
            parent.cancel()
            f.assertReleasedByItself()
        }

    @Test
    fun cancellingTheParentScopeBeforeTheReadLoopStartsReleasesTransport() =
        runStrictTest {
            val parent = CoroutineScope(Job())
            parent.cancel()
            val f = fixture(parent)
            f.assertReleasedByItself()
            assertEquals(emptyList(), f.codec.receive().toList())
            assertEquals(0, f.transport.writtenBuffers.size, "nothing is written on a cancelled scope")
        }

    @Test
    fun closingTwiceReleasesOnce() =
        runStrictTest {
            val f = fixture()
            f.codec.close()
            f.codec.close()
            f.assertReleasedOnce()
        }

    @Test
    fun closingConcurrentlyWithTheReadLoopEndingReleasesOnce() =
        runStrictTest {
            repeat(20) {
                val f = fixture()
                f.transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))
                List(3) { async { f.codec.close() } }.awaitAll()
                f.assertReleasedOnce()
            }
        }

    @Test
    fun peerThatNeverHangsUpIsReleasedAtTheCloseBound() =
        runStrictTest {
            val bound = 1.seconds
            val f = fixture(readTimeout = bound, peerAfterClose = PeerAfterClose.Silent)
            val start = TimeSource.Monotonic.markNow()
            f.transport.enqueueReadBytes(0x83.toByte(), 0x00)
            f.awaitTransportClosed()
            val elapsed = start.elapsedNow()
            assertTrue(elapsed >= bound - 200.milliseconds, "released before the bound: $elapsed")
            assertTrue(elapsed < bound + 3.seconds, "released long after the bound: $elapsed")
            f.assertReleasedByItself()
        }

    @Test
    fun peerThatFloodsAfterAProtocolFailureIsReleasedAtTheByteCap() =
        runStrictTest {
            val f = fixture(peerAfterClose = PeerAfterClose.Silent)
            val start = TimeSource.Monotonic.markNow()
            f.transport.enqueueReadBytes(0x83.toByte(), 0x00)
            repeat(200) {
                val chunk = BufferFactory.managed().allocate(16 * 1024)
                repeat(16 * 1024) { chunk.writeByte(0) }
                f.transport.enqueueRead(chunk)
            }
            f.awaitTransportClosed()
            // The close-handshake bound is 5 s; hitting the byte cap first releases well before it.
            assertTrue(start.elapsedNow() < 3.seconds, "release waited for the time bound, not the byte cap")
            f.assertReleasedByItself()
        }

    @Test
    fun sendsRacingThePeerClosingNeverUseTheCompressorAfterItsRelease() =
        runStrictTest(timeout = 300.seconds) {
            val payload = WebSocketMessage.Text("A".repeat(2000))
            repeat(500) {
                val f = fixture()
                // One sender: a compressor is stateful, so sends on one connection are sequential.
                val sender =
                    async(Dispatchers.Default) {
                        repeat(200) {
                            try {
                                f.codec.send(payload)
                            } catch (_: WebSocketException.ConnectionClosed) {
                            }
                        }
                    }
                f.transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))
                sender.await()
                f.assertReleasedByItself()
                assertEquals(0, f.compressorViolationCount, "compressor used while or after it was closed")
            }
        }

    private fun textPayload(text: String): ReadBuffer {
        val buffer = BufferFactory.managed().allocate(text.length * 4)
        buffer.writeString(text, com.ditchoom.buffer.Charset.UTF8)
        buffer.resetForRead()
        return buffer
    }

    private fun rawPayload(vararg bytes: Int): ReadBuffer {
        val buffer = BufferFactory.managed().allocate(bytes.size)
        for (b in bytes) buffer.writeByte(b.toByte())
        buffer.resetForRead()
        return buffer
    }
}
