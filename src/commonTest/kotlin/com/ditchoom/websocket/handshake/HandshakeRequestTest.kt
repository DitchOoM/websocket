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
    fun rfc6455Section41RequestIncludesGETMethod() {
        // "The method of the request MUST be GET"
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.startsWith("GET "))
    }

    @Test
    fun rfc6455Section41RequestIncludesHTTP11() {
        // "and the HTTP version MUST be at least 1.1"
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("HTTP/1.1"))
    }

    @Test
    fun rfc6455Section41RequestIncludesPathInRequestURI() {
        // "The 'Request-URI' part of the request MUST match the resource name"
        val request = HandshakeRequest.builder("example.com", 80, "/chat/room1").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.startsWith("GET /chat/room1 HTTP/1.1"))
    }

    @Test
    fun rfc6455Section41RequestIncludesHostHeader() {
        // "The request MUST contain a |Host| header field whose value contains
        // /host/ plus optionally ':' followed by /port/"
        val request = HandshakeRequest.builder("example.com", 8080, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("Host: example.com:8080"))
    }

    @Test
    fun rfc6455Section41HostHeaderOmitsDefaultPort80() {
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
    fun rfc6455Section41HostHeaderOmitsDefaultPort443ForTLS() {
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
    fun rfc6455Section41RequestIncludesUpgradeHeader() {
        // "The request MUST contain an |Upgrade| header field whose value
        // MUST include the 'websocket' keyword"
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("Upgrade: websocket"))
    }

    @Test
    fun rfc6455Section41RequestIncludesConnectionHeader() {
        // "The request MUST contain a |Connection| header field whose value
        // MUST include the 'Upgrade' token"
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.contains("Connection: Upgrade"))
    }

    @Test
    fun rfc6455Section41RequestIncludesSecWebSocketKey() {
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
    fun rfc6455Section41RequestIncludesSecWebSocketVersion13() {
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
    fun rfc6455Section41RequestMayIncludeSecWebSocketProtocol() {
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
    fun rfc6455Section41NoProtocolHeaderWhenNotSpecified() {
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertFalse(text.contains("Sec-WebSocket-Protocol"))
    }

    @Test
    fun rfc6455Section41RequestMayIncludeSecWebSocketExtensions() {
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
    fun rfc7692Section7RequestCompressionAddsPermessageDeflate() {
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
    fun rfc7692Section711Client_no_context_takeoverParameter() {
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
    fun rfc7692Section711Server_no_context_takeoverParameter() {
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
    fun rfc7692Section711BothContextTakeoverParameters() {
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
    fun rfc7692Section712Server_max_window_bitsParameter() {
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
    fun rfc7692Section712Client_max_window_bitsParameter() {
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
    fun rfc7692Section712AllCompressionParametersCombined() {
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
    fun rfc7692Section712Client_max_window_bitsWithoutValue() {
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
    fun rfc7692Section712WindowBits0MeansNotIncluded() {
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
    fun customHeadersCanBeAdded() {
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
    fun requestEndsWithBlankLine() {
        // HTTP requests must end with \r\n\r\n
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val buffer = request.toBuffer()
        val text = buffer.readString(buffer.remaining(), Charset.UTF8)

        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun allLinesEndWithCRLF() {
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
    fun expectedAcceptKeyIsComputedCorrectly() {
        // Using the RFC 6455 example key
        val request =
            HandshakeRequest
                .builder("example.com", 80, "/ws")
                .key("dGhlIHNhbXBsZSBub25jZQ==")
                .build()

        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", request.expectedAcceptKey)
    }

    @Test
    fun expectedAcceptKeyIsLazilyComputed() {
        // Just verify it doesn't throw
        val request = HandshakeRequest.builder("example.com", 80, "/ws").build()
        val key1 = request.expectedAcceptKey
        val key2 = request.expectedAcceptKey
        assertEquals(key1, key2)
    }
}
