package com.ditchoom.websocket

import com.ditchoom.socket.TransportKind
import com.ditchoom.socket.networkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

expect class TestRunResult

/**
 * True on platforms with raw socket (TCP) access — JVM, Android, Apple, Linux, Node.js — and false
 * on browser (WebSocket-only). Replaces the v3 `getNetworkCapabilities() == FULL_SOCKET_ACCESS`
 * check now that socket v6 models capabilities as a `Set<TransportKind>`.
 */
internal fun hasFullSocketAccess(): Boolean = TransportKind.TCP in networkCapabilities().transports

// Timeout for simple echo/connect tests (ping/pong, payload, UTF-8, close, fragmentation)
internal val testTimeout = 10.seconds

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
