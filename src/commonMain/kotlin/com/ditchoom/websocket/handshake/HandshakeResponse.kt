package com.ditchoom.websocket.handshake

import com.ditchoom.buffer.ReadBuffer

/**
 * Represents the parsed result of a WebSocket server handshake response.
 *
 * Per RFC 6455 Section 4.2.2, a valid server response must include:
 * - HTTP/1.1 101 Switching Protocols status line
 * - Upgrade: websocket header
 * - Connection: Upgrade header
 * - Sec-WebSocket-Accept header with correct hash
 *
 * @property statusCode The HTTP status code (must be 101 for success)
 * @property statusReason The HTTP status reason phrase
 * @property headers Map of header names (lowercase) to values (preserves original case)
 * @property acceptKey The Sec-WebSocket-Accept value for validation
 * @property protocol The negotiated subprotocol, if any
 * @property extensions The negotiated extensions, if any
 * @property compressionEnabled Whether permessage-deflate was negotiated
 * @property compressionParams The compression parameters if compression is enabled
 */
data class HandshakeResponse(
    val statusCode: Int,
    val statusReason: String,
    val headers: Map<String, String>,
    val acceptKey: String?,
    val protocol: String?,
    val extensions: List<ExtensionOffer>,
    val compressionEnabled: Boolean,
    val compressionParams: CompressionParams?,
) {
    /**
     * Whether this response indicates a successful WebSocket upgrade.
     * Per RFC 6455 Section 4.2.2, status must be 101.
     */
    val isSuccessful: Boolean
        get() = statusCode == 101

    /**
     * Gets a header value by name (case-insensitive lookup).
     */
    fun getHeader(name: String): String? = headers[name.lowercase()]
}

/**
 * Parameters for permessage-deflate compression extension.
 * Per RFC 7692 Section 7.1.
 *
 * @property serverNoContextTakeover If true, server won't reuse LZ77 window between messages
 * @property clientNoContextTakeover If true, client won't reuse LZ77 window between messages
 * @property serverMaxWindowBits Maximum LZ77 window size the server will use (8-15)
 * @property clientMaxWindowBits Maximum LZ77 window size the client should use (8-15)
 */
data class CompressionParams(
    val serverNoContextTakeover: Boolean = false,
    val clientNoContextTakeover: Boolean = false,
    val serverMaxWindowBits: Int? = null,
    val clientMaxWindowBits: Int? = null,
)

/**
 * Represents a single extension offer/response in Sec-WebSocket-Extensions.
 * Per RFC 6455 Section 9.1.
 *
 * @property name The extension name (e.g., "permessage-deflate")
 * @property parameters Map of parameter names to values (null value means parameter has no value)
 */
data class ExtensionOffer(
    val name: String,
    val parameters: Map<String, String?>,
)

/**
 * Exception thrown when WebSocket handshake fails.
 */
class HandshakeException(
    message: String,
    val statusCode: Int? = null,
    val response: ReadBuffer? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
