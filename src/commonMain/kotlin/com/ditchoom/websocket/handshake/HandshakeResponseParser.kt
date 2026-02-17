package com.ditchoom.websocket.handshake

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.toReadBuffer

/**
 * Zero-copy parser for WebSocket server handshake responses.
 *
 * This parser operates directly on ReadBuffer without creating intermediate String copies
 * for the main parsing logic. It uses the buffer's optimized indexOf operations for
 * fast pattern matching (SIMD-accelerated on native platforms).
 *
 * ## RFC Compliance
 *
 * Implements parsing per:
 * - RFC 6455 Section 4.1 (Client Requirements) - response validation
 * - RFC 6455 Section 4.2.2 (Server Requirements) - expected response format
 * - RFC 7692 Section 7 (permessage-deflate) - compression extension parsing
 *
 * ## Zero-Copy Design
 *
 * The parser uses [ReadBuffer.slice] to extract header values without copying data.
 * String conversion only happens at the final stage when values are needed.
 *
 * ## Performance Optimizations
 *
 * - Uses buffer's SIMD-accelerated `indexOf(Byte)` for pattern searching
 * - Uses `readString()` with position/limit manipulation for zero-copy string extraction
 * - Pre-computed byte constants avoid repeated conversions
 */
object HandshakeResponseParser {
    // Pre-allocated marker buffers for pattern matching
    private val HEADER_END = "\r\n\r\n".toReadBuffer(Charset.UTF8)

    // Pre-computed HTTP version prefix for fast comparison
    private const val HTTP_1_PREFIX = "HTTP/1."

    // Expected values
    private val EXTENSION_PERMESSAGE_DEFLATE = "permessage-deflate"

    /**
     * Parses a WebSocket server handshake response from a buffer.
     *
     * The buffer should contain the complete HTTP response up to and including
     * the blank line that terminates the headers (\r\n\r\n).
     *
     * ## RFC 6455 Section 4.2.2 Requirements
     *
     * A valid response MUST have:
     * 1. Status code 101
     * 2. Upgrade header containing "websocket" (case-insensitive)
     * 3. Connection header containing "Upgrade" (case-insensitive)
     * 4. Sec-WebSocket-Accept header with correct value
     *
     * @param buffer The response buffer (position will not be modified)
     * @return Parsed handshake response
     * @throws HandshakeException if the response is malformed
     */
    fun parse(buffer: ReadBuffer): HandshakeResponse {
        val startPos = buffer.position()

        try {
            // Parse status line: "HTTP/1.1 101 Switching Protocols\r\n"
            val (statusCode, statusReason, nextPos) = parseStatusLine(buffer, startPos)
            var pos = nextPos

            // Parse headers
            val headers = mutableMapOf<String, String>()
            var acceptKey: String? = null
            var protocol: String? = null
            var extensionsRaw: String? = null

            while (pos < buffer.limit()) {
                // Check for end of headers (empty line = \r\n)
                if (pos + 1 < buffer.limit() &&
                    buffer.get(pos) == CR &&
                    buffer.get(pos + 1) == LF
                ) {
                    // Found end of headers
                    break
                }

                // Parse header line
                val result =
                    parseHeaderLine(buffer, pos)
                        ?: throw HandshakeException("Malformed header at position $pos")

                val (name, value, newPos) = result
                pos = newPos

                val lowerName = name.lowercase()
                headers[lowerName] = value

                // Extract specific headers we need
                when (lowerName) {
                    "sec-websocket-accept" -> acceptKey = value
                    "sec-websocket-protocol" -> protocol = value
                    "sec-websocket-extensions" -> extensionsRaw = value
                }
            }

            // Parse extensions
            val extensions = extensionsRaw?.let { parseExtensions(it) } ?: emptyList()

            // Check for permessage-deflate
            val deflateExtension = extensions.find { it.name == EXTENSION_PERMESSAGE_DEFLATE }
            val compressionEnabled = deflateExtension != null
            val compressionParams = deflateExtension?.let { parseCompressionParams(it) }

            return HandshakeResponse(
                statusCode = statusCode,
                statusReason = statusReason,
                headers = headers,
                acceptKey = acceptKey,
                protocol = protocol,
                extensions = extensions,
                compressionEnabled = compressionEnabled,
                compressionParams = compressionParams,
            )
        } finally {
            // Restore buffer position for zero-copy semantics
            buffer.position(startPos)
        }
    }

    /**
     * Finds the end of HTTP headers in a buffer.
     *
     * Uses optimized indexOf for fast pattern matching.
     *
     * @param buffer The buffer to search
     * @return Index of the first byte after \r\n\r\n, or -1 if not found
     */
    fun findHeaderEnd(buffer: ReadBuffer): Int {
        HEADER_END.position(0)
        val index = buffer.indexOf(HEADER_END)
        return if (index >= 0) buffer.position() + index + 4 else -1
    }

