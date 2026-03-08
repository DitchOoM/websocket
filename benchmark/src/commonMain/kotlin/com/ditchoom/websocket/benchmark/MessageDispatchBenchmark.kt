package com.ditchoom.websocket.benchmark

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.websocket.WebSocketMessage
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking

/**
 * Benchmarks for WebSocket message dispatch patterns.
 *
 * Compares the overhead of different ways to consume messages from WebSocket:
 * 1. Raw Channel trySend/tryReceive (baseline)
 * 2. Flow filterIsInstance().take(1).first() (current MQTT pattern)
 * 3. Flow first{} with predicate (simpler single-operator alternative)
 * 4. Typed Channel receive (new typed channel approach)
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class MessageDispatchBenchmark {
    private lateinit var binaryPayload: PlatformBuffer
    private lateinit var textPayload: String

    @Setup
    fun setup() {
        binaryPayload = PlatformBuffer.allocate(128).apply {
            repeat(128) { writeByte(it.toByte()) }
            resetForRead()
        }
        textPayload = "Hello, WebSocket benchmark!"
    }

    /**
     * Baseline: raw Channel trySend + tryReceive.
     * Minimum possible overhead for message passing.
     */
    @Benchmark
    fun channelTrySendReceive(bh: Blackhole) {
        val channel = Channel<WebSocketMessage>(Channel.UNLIMITED)
        channel.trySend(WebSocketMessage.Binary(binaryPayload))
        val result = channel.tryReceive()
        bh.consume(result.getOrNull())
    }

    /**
     * Current MQTT pattern: filterIsInstance().take(1).first()
     * Creates 3 Flow operator wrappers per message read.
     */
    @Benchmark
    fun flowFilterTakeFirst(bh: Blackhole) {
        val channel = Channel<WebSocketMessage>(Channel.UNLIMITED)
        channel.trySend(WebSocketMessage.Binary(binaryPayload))
        val result = runBlocking {
            channel.receiveAsFlow()
                .filterIsInstance<WebSocketMessage.Binary>()
                .take(1)
                .first()
        }
        bh.consume(result)
    }

    /**
     * Simpler single-operator alternative: flow.first { predicate }.
     * 1 operator instead of 3.
     */
    @Benchmark
    fun flowFirstWithPredicate(bh: Blackhole) {
        val channel = Channel<WebSocketMessage>(Channel.UNLIMITED)
        channel.trySend(WebSocketMessage.Binary(binaryPayload))
        val result = runBlocking {
            channel.receiveAsFlow().first { it is WebSocketMessage.Binary }
        }
        bh.consume(result)
    }

    /**
     * New typed channel approach: separate Channel<ReadBuffer> for binary messages.
     * No Flow operators, just direct channel receive.
     */
    @Benchmark
    fun typedChannelReceive(bh: Blackhole) {
        val typedChannel = Channel<ReadBuffer>(Channel.UNLIMITED)
        typedChannel.trySend(binaryPayload)
        val result = runBlocking {
            typedChannel.receiveAsFlow().first()
        }
        bh.consume(result)
    }

    /**
     * Typed channel with trySend/tryReceive (no suspend, no Flow).
     * Theoretical minimum for typed dispatch.
     */
    @Benchmark
    fun typedChannelDirect(bh: Blackhole) {
        val typedChannel = Channel<ReadBuffer>(Channel.UNLIMITED)
        typedChannel.trySend(binaryPayload)
        val result = typedChannel.tryReceive()
        bh.consume(result.getOrNull())
    }

    /**
     * Text message path: typed Channel<String> vs filtering.
     */
    @Benchmark
    fun textFilterTakeFirst(bh: Blackhole) {
        val channel = Channel<WebSocketMessage>(Channel.UNLIMITED)
        channel.trySend(WebSocketMessage.Text(textPayload))
        val result = runBlocking {
            channel.receiveAsFlow()
                .filterIsInstance<WebSocketMessage.Text>()
                .take(1)
                .first()
        }
        bh.consume(result)
    }

    @Benchmark
    fun textTypedChannelDirect(bh: Blackhole) {
        val typedChannel = Channel<String>(Channel.UNLIMITED)
        typedChannel.trySend(textPayload)
        val result = typedChannel.tryReceive()
        bh.consume(result.getOrNull())
    }
}
