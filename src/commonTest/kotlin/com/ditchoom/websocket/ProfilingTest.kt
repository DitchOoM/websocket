package com.ditchoom.websocket

import agentName
import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.websocket.frame.FrameWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// Memory measurement helpers - platform-specific implementations

internal expect fun forceGc()

internal expect fun getUsedMemoryMB(): Long

/**
 * Profiling test to identify WebSocket performance bottlenecks across platforms.
 * Measures connection time, frame serialization, socket I/O, and round-trip echo.
 *
 * Run with: ./gradlew jvmTest --tests "*.ProfilingTest" -PintegrationTests
 *           ./gradlew macosArm64Test --tests "*.ProfilingTest" -PintegrationTests
 *           ./gradlew jsNodeTest --tests "*.ProfilingTest" -PintegrationTests
 */
class ProfilingTest {
    @Test
    fun profileMemoryUsageWithLargePayloads() =
        runTestNoTimeSkipping {
            // This test measures memory usage patterns for large message echoing.
            // Autobahn cases 9.5.x/9.6.x use 1MB payloads with compression.
            val payloadSize = 1024 * 1024 // 1MB
            val iterations = 10

            val connectionOptions =
                WebSocketConnectionOptions(
                    name = "localhost",
                    port = 8081,
                    websocketEndpoint = "/echo",
                )
            val ws = WebSocketClient.allocate(connectionOptions, allocationZone = AllocationZone.Heap)
            ws.connect()
            ws.awaitConnected()

            // Force GC before measurement
            forceGc()
            val memBefore = getUsedMemoryMB()

            repeat(iterations) { i ->
                val buf = PlatformBuffer.allocate(payloadSize, AllocationZone.Heap)
                repeat(payloadSize) { buf.writeByte(0xAB.toByte()) }
                buf.position(0)
                ws.write(buf)
                ws.incomingMessages.first()

                if (i == iterations / 2) {
                    forceGc()
                    val memMid = getUsedMemoryMB()
                    println("PROFILE [${agentName()}] memory_mid: ${memMid}MB (delta: ${memMid - memBefore}MB) after $i iterations")
                }
            }

            forceGc()
            val memAfter = getUsedMemoryMB()

            ws.close()
            withTimeoutOrNull(10.seconds) {
                ws.connectionState.first { it is ConnectionState.Disconnected }
            }

            println(
                "PROFILE [${agentName()}] memory_large_payload: " +
                    "payload=${payloadSize / 1024}KB n=$iterations " +
                    "before=${memBefore}MB after=${memAfter}MB delta=${memAfter - memBefore}MB",
            )
        }

    @Test
    fun profileConnectionEstablishment() =
        runTestNoTimeSkipping {
            val iterations = 10
            val times = mutableListOf<Long>()

            repeat(iterations) {
                val mark = TimeSource.Monotonic.markNow()
                val connectionOptions =
                    WebSocketConnectionOptions(
                        name = "localhost",
                        port = 8081,
                        websocketEndpoint = "/echo",
                    )
                val ws = WebSocketClient.allocate(connectionOptions, allocationZone = AllocationZone.Direct)
                ws.connect()
                ws.awaitConnected()
                times.add(mark.elapsedNow().inWholeMilliseconds)
                ws.close()
                withTimeoutOrNull(10.seconds) {
                    ws.connectionState.first { it is ConnectionState.Disconnected }
                }
            }
            println(
                "PROFILE [${agentName()}] connect: avg=${times.average().toLong()}ms min=${times.min()}ms max=${times.max()}ms (n=$iterations)",
            )
        }

    @Test
    fun profileFrameSerializationSmall() =
        runTestNoTimeSkipping {
            profileSerialization(125, 1000)
        }

    @Test
    fun profileFrameSerializationMedium() =
        runTestNoTimeSkipping {
            profileSerialization(4096, 500)
        }

    @Test
    fun profileFrameSerializationLarge() =
        runTestNoTimeSkipping {
            profileSerialization(65536, 100)
        }

    @Test
    fun profileEchoSmallText() =
        runTestNoTimeSkipping {
            profileEchoRoundTrip("small_text", 125, isBinary = false, iterations = 50)
        }

    @Test
    fun profileEchoMediumText() =
        runTestNoTimeSkipping {
            profileEchoRoundTrip("medium_text", 4096, isBinary = false, iterations = 20)
        }

    @Test
    fun profileEchoLargeText() =
        runTestNoTimeSkipping {
            profileEchoRoundTrip("large_text", 65536, isBinary = false, iterations = 10)
        }

