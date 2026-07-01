package com.ditchoom.websocket.handshake

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.toReadBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [HandshakeResponseParser].
 *
 * Tests are organized by RFC section for traceability.
 *
 * References:
 * - RFC 6455: The WebSocket Protocol
 *   https://datatracker.ietf.org/doc/html/rfc6455
 * - RFC 7692: Compression Extensions for WebSocket
 *   https://datatracker.ietf.org/doc/html/rfc7692
 * - RFC 7230: HTTP/1.1 Message Syntax and Routing
 *   https://datatracker.ietf.org/doc/html/rfc7230
 */
class HandshakeResponseParserTest {
    // ========================================================================
    // RFC 6455 Section 4.2.2 - Server Handshake Response
    // https://datatracker.ietf.org/doc/html/rfc6455#section-4.2.2
    // ========================================================================

    @Test
    fun rfc6455Section422ParseValid101ResponseWithAllRequiredHeaders() {
        // "If the server chooses to accept the incoming connection, it MUST
        // reply with a valid HTTP response indicating the following:
        // 1. An HTTP Status-Line with a status code of 101
        // 2. An HTTP Upgrade header field with value 'websocket'
        // 3. An HTTP Connection header field with value 'Upgrade'
        // 4. A Sec-WebSocket-Accept header field"
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        assertEquals(101, result.statusCode)
        assertEquals("Switching Protocols", result.statusReason)
        assertEquals("websocket", result.getHeader("upgrade"))
        assertEquals("Upgrade", result.getHeader("connection"))
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", result.acceptKey)
        assertTrue(result.isSuccessful)
    }

    @Test
    fun rfc6455Section422ParseResponseWithSecWebSocketProtocol() {
        // "Optionally, a |Sec-WebSocket-Protocol| header field, with a value
        // /protocol/ that indicates the subprotocol the server has selected"
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
            Sec-WebSocket-Protocol: chat

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        assertEquals("chat", result.protocol)
    }

    @Test
    fun rfc6455Section422ParseResponseWithSecWebSocketExtensions() {
        // "Optionally, a |Sec-WebSocket-Extensions| header field, with a
        // value /extensions/ indicating the extensions the server accepts"
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
            Sec-WebSocket-Extensions: permessage-deflate

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        assertEquals(1, result.extensions.size)
        assertEquals("permessage-deflate", result.extensions[0].name)
        assertTrue(result.compressionEnabled)
    }

    // ========================================================================
    // RFC 6455 Section 4.2.2 - Sec-WebSocket-Accept Calculation
    // https://datatracker.ietf.org/doc/html/rfc6455#section-4.2.2
    // ========================================================================

    @Test
    fun rfc6455Section422ExampleSecWebSocketAcceptCalculation() {
        // "For this header field, the server has to take the value (as present
        // in the header field, e.g., the base64-encoded [RFC4648] version minus
        // any leading and trailing whitespace) and concatenate this with the
        // Globally Unique Identifier (GUID) '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
        // in string form, [...] giving the value
        // 's3pPLMBiTxaQ9kYGzzhZRbK+xOo='"
        //
        // From the RFC example:
        // Key: "dGhlIHNhbXBsZSBub25jZQ=="
        // Expected Accept: "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        val clientKey = "dGhlIHNhbXBsZSBub25jZQ=="
        val expectedAccept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="

        val computed = computeAcceptKey(clientKey)
        assertEquals(expectedAccept, computed)
    }

    // ========================================================================
    // RFC 7230 Section 3.1.2 - Status Line
    // https://datatracker.ietf.org/doc/html/rfc7230#section-3.1.2
    // ========================================================================

    @Test
    fun rfc7230Section312ParseHTTP11StatusLine() {
        // "status-line = HTTP-version SP status-code SP reason-phrase CRLF"
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: abc=

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        assertEquals(101, result.statusCode)
        assertEquals("Switching Protocols", result.statusReason)
    }

    @Test
    fun rfc7230Section312ParseHTTP10StatusLine() {
        // HTTP/1.0 is also acceptable per the spec
        val response =
            """
            HTTP/1.0 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: abc=

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        assertEquals(101, result.statusCode)
    }

    @Test
    fun rfc7230Section312EmptyReasonPhraseIsValid() {
        // "A client SHOULD ignore the reason-phrase content."
        // The reason phrase can be empty
        val response =
            """
            HTTP/1.1 101
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: abc=

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        assertEquals(101, result.statusCode)
        assertEquals("", result.statusReason)
    }

