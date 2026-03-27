package com.ditchoom.websocket

import com.ditchoom.socket.DefaultReconnectionClassifier
import com.ditchoom.socket.ReconnectDecision.GiveUp
import com.ditchoom.socket.ReconnectionClassifier

/**
 * WebSocket-aware reconnection classifier.
 *
 * Adds knowledge of [WebSocketException] subtypes on top of the socket-level
 * [DefaultReconnectionClassifier]. Delegates transport errors to the socket
 * classifier so TLS/DNS failures are still non-recoverable.
 */
class WebSocketReconnectionClassifier(
    private val delegate: DefaultReconnectionClassifier = DefaultReconnectionClassifier(),
) : ReconnectionClassifier {
    override suspend fun classify(error: Throwable) =
        when (error) {
            is WebSocketException.HandshakeRejected -> GiveUp // server said no
            is WebSocketException.ProtocolViolation -> GiveUp // bug, not transient
            is WebSocketException.TransportFailed -> delegate.classify(error.cause!!)
            is WebSocketException.ConnectionClosed -> {
                if (error.code == 1000.toUShort()) {
                    GiveUp // normal close
                } else {
                    delegate.classify(error)
                }
            }
            else -> delegate.classify(error)
        }

    fun reset() = delegate.reset()
}
