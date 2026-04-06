package com.ditchoom.websocket

import agentName
import autobahnHost
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.withPooling
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.transport.TcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Creates and connects a WebSocket backed by a real TCP socket.
 *
 * Uses [TcpTransport] from the socket library (test dependency) to open
 * a TCP connection, then performs the HTTP upgrade handshake via the
 * production [connectWebSocket] factory.
 */
internal suspend fun connectForTest(
    connectionOptions: WebSocketConnectionOptions,
    bufferFactory: BufferFactory = BufferFactory.Default,
): Connection<WebSocketMessage> {
    val socketOptions =
        if (connectionOptions.tls) {
            SocketOptions.tlsDefault()
        } else {
            SocketOptions.LOW_LATENCY
        }
    val pool = BufferPool()
    val transport =
        TcpTransport().connect(
            hostname = connectionOptions.name,
            port = connectionOptions.port,
            options = ConnectionOptions(
                socketOptions = socketOptions,
                connectionTimeout = connectionOptions.connectionTimeout,
                bufferFactory = bufferFactory.withPooling(pool),
            ),
        )
    return connectWebSocket(
        transport = transport,
        connectionOptions = connectionOptions,
        bufferFactory = bufferFactory,
    )
}

internal suspend fun CoroutineScope.prepareConnection(
    case: Int,
    requestCompression: Boolean = false,
    awaitClose: Boolean = true,
): Connection<WebSocketMessage> {
    val connectionOptions =
        WebSocketConnectionOptions(
            name = autobahnHost(),
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}",
            requestCompression = requestCompression,
        )
    val websocket =
        connectForTest(
            connectionOptions,
        )

    if (awaitClose) {
        // Wait for the receive flow to complete (server closes the connection)
        val completed =
            withTimeoutOrNull(10.seconds) {
                websocket.receive().collect { /* drain */ }
            }
        if (completed == null) {
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
    compressionOptions: CompressionOptions = CompressionOptions(),
) {
    val connectionOptions =
        WebSocketConnectionOptions(
            name = autobahnHost(),
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}",
            requestCompression = requestCompression,
            compressionOptions = compressionOptions,
        )
    val factory = if (case in 277..300 || count > 100) BufferFactory.managed() else BufferFactory.Default
    val mark = TimeSource.Monotonic.markNow()
    val ws =
        connectForTest(
            connectionOptions,
            bufferFactory = factory,
        )
    val connectTime = mark.elapsedNow()
    try {
        var msgIdx = 0
        val perMsgMark = TimeSource.Monotonic.markNow()
        ws.receive().filter { it is WebSocketMessage.Text }.take(count).collect {
            val recvTime = perMsgMark.elapsedNow()
            val m = it as WebSocketMessage.Text
            ws.send(WebSocketMessage.Text(m.value))
            val writeTime = perMsgMark.elapsedNow()
            if (count > 100 && (msgIdx < 5 || msgIdx % 100 == 0 || msgIdx == count - 1)) {
                println(
                    "  MSG[$msgIdx] recv=${recvTime.inWholeMilliseconds}ms write=${writeTime.inWholeMilliseconds}ms len=${m.value.length}",
                )
            }
            msgIdx++
        }
    } catch (_: Exception) {
        // Server may close the connection as part of the test case behavior.
        // Autobahn correctness is validated by validateAutobahnResults.
    }
    val echoTime = mark.elapsedNow() - connectTime
    // Give the read loop a chance to detect any remaining protocol errors
    // before explicitly closing. This allows proper close codes to be sent.
    kotlinx.coroutines.delay(100)
    try {
        ws.close()
    } catch (_: Exception) {
        // Already closed
    }
    val totalTime = mark.elapsedNow()
    val closeTime = totalTime - connectTime - echoTime
    val avgMsg = if (count > 0) echoTime / count else echoTime
    println(
        "AUTOBAHN_TIMING [${agentName()}] case=$case count=$count connect=${connectTime.inWholeMilliseconds}ms echo=${echoTime.inWholeMilliseconds}ms close=${closeTime.inWholeMilliseconds}ms total=${totalTime.inWholeMilliseconds}ms avg_msg=${avgMsg.inWholeMicroseconds}us",
    )
}

