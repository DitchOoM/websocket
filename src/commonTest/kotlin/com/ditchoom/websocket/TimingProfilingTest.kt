package com.ditchoom.websocket

import agentName
import autobahnHost
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.managed
import kotlinx.coroutines.flow.take
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Profiling test that measures individual phases of WebSocket compression echo.
 *
 * Run with:
 *   ./gradlew linuxX64Test --tests "*.TimingProfilingTest" -PintegrationTests
 *   ./gradlew jvmTest --tests "*.TimingProfilingTest" -PintegrationTests
 */
class TimingProfilingTest {
    /**
     * Phase 1: Measure pure compression/decompression speed (no network).
     * This isolates zlib performance from socket I/O and coroutine overhead.
     */
    @Test
    fun profileCompressionOnly() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val iterations = 1000
            val payloadSizes = listOf(64, 256, 1024, 4096, 16384, 65536)

            for (size in payloadSizes) {
                val text = buildString { repeat(size) { append('*') } }
                val compressor =
                    StreamingCompressor.create(
                        CompressionAlgorithm.Raw,
                        CompressionLevel.Default,
                        BufferFactory.Default,
                    )
                val decompressor =
                    StreamingDecompressor.create(
                        CompressionAlgorithm.Raw,
                        BufferFactory.Default,
                    )
                val decoder = StreamingStringDecoder()

                // Warmup
                repeat(10) {
                    val buf = BufferFactory.Default.allocate(size)
                    buf.writeString(text, Charset.UTF8)
                    buf.resetForRead()
                    val compressed = compressSync(buf, compressor)
                    compressor.reset()
                    val combined = combineChunks(compressed, BufferFactory.managed())
                    compressed.freeAll()
                    val decompressedStr = decompressToStringSync(combined, decompressor, decoder)
                    combined.freeIfNeeded()
                    decompressor.reset()
                    buf.freeIfNeeded()
                }

                var compressTotal = Duration.ZERO
                var decompressTotal = Duration.ZERO
                var stringEncodeTotal = Duration.ZERO
                var stringDecodeTotal = Duration.ZERO
                var bufferAllocTotal = Duration.ZERO

                repeat(iterations) {
                    // String encode
                    var mark = TimeSource.Monotonic.markNow()
                    val buf = BufferFactory.Default.allocate(size)
                    buf.writeString(text, Charset.UTF8)
                    buf.resetForRead()
                    stringEncodeTotal += mark.elapsedNow()

                    // Compress
                    mark = TimeSource.Monotonic.markNow()
                    val compressed = compressSync(buf, compressor)
                    compressor.reset()
                    compressTotal += mark.elapsedNow()

                    // Buffer combine
                    mark = TimeSource.Monotonic.markNow()
                    val combined = combineChunks(compressed, BufferFactory.managed())
                    compressed.freeAll()
                    bufferAllocTotal += mark.elapsedNow()

                    // Decompress to string (includes string decode)
                    mark = TimeSource.Monotonic.markNow()
                    val decompressedStr = decompressToStringSync(combined, decompressor, decoder)
                    decompressor.reset()
                    decompressTotal += mark.elapsedNow()

                    combined.freeIfNeeded()
                    buf.freeIfNeeded()
                }

                val total = compressTotal + decompressTotal + stringEncodeTotal + bufferAllocTotal
                println(
                    "PROFILE [${agentName()}] compress_only size=${size}B n=$iterations " +
                        "total=${total.inWholeMilliseconds}ms " +
                        "encode=${stringEncodeTotal.inWholeMilliseconds}ms " +
                        "compress=${compressTotal.inWholeMilliseconds}ms " +
                        "combine=${bufferAllocTotal.inWholeMilliseconds}ms " +
                        "decompress+decode=${decompressTotal.inWholeMilliseconds}ms",
                )

                compressor.close()
                decompressor.close()
            }
        }

    /**
     * Phase 2: Measure full echo round-trip against Autobahn with compression.
     * Breaks down: connect, write (compress+frame+socket), read (socket+decompress+string).
     */
    @Test
    fun profileAutobahnCompressedEcho() =
        runTestNoTimeSkipping(timeout = 120.seconds) {
            val count = 1000
            val connectionOptions =
                WebSocketConnectionOptions(
                    name = autobahnHost(),
                    port = 9001,
                    websocketEndpoint = "/runCase?case=302&agent=${agentName()}",
                    requestCompression = true,
                )
            val connectMark = TimeSource.Monotonic.markNow()
            val ws = connectForTest(connectionOptions, bufferFactory = BufferFactory.managed())

            var writeTotal = Duration.ZERO
            var readTotal = Duration.ZERO

            try {
                ws.receive().take(count).collect { msg ->
                    val text = (msg as WebSocketMessage.Text).value

                    // Measure write (includes compress + frame + socket write)
                    val writeMark = TimeSource.Monotonic.markNow()
                    ws.send(WebSocketMessage.Text(text))
                    writeTotal += writeMark.elapsedNow()
                }
            } catch (_: Exception) {
                // Server may close
            }

            val totalTime = connectMark.elapsedNow()

            // read time = total - write - connect overhead
            readTotal = totalTime - writeTotal

            println(
                "PROFILE [${agentName()}] autobahn_echo case=302 n=$count " +
                    "total=${totalTime.inWholeMilliseconds}ms " +
                    "write=${writeTotal.inWholeMilliseconds}ms " +
                    "read=${readTotal.inWholeMilliseconds}ms " +
                    "avg_write=${(writeTotal / count).inWholeMicroseconds}us " +
                    "avg_total=${(totalTime / count).inWholeMicroseconds}us",
            )

            try {
                ws.close()
            } catch (_: Exception) {
            }
        }

    /**
     * Phase 3: Measure full echo WITHOUT compression for comparison.
     * Uses Autobahn case 1 (echo without compression).
     */
    @Test
    fun profileAutobahnUncompressedEcho() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val count = 100 // case1 uses fewer messages
            val connectionOptions =
                WebSocketConnectionOptions(
                    name = autobahnHost(),
                    port = 9001,
                    websocketEndpoint = "/runCase?case=302&agent=${agentName()}",
                    requestCompression = false, // NO compression
                )
            val connectMark = TimeSource.Monotonic.markNow()
            val ws = connectForTest(connectionOptions, bufferFactory = BufferFactory.managed())

            var writeTotal = Duration.ZERO

            try {
                ws.receive().take(count).collect { msg ->
                    val text = (msg as WebSocketMessage.Text).value

                    val writeMark = TimeSource.Monotonic.markNow()
                    ws.send(WebSocketMessage.Text(text))
                    writeTotal += writeMark.elapsedNow()
                }
            } catch (_: Exception) {
            }

            val totalTime = connectMark.elapsedNow()

            println(
                "PROFILE [${agentName()}] autobahn_echo_nocompress case=302 n=$count " +
                    "total=${totalTime.inWholeMilliseconds}ms " +
                    "write=${writeTotal.inWholeMilliseconds}ms " +
                    "read=${(totalTime - writeTotal).inWholeMilliseconds}ms " +
                    "avg_write=${(writeTotal / count).inWholeMicroseconds}us " +
                    "avg_total=${(totalTime / count).inWholeMicroseconds}us",
            )

            try {
                ws.close()
            } catch (_: Exception) {
            }
        }

    /**
     * Phase 4: Measure buffer allocation overhead (malloc/free churn).
     */
    @Test
    fun profileBufferAllocation() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val iterations = 10000
            val sizes = listOf(128, 1024, 4096, 32768, 65536)

            for (size in sizes) {
                // Direct (NativeBuffer = malloc/free on Linux)
                val directMark = TimeSource.Monotonic.markNow()
                repeat(iterations) {
                    val buf = BufferFactory.Default.allocate(size)
                    buf.freeIfNeeded()
                }
                val directTime = directMark.elapsedNow()

                // Heap (ByteArrayBuffer = managed allocation)
                val heapMark = TimeSource.Monotonic.markNow()
                repeat(iterations) {
                    val buf = BufferFactory.managed().allocate(size)
                    buf.freeIfNeeded()
                }
                val heapTime = heapMark.elapsedNow()

                println(
                    "PROFILE [${agentName()}] alloc size=${size}B n=$iterations " +
                        "direct=${directTime.inWholeMilliseconds}ms " +
                        "heap=${heapTime.inWholeMilliseconds}ms " +
                        "ratio=${if (heapTime.inWholeMilliseconds > 0) directTime.inWholeMilliseconds * 100 / heapTime.inWholeMilliseconds else 0}%",
                )
            }
        }
}
