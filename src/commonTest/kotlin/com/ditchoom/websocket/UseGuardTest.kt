package com.ditchoom.websocket

import com.ditchoom.websocket.internal.UseGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class UseGuardTest {
    @Test
    fun releaseWithNoUseRunsAtOnceAndOnlyOnce() {
        val ran = AtomicInt(0)
        val guard = UseGuard { ran.addAndFetch(1) }
        guard.release()
        guard.release()
        assertEquals(1, ran.load())
    }

    @Test
    fun releaseDuringAUseWaitsForTheUseToFinish() {
        val ran = AtomicInt(0)
        val guard = UseGuard { ran.addAndFetch(1) }
        guard.use {
            guard.release()
            assertEquals(0, ran.load(), "released while a use was running")
        }
        assertEquals(1, ran.load())
    }

    @Test
    fun useAfterReleaseFailsWithoutRunningItsBlock() {
        val guard = UseGuard {}
        guard.release()
        val ran = AtomicInt(0)
        assertFailsWith<WebSocketException.ConnectionClosed> { guard.use { ran.addAndFetch(1) } }
        assertEquals(0, ran.load())
    }

    @Test
    fun useStartedWhileDrainingFails() {
        val guard = UseGuard {}
        guard.use {
            guard.release()
            assertFailsWith<WebSocketException.ConnectionClosed> { guard.use { } }
        }
    }

    @Test
    fun releaseRacingManyUsesRunsOnceAndNeverDuringAUse() =
        runStrictTest(timeout = 120.seconds) {
            repeat(3000) {
                val running = AtomicInt(0)
                val ran = AtomicInt(0)
                val overlapped = AtomicInt(0)
                val guard =
                    UseGuard {
                        if (running.load() != 0) overlapped.addAndFetch(1)
                        ran.addAndFetch(1)
                    }
                coroutineScope {
                    launch(Dispatchers.Default) { guard.release() }
                    repeat(3) {
                        launch(Dispatchers.Default) {
                            repeat(20) {
                                try {
                                    guard.use {
                                        running.addAndFetch(1)
                                        running.addAndFetch(-1)
                                    }
                                } catch (_: WebSocketException.ConnectionClosed) {
                                }
                            }
                        }
                    }
                }
                assertEquals(1, ran.load(), "onRelease runs exactly once")
                assertEquals(0, overlapped.load(), "onRelease ran during a use")
            }
        }
}
