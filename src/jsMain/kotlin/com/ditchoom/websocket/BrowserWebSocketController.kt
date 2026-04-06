package com.ditchoom.websocket

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.pool.BufferPool
import js.buffer.SharedArrayBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
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
    private val useSharedMemory: Boolean = false,
) : Connection<WebSocketMessage> {
    private val scope =
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

    override val id: Long = 0L
    private var closed = false
    private val connectDeferred = CompletableDeferred<Unit>()
    private val incomingMessageChannel = Channel<WebSocketMessage>(Channel.UNLIMITED)

    override fun receive(): Flow<WebSocketMessage> = incomingMessageChannel.receiveAsFlow()

    private val crossOriginIsolated = js("crossOriginIsolated") == true

    init {
        webSocket.binaryType = BinaryType.ARRAYBUFFER
    }

    internal suspend fun connect() {
        webSocket.onclose = {
            val closeEvent = it as CloseEvent
            val code = closeEvent.code.toUShort()
            val reason = closeEvent.reason
            closed = true
            closeInternal()
            if (!connectDeferred.isCompleted) {
                connectDeferred.completeExceptionally(
                    WebSocketException.TransportFailed(
                        "WebSocket closed during connect: code=$code, reason=$reason",
                        Exception("Connection failed"),
                    ),
                )
            }
        }
        webSocket.onerror = {
            closed = true
            closeInternal()
            if (!connectDeferred.isCompleted) {
                connectDeferred.completeExceptionally(
                    WebSocketException.TransportFailed("WebSocket error", Exception("$it")),
                )
            }
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
                    }
                }
                is String ->
                    scope.launch {
                        incomingMessageChannel.trySend(WebSocketMessage.Text(data))
                    }
                else -> throw IllegalArgumentException("Received invalid message type!")
            }
        }

        webSocket.onopen = { _ ->
            connectDeferred.complete(Unit)
            Unit
        }
        withTimeout(connectionOptions.connectionTimeout) {
            connectDeferred.await()
        }
    }

    override suspend fun send(message: WebSocketMessage) {
        when (message) {
            is WebSocketMessage.Text -> webSocket.send(message.value)
            is WebSocketMessage.Binary -> {
                val jsBuffer = message.value as JsBuffer
                val arrayBufferView =
                    if (jsBuffer.sharedArrayBuffer != null) {
                        val copy = Int8Array(message.value.capacity)
                        copy.set(jsBuffer.buffer)
                        copy
                    } else {
                        message.value.buffer.subarray(0, message.value.limit())
                    }
                webSocket.send(arrayBufferView)
            }
            is WebSocketMessage.Ping,
            is WebSocketMessage.Pong,
            -> { /* Not surfaced on browser */ }
            is WebSocketMessage.Close -> webSocket.close()
        }
    }

    override suspend fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        closed = true
        incomingMessageChannel.close()
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
