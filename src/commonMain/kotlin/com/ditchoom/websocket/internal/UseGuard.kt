package com.ditchoom.websocket.internal

import com.ditchoom.websocket.WebSocketException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Runs [onRelease] exactly once, after the last in-flight [use] has finished and never while one is running.
 *
 * - [release] with no [use] in flight runs [onRelease] on the calling thread, and returns without waiting
 *   otherwise: the [use] that finishes last runs it.
 * - A [use] that starts after [release] throws [WebSocketException.ConnectionClosed] without running its block.
 *
 * One atomic holds the number of uses in flight and whether release was requested, so entering,
 * leaving and releasing are single compare-and-set steps and need no lock.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class UseGuard(
    private val onRelease: () -> Unit,
) {
    private sealed interface State {
        /** Accepting uses; [inFlight] of them are running. */
        data class Active(
            val inFlight: Int,
        ) : State

        /** Release requested; [inFlight] uses are still running and the last to leave releases. */
        data class Draining(
            val inFlight: Int,
        ) : State

        /** [onRelease] has been claimed by exactly one caller. */
        data object Released : State
    }

    private val state = AtomicReference<State>(State.Active(0))

    /** Runs [block] holding the resource; throws [WebSocketException.ConnectionClosed] once release was requested. */
    fun <R> use(block: () -> R): R {
        enter()
        try {
            return block()
        } finally {
            leave()
        }
    }

    /** Requests release; see the class description for who runs [onRelease]. */
    fun release() {
        while (true) {
            when (val current = state.load()) {
                is State.Active ->
                    if (current.inFlight == 0) {
                        if (state.compareAndSet(current, State.Released)) {
                            onRelease()
                            return
                        }
                    } else if (state.compareAndSet(current, State.Draining(current.inFlight))) {
                        return
                    }
                is State.Draining, State.Released -> return
            }
        }
    }

    private fun enter() {
        while (true) {
            val current = state.load()
            if (current !is State.Active) throw WebSocketException.ConnectionClosed("Connection closed")
            if (state.compareAndSet(current, State.Active(current.inFlight + 1))) return
        }
    }

    private fun leave() {
        while (true) {
            when (val current = state.load()) {
                is State.Active -> if (state.compareAndSet(current, State.Active(current.inFlight - 1))) return
                is State.Draining ->
                    if (current.inFlight == 1) {
                        if (state.compareAndSet(current, State.Released)) {
                            onRelease()
                            return
                        }
                    } else if (state.compareAndSet(current, State.Draining(current.inFlight - 1))) {
                        return
                    }
                State.Released -> return
            }
        }
    }
}
