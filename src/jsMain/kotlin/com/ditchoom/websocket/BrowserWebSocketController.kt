package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.SocketClosedException
import js.buffer.SharedArrayBuffer
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
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.ARRAYBUFFER
import org.w3c.dom.BinaryType
import org.w3c.dom.CloseEvent
import org.w3c.dom.WebSocket

class BrowserWebSocketController(
    private val connectionOptions: WebSocketConnectionOptions,
    private val pool: BufferPool,
    parentScope: CoroutineScope?,
    private val allocationZone: AllocationZone = AllocationZone.Direct,
) : WebSocketClient {
    override val scope =
        if (parentScope == null) {
            CoroutineScope(
                Dispatchers.Default +
                    CoroutineName(
                        "Websocket Connection #${getCountForConnection(connectionOptions)}" +
                            ": ${connectionOptions.name}:${connectionOptions.port}",
                    ),
            )
        } else {
            parentScope + Dispatchers.Default +
                CoroutineName(
                    "Websocket Connection #${getCountForConnection(connectionOptions)}" +
                        ": ${connectionOptions.name}:${connectionOptions.port}",
                )
        }

    private val url = connectionOptions.buildUrl()
    private val webSocket: WebSocket =
        if (connectionOptions.protocols.isNotEmpty()) {
            WebSocket(url, connectionOptions.protocols.first())
        } else {
            WebSocket(url)
        }

    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    override val connectionState = connectionStateFlow.asStateFlow()
    private val incomingMessageChannel = Channel<WebSocketMessage>(Channel.UNLIMITED)
    override val incomingMessages = incomingMessageChannel.receiveAsFlow()

    private val incomingTextChannel = Channel<String>(Channel.UNLIMITED)
    override val incomingTextMessages: Flow<String> = incomingTextChannel.receiveAsFlow()

    private val incomingBinaryChannel = Channel<ReadBuffer>(Channel.UNLIMITED)
    override val incomingBinaryMessages: Flow<ReadBuffer> = incomingBinaryChannel.receiveAsFlow()

    private val crossOriginIsolated = js("crossOriginIsolated") == true
    private val useSharedMemory = allocationZone == AllocationZone.SharedMemory

    init {
        webSocket.binaryType = BinaryType.ARRAYBUFFER
    }

    override suspend fun localPort(): Int = throw UnsupportedOperationException("Unavailable on browser")

    override suspend fun remotePort(): Int = throw UnsupportedOperationException("Unavailable on browser")

    override suspend fun connect(): WebSocketClient {
        webSocket.onclose = {
            val closeEvent = it as CloseEvent
            connectionStateFlow.value =
                ConnectionState.Disconnected(
                    code = closeEvent.code.toUShort(),
                    reason = closeEvent.reason,
                )
            closeInternal()
        }
        webSocket.onerror = {
            connectionStateFlow.value = ConnectionState.Disconnected(Exception("Error on websocket: $it"))
            closeInternal()
        }
        webSocket.onmessage = {
            when (val data = it.data) {
                is ArrayBuffer -> {
                    val buffer =
                        if (useSharedMemory && crossOriginIsolated) {
                            val sharedArrayBuffer = SharedArrayBuffer(data.byteLength)
                            val array = Int8Array(sharedArrayBuffer.unsafeCast<ArrayBuffer>())
                            array.set(Int8Array(it.data as ArrayBuffer), 0)
                            JsBuffer(array, sharedArrayBuffer = sharedArrayBuffer)
                        } else {
                            if (useSharedMemory && !crossOriginIsolated) {
                                console.warn(
                                    "Failed to allocate shared buffer in " +
                                        "BrowserWebSocketController.kt. " +
                                        "Please check and validate the appropriate headers are " +
                                        "set on the http request as " +
                                        "defined in the SharedArrayBuffer MDN docs. see: " +
                                        "https://developer.mozilla.org/en-US/docs/Web/" +
                                        "JavaScript/Reference/Global_Objects/" +
                                        "SharedArrayBuffer#security_requirements",
                                )
                            }
                            val array = Int8Array(data)
                            val jsBuffer = JsBuffer(array)
                            jsBuffer.setLimit(array.length)
                            jsBuffer.position(0)
                            jsBuffer.slice()
                        }
                    scope.launch {
                        incomingMessageChannel.trySend(WebSocketMessage.Binary(buffer))
                        incomingBinaryChannel.trySend(buffer)
                    }
                }
                is String ->
                    scope.launch {
                        incomingMessageChannel.trySend(WebSocketMessage.Text(data))
                        incomingTextChannel.trySend(data)
                    }
                else -> throw IllegalArgumentException("Received invalid message type!")
            }
        }

        webSocket.onopen = { _ ->
            connectionStateFlow.value = ConnectionState.Connected
            Unit
        }
        withTimeout(connectionOptions.connectionTimeout) {
            val connectionStateValue = connectionState.value
            when (connectionStateValue) {
                ConnectionState.Initialized, ConnectionState.Connecting -> {
                    connectionState.first { it is ConnectionState.Connected }
                }
                is ConnectionState.Disconnected ->
                    throw SocketClosedException(
                        "Failed to connect. Reason: ${connectionStateValue.reason}," +
                            " Code: ${connectionStateValue.code}",
                        connectionStateValue.t,
                    )
                ConnectionState.Connected -> {} // nothing to wait for
            }
        }
        return this
    }

    override suspend fun write(string: String) {
        webSocket.send(string)
    }

    override suspend fun write(buffer: ReadBuffer) {
        val jsBuffer = buffer as JsBuffer
        val arrayBufferView =
            if (jsBuffer.sharedArrayBuffer != null) {
                // shared buffers are not allowed with websocket
                val copy = Int8Array(buffer.capacity)
                copy.set(jsBuffer.buffer)
                copy
            } else {
                buffer.buffer.subarray(0, buffer.limit())
            }
        webSocket.send(arrayBufferView)
    }

    override suspend fun isPingSupported(): Boolean = false

    override suspend fun ping(payloadData: ReadBuffer) { /*Not surfaced on browser*/ }

    override suspend fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        incomingMessageChannel.close()
        incomingTextChannel.close()
        incomingBinaryChannel.close()
        webSocket.close()
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
