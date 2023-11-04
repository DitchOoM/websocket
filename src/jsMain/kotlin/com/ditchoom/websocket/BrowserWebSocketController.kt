package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.ReadBuffer
import js.buffer.SharedArrayBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.ARRAYBUFFER
import org.w3c.dom.BinaryType
import org.w3c.dom.CloseEvent
import org.w3c.dom.WebSocket

class BrowserWebSocketController(
    connectionOptions: WebSocketConnectionOptions,
    private val zone: AllocationZone
) : WebSocketClient {
    private val url = connectionOptions.buildUrl()
    private val webSocket: WebSocket = if (connectionOptions.protocols.isNotEmpty()) {
        WebSocket(url, connectionOptions.protocols.first())
    } else {
        WebSocket(url)
    }

    private val _connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    override val connectionState = _connectionStateFlow.asStateFlow()
    private val _incomingMessageSharedFlow = MutableSharedFlow<WebSocketMessage>()
    override val incomingMessages = _incomingMessageSharedFlow.asSharedFlow()

    private val crossOriginIsolated = js("crossOriginIsolated") == true

    init {
        webSocket.binaryType = BinaryType.ARRAYBUFFER
    }

    override suspend fun localPort(): Int = throw UnsupportedOperationException("Unavailable on browser")
    override suspend fun remotePort(): Int = throw UnsupportedOperationException("Unavailable on browser")

    override fun connect(scope: CoroutineScope, tag: String) {
        webSocket.onclose = {
            val closeEvent = it as CloseEvent
            _connectionStateFlow.value = ConnectionState.Disconnected(
                code = closeEvent.code.toUShort(),
                reason = closeEvent.reason
            )
            closeInternal()
        }
        webSocket.onerror = {
            _connectionStateFlow.value = ConnectionState.Disconnected(Exception("Error on websocket: $it"))
            closeInternal()
        }
        webSocket.onmessage = {
            when (val data = it.data) {
                is ArrayBuffer -> {
                    val buffer = if (zone == AllocationZone.SharedMemory && crossOriginIsolated) {
                        val sharedArrayBuffer = SharedArrayBuffer(data.byteLength)
                        val array = Uint8Array(sharedArrayBuffer.unsafeCast<ArrayBuffer>())
                        array.set(Uint8Array(it.data as ArrayBuffer), 0)
                        JsBuffer(array, false, data.byteLength, data.byteLength, data.byteLength, sharedArrayBuffer)
                    } else {
                        if (zone == AllocationZone.SharedMemory && !crossOriginIsolated) {
                            console.warn(
                                "Failed to allocate shared buffer in BrowserWebSocketController.kt. " +
                                    "Please check and validate the appropriate headers are set on the http request as " +
                                    "defined in the SharedArrayBuffer MDN docs. see: " +
                                    "https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/SharedArrayBuffer#security_requirements"
                            )
                        }
                        val array = Uint8Array(data)
                        val buffer = JsBuffer(array)
                        buffer.setLimit(array.length)
                        buffer.setPosition(array.length)
                        buffer
                    }
                    scope.launch {
                        _incomingMessageSharedFlow.emit(WebSocketMessage.Binary(buffer))
                    }
                }
                is String -> scope.launch {
                    _incomingMessageSharedFlow.emit(WebSocketMessage.Text(data))
                }
                else -> throw IllegalArgumentException("Received invalid message type!")
            }
        }

        webSocket.onopen = { _ ->
            _connectionStateFlow.value = ConnectionState.Connected
            Unit
        }
    }

    override suspend fun write(string: String) {
        webSocket.send(string)
    }

    override suspend fun write(buffer: ReadBuffer) {
        val jsBuffer = buffer as JsBuffer
        val arrayBufferView = if (jsBuffer.sharedArrayBuffer != null) {
            // shared buffers are not allowed with websocket
            val copy = Uint8Array(buffer.capacity)
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
        webSocket.close()
    }
}