internal suspend fun CoroutineScope.echoBinaryMessageAndClose(
    case: Int,
    count: Int = 1,
    requestCompression: Boolean = false,
    compressionOptions: CompressionOptions = CompressionOptions(),
) {
    val connectionOptions =
        WebSocketConnectionOptions(
            name = autobahnHost(),
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}",
            requestCompression = requestCompression,
            compressionOptions = compressionOptions,
        )
    val factory = if (case in 277..300 || count > 100) BufferFactory.managed() else BufferFactory.Default
    val mark = TimeSource.Monotonic.markNow()
    val ws =
        connectForTest(
            connectionOptions,
            bufferFactory = factory,
        )
    val connectTime = mark.elapsedNow()
    try {
        ws.receive().filter { it is WebSocketMessage.Binary }.take(count).collect {
            val m = it as WebSocketMessage.Binary
            ws.send(WebSocketMessage.Binary(m.value))
            m.value.freeIfNeeded() // Free NativeBuffer after echo
        }
    } catch (_: Exception) {
        // Server may close the connection as part of the test case behavior.
        // Autobahn correctness is validated by validateAutobahnResults.
    }
    val echoTime = mark.elapsedNow() - connectTime
    // Give the read loop a chance to process the server's close frame naturally.
    // Without this delay, ws.close() cancels the read loop while an io_uring recv
    // may still be in-flight, causing the buffer to be freed while the kernel is
    // still writing to it (heap corruption on Linux K/N).
    kotlinx.coroutines.delay(100)
    try {
        ws.close()
    } catch (_: Exception) {
        // Already closed
    }
    val totalTime = mark.elapsedNow()
    val closeTime = totalTime - connectTime - echoTime
    val avgMsg = if (count > 0) echoTime / count else echoTime
    println(
        "AUTOBAHN_TIMING [${agentName()}] case=$case count=$count connect=${connectTime.inWholeMilliseconds}ms echo=${echoTime.inWholeMilliseconds}ms close=${closeTime.inWholeMilliseconds}ms total=${totalTime.inWholeMilliseconds}ms avg_msg=${avgMsg.inWholeMicroseconds}us",
    )
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
        connectForTest(
            connectionOptions,
        )
    try {
        val msg = ws.receive().first { it is WebSocketMessage.Text } as WebSocketMessage.Text
        ws.send(WebSocketMessage.Text(msg.value))
    } catch (_: Exception) {
        // Server may close the connection as part of the test case behavior.
        // Autobahn correctness is validated by validateAutobahnResults.
    }
    // Give the read loop a chance to process the server's close frame naturally.
    kotlinx.coroutines.delay(100)
    try {
        ws.close()
    } catch (_: Exception) {
        // Already closed
    }
}

internal suspend fun closeConnection(websocket: Connection<WebSocketMessage>) {
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
    websocket: Connection<WebSocketMessage>,
    length: Int,
) {
    val string =
        if (length < 1) {
            ""
        } else {
            randomStringByKotlinRandom(length)
        }
    try {
        websocket.send(WebSocketMessage.Text(string))
    } catch (_: Exception) {
        // Server may have already closed the connection
    }
}

internal suspend fun sendBinaryWithPayloadLengthOf(
    websocket: Connection<WebSocketMessage>,
    length: Int,
) {
    val binary =
        if (length < 1) {
            EMPTY_BUFFER
        } else {
            val b = BufferFactory.Default.allocate(length)
            repeat(length) {
                b.writeByte(0xfe.toByte())
            }
            b.position(0)
            b
        }
    try {
        websocket.send(WebSocketMessage.Binary(binary))
    } catch (_: Exception) {
        // Server may have already closed the connection
    }
}

private val charPool: List<Char> = listOf('*')

internal fun randomStringByKotlinRandom(len: Int) =
    (1..len)
        .map { Random.nextInt(0, charPool.size).let { charPool[it] } }
        .joinToString("")
