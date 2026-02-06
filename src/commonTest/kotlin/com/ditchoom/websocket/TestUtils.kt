@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.websocket

import com.ditchoom.socket.NetworkCapabilities.FULL_SOCKET_ACCESS
import com.ditchoom.socket.NetworkCapabilities.WEBSOCKETS_ONLY
import com.ditchoom.socket.getNetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Timeout for individual tests (reduced from 60s to improve CI times)
private val testTimeout = 30.seconds

// Extended timeout for heavy compression tests (Linux native is slower)
private val heavyCompressionTimeout = 120.seconds

internal fun runTestNoTimeSkipping1(
    count: Int = 1,
    block: suspend TestScope.() -> Unit,
) = runTest(timeout = testTimeout) {
    try {
        withContext(Dispatchers.Default) {
            block()
        }
    } catch (e: UnsupportedOperationException) {
        // ignore
        when (getNetworkCapabilities()) {
            FULL_SOCKET_ACCESS -> throw e
            WEBSOCKETS_ONLY -> {} // ignore, expected on browsers
        }
    }
}

internal fun runTestNoTimeSkipping(
    count: Int = 1,
    timeout: Duration = testTimeout,
    block: suspend TestScope.() -> Unit,
) = runTest(timeout = timeout) {
    try {
        withContext(Dispatchers.Default.limitedParallelism(count)) {
            block()
        }
    } catch (e: UnsupportedOperationException) {
        // ignore
        when (getNetworkCapabilities()) {
            FULL_SOCKET_ACCESS -> throw e
            WEBSOCKETS_ONLY -> {} // ignore, expected on browsers
        }
    }
}

/**
 * Test helper for heavy compression tests that need extended timeout.
 * Linux native compression is slower than JVM, so use 120s timeout.
 */
internal fun runHeavyCompressionTest(
    count: Int = 1,
    block: suspend TestScope.() -> Unit,
) = runTestNoTimeSkipping(count = count, timeout = heavyCompressionTimeout, block = block)

/**
 * Strict test helper with configurable timeout.
 * Uses Dispatchers.Default for real-time execution without time skipping.
 */
internal fun runStrictTest(
    timeout: Duration = 10.seconds,
    block: suspend TestScope.() -> Unit,
) = runTest(timeout = timeout) {
    withContext(Dispatchers.Default) { block() }
}
