package com.ditchoom.websocket

import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

expect class TestRunResult

/**
 * True on platforms with raw socket (TCP) access — JVM, Android, Apple, Linux, Node.js — and false
 * on browser (WebSocket-only), which must use the native `WebSocket` path.
 *
 * Must be `expect`/`actual`: socket v6's js `networkCapabilities()` advertises TCP unconditionally
 * (no Node-vs-browser split — browser-only capabilities are a separate wasmJs target this module
 * doesn't have), so a common `TransportKind.TCP in networkCapabilities()` check wrongly returns true
 * in the browser and routes tests into `TcpTransport().connect()`, which throws
 * `UnsupportedOperationException("Sockets are not supported in the browser")`. The js actual splits
 * on `isNodeJs` instead.
 */
internal expect fun hasFullSocketAccess(): Boolean

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
