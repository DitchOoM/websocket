@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.websocket.apple

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.wrapReadOnly
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketMessage
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionWebSocketCloseCode
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSURLSessionWebSocketMessageTypeData
import platform.Foundation.NSURLSessionWebSocketMessageTypeString
import platform.Foundation.NSURLSessionWebSocketTask
import platform.Foundation.create
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Apple-native WebSocket connection wrapping `NSURLSessionWebSocketTask`.
 *
 * Unlike the TCP-backed code path that implements RFC 6455 framing directly,
 * this controller delegates framing to Apple's Foundation framework. Text
 * frames surface as [String], binary frames flow through [binaryCodec].
 *
 * ### Platform limitations
 *
 *  - Ping sends use `sendPingWithPongReceiveHandler` — no custom app-data
 *    payload (the RFC 6455 ping body is not exposed by the Foundation API).
 *  - Pong sends are no-ops — `NSURLSessionWebSocketTask` replies to pings
 *    automatically.
 *  - `compressionOptions` on [WebSocketConnectionOptions] is ignored; the
 *    system negotiates permessage-deflate itself.
 *  - TLS configuration uses the system trust store; custom certificates
 *    require an `NSURLSessionDelegate` with `didReceiveChallenge`.
 */
class AppleWebSocketController<B>(
    private val connectionOptions: WebSocketConnectionOptions,
    private val binaryCodec: Codec<B>,
    private val bufferFactory: BufferFactory,
    parentScope: CoroutineScope?,
) : Connection<WebSocketMessage<B>> {
    override val id: Long = 0L
    private var closed = false
    private val connectDeferred = CompletableDeferred<Unit>()
    private val incomingMessageChannel = Channel<WebSocketMessage<B>>(Channel.UNLIMITED)

    private val scope: CoroutineScope =
        if (parentScope == null) {
            CoroutineScope(
                Dispatchers.Default +
                    CoroutineName("AppleWebSocket: ${connectionOptions.name}:${connectionOptions.port}"),
            )
        } else {
            parentScope + Dispatchers.Default +
                CoroutineName("AppleWebSocket: ${connectionOptions.name}:${connectionOptions.port}")
        }

    private val sessionDelegate =
        WebSocketSessionDelegate(
            connectDeferred = connectDeferred,
            onClose = {
                closed = true
                incomingMessageChannel.close()
            },
        )

    private val session: NSURLSession =
        NSURLSession.sessionWithConfiguration(
            NSURLSessionConfiguration.defaultSessionConfiguration,
            sessionDelegate,
            NSOperationQueue.mainQueue,
        )

    private val url = NSURL(string = connectionOptions.buildUrl())

    private val task: NSURLSessionWebSocketTask =
        if (connectionOptions.protocols.isNotEmpty()) {
            session.webSocketTaskWithURL(url, connectionOptions.protocols)
        } else {
            session.webSocketTaskWithURL(url)
        }

    override fun receive(): Flow<WebSocketMessage<B>> = incomingMessageChannel.receiveAsFlow()

    internal suspend fun connect() {
        task.resume()
        withTimeout(connectionOptions.connectionTimeout) {
            connectDeferred.await()
        }
        scope.launch { readLoop() }
    }

    private suspend fun readLoop() {
        while (!closed && scope.isActive) {
            try {
                val nsMessage = receiveOneMessage()
                val wsMessage = convertMessage(nsMessage)
                incomingMessageChannel.trySend(wsMessage)
            } catch (_: Exception) {
                if (!closed) {
                    closed = true
                    incomingMessageChannel.close()
                }
                break
            }
        }
    }

    private suspend fun receiveOneMessage(): NSURLSessionWebSocketMessage =
        suspendCancellableCoroutine { cont ->
            task.receiveMessageWithCompletionHandler { message, error ->
                if (error != null) {
                    cont.resumeWithException(
                        Exception(error.localizedDescription ?: "WebSocket receive error"),
                    )
                } else if (message != null) {
                    cont.resume(message)
                } else {
                    cont.resumeWithException(Exception("WebSocket received null message"))
                }
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun convertMessage(nsMessage: NSURLSessionWebSocketMessage): WebSocketMessage<B> =
        when (nsMessage.type) {
            NSURLSessionWebSocketMessageTypeString -> {
                WebSocketMessage.Text(nsMessage.string!!)
            }
            NSURLSessionWebSocketMessageTypeData -> {
                val buffer = PlatformBuffer.wrapReadOnly(nsMessage.data!!)
                val payload = binaryCodec.decode(buffer, DecodeContext.Empty)
                WebSocketMessage.Binary(payload)
            }
            else -> throw IllegalStateException("Unknown NSURLSessionWebSocketMessage type: ${nsMessage.type}")
        }

    override suspend fun send(message: WebSocketMessage<B>) {
        when (message) {
            is WebSocketMessage.Text -> {
                val nsMessage = NSURLSessionWebSocketMessage(message.payload)
                sendNsMessage(nsMessage)
            }
            is WebSocketMessage.Binary -> {
                val scratch = bufferFactory.allocate(256)
                binaryCodec.encode(scratch, message.payload, EncodeContext.Empty)
                val size = scratch.position()
                scratch.resetForRead()
                val bytes = ByteArray(size)
                for (i in 0 until size) {
                    bytes[i] = scratch.readByte()
                }
                scratch.freeIfNeeded()
                val nsData = bytes.toNSData()
                val nsMessage = NSURLSessionWebSocketMessage(nsData)
                sendNsMessage(nsMessage)
            }
            is WebSocketMessage.Ping -> {
                task.sendPingWithPongReceiveHandler { error ->
                    if (error != null) {
                        // Ping failed — connection is likely broken
                    }
                }
            }
            is WebSocketMessage.Pong -> {
                // NSURLSessionWebSocketTask sends pongs automatically
            }
            is WebSocketMessage.Close -> {
                close()
            }
        }
    }

    private suspend fun sendNsMessage(nsMessage: NSURLSessionWebSocketMessage) =
        suspendCancellableCoroutine<Unit> { cont ->
            task.sendMessage(nsMessage) { error ->
                if (error != null) {
                    cont.resumeWithException(
                        Exception(error.localizedDescription ?: "WebSocket send error"),
                    )
                } else {
                    cont.resume(Unit)
                }
            }
        }

    override suspend fun close() {
        if (!closed) {
            closed = true
            task.cancelWithCloseCode(1000.convert(), null)
            incomingMessageChannel.close()
            session.invalidateAndCancel()
        }
    }
}

private fun ByteArray.toNSData(): NSData =
    usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.convert())
    }

private class WebSocketSessionDelegate(
    private val connectDeferred: CompletableDeferred<Unit>,
    private val onClose: () -> Unit,
) : NSObject(),
    NSURLSessionWebSocketDelegateProtocol {
    override fun URLSession(
        session: NSURLSession,
        webSocketTask: NSURLSessionWebSocketTask,
        didOpenWithProtocol: String?,
    ) {
        connectDeferred.complete(Unit)
    }

    override fun URLSession(
        session: NSURLSession,
        webSocketTask: NSURLSessionWebSocketTask,
        didCloseWithCode: NSURLSessionWebSocketCloseCode,
        reason: NSData?,
    ) {
        onClose()
    }
}
