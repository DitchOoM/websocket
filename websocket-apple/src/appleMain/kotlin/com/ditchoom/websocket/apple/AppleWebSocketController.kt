@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.websocket.apple

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.toNativeData
import com.ditchoom.buffer.wrapReadOnly
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketException
import com.ditchoom.websocket.WebSocketMessage
import com.ditchoom.websocket.encodeToBuffer
import kotlinx.cinterop.convert
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
import platform.Foundation.NSError
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionWebSocketCloseCode
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSURLSessionWebSocketMessageTypeData
import platform.Foundation.NSURLSessionWebSocketMessageTypeString
import platform.Foundation.NSURLSessionWebSocketTask
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
 *  - TLS configuration uses the system trust store unless [authChallengeHandler]
 *    is set. The handler receives every URLSession auth challenge (server trust,
 *    client cert, basic auth, etc.); a non-null returned [NSURLCredential] is
 *    applied with `useCredential`, null falls through to `performDefaultHandling`.
 *    Primary use case: K/N test binaries spawned via `xcrun simctl spawn` on
 *    iOS / tvOS / watchOS simulators get -1202 from default handling for any
 *    public TLS endpoint (Safari trusts the same cert fine — the spawn
 *    environment lacks app-level trust eval entitlements). Tests pin
 *    server trust explicitly via this hook.
 */
class AppleWebSocketController<B>(
    private val connectionOptions: WebSocketConnectionOptions,
    private val binaryCodec: Codec<B>,
    private val bufferFactory: BufferFactory,
    parentScope: CoroutineScope?,
    private val authChallengeHandler: ((NSURLAuthenticationChallenge) -> NSURLCredential?)? = null,
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
            authChallengeHandler = authChallengeHandler,
        )

    // Dedicated serial delegate queue per session. Using
    // NSOperationQueue.mainQueue would make every NSURLSession delegate
    // callback (didOpenWithProtocol, didCloseWithCode, didCompleteWithError)
    // require a pumping main runloop — which UI apps have but Kotlin/Native
    // unit tests, command-line tools, and background services don't. The
    // pre-v2 code tied us to mainQueue, which is exactly why the
    // NativeWebSocketClientTest times out after 15s on the macOS/iOS
    // simulator test binaries: the handshake completes at the TCP/TLS
    // layer but `didOpenWithProtocol` never fires because nothing is
    // draining the main queue. A fresh NSOperationQueue is serviced by a
    // Foundation-managed background thread and fires delegate callbacks
    // regardless of whether the hosting environment has a main runloop.
    private val delegateQueue = NSOperationQueue()

    private val session: NSURLSession =
        NSURLSession.sessionWithConfiguration(
            NSURLSessionConfiguration.defaultSessionConfiguration,
            sessionDelegate,
            delegateQueue,
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
                // encodeToBuffer uses buffer-codec's GrowableWriteBuffer
                // (2x doubling) so the pre-v2 fixed 256-byte scratch —
                // which would have thrown BufferOverflowException on any
                // payload > 256 bytes — is gone. toNativeData().nsData
                // bridges to NSURLSessionWebSocketMessage zero-copy (or at
                // worst one subdataWithRange memcpy) against the scratch's
                // NSMutableData.
                val encoded = binaryCodec.encodeToBuffer(message.payload, bufferFactory)
                try {
                    val nsData = encoded.toNativeData().nsData
                    val nsMessage = NSURLSessionWebSocketMessage(nsData)
                    sendNsMessage(nsMessage)
                } finally {
                    encoded.freeIfNeeded()
                }
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

private class WebSocketSessionDelegate(
    private val connectDeferred: CompletableDeferred<Unit>,
    private val onClose: () -> Unit,
    private val authChallengeHandler: ((NSURLAuthenticationChallenge) -> NSURLCredential?)? = null,
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

    /**
     * Fires when the task ends — cleanly or with an error. Without this
     * delegate method, a network failure during handshake (DNS, TLS
     * failure, connection refused, etc.) surfaces as a 15s timeout on
     * [connectDeferred.await] rather than a catchable exception, because
     * neither `didOpenWithProtocol` nor `didCloseWithCode` fires for
     * pre-upgrade failures. Completing the deferred exceptionally here
     * turns those into a [WebSocketException.TransportFailed] the caller
     * can pattern-match on — matching the shape that [BrowserWebSocketController]
     * produces from its own `onerror` platform hook.
     */
    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        if (didCompleteWithError != null) {
            if (!connectDeferred.isCompleted) {
                // NSError is a Foundation object, not a Kotlin Throwable, so
                // wrap it as a plain Exception with its localizedDescription
                // + domain + code. This mirrors how BrowserWebSocketController
                // wraps the JS `Event` object it receives from `onerror`.
                val nsMessage = didCompleteWithError.localizedDescription
                val nsDomain = didCompleteWithError.domain
                val nsCode = didCompleteWithError.code
                connectDeferred.completeExceptionally(
                    WebSocketException.TransportFailed(
                        message = "WebSocket connect failed: $nsMessage (domain=$nsDomain, code=$nsCode)",
                        cause = Exception("NSError $nsDomain:$nsCode — $nsMessage"),
                    ),
                )
            }
            onClose()
        }
    }

    /**
     * Per-task auth challenge dispatch. Only installed when the controller was
     * built with a handler — without one, NSURLSession does its standard default
     * handling. With one, the handler decides per challenge: a non-null
     * NSURLCredential applies via `useCredential`, null falls through to
     * `performDefaultHandling` (so the handler can opt-out per challenge type
     * — e.g. accept server trust but defer client-cert challenges to the system).
     */
    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didReceiveChallenge: NSURLAuthenticationChallenge,
        completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit,
    ) {
        val handler = authChallengeHandler
        if (handler == null) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            return
        }
        val credential = handler(didReceiveChallenge)
        if (credential != null) {
            completionHandler(NSURLSessionAuthChallengeUseCredential, credential)
        } else {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
        }
    }
}
