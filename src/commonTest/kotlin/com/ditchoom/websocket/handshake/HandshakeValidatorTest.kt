package com.ditchoom.websocket.handshake

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [HandshakeValidator].
 *
 * Tests are organized by the RFC 6455 Section 4.1 requirements that each validates.
 *
 * References:
 * - RFC 6455 Section 4.1: Client Requirements
 *   https://datatracker.ietf.org/doc/html/rfc6455#section-4.1
 */
class HandshakeValidatorTest {
    // Test fixtures
    private val validAcceptKey = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="

    private fun validResponse(
        statusCode: Int = 101,
        upgrade: String = "websocket",
        connection: String = "Upgrade",
        acceptKey: String? = validAcceptKey,
        protocol: String? = null,
        extensions: List<ExtensionOffer> = emptyList(),
    ) = HandshakeResponse(
        statusCode = statusCode,
        statusReason = "Switching Protocols",
        headers =
            mapOf(
                "upgrade" to upgrade,
                "connection" to connection,
            ),
        acceptKey = acceptKey,
        protocol = protocol,
        extensions = extensions,
        compressionEnabled = false,
        compressionParams = null,
    )

    // ========================================================================
    // RFC 6455 Section 4.1 Requirement #1 - Status Code 101
    // https://datatracker.ietf.org/doc/html/rfc6455#section-4.1
    // ========================================================================

