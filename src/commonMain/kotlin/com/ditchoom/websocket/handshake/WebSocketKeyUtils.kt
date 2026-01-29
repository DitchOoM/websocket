package com.ditchoom.websocket.handshake

import com.ditchoom.websocket.generateWebSocketKey as platformGenerateKey

/**
 * WebSocket key generation and validation utilities.
 *
 * Per RFC 6455 Section 4.1 and 4.2.2.
 */

/**
 * The GUID used in Sec-WebSocket-Accept computation.
 *
 * Per RFC 6455 Section 4.2.2:
 * "The |Sec-WebSocket-Accept| header field indicates whether the server is
 * willing to accept the connection. [...] The server would use the
 * SHA-1 hash of the concatenation of the |Sec-WebSocket-Key| [...] with
 * the string '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'"
 */
const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

/**
 * Generates a new random WebSocket key.
 *
 * Per RFC 6455 Section 4.1:
 * "The value of this header field MUST be a nonce consisting of a randomly
 * selected 16-byte value that has been base64-encoded"
 *
 * This delegates to platform-specific implementations.
 */
fun generateWebSocketKey(): String = platformGenerateKey()

/**
 * Computes the expected Sec-WebSocket-Accept value for a given client key.
 *
 * Per RFC 6455 Section 4.2.2:
 * "The |Sec-WebSocket-Accept| header field indicates whether the server is
 * willing to accept the connection. If the server accepts the connection,
 * this header field must include a hash of the client's nonce sent in
 * |Sec-WebSocket-Key| as follows:
 *
 * 1. Take the value of the |Sec-WebSocket-Key| header field (as a string,
 *    not base64-decoded).
 * 2. Append the string '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'.
 * 3. SHA-1 hash the resulting string.
 * 4. base64-encode the 20-byte hash."
 *
 * @param clientKey The Sec-WebSocket-Key value sent by the client
 * @return The expected Sec-WebSocket-Accept value
 */
expect fun computeAcceptKey(clientKey: String): String