    @Test
    fun rfc7230Section312ParseVariousStatusCodes() {
        // Non-101 status codes should still be parseable
        for ((code, reason) in listOf(
            200 to "OK",
            400 to "Bad Request",
            403 to "Forbidden",
            404 to "Not Found",
            426 to "Upgrade Required",
            500 to "Internal Server Error",
        )) {
            val response =
                """
                HTTP/1.1 $code $reason
                Content-Length: 0

                """.trimIndent().replace("\n", "\r\n")

            val buffer = response.toReadBuffer(Charset.UTF8)
            val result = HandshakeResponseParser.parse(buffer)

            assertEquals(code, result.statusCode, "Failed for status code $code")
            assertEquals(reason, result.statusReason, "Failed for status code $code")
            assertFalse(result.isSuccessful, "Status $code should not be successful")
        }
    }

    // ========================================================================
    // RFC 7230 Section 3.2 - Header Fields
    // https://datatracker.ietf.org/doc/html/rfc7230#section-3.2
    // ========================================================================

    @Test
    fun rfc7230Section32HeaderNamesAreCaseInsensitive() {
        // "Each header field consists of a case-insensitive field name"
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            UPGRADE: websocket
            CONNECTION: Upgrade
            SEC-WEBSOCKET-ACCEPT: abc=

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        // Headers should be accessible case-insensitively
        assertEquals("websocket", result.getHeader("upgrade"))
        assertEquals("websocket", result.getHeader("UPGRADE"))
        assertEquals("websocket", result.getHeader("Upgrade"))
    }

    @Test
    fun rfc7230Section32HeaderValueWithLeadingWhitespace() {
        // "optional leading whitespace (OWS) [...] before the field value"
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            Upgrade:   websocket
            Connection:		Upgrade
            Sec-WebSocket-Accept: abc=

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        assertEquals("websocket", result.getHeader("upgrade"))
        assertEquals("Upgrade", result.getHeader("connection"))
    }

    @Test
    fun rfc7230Section32HeaderValueWithTrailingWhitespace() {
        // "optional [...] trailing whitespace (OWS)"
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: abc=

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        assertEquals("websocket", result.getHeader("upgrade"))
        assertEquals("Upgrade", result.getHeader("connection"))
    }

    // ========================================================================
    // RFC 7692 Section 7 - permessage-deflate Extension
    // https://datatracker.ietf.org/doc/html/rfc7692#section-7
    // ========================================================================

    @Test
    fun rfc7692Section711Server_no_context_takeoverParameter() {
        // "A server MAY include the 'server_no_context_takeover' extension
        // parameter in an extension negotiation response to indicate that
        // the server will not reuse the LZ77 sliding window"
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: abc=
            Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        val params = checkNotNull(result.compressionParams)
        assertTrue(params.serverNoContextTakeover)
        assertFalse(params.clientNoContextTakeover)
    }

    @Test
    fun rfc7692Section711Client_no_context_takeoverParameter() {
        // "A server MAY include the 'client_no_context_takeover' extension
        // parameter in an extension negotiation response to require the
        // client to not reuse the LZ77 sliding window"
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: abc=
            Sec-WebSocket-Extensions: permessage-deflate; client_no_context_takeover

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        val params = checkNotNull(result.compressionParams)
        assertFalse(params.serverNoContextTakeover)
        assertTrue(params.clientNoContextTakeover)
    }

    @Test
    fun rfc7692Section712Server_max_window_bitsParameter() {
        // "A server MAY include the 'server_max_window_bits' extension parameter
        // in an extension negotiation response to inform the client of the LZ77
        // sliding window size it will use"
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: abc=
            Sec-WebSocket-Extensions: permessage-deflate; server_max_window_bits=12

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        val params = checkNotNull(result.compressionParams)
        assertEquals(12, params.serverMaxWindowBits)
    }

    @Test
    fun rfc7692Section712Client_max_window_bitsParameter() {
        // "A server MAY include the 'client_max_window_bits' extension parameter
        // in an extension negotiation response"
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: abc=
            Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits=10

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        val params = checkNotNull(result.compressionParams)
        assertEquals(10, params.clientMaxWindowBits)
    }

    @Test
    fun rfc7692Section7AllCompressionParametersTogether() {
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: abc=
            Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover; client_no_context_takeover; server_max_window_bits=12; client_max_window_bits=10

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        val params = checkNotNull(result.compressionParams)
        assertTrue(params.serverNoContextTakeover)
        assertTrue(params.clientNoContextTakeover)
        assertEquals(12, params.serverMaxWindowBits)
        assertEquals(10, params.clientMaxWindowBits)
    }