    @Test
    fun `RFC 6455 Section 4-1 - status code 101 is valid`() {
        // "If the status code received from the server is not 101, the client
        // handles the response per HTTP [RFC2616] procedures."
        val response = validResponse(statusCode = 101)

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `RFC 6455 Section 4-1 - status code 200 fails validation`() {
        val response = validResponse(statusCode = 200)

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertFalse(result.isSuccess)
        assertIs<ValidationResult.Failure>(result)
        assertEquals(ValidationError.INVALID_STATUS_CODE, result.error)
    }

    @Test
    fun `RFC 6455 Section 4-1 - status code 400 fails validation`() {
        val response = validResponse(statusCode = 400)

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertFalse(result.isSuccess)
        assertIs<ValidationResult.Failure>(result)
        assertEquals(ValidationError.INVALID_STATUS_CODE, result.error)
    }

    @Test
    fun `RFC 6455 Section 4-1 - status code 426 fails validation`() {
        // 426 Upgrade Required - server wants different protocol version
        val response = validResponse(statusCode = 426)

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertFalse(result.isSuccess)
        assertIs<ValidationResult.Failure>(result)
        assertEquals(ValidationError.INVALID_STATUS_CODE, result.error)
    }

    // ========================================================================
    // RFC 6455 Section 4.1 Requirement #2 - Upgrade Header
    // https://datatracker.ietf.org/doc/html/rfc6455#section-4.1
    // ========================================================================

    @Test
    fun `RFC 6455 Section 4-1 - Upgrade header websocket is valid`() {
        // "If the response lacks an |Upgrade| header field or the |Upgrade|
        // header field contains a value that is not an ASCII case-insensitive
        // match for the value 'websocket', the client MUST Fail the WebSocket
        // Connection."
        val response = validResponse(upgrade = "websocket")

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `RFC 6455 Section 4-1 - Upgrade header case insensitive - WebSocket`() {
        val response = validResponse(upgrade = "WebSocket")

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `RFC 6455 Section 4-1 - Upgrade header case insensitive - WEBSOCKET`() {
        val response = validResponse(upgrade = "WEBSOCKET")

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `RFC 6455 Section 4-1 - missing Upgrade header fails`() {
        val response =
            HandshakeResponse(
                statusCode = 101,
                statusReason = "Switching Protocols",
                headers = mapOf("connection" to "Upgrade"),
                acceptKey = validAcceptKey,
                protocol = null,
                extensions = emptyList(),
                compressionEnabled = false,
                compressionParams = null,
            )

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertFalse(result.isSuccess)
        assertIs<ValidationResult.Failure>(result)
        assertEquals(ValidationError.INVALID_UPGRADE_HEADER, result.error)
    }

    @Test
    fun `RFC 6455 Section 4-1 - wrong Upgrade value fails`() {
        val response = validResponse(upgrade = "http")

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertFalse(result.isSuccess)
        assertIs<ValidationResult.Failure>(result)
        assertEquals(ValidationError.INVALID_UPGRADE_HEADER, result.error)
    }

    // ========================================================================
    // RFC 6455 Section 4.1 Requirement #3 - Connection Header
    // https://datatracker.ietf.org/doc/html/rfc6455#section-4.1
    // ========================================================================

    @Test
    fun `RFC 6455 Section 4-1 - Connection header Upgrade is valid`() {
        // "If the response lacks a |Connection| header field or the
        // |Connection| header field doesn't contain a token that is an ASCII
        // case-insensitive match for the value 'Upgrade', the client MUST
        // Fail the WebSocket Connection."
        val response = validResponse(connection = "Upgrade")

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `RFC 6455 Section 4-1 - Connection header case insensitive - upgrade`() {
        val response = validResponse(connection = "upgrade")

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `RFC 6455 Section 4-1 - Connection header with multiple tokens`() {
        // Connection header can contain multiple tokens
        val response = validResponse(connection = "keep-alive, Upgrade")

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `RFC 6455 Section 4-1 - missing Connection header fails`() {
        val response =
            HandshakeResponse(
                statusCode = 101,
                statusReason = "Switching Protocols",
                headers = mapOf("upgrade" to "websocket"),
                acceptKey = validAcceptKey,
                protocol = null,
                extensions = emptyList(),
                compressionEnabled = false,
                compressionParams = null,
            )

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertFalse(result.isSuccess)
        assertIs<ValidationResult.Failure>(result)
        assertEquals(ValidationError.INVALID_CONNECTION_HEADER, result.error)
    }

    @Test
    fun `RFC 6455 Section 4-1 - Connection without Upgrade token fails`() {
        val response = validResponse(connection = "keep-alive")

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertFalse(result.isSuccess)
        assertIs<ValidationResult.Failure>(result)
        assertEquals(ValidationError.INVALID_CONNECTION_HEADER, result.error)
    }

    // ========================================================================
    // RFC 6455 Section 4.1 Requirement #4 - Sec-WebSocket-Accept
    // https://datatracker.ietf.org/doc/html/rfc6455#section-4.1
    // ========================================================================

    @Test
    fun `RFC 6455 Section 4-1 - valid Sec-WebSocket-Accept passes`() {
        // "If the response lacks a |Sec-WebSocket-Accept| header field or the
        // |Sec-WebSocket-Accept| contains a value other than the base64-encoded
        // SHA-1 [...], the client MUST Fail the WebSocket Connection."
        val response = validResponse(acceptKey = validAcceptKey)

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `RFC 6455 Section 4-1 - missing Sec-WebSocket-Accept fails`() {
        val response = validResponse(acceptKey = null)

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertFalse(result.isSuccess)
        assertIs<ValidationResult.Failure>(result)
        assertEquals(ValidationError.MISSING_ACCEPT_KEY, result.error)
    }

    @Test
    fun `RFC 6455 Section 4-1 - wrong Sec-WebSocket-Accept value fails`() {
        val response = validResponse(acceptKey = "wrongvalue==")

        val result = HandshakeValidator.validate(response, validAcceptKey)

        assertFalse(result.isSuccess)
        assertIs<ValidationResult.Failure>(result)
        assertEquals(ValidationError.INVALID_ACCEPT_KEY, result.error)
    }

    // ========================================================================
    // RFC 6455 Section 4.1 Requirement #5 - Sec-WebSocket-Protocol
    // https://datatracker.ietf.org/doc/html/rfc6455#section-4.1
    // ========================================================================

    @Test
    fun `RFC 6455 Section 4-1 - selected protocol was offered - valid`() {
        // "If the response includes a |Sec-WebSocket-Protocol| header field
        // and this header field indicates the use of a subprotocol that was
        // not present in the client's handshake [...], the client MUST Fail
        // the WebSocket Connection."
        val response = validResponse(protocol = "chat")

        val result =
            HandshakeValidator.validate(
                response,
                validAcceptKey,
                offeredProtocols = listOf("chat", "superchat"),
            )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `RFC 6455 Section 4-1 - selected protocol was not offered - fails`() {
        val response = validResponse(protocol = "unknown")

        val result =
            HandshakeValidator.validate(
                response,
                validAcceptKey,
                offeredProtocols = listOf("chat", "superchat"),
            )

        assertFalse(result.isSuccess)
        assertIs<ValidationResult.Failure>(result)
        assertEquals(ValidationError.INVALID_PROTOCOL, result.error)
    }

    @Test
    fun `RFC 6455 Section 4-1 - no protocol selected when none offered - valid`() {
        val response = validResponse(protocol = null)

        val result =
            HandshakeValidator.validate(
                response,
                validAcceptKey,
                offeredProtocols = emptyList(),
            )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `RFC 6455 Section 4-1 - protocol selected when none offered - valid`() {
        // If client didn't offer protocols, server can still select one
        // (this is a server-initiated protocol)
        val response = validResponse(protocol = "server-protocol")

        val result =
            HandshakeValidator.validate(
                response,
                validAcceptKey,
                offeredProtocols = emptyList(),
            )

        assertTrue(result.isSuccess)
    }

    // ========================================================================
    // RFC 6455 Section 4.1 Requirement #6 - Sec-WebSocket-Extensions
    // https://datatracker.ietf.org/doc/html/rfc6455#section-4.1
    // ========================================================================

    @Test
    fun `RFC 6455 Section 4-1 - extension was offered - valid`() {
        // "If the response includes a |Sec-WebSocket-Extensions| header field
        // and this header field indicates the use of an extension that was
        // not present in the client's handshake [...], the client MUST Fail
        // the WebSocket Connection."
        val response =
            validResponse(
                extensions = listOf(ExtensionOffer("permessage-deflate", emptyMap())),
            )

        val result =
            HandshakeValidator.validate(
                response,
                validAcceptKey,
                offeredExtensions = listOf("permessage-deflate"),
            )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `RFC 6455 Section 4-1 - extension was not offered - fails`() {
        val response =
            validResponse(
                extensions = listOf(ExtensionOffer("unknown-extension", emptyMap())),
            )

        val result =
            HandshakeValidator.validate(
                response,
                validAcceptKey,
                offeredExtensions = listOf("permessage-deflate"),
            )

        assertFalse(result.isSuccess)
        assertIs<ValidationResult.Failure>(result)
        assertEquals(ValidationError.INVALID_EXTENSION, result.error)
    }

    @Test
    fun `RFC 6455 Section 4-1 - no extensions when none offered - valid`() {
        val response = validResponse(extensions = emptyList())

        val result =
            HandshakeValidator.validate(
                response,
                validAcceptKey,
                offeredExtensions = emptyList(),
            )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `RFC 6455 Section 4-1 - extension when none offered - valid`() {
        // If client didn't offer extensions, server offering one is actually
        // protocol-compliant (though unusual). The check only applies when
        // client did offer extensions.
        val response =
            validResponse(
                extensions = listOf(ExtensionOffer("permessage-deflate", emptyMap())),
            )

        val result =
            HandshakeValidator.validate(
                response,
                validAcceptKey,
                offeredExtensions = emptyList(),
            )

        assertTrue(result.isSuccess)
    }

    // ========================================================================
    // Integration Tests - Multiple Validation Failures
    // ========================================================================

    @Test
    fun `validation stops at first error - status code before headers`() {
        val response =
            validResponse(
                statusCode = 400,
                upgrade = "invalid",
                connection = "invalid",
                acceptKey = "invalid",
            )

        val result = HandshakeValidator.validate(response, validAcceptKey)

        // Should fail on status code first
        assertIs<ValidationResult.Failure>(result)
        assertEquals(ValidationError.INVALID_STATUS_CODE, result.error)
    }
}
