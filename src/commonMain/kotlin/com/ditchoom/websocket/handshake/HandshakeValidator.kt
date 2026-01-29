package com.ditchoom.websocket.handshake

/**
 * Validates WebSocket server handshake responses per RFC 6455.
 *
 * ## RFC 6455 Section 4.1 - Client Requirements
 *
 * The client MUST validate that the server's response meets these criteria:
 *
 * 1. Status code is 101 Switching Protocols
 * 2. Upgrade header contains "websocket" (case-insensitive)
 * 3. Connection header contains "Upgrade" token (case-insensitive)
 * 4. Sec-WebSocket-Accept matches expected value
 * 5. Sec-WebSocket-Protocol (if present) was one of the client's offered protocols
 * 6. Sec-WebSocket-Extensions only contains extensions the client offered
 *
 * If any validation fails, the client MUST "Fail the WebSocket Connection".
 */
object HandshakeValidator {
    /**
     * Validates a parsed handshake response.
     *
     * @param response The parsed response to validate
     * @param expectedAcceptKey The expected Sec-WebSocket-Accept value (computed from client's key)
     * @param offeredProtocols List of protocols the client offered (may be empty)
     * @param offeredExtensions List of extensions the client offered (may be empty)
     * @return ValidationResult indicating success or failure with reason
     */
    fun validate(
        response: HandshakeResponse,
        expectedAcceptKey: String,
        offeredProtocols: List<String> = emptyList(),
        offeredExtensions: List<String> = emptyList(),
    ): ValidationResult {
        // RFC 6455 Section 4.1 #1: Status code must be 101
        // "If the status code received from the server is not 101, the client
        // handles the response per HTTP [RFC2616] procedures."
        if (response.statusCode != 101) {
            return ValidationResult.failure(
                ValidationError.INVALID_STATUS_CODE,
                "Expected status 101, got ${response.statusCode}",
            )
        }

        // RFC 6455 Section 4.1 #2: Upgrade header must contain "websocket"
        // "If the response lacks an |Upgrade| header field or the |Upgrade|
        // header field contains a value that is not an ASCII case-insensitive
        // match for the value 'websocket', the client MUST Fail the WebSocket Connection."
        val upgradeHeader = response.getHeader("upgrade")
        if (upgradeHeader == null || !upgradeHeader.equals("websocket", ignoreCase = true)) {
            return ValidationResult.failure(
                ValidationError.INVALID_UPGRADE_HEADER,
                "Upgrade header must be 'websocket', got: $upgradeHeader",
            )
        }

        // RFC 6455 Section 4.1 #3: Connection header must contain "Upgrade"
        // "If the response lacks a |Connection| header field or the |Connection|
        // header field doesn't contain a token that is an ASCII case-insensitive
        // match for the value 'Upgrade', the client MUST Fail the WebSocket Connection."
        val connectionHeader = response.getHeader("connection")
        if (connectionHeader == null || !containsToken(connectionHeader, "upgrade")) {
            return ValidationResult.failure(
                ValidationError.INVALID_CONNECTION_HEADER,
                "Connection header must contain 'Upgrade' token, got: $connectionHeader",
            )
        }

        // RFC 6455 Section 4.1 #4: Sec-WebSocket-Accept must match expected value
        // "If the response lacks a |Sec-WebSocket-Accept| header field or the
        // |Sec-WebSocket-Accept| contains a value other than the base64-encoded
        // SHA-1 of the concatenation of the |Sec-WebSocket-Key| (as a string,
        // not base64-decoded) with the string '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
        // [...], the client MUST Fail the WebSocket Connection."
        if (response.acceptKey == null) {
            return ValidationResult.failure(
                ValidationError.MISSING_ACCEPT_KEY,
                "Sec-WebSocket-Accept header is missing",
            )
        }
        if (response.acceptKey != expectedAcceptKey) {
            return ValidationResult.failure(
                ValidationError.INVALID_ACCEPT_KEY,
                "Sec-WebSocket-Accept mismatch: expected '$expectedAcceptKey', got '${response.acceptKey}'",
            )
        }

        // RFC 6455 Section 4.1 #5: Protocol must be one we offered
        // "If the response includes a |Sec-WebSocket-Protocol| header field and
        // this header field indicates the use of a subprotocol that was not
        // present in the client's handshake (the server has indicated a subprotocol
        // not requested by the client), the client MUST Fail the WebSocket Connection."
        if (response.protocol != null && offeredProtocols.isNotEmpty()) {
            if (response.protocol !in offeredProtocols) {
                return ValidationResult.failure(
                    ValidationError.INVALID_PROTOCOL,
                    "Server selected protocol '${response.protocol}' which was not offered. Offered: $offeredProtocols",
                )
            }
        }

        // RFC 6455 Section 4.1 #6: Extensions must be ones we offered
        // "If the response includes a |Sec-WebSocket-Extensions| header field and
        // this header field indicates the use of an extension that was not present
        // in the client's handshake (the server has indicated an extension not
        // requested by the client), the client MUST Fail the WebSocket Connection."
        if (offeredExtensions.isNotEmpty()) {
            for (ext in response.extensions) {
                if (ext.name !in offeredExtensions) {
                    return ValidationResult.failure(
                        ValidationError.INVALID_EXTENSION,
                        "Server offered extension '${ext.name}' which was not requested. Requested: $offeredExtensions",
                    )
                }
            }
        }

        return ValidationResult.success()
    }

    /**
     * Checks if a header value contains a specific token (case-insensitive).
     *
     * Tokens in HTTP headers are comma-separated.
     * Per RFC 7230 Section 3.2.6.
     */
    private fun containsToken(headerValue: String, token: String): Boolean {
        val tokens = headerValue.split(',')
        return tokens.any { it.trim().equals(token, ignoreCase = true) }
    }
}

/**
 * Result of handshake validation.
 */
sealed class ValidationResult {
    data object Success : ValidationResult()

    data class Failure(
        val error: ValidationError,
        val message: String,
    ) : ValidationResult()

    val isSuccess: Boolean
        get() = this is Success

    companion object {
        fun success(): ValidationResult = Success

        fun failure(error: ValidationError, message: String): ValidationResult =
            Failure(error, message)
    }
}

/**
 * Types of handshake validation errors.
 *
 * Each corresponds to a specific RFC requirement.
 */
enum class ValidationError {
    /** RFC 6455 Section 4.1 - Status code is not 101 */
    INVALID_STATUS_CODE,

    /** RFC 6455 Section 4.1 - Upgrade header missing or invalid */
    INVALID_UPGRADE_HEADER,

    /** RFC 6455 Section 4.1 - Connection header missing or doesn't contain Upgrade */
    INVALID_CONNECTION_HEADER,

    /** RFC 6455 Section 4.1 - Sec-WebSocket-Accept header missing */
    MISSING_ACCEPT_KEY,

    /** RFC 6455 Section 4.1 - Sec-WebSocket-Accept value doesn't match expected */
    INVALID_ACCEPT_KEY,

    /** RFC 6455 Section 4.1 - Server selected a protocol not offered by client */
    INVALID_PROTOCOL,

    /** RFC 6455 Section 4.1 - Server offered an extension not requested by client */
    INVALID_EXTENSION,
}
