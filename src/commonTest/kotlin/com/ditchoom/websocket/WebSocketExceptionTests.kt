package com.ditchoom.websocket

import com.ditchoom.websocket.handshake.HandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WebSocketExceptionTests {
    @Test
    fun transportFailed_preservesCause() {
        val cause = Exception("Connection refused")
        val wsError = WebSocketException.TransportFailed("Transport failed", cause)

        assertIs<WebSocketException.TransportFailed>(wsError)
        assertNotNull(wsError.cause)
        assertEquals("Transport failed", wsError.message)
    }

    @Test
    fun handshakeRejected_preservesStatusCode() {
        val wsError = WebSocketException.HandshakeRejected("403 Forbidden", statusCode = 403)

        assertIs<WebSocketException.HandshakeRejected>(wsError)
        assertEquals(403, wsError.statusCode)
        assertEquals("403 Forbidden", wsError.message)
        assertNull(wsError.cause)
    }

    @Test
    fun handshakeRejected_preservesCause() {
        val handshakeError = HandshakeException("Bad accept key")
        val wsError = WebSocketException.HandshakeRejected("Handshake failed", cause = handshakeError)

        assertIs<WebSocketException.HandshakeRejected>(wsError)
        assertNotNull(wsError.cause)
        assertIs<HandshakeException>(wsError.cause)
    }

    @Test
    fun protocolViolation_basic() {
        val wsError = WebSocketException.ProtocolViolation("Invalid opcode 0xF")

        assertIs<WebSocketException.ProtocolViolation>(wsError)
        assertEquals("Invalid opcode 0xF", wsError.message)
    }

    @Test
    fun connectionClosed_preservesCloseCode() {
        val wsError =
            WebSocketException.ConnectionClosed(
                "Connection closed",
                code = 1001.toUShort(),
                reason = "Going away",
            )

        assertIs<WebSocketException.ConnectionClosed>(wsError)
        assertEquals(1001.toUShort(), wsError.code)
        assertEquals("Going away", wsError.reason)
    }

    @Test
    fun connectionClosed_normalClose() {
        val wsError =
            WebSocketException.ConnectionClosed(
                "Normal close",
                code = 1000.toUShort(),
            )

        assertEquals(1000.toUShort(), wsError.code)
        assertNull(wsError.reason)
    }

    @Test
    fun wrapException_handshakeException_becomesHandshakeRejected() {
        val hsError = HandshakeException("Bad response")
        val wrapped = wrapException(hsError)

        assertIs<WebSocketException.HandshakeRejected>(wrapped)
        assertEquals(hsError, wrapped.cause)
    }

    @Test
    fun wrapException_webSocketException_passedThrough() {
        val original = WebSocketException.ProtocolViolation("Bad frame")
        val wrapped = wrapException(original)

        assertEquals(original, wrapped)
    }

    @Test
    fun wrapException_genericException_becomesTransportFailed() {
        val original = IllegalStateException("unexpected")
        val wrapped = wrapException(original)

        assertIs<WebSocketException.TransportFailed>(wrapped)
        assertEquals(original, wrapped.cause)
    }
}
