package com.ditchoom.websocket

import com.ditchoom.socket.NetworkCapabilities.FULL_SOCKET_ACCESS
import com.ditchoom.socket.NetworkCapabilities.WEBSOCKETS_ONLY
import com.ditchoom.socket.getNetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Timeout for individual tests (reduced from 60s to improve CI times)
private val testTimeout = 30.seconds

// Extended timeout for heavy compression tests (Linux native is slower)
private val heavyCompressionTimeout = 120.seconds

internal fun runTestNoTimeSkipping1(
    count: Int = 1,
    block: suspend CoroutineScope.() -> Unit,
) = runBlocking {
    try {
        withTimeout(testTimeout) {
            withContext(Dispatchers.Default) {
                block()
            }
        }
    } catch (e: UnsupportedOperationException) {
        when (getNetworkCapabilities()) {
            FULL_SOCKET_ACCESS -> throw e
            WEBSOCKETS_ONLY -> {} // ignore, expected on browsers
        }
    }
}

internal fun runTestNoTimeSkipping(
    count: Int = 1,
    timeout: Duration = testTimeout,
    block: suspend CoroutineScope.() -> Unit,
) = runBlocking {
    try {
        withTimeout(timeout) {
            withContext(Dispatchers.Default.limitedParallelism(count)) {
                block()
            }
        }
    } catch (e: UnsupportedOperationException) {
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
    block: suspend CoroutineScope.() -> Unit,
) = runTestNoTimeSkipping(count = count, timeout = heavyCompressionTimeout, block = block)

/**
 * Strict test helper with configurable timeout.
 * Uses Dispatchers.Default for real-time execution without time skipping.
 */
internal fun runStrictTest(
    timeout: Duration = 10.seconds,
    block: suspend CoroutineScope.() -> Unit,
) = runBlocking {
    withTimeout(timeout) {
        withContext(Dispatchers.Default) { block() }
    }
}
