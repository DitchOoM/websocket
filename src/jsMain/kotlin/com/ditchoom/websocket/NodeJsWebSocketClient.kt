package com.ditchoom.websocket

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.socket.SocketClosedException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeout
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

class NodeJsWebSocketClient(
    private val connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope?,
    private val wsModule: dynamic,
) : WebSocketClient {
    override val scope =
        if (parentScope == null) {
            CoroutineScope(
                Dispatchers.Default +
                    CoroutineName(
                        "NodeWS #${getCountForConnection(connectionOptions)}" +
                            ": ${connectionOptions.name}:${connectionOptions.port}",
                    ),
            )
        } else {
            parentScope + Dispatchers.Default +
                CoroutineName(
                    "NodeWS #${getCountForConnection(connectionOptions)}" +
                        ": ${connectionOptions.name}:${connectionOptions.port}",
                )
        }

    private val url = connectionOptions.buildUrl()
    private var ws: dynamic = null

    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    override val connectionState = connectionStateFlow.asStateFlow()

    private val incomingMessageChannel = Channel<WebSocketMessage>(Channel.UNLIMITED)
    override val incomingMessages = incomingMessageChannel.receiveAsFlow()

    private val incomingTextChannel = Channel<String>(Channel.UNLIMITED)
    override val incomingTextMessages: Flow<String> = incomingTextChannel.receiveAsFlow()

    private val incomingBinaryChannel = Channel<ReadBuffer>(Channel.UNLIMITED)
    override val incomingBinaryMessages: Flow<ReadBuffer> = incomingBinaryChannel.receiveAsFlow()

    override suspend fun localPort(): Int {
        val socket = ws?._socket ?: return -1
        return (socket.localPort as? Number)?.toInt() ?: -1
    }

    override suspend fun remotePort(): Int {
        val socket = ws?._socket ?: return connectionOptions.port
        return (socket.remotePort as? Number)?.toInt() ?: connectionOptions.port
    }

    override suspend fun connect(): WebSocketClient {
        connectionStateFlow.value = ConnectionState.Connecting

        // Build ws options
        val options = js("{}")
        options.handshakeTimeout = connectionOptions.connectionTimeout.inWholeMilliseconds.toInt()
        if (connectionOptions.requestCompression) {
            options.perMessageDeflate = true
        }

        // Create WebSocket instance using Reflect.construct for dynamic constructor call
        val protocols =
            if (connectionOptions.protocols.isNotEmpty()) {
                connectionOptions.protocols.toTypedArray()
            } else {
                js("undefined")
            }
        val args = js("[]")
        args.push(url)
        args.push(protocols)
        args.push(options)
        ws = js("Reflect").construct(wsModule, args)

        val wsInstance = ws

        // Register event handlers using Node.js EventEmitter .on() pattern
        wsInstance.on("open", fun() {
            connectionStateFlow.value = ConnectionState.Connected
        })

        wsInstance.on("close", fun(code: dynamic, reason: dynamic) {
            val closeCode = (code as? Number)?.toInt()?.toUShort() ?: 1006u.toUShort()
            val closeReason =
                if (reason != null && reason != undefined) {
                    // reason is a Buffer in ws module; decode to string
                    reason.toString("utf8") as String
                } else {
                    ""
                }
            connectionStateFlow.value =
                ConnectionState.Disconnected(code = closeCode, reason = closeReason)
            closeInternal()
        })

        wsInstance.on("error", fun(err: dynamic) {
            val message = err?.message?.toString() ?: "WebSocket error"
            if (connectionState.value !is ConnectionState.Disconnected) {
                connectionStateFlow.value = ConnectionState.Disconnected(Exception(message))
            }
            closeInternal()
        })

        wsInstance.on("message", fun(data: dynamic, isBinary: Boolean) {
            if (isBinary) {
                val buffer = nodeBufferToJsBuffer(data)
                scope.launch {
                    incomingMessageChannel.trySend(WebSocketMessage.Binary(buffer))
                    incomingBinaryChannel.trySend(buffer)
                }
            } else {
                val text = data.toString()
                scope.launch {
                    incomingMessageChannel.trySend(WebSocketMessage.Text(text))
                    incomingTextChannel.trySend(text)
                }
            }
        })

        wsInstance.on("ping", fun(data: dynamic) {
            val buffer =
                if (data != null && data != undefined) nodeBufferToJsBuffer(data) else EMPTY_BUFFER
            scope.launch {
                incomingMessageChannel.trySend(WebSocketMessage.Ping(buffer))
            }
        })

        wsInstance.on("pong", fun(data: dynamic) {
            val buffer =
                if (data != null && data != undefined) nodeBufferToJsBuffer(data) else EMPTY_BUFFER
            scope.launch {
                incomingMessageChannel.trySend(WebSocketMessage.Pong(buffer))
            }
        })

        // Wait for connection
        withTimeout(connectionOptions.connectionTimeout) {
            when (val state = connectionState.value) {
                ConnectionState.Initialized, ConnectionState.Connecting -> {
                    connectionState.first {
                        it is ConnectionState.Connected || it is ConnectionState.Disconnected
                    }
                    val currentState = connectionState.value
                    if (currentState is ConnectionState.Disconnected) {
                        throw SocketClosedException(
                            "Failed to connect. Reason: ${currentState.reason}, Code: ${currentState.code}",
                            currentState.t,
                        )
                    }
                }
                is ConnectionState.Disconnected ->
                    throw SocketClosedException(
                        "Failed to connect. Reason: ${state.reason}, Code: ${state.code}",
                        state.t,
                    )
                ConnectionState.Connected -> {}
            }
        }

        return this
    }

    override suspend fun write(string: String) {
        ws?.send(string)
    }

    override suspend fun write(buffer: ReadBuffer) {
        val jsBuffer = buffer as JsBuffer
        val uint8Array = Uint8Array(jsBuffer.buffer.buffer, 0, buffer.limit())
        ws?.send(uint8Array)
    }

    override suspend fun isPingSupported(): Boolean = true

    override suspend fun ping(payloadData: ReadBuffer) {
        if (payloadData.remaining() > 0) {
            val jsBuffer = payloadData as JsBuffer
            val uint8Array = Uint8Array(jsBuffer.buffer.buffer, 0, payloadData.limit())
            ws?.ping(uint8Array)
        } else {
            ws?.ping()
        }
    }

    override suspend fun close() {
        try {
            ws?.close(1000)
        } catch (_: dynamic) {
            // Ignore close errors
        }
        closeInternal()
    }

    private fun closeInternal() {
        incomingMessageChannel.close()
        incomingTextChannel.close()
        incomingBinaryChannel.close()
    }

    private fun nodeBufferToJsBuffer(data: dynamic): ReadBuffer {
        // Node.js Buffer is a Uint8Array subclass
        val uint8 = data.unsafeCast<Uint8Array>()
        val int8 = Int8Array(uint8.buffer, uint8.byteOffset, uint8.length)
        val jsBuffer = JsBuffer(int8)
        jsBuffer.setLimit(int8.length)
        jsBuffer.position(0)
        return jsBuffer.slice()
    }

    companion object {
        private val countMap = mutableMapOf<String, Int>()

        private fun getCountForConnection(connectionOptions: WebSocketConnectionOptions): Int {
            val key = "${connectionOptions.name}:${connectionOptions.port}"
            val value = countMap[key]
            return if (value == null) {
                countMap[key] = 1
                1
            } else {
                val newCount = value + 1
                countMap[key] = newCount
                newCount
            }
        }
    }
}
