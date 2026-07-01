package com.ditchoom.websocket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

actual typealias TestRunResult = Any

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
internal actual fun runTestNoTimeSkipping(
    count: Int,
    timeout: Duration,
    block: suspend CoroutineScope.() -> Unit,
): TestRunResult =
    GlobalScope.promise {
        withTimeout(timeout) {
            block()
        }
    }

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
internal actual fun runStrictTest(
    timeout: Duration,
    block: suspend CoroutineScope.() -> Unit,
): TestRunResult =
    GlobalScope.promise {
        withTimeout(timeout) {
            block()
        }
    }
