package com.ditchoom.websocket.handshake

import com.ditchoom.buffer.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [HandshakeRequest].
 *
 * Tests verify RFC 6455 Section 4.1 compliance for client handshake requests.
 *
 * References:
 * - RFC 6455 Section 4.1: Client Requirements
 *   https://datatracker.ietf.org/doc/html/rfc6455#section-4.1
 * - RFC 7692 Section 7: permessage-deflate Extension
 *   https://datatracker.ietf.org/doc/html/rfc7692#section-7
 */
class HandshakeRequestTest {
    // ========================================================================
    // RFC 6455 Section 4.1 - Required Headers
    // https://datatracker.ietf.org/doc/html/rfc6455#section-4.1
    // ========================================================================

    @Test
    fun `RFC 6455 Section 4-1 - request includes GET method`() {
        // "The method of the request MUST be GET"
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.startsWith("GET "))
    }

    @Test
    fun `RFC 6455 Section 4-1 - request includes HTTP 1-1`() {
        // "and the HTTP version MUST be at least 1.1"
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("HTTP/1.1"))
    }

    @Test
    fun `RFC 6455 Section 4-1 - request includes path in Request-URI`() {
        // "The 'Request-URI' part of the request MUST match the resource name"
        val request = HandshakeRequest.builder("example.com", 80, "/chat/room1").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.startsWith("GET /chat/room1 HTTP/1.1"))
    }

    @Test
    fun `RFC 6455 Section 4-1 - request includes Host header`() {
        // "The request MUST contain a |Host| header field whose value contains
        // /host/ plus optionally ':' followed by /port/"
        val request = HandshakeRequest.builder("example.com", 8080, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("Host: example.com:8080"))
    }

    @Test
    fun `RFC 6455 Section 4-1 - Host header omits default port 80`() {
        // "When the default port for the scheme is used (port 80 for ws://),
        // the port component may be omitted"
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .useTls(false)
                .build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("Host: example.com\r\n"))
        assertFalse(text.contains("Host: example.com:80"))
    }

    @Test
    fun `RFC 6455 Section 4-1 - Host header omits default port 443 for TLS`() {
        // "port 443 for wss://"
        val request =
            HandshakeRequest
                .builder("example.com", 443, "/ws")
                .useTls(true)
                .build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("Host: example.com\r\n"))
        assertFalse(text.contains("Host: example.com:443"))
    }

    @Test
    fun `RFC 6455 Section 4-1 - request includes Upgrade header`() {
        // "The request MUST contain an |Upgrade| header field whose value
        // MUST include the 'websocket' keyword"
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("Upgrade: websocket"))
    }

    @Test
    fun `RFC 6455 Section 4-1 - request includes Connection header`() {
        // "The request MUST contain a |Connection| header field whose value
        // MUST include the 'Upgrade' token"
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("Connection: Upgrade"))
    }

    @Test
    fun `RFC 6455 Section 4-1 - request includes Sec-WebSocket-Key`() {
        // "The request MUST include a header field with the name
        // |Sec-WebSocket-Key|. The value of this header field MUST be a nonce
        // consisting of a randomly selected 16-byte value that has been
        // base64-encoded"
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("Sec-WebSocket-Key: "))
        // Base64 of 16 bytes = 24 characters (including padding)
        val keyLine = text.lines().find { it.startsWith("Sec-WebSocket-Key:") }
        val key = keyLine?.substringAfter("Sec-WebSocket-Key: ")?.trim()
        // Base64 encoded 16 bytes = 24 characters
        assertEquals(24, key?.length, "Key should be 24 characters (base64 of 16 bytes)")
    }

    @Test
    fun `RFC 6455 Section 4-1 - request includes Sec-WebSocket-Version 13`() {
        // "The request MUST include a header field with the name
        // |Sec-WebSocket-Version|. The value of this header field MUST be 13."
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("Sec-WebSocket-Version: 13"))
    }

    // ========================================================================
    // RFC 6455 Section 4.1 - Optional Headers
    // https://datatracker.ietf.org/doc/html/rfc6455#section-4.1
    // ========================================================================

    @Test
    fun `RFC 6455 Section 4-1 - request may include Sec-WebSocket-Protocol`() {
        // "The request MAY include a header field with the name
        // |Sec-WebSocket-Protocol|. If present, this value indicates one or
        // more comma-separated subprotocol the client wishes to speak"
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .protocols("chat", "superchat")
                .build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("Sec-WebSocket-Protocol: chat, superchat"))
    }

    @Test
    fun `RFC 6455 Section 4-1 - no protocol header when not specified`() {
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertFalse(text.contains("Sec-WebSocket-Protocol"))
    }

    @Test
    fun `RFC 6455 Section 4-1 - request may include Sec-WebSocket-Extensions`() {
        // "The request MAY include a header field with the name
        // |Sec-WebSocket-Extensions|"
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .extension("permessage-deflate")
                .build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("Sec-WebSocket-Extensions: permessage-deflate"))
    }

    // ========================================================================
    // RFC 7692 Section 7 - permessage-deflate Extension
    // https://datatracker.ietf.org/doc/html/rfc7692#section-7
    // ========================================================================

    @Test
    fun `RFC 7692 Section 7 - requestCompression adds permessage-deflate`() {
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .requestCompression()
                .build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("permessage-deflate"))
    }

    @Test
    fun `RFC 7692 Section 7-1-1 - client_no_context_takeover parameter`() {
        // "A client MAY include the 'client_no_context_takeover' extension
        // parameter in an extension negotiation offer"
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .requestCompression(clientNoContextTakeover = true, serverNoContextTakeover = false)
                .build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("client_no_context_takeover"))
        assertFalse(text.contains("server_no_context_takeover"))
    }

    @Test
    fun `RFC 7692 Section 7-1-1 - server_no_context_takeover parameter`() {
        // "A client MAY include the 'server_no_context_takeover' extension
        // parameter in an extension negotiation offer"
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .requestCompression(clientNoContextTakeover = false, serverNoContextTakeover = true)
                .build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("server_no_context_takeover"))
        assertFalse(text.contains("client_no_context_takeover"))
    }

    @Test
    fun `RFC 7692 Section 7-1-1 - both context takeover parameters`() {
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .requestCompression(clientNoContextTakeover = true, serverNoContextTakeover = true)
                .build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("client_no_context_takeover"))
        assertTrue(text.contains("server_no_context_takeover"))
    }

    @Test
    fun `RFC 7692 Section 7-1-2 - server_max_window_bits parameter`() {
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .requestCompression(
                    clientNoContextTakeover = false,
                    serverNoContextTakeover = false,
                    serverMaxWindowBits = 8,
                ).build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("server_max_window_bits=8"))
        assertFalse(text.contains("client_no_context_takeover"))
        assertFalse(text.contains("server_no_context_takeover"))
        assertFalse(text.contains("client_max_window_bits"))
    }

    @Test
    fun `RFC 7692 Section 7-1-2 - client_max_window_bits parameter`() {
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .requestCompression(
                    clientNoContextTakeover = false,
                    serverNoContextTakeover = false,
                    clientMaxWindowBits = 12,
                ).build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("client_max_window_bits=12"))
        assertFalse(text.contains("server_max_window_bits"))
    }

    @Test
    fun `RFC 7692 Section 7-1-2 - all compression parameters combined`() {
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .requestCompression(
                    clientNoContextTakeover = true,
                    serverNoContextTakeover = true,
                    serverMaxWindowBits = 8,
                    clientMaxWindowBits = 15,
                ).build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("client_no_context_takeover"))
        assertTrue(text.contains("server_no_context_takeover"))
        assertTrue(text.contains("server_max_window_bits=8"))
        assertTrue(text.contains("client_max_window_bits=15"))
    }

    @Test
    fun `RFC 7692 Section 7-1-2 - client_max_window_bits without value`() {
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .requestCompression(
                    clientNoContextTakeover = false,
                    serverNoContextTakeover = false,
                    clientMaxWindowBits = -1,
                ).build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("client_max_window_bits"))
        assertFalse(text.contains("client_max_window_bits="))
    }

    @Test
    fun `RFC 7692 Section 7-1-2 - window bits 0 means not included`() {
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .requestCompression(serverMaxWindowBits = 0, clientMaxWindowBits = 0)
                .build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertFalse(text.contains("server_max_window_bits"))
        assertFalse(text.contains("client_max_window_bits"))
    }

    // ========================================================================
    // Additional Headers
    // ========================================================================

    @Test
    fun `custom headers can be added`() {
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .header("Origin", "https://example.com")
                .header("User-Agent", "TestClient/1.0")
                .build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("Origin: https://example.com"))
        assertTrue(text.contains("User-Agent: TestClient/1.0"))
    }

    // ========================================================================
    // Request Structure
    // ========================================================================

    @Test
    fun `request ends with blank line`() {
        // HTTP requests must end with \r\n\r\n
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun `all lines end with CRLF`() {
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        // Remove trailing \r\n\r\n and check each line
        val lines = text.dropLast(4).split("\r\n")
        assertTrue(lines.isNotEmpty())
        // All lines should have been terminated with \r\n (split removes them)
        assertFalse(lines.any { it.contains("\n") && !it.contains("\r\n") })
    }

    // ========================================================================
    // Expected Accept Key Calculation
    // ========================================================================

    @Test
    fun `expectedAcceptKey is computed correctly`() {
        // Using the RFC 6455 example key
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .key("dGhlIHNhbXBsZSBub25jZQ==")
                .build()

        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", request.expectedAcceptKey)
    }

    @Test
    fun `expectedAcceptKey is lazily computed`() {
        // Just verify it doesn't throw
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val key1 = request.expectedAcceptKey
        val key2 = request.expectedAcceptKey
        assertEquals(key1, key2)
    }
}
