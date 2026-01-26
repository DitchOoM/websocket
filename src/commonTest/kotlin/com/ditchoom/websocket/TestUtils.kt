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
import kotlin.time.Duration.Companion.seconds

// Increase timeout for CI environments where tests may be slower
private val testTimeout = 60.seconds

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
    block: suspend TestScope.() -> Unit,
) = runTest(timeout = testTimeout) {
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
