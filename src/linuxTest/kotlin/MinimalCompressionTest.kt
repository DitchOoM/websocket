package com.ditchoom.websocket

import agentName
import autobahnHost
import com.ditchoom.buffer.AllocationZone
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Verify large-payload compression cases complete correctly (not just timeout).
 */
class MinimalCompressionTest {

    /** 12.2.9: binary 65KB × 1000 */
    @Test
    fun case12_2_9_binary65KB() = runStrictTest(timeout = 60.seconds) {
        echoNMessages("12.2.9", case = 328, count = 5)
    }

    /** 12.2.10: binary 128KB × 1000 */
    @Test
    fun case12_2_10_binary128KB() = runStrictTest(timeout = 60.seconds) {
        echoNMessages("12.2.10", case = 329, count = 5)
    }

    /** 12.5.9: binary 65KB × 1000 (default offer) */
    @Test
    fun case12_5_9() = runStrictTest(timeout = 60.seconds) {
        echoNMessages("12.5.9", case = 382, count = 5)
    }

    /** 12.5.10: binary 128KB × 1000 (default offer) */
    @Test
    fun case12_5_10() = runStrictTest(timeout = 60.seconds) {
        echoNMessages("12.5.10", case = 383, count = 5)
    }

    /** 13.2.13: 32KB frag256 × 1000 */
    @Test
    fun case13_2_13() = runStrictTest(timeout = 60.seconds) {
        echoNMessages("13.2.13", case = 422, count = 5)
    }

    /** 13.2.14: 64KB frag256 × 1000 */
    @Test
    fun case13_2_14() = runStrictTest(timeout = 60.seconds) {
        echoNMessages("13.2.14", case = 423, count = 5)
    }

    /** 13.2.15: 128KB frag256 × 1000 */
    @Test
    fun case13_2_15() = runStrictTest(timeout = 60.seconds) {
        echoNMessages("13.2.15", case = 424, count = 5)
    }

    private suspend fun TestScope.echoNMessages(label: String, case: Int, count: Int) {
        val connectionOptions = WebSocketConnectionOptions(
            name = autobahnHost(),
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}",
            requestCompression = true,
        )
        val ws = WebSocketClient.allocate(
            connectionOptions,
            AllocationZone.Direct,
            this + CoroutineName(label),
            WebSocketImplementation.MODULAR,
        )
        ws.connect()
        ws.connectionState.first { it is ConnectionState.Connected }

        var received = 0
        ws.incomingMessages.take(count).collect { msg ->
            received++
            when (msg) {
                is WebSocketMessage.Text -> {
                    println("$label: echo text #$received, len=${msg.value.length}")
                    ws.write(msg.value)
                }
                is WebSocketMessage.Binary -> {
                    println("$label: echo binary #$received, size=${msg.value.remaining()}")
                    ws.write(msg.value)
                }
                is WebSocketMessage.Close -> println("$label: close code=${msg.code}")
                else -> {}
            }
        }
        println("$label: completed $received/$count messages")
        assertEquals(count, received, "$label should have echoed $count messages")
        try { ws.close() } catch (_: Exception) {}
    }
}
