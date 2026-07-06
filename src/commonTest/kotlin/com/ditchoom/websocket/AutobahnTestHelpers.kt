package com.ditchoom.websocket

import agentName
import autobahnHost
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.IoTuning
import com.ditchoom.socket.TlsConfig
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.TcpTransport
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Connects a WebSocket the way a consumer of the library would on the current
 * platform: on targets with raw socket access (JVM, Android, iOS/macOS/tvOS/watchOS,
 * Linux, Node.js) this opens a [TcpTransport] and drives the full [connectWebSocket]
 * framing/handshake/compression path; on browser JS it resolves to
 * [connectBrowserWebSocket][connectBrowserPath] → [BrowserWebSocketController]
 * and hits the platform's native `WebSocket` API. Writing tests against this
 * helper means the autobahn suite runs end-to-end on every target.
 */
internal suspend fun <B> connectForTest(
    connectionOptions: WebSocketConnectionOptions,
    binaryCodec: Codec<B>,
): Connection<WebSocketMessage<B>> =
    if (hasFullSocketAccess()) {
        val transport =
            TcpTransport().connect(
                hostname = connectionOptions.name,
                port = connectionOptions.port,
                config =
                    TransportConfig(
                        bufferFactory = connectionOptions.bufferFactory,
                        connectTimeout = connectionOptions.connectionTimeout,
                        readPolicy = ReadPolicy.Bounded(connectionOptions.readTimeout),
                        writePolicy = WritePolicy.Bounded(connectionOptions.writeTimeout),
                        tls = if (connectionOptions.tls) TlsConfig.DEFAULT else null,
                        io = IoTuning(tcpNoDelay = true),
                    ),
            )
        connectWebSocket(
            transport = transport,
            connectionOptions = connectionOptions,
            binaryCodec = binaryCodec,
        )
    } else {
        connectBrowserPath(
            connectionOptions = connectionOptions,
            binaryCodec = binaryCodec,
        )
    }

/** Text-only overload — binary frames surface as `Binary(Unit)` and are typically ignored. */
internal suspend fun connectForTest(connectionOptions: WebSocketConnectionOptions): Connection<WebSocketMessage<Unit>> =
    connectForTest(connectionOptions, com.ditchoom.websocket.codecs.EmptyCodec)

/**
 * Prepares a connection for an autobahn case and optionally waits for the server to close.
 * Protocol-conformance cases don't examine binary payloads, so we connect without a binary
 * codec (binary frames surface as `Binary(Unit)` and are ignored).
 *
 * The [bufferFactory] default is [BufferFactory.deterministic] — the same library default as
 * [WebSocketConnectionOptions.bufferFactory] — NOT `BufferFactory.Default`. On Android the
 * `allocateDirect`-backed Default factory fragments both ART spaces after ~26 min of full-suite
 * churn and OOMs every late case (see ANDROID_ART_ALLOCATOR.md / the managed-heap OOM diagnosis);
 * the deterministic default allocates off both ART spaces and passes the suite on a wrecked heap.
 * Keep these tracking the library default so conformance runs exercise what real consumers get.
 */
