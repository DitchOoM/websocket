package com.ditchoom.websocket

import agentName
import autobahnHost
import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.allocate
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private val testImplementation = WebSocketImplementation.MODULAR

internal suspend fun CoroutineScope.prepareConnection(
    case: Int,
    requestCompression: Boolean = false,
    awaitClose: Boolean = true,
): WebSocketClient {
    val connectionOptions =
        WebSocketConnectionOptions(
            name = autobahnHost(),
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}",
            requestCompression = requestCompression,
        )
    val websocket =
        WebSocketClient.allocate(
            connectionOptions,
            AllocationZone.Direct,
            this + CoroutineName(case.toString()),
            testImplementation,
        )
    websocket.connect()
    websocket.connectionState.first { it is ConnectionState.Connected }

    if (awaitClose) {
        // Wait for server to close the connection (with timeout)
        val closed =
            withTimeoutOrNull(10.seconds) {
                websocket.connectionState.first { it is ConnectionState.Disconnected }
            }
        if (closed == null) {
            // Server didn't close in time, close from client side
            try {
                websocket.close()
            } catch (_: Exception) {
                // Ignore close errors
            }
        }
    }

    return websocket
}

internal suspend fun CoroutineScope.echoMessageAndClose(
    case: Int,
    count: Int = 1,
    requestCompression: Boolean = false,
) {
    val connectionOptions =
        WebSocketConnectionOptions(
            name = autobahnHost(),
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}",
            requestCompression = requestCompression,
        )
    val zone = if (case in 277..300 || count > 100) AllocationZone.Heap else AllocationZone.Direct
    val ws =
        WebSocketClient.allocate(
            connectionOptions,
            zone,
            this,
            testImplementation,
        )
    ws.scope.launch {
        ws.connect()
        ws.connectionState.first { it is ConnectionState.Connected }
    }
    try {
        ws.incomingMessages.take(count).collect {
            val m = it as WebSocketMessage.Text
            ws.write(m.value)
        }
    } catch (_: Exception) {
        // Server may close the connection as part of the test case behavior.
        // Autobahn correctness is validated by validateAutobahnResults.
    }
    // Give the read loop a chance to detect any remaining protocol errors
    // before explicitly closing. This allows proper close codes to be sent.
    kotlinx.coroutines.delay(100)
    try {
        ws.close()
    } catch (_: Exception) {
        // Already closed
    }
}

internal suspend fun CoroutineScope.echoBinaryMessageAndClose(
    case: Int,
    count: Int = 1,
    requestCompression: Boolean = false,
) {
    val connectionOptions =
        WebSocketConnectionOptions(
            name = autobahnHost(),
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}",
            requestCompression = requestCompression,
        )
    val zone = if (case in 277..300 || count > 100) AllocationZone.Heap else AllocationZone.Direct
    val ws =
        WebSocketClient.allocate(
            connectionOptions,
            zone,
            this + CoroutineName(case.toString()),
            testImplementation,
        )
    ws.scope.launch {
        ws.connect()
        ws.connectionState.first { it is ConnectionState.Connected }
    }
    try {
        ws.incomingMessages.take(count).collect {
            val m = it as WebSocketMessage.Binary
            ws.write(m.value)
        }
    } catch (_: Exception) {
        // Server may close the connection as part of the test case behavior.
        // Autobahn correctness is validated by validateAutobahnResults.
    }
    try {
        ws.close()
    } catch (_: Exception) {
        // Already closed
    }
}

internal suspend fun CoroutineScope.echoMessageWhenFoundText(
    case: Int,
    requestCompression: Boolean = false,
) {
    val connectionOptions =
        WebSocketConnectionOptions(
            name = autobahnHost(),
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}",
            requestCompression = requestCompression,
        )
    val ws =
        WebSocketClient.allocate(
            connectionOptions,
            AllocationZone.Direct,
            this + CoroutineName(case.toString()),
            testImplementation,
        )
    ws.scope.launch {
        ws.connect()
        ws.connectionState.first { it is ConnectionState.Connected }
    }
    try {
        val msg = ws.incomingMessages.first { it is WebSocketMessage.Text } as WebSocketMessage.Text
        ws.write(msg.value)
    } catch (_: Exception) {
        // Server may close the connection as part of the test case behavior.
        // Autobahn correctness is validated by validateAutobahnResults.
    }
    try {
        ws.close()
    } catch (_: Exception) {
        // Already closed
    }
}

internal suspend fun closeConnection(websocket: WebSocketClient) {
    // Give the read loop a chance to detect any protocol errors
    // before explicitly closing. This allows proper close codes to be sent.
    kotlinx.coroutines.delay(100)
    try {
        websocket.close()
    } catch (_: Exception) {
        // Server may have already closed the connection
    }
}

internal suspend fun sendMessageWithPayloadLengthOf(
    websocket: WebSocketClient,
    length: Int,
) {
    val string =
        if (length < 1) {
            ""
        } else {
            randomStringByKotlinRandom(length)
        }
    try {
        websocket.write(string)
    } catch (_: Exception) {
        // Server may have already closed the connection
    }
}

internal suspend fun sendBinaryWithPayloadLengthOf(
    websocket: WebSocketClient,
    length: Int,
) {
    val binary =
        if (length < 1) {
            EMPTY_BUFFER
        } else {
            val b = PlatformBuffer.allocate(length)
            repeat(length) {
                b.writeByte(0xfe.toByte())
            }
            b.position(0)
            b
        }
    try {
        websocket.write(binary)
    } catch (_: Exception) {
        // Server may have already closed the connection
    }
}

private val charPool: List<Char> = listOf('*')

internal fun randomStringByKotlinRandom(len: Int) =
    (1..len)
        .map { Random.nextInt(0, charPool.size).let { charPool[it] } }
        .joinToString("")
