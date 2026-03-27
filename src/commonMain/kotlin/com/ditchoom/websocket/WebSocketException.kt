package com.ditchoom.websocket

/**
 * Sealed hierarchy for WebSocket-specific errors.
 *
 * Callers catch `WebSocketException` subtypes without importing `com.ditchoom.socket`.
 * The original socket-level exception is available in [cause] for `TransportFailed`.
 */
sealed class WebSocketException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Transport (TCP/TLS) failure. Original socket exception in [cause]. */
    class TransportFailed(
        message: String,
        cause: Throwable,
    ) : WebSocketException(message, cause)

    /** HTTP upgrade rejected or invalid handshake response. */
    class HandshakeRejected(
        message: String,
        val statusCode: Int? = null,
        cause: Throwable? = null,
    ) : WebSocketException(message, cause)

    /** RFC 6455 protocol violation (bad frame, invalid UTF-8, reserved opcode). */
    class ProtocolViolation(
        message: String,
        cause: Throwable? = null,
    ) : WebSocketException(message, cause)

    /** Connection closed — carries optional WebSocket close code and reason. */
    class ConnectionClosed(
        message: String,
        val code: UShort? = null,
        val reason: String? = null,
        cause: Throwable? = null,
    ) : WebSocketException(message, cause)
}
