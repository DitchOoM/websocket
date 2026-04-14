package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.shared
import kotlin.test.Test

/**
 * Sustained-load Autobahn stress tests, parameterized over [BufferFactory].
 *
 * The library itself takes a [BufferFactory] from [WebSocketConnectionOptions] and
 * never assumes a specific allocation strategy. These tests verify the library
 * behaves correctly — and survives sustained high-throughput workloads — under
 * each factory variant a real consumer might plug in.
 *
 * Coverage split:
 * - [AutobahnCase1PayloadTests] etc. — RFC 6455 wire conformance with the default factory.
 * - `MockAutobahn*` tests — library/codec behavior with each factory, no network.
 * - This class — sustained real-network throughput per factory variant.
 *
 * Each subclass reports to fuzzingserver under agent `<platform>-stress-<factoryName>`,
 * e.g. `JVM-stress-Pooled`, so per-factory results are partitioned in the autobahn
 * report. The Gradle `validateAutobahnResults*` tasks intentionally skip agents
 * containing `-stress-` — these tests pass as long as the JUnit assertion completes
 * without exception (no OOM, no timeout, no transport error).
 */
abstract class AbstractAutobahnStressTests {
    abstract val bufferFactory: BufferFactory

    /** Used as the suffix on the fuzzingserver agent name (`<platform>-stress-<factoryName>`). */
    abstract val factoryName: String

    private val agentSuffix: String get() = "-stress-$factoryName"

    /**
     * 1000-message text echo at varying message sizes (autobahn cases 9.7.x).
     * Exercises sustained allocation churn through the factory under compression.
     */
    @Test
    fun manyTextMessagesCompressed() =
        runHeavyCompressionTest {
            for (case in 289..294) {
                echoMessageAndClose(
                    case = case,
                    count = 1000,
                    requestCompression = true,
                    bufferFactory = bufferFactory,
                    agentSuffix = agentSuffix,
                )
            }
        }

    /**
     * 1000-message binary echo at varying message sizes (autobahn cases 9.8.x).
     * Mirror of [manyTextMessagesCompressed] for the binary encode path.
     */
    @Test
    fun manyBinaryMessagesCompressed() =
        runHeavyCompressionTest {
            for (case in 295..300) {
                echoBinaryMessageAndClose(
                    case = case,
                    count = 1000,
                    requestCompression = true,
                    bufferFactory = bufferFactory,
                    agentSuffix = agentSuffix,
                )
            }
        }

    /**
     * 1MB text payloads in varying chop sizes (autobahn cases 9.5.x).
     * Tests single large-message allocation through the factory. The Case 9.5.1 OOM
     * with default Android `largeHeap` was the original motivator for this class.
     */
    @Test
    fun largeTextChopped() =
        runHeavyCompressionTest {
            for (case in 277..282) {
                echoMessageAndClose(
                    case = case,
                    requestCompression = true,
                    bufferFactory = bufferFactory,
                    agentSuffix = agentSuffix,
                )
            }
        }
}

class AutobahnStressDefaultTests : AbstractAutobahnStressTests() {
    override val bufferFactory: BufferFactory = BufferFactory.Default
    override val factoryName: String = "Default"
}

class AutobahnStressManagedTests : AbstractAutobahnStressTests() {
    override val bufferFactory: BufferFactory = BufferFactory.managed()
    override val factoryName: String = "Managed"
}

class AutobahnStressDeterministicTests : AbstractAutobahnStressTests() {
    override val bufferFactory: BufferFactory = BufferFactory.deterministic()
    override val factoryName: String = "Deterministic"
}

class AutobahnStressSharedTests : AbstractAutobahnStressTests() {
    override val bufferFactory: BufferFactory = BufferFactory.shared()
    override val factoryName: String = "Shared"
}

class AutobahnStressPooledTests : AbstractAutobahnStressTests() {
    override val bufferFactory: BufferFactory = BufferPool(factory = BufferFactory.managed())
    override val factoryName: String = "Pooled"
}
