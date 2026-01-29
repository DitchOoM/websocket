package com.ditchoom.websocket.handshake

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate

/**
 * Builder for WebSocket client handshake requests.
 *
 * Generates HTTP upgrade requests per RFC 6455 Section 4.1.
 *
 * ## RFC 6455 Section 4.1 - Client Requirements
 *
 * The client's opening handshake consists of:
 * 1. GET request with resource name as Request-URI
 * 2. Host header with server's authority
 * 3. Upgrade header containing "websocket"
 * 4. Connection header containing "Upgrade"
 * 5. Sec-WebSocket-Key header with base64-encoded 16-byte random value
 * 6. Sec-WebSocket-Version header with value "13"
 * 7. Optional: Origin header (required for browsers)
 * 8. Optional: Sec-WebSocket-Protocol header
 * 9. Optional: Sec-WebSocket-Extensions header
 */
class HandshakeRequest private constructor(
    val host: String,
    val port: Int,
    val path: String,
    val key: String,
    val protocols: List<String>,
    val extensions: List<String>,
    val additionalHeaders: Map<String, String>,
    val useTls: Boolean,
) {
    /**
     * The expected Sec-WebSocket-Accept value the server should return.
     *
     * Per RFC 6455 Section 4.2.2, this is computed as:
     * Base64(SHA-1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
     */
    val expectedAcceptKey: String by lazy {
        computeAcceptKey(key)
    }

    /**
     * Serializes this request to a buffer ready to send to the server.
     *
     * @return ReadBuffer containing the HTTP request bytes
     */
    fun toBuffer(): ReadBuffer {
        val request = buildString {
            // Request line: GET /path HTTP/1.1\r\n
            append("GET ")
            append(path)
            append(" HTTP/1.1\r\n")

            // Host header (RFC 6455 Section 4.1 #2)
            append("Host: ")
            append(hostHeader())
            append("\r\n")

            // Required headers (RFC 6455 Section 4.1 #3-6)
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ")
            append(key)
            append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")

            // Optional protocol header (RFC 6455 Section 4.1 #8)
            if (protocols.isNotEmpty()) {
                append("Sec-WebSocket-Protocol: ")
                append(protocols.joinToString(", "))
                append("\r\n")
            }

            // Optional extensions header (RFC 6455 Section 4.1 #9)
            if (extensions.isNotEmpty()) {
                append("Sec-WebSocket-Extensions: ")
                append(extensions.joinToString(", "))
                append("\r\n")
            }

            // Additional headers
            for ((name, value) in additionalHeaders) {
                append(name)
                append(": ")
                append(value)
                append("\r\n")
            }

            // Empty line terminates headers
            append("\r\n")
        }

        val bytes = request.encodeToByteArray()
        val buffer = PlatformBuffer.allocate(bytes.size)
        buffer.writeBytes(bytes)
        buffer.resetForRead()
        return buffer
    }

    /**
     * Constructs the Host header value.
     *
     * Per RFC 6455 Section 4.1, the port is omitted if it's the default
     * for the scheme (80 for ws://, 443 for wss://).
     */
    private fun hostHeader(): String {
        val defaultPort = if (useTls) 443 else 80
        return if (port == defaultPort) {
            host
        } else {
            "$host:$port"
        }
    }

    /**
     * Builder for constructing handshake requests.
     */
    class Builder(private val host: String, private val port: Int, private val path: String) {
        private var key: String = generateWebSocketKey()
        private var protocols: List<String> = emptyList()
        private var extensions: MutableList<String> = mutableListOf()
        private var additionalHeaders: MutableMap<String, String> = mutableMapOf()
        private var useTls: Boolean = false

        /**
         * Sets a specific WebSocket key (for testing).
         * Normally you should let the builder generate a random key.
         */
        fun key(key: String) = apply { this.key = key }

        /**
         * Sets the subprotocols to request.
         * Per RFC 6455 Section 4.1 #8.
         */
        fun protocols(vararg protocols: String) = apply {
            this.protocols = protocols.toList()
        }

        /**
         * Requests the permessage-deflate compression extension.
         * Per RFC 7692.
         *
         * @param clientNoContextTakeover If true, client won't reuse LZ77 window
         * @param serverNoContextTakeover If true, request server not reuse LZ77 window
         */
        fun requestCompression(
            clientNoContextTakeover: Boolean = true,
            serverNoContextTakeover: Boolean = true,
        ) = apply {
            val params = buildList {
                if (clientNoContextTakeover) add("client_no_context_takeover")
                if (serverNoContextTakeover) add("server_no_context_takeover")
            }
            val ext = if (params.isEmpty()) {
                "permessage-deflate"
            } else {
                "permessage-deflate; ${params.joinToString("; ")}"
            }
            extensions.add(ext)
        }

        /**
         * Adds a custom extension request.
         */
        fun extension(extension: String) = apply {
            extensions.add(extension)
        }

        /**
         * Adds an additional header.
         */
        fun header(name: String, value: String) = apply {
            additionalHeaders[name] = value
        }

        /**
         * Sets whether TLS is used (affects default port in Host header).
         */
        fun useTls(useTls: Boolean) = apply {
            this.useTls = useTls
        }

        /**
         * Builds the handshake request.
         */
        fun build(): HandshakeRequest = HandshakeRequest(
            host = host,
            port = port,
            path = path,
            key = key,
            protocols = protocols,
            extensions = extensions.toList(),
            additionalHeaders = additionalHeaders.toMap(),
            useTls = useTls,
        )
    }

    companion object {
        /**
         * Creates a builder for a handshake request.
         *
         * @param host The server hostname
         * @param port The server port
         * @param path The WebSocket endpoint path (e.g., "/ws" or "/chat")
         */
        fun builder(host: String, port: Int, path: String): Builder =
            Builder(host, port, path)
    }
}
