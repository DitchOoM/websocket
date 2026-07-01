package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
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
 * Unlike the TCP-backed code path that implements RFC 6455 framing directly, the
 * browser path delegates framing to the native `WebSocket`. Text frames always
 * surface as [String] (per the WebSocket spec) so they pass through without a codec.
 * Binary frames arrive as `ArrayBuffer` and flow through [binaryCodec] via a
 * zero-copy slice on decode; on send, the codec writes into a `GrowableWriteBuffer`
 * that we hand to the native API as an `ArrayBuffer`.
 *
 * ### Browser `WebSocket` API limitations
 *
 * The following [WebSocketMessage] and [WebSocketConnectionOptions] features are
 * unreachable on this target. Consumer code that compiles on JVM / Node / Native
 * will compile here too, but these operations become no-ops or are ignored:
 *  - [WebSocketMessage.Ping] / [WebSocketMessage.Pong] sends do nothing — the
 *    browser manages protocol keep-alive internally and no API surfaces it to
 *    page JavaScript.
 *  - [WebSocketConnectionOptions.compressionOptions] (window bits, context
 *    takeover) is ignored; the browser negotiates permessage-deflate itself.
 *  - Close codes outside 1000 and 3000–4999 are rejected by the browser before
 *    they hit the wire. [send] of a [WebSocketMessage.Close] only issues
 *    `webSocket.close()` with the default code.
 *  - Custom HTTP upgrade headers other than `Sec-WebSocket-Protocol` cannot be
 *    set because no spec-level browser API exists.
 *  - TLS configuration, client certificates, and local/remote port introspection
 *    are all handled by the browser and are not available to this class.
 *
 * See `docs/docs/platforms/javascript.md` for the full feature matrix.
 */
class BrowserWebSocketController<B>(
    private val connectionOptions: WebSocketConnectionOptions,
    private val binaryCodec: Codec<B>,
    private val bufferFactory: BufferFactory,
    parentScope: CoroutineScope?,
    private val useSharedMemory: Boolean = false,
) : Connection<WebSocketMessage<B>> {
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
    private val incomingMessageChannel = Channel<WebSocketMessage<B>>(Channel.UNLIMITED)

    /**
     * On the browser path the native `WebSocket` manages its own bytes, so there is no
     * library-owned cleanup buffer to release per message — we return the channel as-is.
     * The JVM / Linux code path uses an envelope + cleanup hook; parity is structural,
     * not observable.
     */
    override fun receive(): Flow<WebSocketMessage<B>> = incomingMessageChannel.receiveAsFlow()

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
                        val payload = binaryCodec.decode(buffer, DecodeContext.Empty)
                        buffer.freeIfNeeded()
                        incomingMessageChannel.trySend(WebSocketMessage.Binary(payload))
                    }
                }
                is String -> {
                    // RFC 6455 §5.6 guarantees text is UTF-8; the browser already delivers
                    // it as a decoded String, so we pass it through without a codec.
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

    override suspend fun send(message: WebSocketMessage<B>) {
        when (message) {
            is WebSocketMessage.Text -> {
                // Browser's WebSocket.send(String) already handles UTF-8 encoding.
                webSocket.send(message.payload)
            }
            is WebSocketMessage.Binary -> {
                val scratch = GrowableWriteBuffer(bufferFactory, initialSize = 256)
                binaryCodec.encode(scratch, message.payload, EncodeContext.Empty)
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