    /**
     * Parses the HTTP status line.
     *
     * Format per RFC 7230 Section 3.1.2:
     * status-line = HTTP-version SP status-code SP reason-phrase CRLF
     *
     * @return Triple of (statusCode, reasonPhrase, nextPosition)
     */
    private fun parseStatusLine(
        buffer: ReadBuffer,
        startPos: Int,
    ): Triple<Int, String, Int> {
        // Find end of line
        val lineEnd = findCRLF(buffer, startPos)
        if (lineEnd < 0) {
            throw HandshakeException("Status line not terminated with CRLF")
        }

        val lineLength = lineEnd - startPos

        // Minimum valid: "HTTP/1.1 101" = 12 chars
        if (lineLength < 12) {
            throw HandshakeException("Status line too short")
        }

        // Verify HTTP version prefix using bulk string comparison
        // This uses readString which is optimized for bulk reads
        val savedPos = buffer.position()
        val savedLimit = buffer.limit()

        buffer.position(startPos)
        if (buffer.remaining() < HTTP_1_PREFIX.length) {
            buffer.position(savedPos)
            buffer.setLimit(savedLimit)
            throw HandshakeException("Status line too short for HTTP version")
        }
        val versionStr = buffer.readString(HTTP_1_PREFIX.length, Charset.UTF8)
        if (versionStr != HTTP_1_PREFIX) {
            buffer.position(savedPos)
            buffer.setLimit(savedLimit)
            throw HandshakeException("Invalid HTTP version")
        }

        buffer.position(savedPos)
        buffer.setLimit(savedLimit)

        // Find first space after version (position 8+ after "HTTP/1.X")
        // Use SIMD-accelerated indexOf for space search
        buffer.position(startPos + 8)
        buffer.setLimit(lineEnd)
        val spaceOffset = buffer.indexOf(SPACE)
        buffer.position(savedPos)
        buffer.setLimit(savedLimit)

        val spaceIdx = if (spaceOffset >= 0) startPos + 8 + spaceOffset else lineEnd
        if (spaceIdx >= lineEnd) {
            throw HandshakeException("Missing status code in status line")
        }

        // Parse status code (3 digits)
        val codeStart = spaceIdx + 1
        if (codeStart + 3 > lineEnd) {
            throw HandshakeException("Status code too short")
        }

        val statusCode =
            parseDigits(buffer, codeStart, 3)
                ?: throw HandshakeException("Invalid status code")

        // Extract reason phrase (everything after status code + space)
        val reasonStart = codeStart + 3
        val statusReason =
            if (reasonStart < lineEnd && buffer.get(reasonStart) == SPACE) {
                // Skip space and read reason
                extractString(buffer, reasonStart + 1, lineEnd)
            } else if (reasonStart < lineEnd) {
                // No space, but there's content (unusual but handle it)
                extractString(buffer, reasonStart, lineEnd)
            } else {
                ""
            }

        // Return position after CRLF
        return Triple(statusCode, statusReason, lineEnd + 2)
    }

    /**
     * Parses a single header line.
     *
     * Format per RFC 7230 Section 3.2:
     * header-field = field-name ":" OWS field-value OWS
     *
     * @return Triple of (headerName, headerValue, nextPosition) or null if malformed
     */
    private fun parseHeaderLine(
        buffer: ReadBuffer,
        startPos: Int,
    ): Triple<String, String, Int>? {
        val lineEnd = findCRLF(buffer, startPos)
        if (lineEnd < 0) return null

        // Save buffer state
        val savedPos = buffer.position()
        val savedLimit = buffer.limit()

        // Find colon separator using SIMD-accelerated indexOf
        buffer.position(startPos)
        buffer.setLimit(lineEnd)
        val colonOffset = buffer.indexOf(COLON)
        buffer.position(savedPos)
        buffer.setLimit(savedLimit)

        val colonIdx = if (colonOffset >= 0) startPos + colonOffset else lineEnd
        if (colonIdx >= lineEnd) return null

        // Extract header name
        val name = extractString(buffer, startPos, colonIdx)

        // Skip colon and optional whitespace
        var valueStart = colonIdx + 1
        while (valueStart < lineEnd && isOptionalWhitespace(buffer.get(valueStart))) {
            valueStart++
        }

        // Trim trailing whitespace
        var valueEnd = lineEnd
        while (valueEnd > valueStart && isOptionalWhitespace(buffer.get(valueEnd - 1))) {
            valueEnd--
        }

        // Extract value
        val value = extractString(buffer, valueStart, valueEnd)

        // Return position after CRLF
        return Triple(name, value, lineEnd + 2)
    }