    @Test
    fun profileEchoSmallBinary() =
        runTestNoTimeSkipping {
            profileEchoRoundTrip("small_binary", 125, isBinary = true, iterations = 50)
        }

    @Test
    fun profileEchoMediumBinary() =
        runTestNoTimeSkipping {
            profileEchoRoundTrip("medium_binary", 4096, isBinary = true, iterations = 20)
        }

    @Test
    fun profileEchoLargeBinary() =
        runTestNoTimeSkipping {
            profileEchoRoundTrip("large_binary", 65536, isBinary = true, iterations = 10)
        }

    private suspend fun profileSerialization(
        size: Int,
        iterations: Int,
    ) {
        val pool = BufferPool(allocationZone = AllocationZone.Heap)
        val writer = FrameWriter(clientMode = true, pool = pool)
        val payload = PlatformBuffer.allocate(size)
        repeat(size) { payload.writeByte(0x42) }
        payload.position(0)

        // Warmup
        repeat(10) {
            writer.writeBinaryFrame(payload)
            payload.position(0)
        }

        val mark = TimeSource.Monotonic.markNow()
        repeat(iterations) {
            writer.writeBinaryFrame(payload)
            payload.position(0)
        }
        val elapsed = mark.elapsedNow()
        val avgUs = elapsed.inWholeMicroseconds / iterations
        val throughputKBs =
            if (elapsed.inWholeMilliseconds > 0) {
                (size.toLong() * iterations) / elapsed.inWholeMilliseconds
            } else {
                0L
            }
        println(
            "PROFILE [${agentName()}] serialize: size=${size}B n=$iterations total=${elapsed.inWholeMilliseconds}ms avg=${avgUs}us throughput=${throughputKBs}KB/s",
        )
    }

    private suspend fun profileEchoRoundTrip(
        label: String,
        payloadSize: Int,
        isBinary: Boolean,
        iterations: Int,
    ) {
        val zone = if (payloadSize > 16384) AllocationZone.Heap else AllocationZone.Direct

        // Connect (with independent scope - null parentScope)
        val connectMark = TimeSource.Monotonic.markNow()
        val connectionOptions =
            WebSocketConnectionOptions(
                name = "localhost",
                port = 8081,
                websocketEndpoint = "/echo",
            )
        val ws = WebSocketClient.allocate(connectionOptions, allocationZone = zone)
        ws.connect()
        ws.awaitConnected()
        val connectTime = connectMark.elapsedNow()

        // Echo loop - measure write and read separately
        val writeTimes = mutableListOf<Long>()
        val readTimes = mutableListOf<Long>()
        val roundTripTimes = mutableListOf<Long>()

        val textPayload = if (!isBinary) buildString { repeat(payloadSize) { append('X') } } else ""

        repeat(iterations) {
            val rtMark = TimeSource.Monotonic.markNow()

            // Write
            val wMark = TimeSource.Monotonic.markNow()
            if (isBinary) {
                val buf = PlatformBuffer.allocate(payloadSize, zone)
                repeat(payloadSize) { buf.writeByte(0xAB.toByte()) }
                buf.position(0)
                ws.write(buf)
            } else {
                ws.write(textPayload)
            }
            writeTimes.add(wMark.elapsedNow().inWholeMicroseconds)

            // Read echo
            val rMark = TimeSource.Monotonic.markNow()
            ws.incomingMessages.first()
            readTimes.add(rMark.elapsedNow().inWholeMicroseconds)

            roundTripTimes.add(rtMark.elapsedNow().inWholeMicroseconds)
        }

        ws.close()
        withTimeoutOrNull(10.seconds) {
            ws.connectionState.first { it is ConnectionState.Disconnected }
        }

        val totalRtMs = roundTripTimes.sum() / 1000
        val avgWriteUs = writeTimes.average().toLong()
        val avgReadUs = readTimes.average().toLong()
        val avgRtUs = roundTripTimes.average().toLong()
        val throughputKBs =
            if (totalRtMs > 0) {
                (payloadSize.toLong() * iterations * 2) / totalRtMs
            } else {
                0L
            }

        println(
            "PROFILE [${agentName()}] echo_$label: " +
                "payload=${payloadSize}B n=$iterations " +
                "connect=${connectTime.inWholeMilliseconds}ms " +
                "avg_write=${avgWriteUs}us avg_read=${avgReadUs}us avg_rt=${avgRtUs}us " +
                "total_rt=${totalRtMs}ms throughput=${throughputKBs}KB/s",
        )
    }
}
