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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Every way a [WebSocketCodec] stops being usable releases its transport and compression
 * resources exactly once, without the caller having to reach `close()` on a live connection.
 */
@OptIn(ExperimentalAtomicApi::class)
class WebSocketCodecReleaseTest {
    private class CountingCompressor(
        private val inner: StreamingCompressor,
        private val closes: AtomicInt,
    ) : StreamingCompressor by inner {
        override fun close() {
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
    ) {
        val compressorCloseCount: Int get() = compressorCloses.load()
        val decompressorCloseCount: Int get() = decompressorCloses.load()
    }

    private fun fixture(parentScope: CoroutineScope? = null): Fixture {
        val transport = MockWebSocketTransport()
        val compressorCloses = AtomicInt(0)
        val decompressorCloses = AtomicInt(0)
        val compression =
            CompressionConfig.Enabled(
                decompressor = CountingDecompressor(StreamingDecompressor.create(CompressionAlgorithm.Raw), decompressorCloses),
                compressor = CountingCompressor(StreamingCompressor.create(CompressionAlgorithm.Raw), compressorCloses),
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
                readTimeout = 15.seconds,
                writeTimeout = 15.seconds,
                parentScope = parentScope,
                compressor = compression.compressor,
                compressionEnabled = true,
            )
        codec.startReadLoop()
        return Fixture(transport, codec, compressorCloses, decompressorCloses)
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
