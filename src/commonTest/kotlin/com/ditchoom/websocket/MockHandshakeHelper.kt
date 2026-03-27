package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.websocket.handshake.computeAcceptKey

/**
 * Test helper for building mock WebSocket handshake responses.
 *
 * All buffer-building methods leave the buffer in WRITE mode (position at end of data)
 * because [MockClientToServerSocket.read] calls `resetForRead()` before copying to
 * the caller's buffer. Calling `resetForRead()` twice would set limit=0.
 */
object MockHandshakeHelper {
    /**
     * Extracts the Sec-WebSocket-Key from a captured handshake request buffer.
     */
    fun extractClientKey(handshakeRequest: ReadBuffer): String {
        handshakeRequest.position(0)
        val text = handshakeRequest.readString(handshakeRequest.remaining(), Charset.UTF8)
        val keyLine = text.lineSequence().first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
        return keyLine.substringAfter(":").trim()
    }

    private fun bytesToBuffer(bytes: ByteArray): ReadBuffer {
        val buffer = BufferFactory.Default.allocate(bytes.size)
        buffer.writeBytes(bytes)
        // Leave in write mode - mock socket will call resetForRead()
        return buffer
    }

    /**
     * Builds a valid HTTP 101 WebSocket handshake response for the given client key.
     */
    fun buildHandshakeResponse(clientKey: String): ReadBuffer {
        val acceptKey = computeAcceptKey(clientKey)
        val response =
            buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: $acceptKey\r\n")
                append("\r\n")
            }
        return bytesToBuffer(response.encodeToByteArray())
    }

    /**
     * Builds an HTTP response with a custom status code (for error tests).
     */
    fun buildErrorResponse(
        statusCode: Int,
        statusText: String,
    ): ReadBuffer {
        val response =
            buildString {
                append("HTTP/1.1 $statusCode $statusText\r\n")
                append("Content-Length: 0\r\n")
                append("\r\n")
            }
        return bytesToBuffer(response.encodeToByteArray())
    }

    /**
     * Builds a 101 response with a wrong accept key.
     */
    fun buildWrongAcceptResponse(): ReadBuffer {
        val response =
            buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                append("\r\n")
            }
        return bytesToBuffer(response.encodeToByteArray())
    }

    /**
     * Builds a 101 response without the Sec-WebSocket-Accept header.
     */
    fun buildMissingAcceptResponse(): ReadBuffer {
        val response =
            buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("\r\n")
            }
        return bytesToBuffer(response.encodeToByteArray())
    }

    /**
     * Builds an unmasked server frame (text or binary).
     *
     * Server-to-client frames are NOT masked per RFC 6455.
     */
    fun buildServerFrame(
        opcode: Opcode,
        payload: ByteArray,
        fin: Boolean = true,
    ): ReadBuffer {
        val byte1 = (opcode.value.toInt() and 0x0F) or (if (fin) 0x80 else 0)

        val headerSize =
            when {
                payload.size <= 125 -> 2
                payload.size <= 65535 -> 4
                else -> 10
            }
        val buffer = BufferFactory.Default.allocate(headerSize + payload.size)

        buffer.writeByte(byte1.toByte())
        when {
            payload.size <= 125 -> buffer.writeByte(payload.size.toByte())
            payload.size <= 65535 -> {
                buffer.writeByte(126.toByte())
                buffer.writeShort(payload.size.toShort())
            }
            else -> {
                buffer.writeByte(127.toByte())
                buffer.writeLong(payload.size.toLong())
            }
        }
        buffer.writeBytes(payload)
        // Leave in write mode - mock socket will call resetForRead()
        return buffer
    }

    /**
     * Builds an unmasked server text frame.
     */
    fun buildServerTextFrame(
        text: String,
        fin: Boolean = true,
    ): ReadBuffer = buildServerFrame(Opcode.Text, text.encodeToByteArray(), fin)

    /**
     * Builds an unmasked server binary frame.
     */
    fun buildServerBinaryFrame(
        data: ByteArray,
        fin: Boolean = true,
    ): ReadBuffer = buildServerFrame(Opcode.Binary, data, fin)

    /**
     * Builds an unmasked server ping frame.
     */
    fun buildServerPingFrame(payload: ByteArray = byteArrayOf()): ReadBuffer = buildServerFrame(Opcode.Ping, payload)

    /**
     * Builds an unmasked server pong frame.
     */
    fun buildServerPongFrame(payload: ByteArray = byteArrayOf()): ReadBuffer = buildServerFrame(Opcode.Pong, payload)

    /**
     * Builds an unmasked server close frame.
     *
     * @param code Close status code (e.g. 1000 for normal closure)
     * @param reason Optional close reason string
     */
    fun buildServerCloseFrame(
        code: UShort = 1000u,
        reason: String = "",
    ): ReadBuffer {
        val reasonBytes = reason.encodeToByteArray()
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = (code.toInt() shr 8).toByte()
        payload[1] = (code.toInt() and 0xFF).toByte()
        reasonBytes.copyInto(payload, 2)
        return buildServerFrame(Opcode.Close, payload)
    }
}
