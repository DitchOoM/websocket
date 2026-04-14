package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.codec.payload.ReadBufferPayloadReader
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.websocket.internal.GrowableWriteBuffer
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

/**
 * Browser WebSocket connection wrapping the native `WebSocket` API.
 *
 * Unlike the TCP-backed code path that implements RFC 6455 framing directly,
 * the browser path delegates framing to the native `WebSocket`. The codec still
 * bridges `P` to/from payload bytes:
 * - **Receive**: text frames arrive as `String` (per WebSocket spec, text is always
 *   delivered as String regardless of `binaryType`). We encode that String's UTF-8
 *   bytes into a scratch buffer, wrap it in a `PayloadReader`, and invoke
 *   `codec.decode`. Binary frames arrive as `ArrayBuffer` and flow through the codec
 *   via a zero-copy slice.
 * - **Send**: `codec.encode` writes bytes into a `GrowableWriteBuffer`. For `Binary`
 *   we extract the bytes as an `ArrayBuffer`. For `Text` we decode the bytes back to
 *   a String so the native API emits a text frame.
 *
 * The text-frame round-trip (String → bytes → String) is a small overhead to keep
 * the API shape uniform with the TCP/native path. Callers who need browser-optimal
 * throughput can pin `P = String` + `StringCodec` and rely on the UTF-8 encode/decode
 * being cheap; or for perf-critical paths, use the Node.js / TCP transport.
 */
class BrowserWebSocketController<P>(
    private val connectionOptions: WebSocketConnectionOptions,
    private val payloadCodec: PayloadCodec<P>,
    private val bufferFactory: BufferFactory,
    parentScope: CoroutineScope?,
    private val useSharedMemory: Boolean = false,
) : Connection<WebSocketMessage<P>> {
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
    private val incomingMessageChannel = Channel<WebSocketMessage<P>>(Channel.UNLIMITED)

    override fun receive(): Flow<WebSocketMessage<P>> = incomingMessageChannel.receiveAsFlow()

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
                        val reader = ReadBufferPayloadReader(buffer)
                        val payload = with(payloadCodec) { reader.decode() }
                        buffer.freeIfNeeded()
                        incomingMessageChannel.trySend(WebSocketMessage.Binary(payload))
                    }
                }
                is String ->
                    scope.launch {
                        // Encode the String as UTF-8 into a scratch buffer so the codec
                        // can decode from a uniform PayloadReader interface.
                        val bytes = data.encodeToByteArray()
                        val scratch = bufferFactory.allocate(bytes.size)
                        scratch.writeBytes(bytes)
                        scratch.resetForRead()
                        val reader = ReadBufferPayloadReader(scratch)
                        val payload = with(payloadCodec) { reader.decode() }
                        scratch.freeIfNeeded()
                        incomingMessageChannel.trySend(WebSocketMessage.Text(payload))
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

    override suspend fun send(message: WebSocketMessage<P>) {
        when (message) {
            is WebSocketMessage.Text -> {
                val scratch = GrowableWriteBuffer(bufferFactory, initialSize = 256)
                with(payloadCodec) { scratch.encode(message.payload) }
                val inner = scratch.underlying
                val size = inner.position()
                inner.position(0)
                inner.setLimit(size)
                // Text frame: convert bytes back to a String so the browser emits as text.
                val text = inner.readString(size, Charset.UTF8)
                inner.freeIfNeeded()
                webSocket.send(text)
            }
            is WebSocketMessage.Binary -> {
                val scratch = GrowableWriteBuffer(bufferFactory, initialSize = 256)
                with(payloadCodec) { scratch.encode(message.payload) }
                val inner = scratch.underlying
                val size = inner.position()
                inner.position(0)
                inner.setLimit(size)
                val jsBuffer = inner as JsBuffer
                val arrayBufferView =
                    if (jsBuffer.sharedArrayBuffer != null) {
                        val copy = Int8Array(size)
                        copy.set(jsBuffer.buffer.subarray(0, size))
                        copy
                    } else {
                        jsBuffer.buffer.subarray(0, size)
                    }
                webSocket.send(arrayBufferView)
                // Note: browser-owned ArrayBuffer keeps the memory alive until the
                // send completes; freeIfNeeded after webSocket.send is safe since the
                // Int8Array.subarray view only references the bytes, and the browser
                // has already copied them into its own send queue.
                inner.freeIfNeeded()
            }
            is WebSocketMessage.Ping,
            is WebSocketMessage.Pong,
            -> { /* Browser WebSocket API does not expose app-level ping/pong */ }
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