internal suspend fun CoroutineScope.prepareConnection(
    case: Int,
    requestCompression: Boolean = false,
    awaitClose: Boolean = true,
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
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
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
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
    var echoTime = kotlin.time.Duration.ZERO
    // Echo the expected messages, then KEEP COLLECTING until the server closes (the
    // fuzzingserver ends every case by closing the connection). Dropping the TCP
    // connection a fixed delay after our own close frame races the server: on slow
    // servers (e.g. the chopped-send 9.5/9.6 cases on the CPython Alpine image, whose
    // sync chop loop blocks the reactor for seconds) the server hasn't read the echo
    // yet, and the teardown discards it — wstest records the case as FAILED even
    // though the echo was correct. Waiting for the server's close is also what RFC
    // 6455 §7.1.1 prescribes for the closing handshake.
    //
    // CRITICAL: the close-wait watchdog arms only AFTER the last echo. The echo phase
    // itself must stay unbounded — the 12.x/13.x torture cases echo ~1000 compressed
    // messages and legitimately exceed any fixed cap on slow CI runners (a whole-loop
    // 15s cap truncated them at ~930/1000 and failed the cases on ubuntu runners).
    var closeWait: Job? = null
    try {
        var msgIdx = 0
        val perMsgMark = TimeSource.Monotonic.markNow()
        ws.receive().collect {
            if (it is WebSocketMessage.Text && msgIdx < count) {
                val recvTime = perMsgMark.elapsedNow()
                ws.send(WebSocketMessage.Text(it.payload))
                val writeTime = perMsgMark.elapsedNow()
                if (count > 100 && (msgIdx < 5 || msgIdx % 100 == 0 || msgIdx == count - 1)) {
                    println(
                        "  MSG[$msgIdx] recv=${recvTime.inWholeMilliseconds}ms write=${writeTime.inWholeMilliseconds}ms len=${it.payload.length}",
                    )
                }
                msgIdx++
                if (msgIdx == count) {
                    echoTime = mark.elapsedNow() - connectTime
                    closeWait = armCloseWaitWatchdog(ws)
                }
            }
        }
    } catch (_: Exception) {
        // Server may close the connection as part of the test case behavior.
        // Autobahn correctness is validated by validateAutobahnResults.
    } finally {
        closeWait?.cancel()
    }
    if (echoTime == kotlin.time.Duration.ZERO) echoTime = mark.elapsedNow() - connectTime
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
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
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
    var echoTime = kotlin.time.Duration.ZERO
    // See echoMessageAndClose: echo (unbounded — torture cases legitimately run long on
    // slow runners), then wait for the SERVER to close, with the grace watchdog arming
    // only after the last echo.
    var closeWait: Job? = null
    try {
        var msgIdx = 0
        ws.receive().collect {
            if (it is WebSocketMessage.Binary<*> && msgIdx < count) {
                @Suppress("UNCHECKED_CAST")
                val m = it as WebSocketMessage.Binary<ReadBuffer>
                // Library owns m.payload for the duration of this collect block; it frees
                // the buffer once we return. send() copies/writes the bytes immediately.
                ws.send(WebSocketMessage.Binary(m.payload))
                msgIdx++
                if (msgIdx == count) {
                    echoTime = mark.elapsedNow() - connectTime
                    closeWait = armCloseWaitWatchdog(ws)
                }
            }
        }
    } catch (_: Exception) {
        // Server may close the connection as part of the test case behavior.
    } finally {
        closeWait?.cancel()
    }
    if (echoTime == kotlin.time.Duration.ZERO) echoTime = mark.elapsedNow() - connectTime
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
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
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
    // See echoMessageAndClose: echo, then wait for the SERVER to close so slow
    // servers still receive the echo before the connection goes away.
    var closeWait: Job? = null
    try {
        var echoed = false
        ws.receive().collect {
            if (it is WebSocketMessage.Text && !echoed) {
                ws.send(WebSocketMessage.Text(it.payload))
                echoed = true
                closeWait = armCloseWaitWatchdog(ws)
            }
        }
    } catch (_: Exception) {
        // Server may close the connection as part of the test case behavior.
    } finally {
        closeWait?.cancel()
    }
    try {
        ws.close()
    } catch (_: Exception) {
        // Already closed
    }
}

/**
 * Grace period after the last echo for the server to finish reading it and initiate the
 * closing handshake. Bounds only the post-echo close wait — never the echo phase — so a
 * server that never closes can't stall a test, while slow servers (CPython chopped-send
 * cases block the reactor for seconds) still receive everything before teardown.
 */
private val SERVER_CLOSE_GRACE = 15.seconds

/**
 * Arms a watchdog that client-closes [ws] if the server hasn't closed within
 * [SERVER_CLOSE_GRACE]. Closing completes the receive flow, ending the caller's collect.
 * Cancel it once the flow completes normally.
 */
private fun <B> CoroutineScope.armCloseWaitWatchdog(ws: Connection<WebSocketMessage<B>>): Job =
    launch {
        kotlinx.coroutines.delay(SERVER_CLOSE_GRACE)
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