    /**
     * Extracts a string from buffer without modifying buffer position.
     * This is the only place where we convert bytes to String.
     *
     * Uses buffer's optimized position/limit manipulation and bulk readString()
     * for zero-copy string extraction when possible.
     */
    private fun extractString(
        buffer: ReadBuffer,
        start: Int,
        end: Int,
    ): String {
        if (start >= end) return ""
        val length = end - start

        // Save current buffer state
        val savedPos = buffer.position()
        val savedLimit = buffer.limit()

        // Position buffer at the slice we want to read
        buffer.position(start)
        buffer.setLimit(end)

        // Use bulk readString - internally optimized with SIMD on supported platforms
        val result = buffer.readString(length, Charset.UTF8)

        // Restore buffer state
        buffer.position(savedPos)
        buffer.setLimit(savedLimit)

        return result
    }

    /**
     * Finds CRLF starting from the given position.
     *
     * Uses buffer's SIMD-accelerated indexOf(Byte) to find CR bytes,
     * then verifies LF follows. This is significantly faster than
     * byte-by-byte iteration for longer buffers.
     *
     * @return Position of CR, or -1 if not found
     */
    private fun findCRLF(
        buffer: ReadBuffer,
        startPos: Int,
    ): Int {
        val savedPos = buffer.position()
        val savedLimit = buffer.limit()
        val limit = buffer.limit()

        var searchPos = startPos
        while (searchPos < limit - 1) {
            // Position buffer for indexOf search
            buffer.position(searchPos)

            // Use SIMD-accelerated indexOf to find CR byte
            val crOffset = buffer.indexOf(CR)

            if (crOffset < 0) {
                // No CR found in remaining buffer
                buffer.position(savedPos)
                buffer.setLimit(savedLimit)
                return -1
            }

            val crPos = searchPos + crOffset

            // Check if there's room for LF and verify it follows
            if (crPos + 1 < limit && buffer.get(crPos + 1) == LF) {
                buffer.position(savedPos)
                buffer.setLimit(savedLimit)
                return crPos
            }

            // CR not followed by LF, continue searching after this CR
            searchPos = crPos + 1
        }

        buffer.position(savedPos)
        buffer.setLimit(savedLimit)
        return -1
    }

    /**
     * Parses the Sec-WebSocket-Extensions header value.
     *
     * Format per RFC 6455 Section 9.1:
     * extension = extension-token *( ";" extension-param )
     * extension-param = token [ "=" (token | quoted-string) ]
     *
     * Multiple extensions are comma-separated.
     */
    internal fun parseExtensions(value: String): List<ExtensionOffer> {
        if (value.isBlank()) return emptyList()

        val extensions = mutableListOf<ExtensionOffer>()
        val offers = value.split(',')

        for (offer in offers) {
            val trimmed = offer.trim()
            if (trimmed.isEmpty()) continue

            val parts = trimmed.split(';')
            val name = parts[0].trim()
            val params = mutableMapOf<String, String?>()

            for (i in 1 until parts.size) {
                val param = parts[i].trim()
                val eqIdx = param.indexOf('=')
                if (eqIdx > 0) {
                    val pName = param.substring(0, eqIdx).trim()
                    var pValue = param.substring(eqIdx + 1).trim()
                    // Remove quotes if present
                    if (pValue.startsWith('"') && pValue.endsWith('"') && pValue.length >= 2) {
                        pValue = pValue.substring(1, pValue.length - 1)
                    }
                    params[pName] = pValue
                } else {
                    params[param] = null
                }
            }

            extensions.add(ExtensionOffer(name, params))
        }

        return extensions
    }

    /**
     * Parses compression parameters from a permessage-deflate extension offer.
     *
     * Per RFC 7692 Section 7.1.
     */
    private fun parseCompressionParams(extension: ExtensionOffer): CompressionParams {
        val serverNoContext = "server_no_context_takeover" in extension.parameters
        val clientNoContext = "client_no_context_takeover" in extension.parameters
        val serverMaxBits = extension.parameters["server_max_window_bits"]?.toIntOrNull()
        val clientMaxBits = extension.parameters["client_max_window_bits"]?.toIntOrNull()

        return CompressionParams(
            serverNoContextTakeover = serverNoContext,
            clientNoContextTakeover = clientNoContext,
            serverMaxWindowBits = serverMaxBits?.coerceIn(8, 15),
            clientMaxWindowBits = clientMaxBits?.coerceIn(8, 15),
        )
    }

    /**
     * Parses decimal digits from buffer without string allocation.
     */
    private fun parseDigits(
        buffer: ReadBuffer,
        start: Int,
        count: Int,
    ): Int? {
        var result = 0
        for (i in 0 until count) {
            val b = buffer.get(start + i)
            if (b < '0'.code.toByte() || b > '9'.code.toByte()) {
                return null
            }
            result = result * 10 + (b - '0'.code.toByte())
        }
        return result
    }

    private fun isOptionalWhitespace(b: Byte): Boolean = b == SPACE || b == HTAB

    private const val CR: Byte = '\r'.code.toByte()
    private const val LF: Byte = '\n'.code.toByte()
    private const val SPACE: Byte = ' '.code.toByte()
    private const val HTAB: Byte = '\t'.code.toByte()
    private const val COLON: Byte = ':'.code.toByte()
}
