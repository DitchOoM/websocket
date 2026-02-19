package com.ditchoom.websocket

import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

expect class TestRunResult

// Timeout for echo/connect tests. 60s accommodates tests with many sequential
// websocket connections on Apple K/N where each connection is slower than JVM.
internal val testTimeout = 60.seconds

// Extended timeout for heavy compression tests (6 cases x 1000 messages each per batch)
// Linux K/N compression is ~10x slower than JVM, and Cat 12 payloads reach 512KB per message
internal val heavyCompressionTimeout = 600.seconds

internal expect fun runTestNoTimeSkipping(
    count: Int = 1,
    timeout: Duration = testTimeout,
    block: suspend CoroutineScope.() -> Unit,
): TestRunResult

/**
 * Test helper for heavy compression tests that need extended timeout.
 */
internal fun runHeavyCompressionTest(
    count: Int = 1,
    block: suspend CoroutineScope.() -> Unit,
) = runTestNoTimeSkipping(count = count, timeout = heavyCompressionTimeout, block = block)

/**
 * Strict test helper with configurable timeout.
 * Uses Dispatchers.Default for real-time execution without time skipping.
 */
internal expect fun runStrictTest(
    timeout: Duration = 10.seconds,
    block: suspend CoroutineScope.() -> Unit,
): TestRunResult
