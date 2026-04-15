package com.ditchoom.websocket

import agentName
import autobahnHost
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.ConnectionContext
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.transport.TcpTransport
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec
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
 * production [connectWebSocket] factory with the supplied [payloadCodec].
 *
 * The caller's `connectionOptions.bufferFactory` is propagated into the
 * socket transport's [ConnectionOptions] so both transport reads and codec
 * allocations use the same allocator — see
 * `socket/CONNECTION_OPTIONS_BUFFER_FACTORY_BYPASS.md` for why this matters.
 */
internal suspend fun <B> connectForTest(
    connectionOptions: WebSocketConnectionOptions,
    binaryCodec: Codec<B>,
): Connection<WebSocketMessage<B>> {
    val socketOptions =
        if (connectionOptions.tls) {
            SocketOptions.tlsDefault()
        } else {
            SocketOptions.LOW_LATENCY
        }
    val transport =
        TcpTransport().connect(
            hostname = connectionOptions.name,
            port = connectionOptions.port,
            context = ConnectionContext(
                ConnectionOptions(
                    socketOptions = socketOptions,
                    connectionTimeout = connectionOptions.connectionTimeout,
                    bufferFactory = connectionOptions.bufferFactory,
                ),
            ),
        )
    return connectWebSocket(
        transport = transport,
        connectionOptions = connectionOptions,
        binaryCodec = binaryCodec,
    )
}

/** Text-only overload — binary frames surface as `Binary(Unit)` and are typically ignored. */
internal suspend fun connectForTest(
    connectionOptions: WebSocketConnectionOptions,
): Connection<WebSocketMessage<Unit>> =
    connectForTest(connectionOptions, com.ditchoom.websocket.codecs.EmptyCodec)

/**
 * Prepares a connection for an autobahn case and optionally waits for the server to close.
 * Protocol-conformance cases don't examine binary payloads, so we connect without a binary
 * codec (binary frames surface as `Binary(Unit)` and are ignored).
 */
internal suspend fun CoroutineScope.prepareConnection(
    case: Int,
    requestCompression: Boolean = false,
    awaitClose: Boolean = true,
    bufferFactory: BufferFactory = BufferFactory.Default,
    agentSuffix: String = "",
): Connection<WebSocketMessage<Unit>> {
    val connectionOptions =
        WebSocketConnectionOptions(
            name = autobahnHost(),
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}$agentSuffix",
            requestCompression = requestCompression,
            bufferFactory = bufferFactory,
        )
    val websocket = connectForTest(connectionOptions)

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
    bufferFactory: BufferFactory = BufferFactory.Default,
    agentSuffix: String = "",
) {
    val connectionOptions =
        WebSocketConnectionOptions(
            name = autobahnHost(),
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}$agentSuffix",
            requestCompression = requestCompression,
            compressionOptions = compressionOptions,
            bufferFactory = bufferFactory,
        )
    val mark = TimeSource.Monotonic.markNow()
    val ws = connectForTest(connectionOptions)
    val connectTime = mark.elapsedNow()
    try {
        var msgIdx = 0
        val perMsgMark = TimeSource.Monotonic.markNow()
        ws.receive().filter { it is WebSocketMessage.Text }.take(count).collect {
            val recvTime = perMsgMark.elapsedNow()
            val m = it as WebSocketMessage.Text
            ws.send(WebSocketMessage.Text(m.payload))
            val writeTime = perMsgMark.elapsedNow()
            if (count > 100 && (msgIdx < 5 || msgIdx % 100 == 0 || msgIdx == count - 1)) {
                println(
                    "  MSG[$msgIdx] recv=${recvTime.inWholeMilliseconds}ms write=${writeTime.inWholeMilliseconds}ms len=${m.payload.length}",
                )
            }
            msgIdx++
        }
    } catch (_: Exception) {
        // Server may close the connection as part of the test case behavior.
        // Autobahn correctness is validated by validateAutobahnResults.
    }
    val echoTime = mark.elapsedNow() - connectTime
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
        "AUTOBAHN_TIMING [${agentName()}$agentSuffix] case=$case count=$count connect=${connectTime.inWholeMilliseconds}ms echo=${echoTime.inWholeMilliseconds}ms close=${closeTime.inWholeMilliseconds}ms total=${totalTime.inWholeMilliseconds}ms avg_msg=${avgMsg.inWholeMicroseconds}us",
    )
}

internal suspend fun CoroutineScope.echoBinaryMessageAndClose(
    case: Int,
    count: Int = 1,
    requestCompression: Boolean = false,
    compressionOptions: CompressionOptions = CompressionOptions(),
    bufferFactory: BufferFactory = BufferFactory.Default,
    agentSuffix: String = "",
) {
    val connectionOptions =
        WebSocketConnectionOptions(
            name = autobahnHost(),
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}$agentSuffix",
            requestCompression = requestCompression,
            compressionOptions = compressionOptions,
            bufferFactory = bufferFactory,
        )
    val mark = TimeSource.Monotonic.markNow()
    // Binary echo needs access to raw bytes; BinaryPassThroughCodec surfaces them as ReadBuffer.
    val ws = connectForTest(connectionOptions, BinaryPassThroughCodec)
    val connectTime = mark.elapsedNow()
    try {
        ws.receive().filter { it is WebSocketMessage.Binary<*> }.take(count).collect {
            @Suppress("UNCHECKED_CAST")
            val m = it as WebSocketMessage.Binary<ReadBuffer>
            // Library owns m.payload for the duration of this collect block; it frees
            // the buffer once we return. send() copies/writes the bytes immediately.
            ws.send(WebSocketMessage.Binary(m.payload))
        }
    } catch (_: Exception) {
        // Server may close the connection as part of the test case behavior.
    }
    val echoTime = mark.elapsedNow() - connectTime
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
        "AUTOBAHN_TIMING [${agentName()}$agentSuffix] case=$case count=$count connect=${connectTime.inWholeMilliseconds}ms echo=${echoTime.inWholeMilliseconds}ms close=${closeTime.inWholeMilliseconds}ms total=${totalTime.inWholeMilliseconds}ms avg_msg=${avgMsg.inWholeMicroseconds}us",
    )
}

internal suspend fun CoroutineScope.echoMessageWhenFoundText(
    case: Int,
    requestCompression: Boolean = false,
    bufferFactory: BufferFactory = BufferFactory.Default,
    agentSuffix: String = "",
) {
    val connectionOptions =
        WebSocketConnectionOptions(
            name = autobahnHost(),
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}$agentSuffix",
            requestCompression = requestCompression,
            bufferFactory = bufferFactory,
        )
    val ws = connectForTest(connectionOptions)
    try {
        val msg = ws.receive().first { it is WebSocketMessage.Text } as WebSocketMessage.Text
        ws.send(WebSocketMessage.Text(msg.payload))
    } catch (_: Exception) {
        // Server may close the connection as part of the test case behavior.
    }
    kotlinx.coroutines.delay(100)
    try {
        ws.close()
    } catch (_: Exception) {
        // Already closed
    }
}

internal suspend fun <B> closeConnection(websocket: Connection<WebSocketMessage<B>>) {
    kotlinx.coroutines.delay(100)
    try {
        websocket.close()
    } catch (_: Exception) {
        // Server may have already closed the connection
    }
}

internal suspend fun <B> sendMessageWithPayloadLengthOf(
    websocket: Connection<WebSocketMessage<B>>,
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
    websocket: Connection<WebSocketMessage<ReadBuffer>>,
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