    // ========================================================================
    // RFC 6455 Section 9.1 - Extension Parsing
    // https://datatracker.ietf.org/doc/html/rfc6455#section-9.1
    // ========================================================================

    @Test
    fun rfc6455Section91ParseMultipleExtensions() {
        // "The value of the 'Sec-WebSocket-Extensions' header field consists
        // of a comma-separated list of extension offers"
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: abc=
            Sec-WebSocket-Extensions: permessage-deflate, x-custom-ext; param=value

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val result = HandshakeResponseParser.parse(buffer)

        assertEquals(2, result.extensions.size)
        assertEquals("permessage-deflate", result.extensions[0].name)
        assertEquals("x-custom-ext", result.extensions[1].name)
        assertEquals("value", result.extensions[1].parameters["param"])
    }

    @Test
    fun rfc6455Section91ExtensionParameterWithoutValue() {
        // "extension-param = token [ '=' (token | quoted-string) ]"
        // Parameter can have no value
        val extensions = HandshakeResponseParser.parseExtensions("ext; flag")

        assertEquals(1, extensions.size)
        assertTrue("flag" in extensions[0].parameters)
        assertNull(extensions[0].parameters["flag"])
    }

    @Test
    fun rfc6455Section91ExtensionParameterWithQuotedValue() {
        // "extension-param = token [ '=' (token | quoted-string) ]"
        val extensions = HandshakeResponseParser.parseExtensions("ext; param=\"quoted value\"")

        assertEquals(1, extensions.size)
        assertEquals("quoted value", extensions[0].parameters["param"])
    }

    // ========================================================================
    // Zero-Copy Verification
    // ========================================================================

    @Test
    fun zeroCopyBufferPositionIsRestoredAfterParsing() {
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: abc=

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)
        val originalPosition = buffer.position()

        HandshakeResponseParser.parse(buffer)

        assertEquals(originalPosition, buffer.position(), "Buffer position should be restored")
    }

    @Test
    fun zeroCopyBufferCanBeParsedMultipleTimes() {
        val response =
            """
            HTTP/1.1 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: abc=

            """.trimIndent().replace("\n", "\r\n")

        val buffer = response.toReadBuffer(Charset.UTF8)

        val result1 = HandshakeResponseParser.parse(buffer)
        val result2 = HandshakeResponseParser.parse(buffer)

        assertEquals(result1.statusCode, result2.statusCode)
        assertEquals(result1.acceptKey, result2.acceptKey)
    }

    // ========================================================================
    // findHeaderEnd Tests
    // ========================================================================

    @Test
    fun findHeaderEndReturnsCorrectIndex() {
        val response = "HTTP/1.1 101 OK\r\nHeader: value\r\n\r\nbody".toReadBuffer(Charset.UTF8)

        val endIndex = HandshakeResponseParser.findHeaderEnd(response)

        // Index should point to first byte after \r\n\r\n
        // "HTTP/1.1 101 OK" (15) + "\r\n" (2) + "Header: value" (13) + "\r\n\r\n" (4) = 34
        assertEquals(34, endIndex)
    }

    @Test
    fun findHeaderEndReturns1WhenNotFound() {
        val incomplete = "HTTP/1.1 101 OK\r\nHeader: value\r\n".toReadBuffer(Charset.UTF8)

        val endIndex = HandshakeResponseParser.findHeaderEnd(incomplete)

        assertEquals(-1, endIndex)
    }

    // ========================================================================
    // Error Handling
    // ========================================================================

    @Test
    fun errorMalformedStatusLineThrowsHandshakeException() {
        val malformed = "INVALID\r\n\r\n".toReadBuffer(Charset.UTF8)

        assertFailsWith<HandshakeException> {
            HandshakeResponseParser.parse(malformed)
        }
    }

    @Test
    fun errorMissingStatusCodeThrowsHandshakeException() {
        val malformed = "HTTP/1.1 \r\n\r\n".toReadBuffer(Charset.UTF8)

        assertFailsWith<HandshakeException> {
            HandshakeResponseParser.parse(malformed)
        }
    }

    @Test
    fun errorNonNumericStatusCodeThrowsHandshakeException() {
        val malformed = "HTTP/1.1 ABC Switching\r\n\r\n".toReadBuffer(Charset.UTF8)

        assertFailsWith<HandshakeException> {
            HandshakeResponseParser.parse(malformed)
        }
    }
}
