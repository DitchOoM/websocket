package com.ditchoom.websocket

import com.ditchoom.socket.TlsConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class CompressionOptions(
    val clientNoContextTakeover: Boolean = true,
    val serverNoContextTakeover: Boolean = true,
    val serverMaxWindowBits: Int = 0,
    val clientMaxWindowBits: Int = 0,
)

/**
 * Whether the platform's zlib/Deflater supports custom window bits for compression.
 * Linux: true (passes windowBits to deflateInit2).
 * JVM/Android/JS/Apple: false (Deflater/CompressionStream ignores windowBits).
 */
internal expect val supportsCustomDeflateWindowBits: Boolean

/**
 * Whether the platform's streaming compressor/decompressor maintains zlib state
 * across messages (required for permessage-deflate context takeover).
 * JVM/Linux/Apple: true (persistent Deflater/Inflater/z_stream).
 * JS: false (Node.js sync APIs create fresh compressor/decompressor per call).
 */
internal expect val supportsDeflateContextTakeover: Boolean

data class WebSocketConnectionOptions(
    val name: String,
    val port: Int = 443,
    val tls: TlsConfig? = if (port == 443) TlsConfig.DEFAULT else null,
    val connectionTimeout: Duration = 15.seconds,
    val readTimeout: Duration = connectionTimeout,
    val writeTimeout: Duration = connectionTimeout,
    val websocketEndpoint: String = "/",
    val protocols: List<String> = emptyList(),
    val requestCompression: Boolean = false,
    val compressionOptions: CompressionOptions = CompressionOptions(),
) {
    /**
     * Backward-compatible constructor accepting `tls: Boolean`.
     */
    constructor(
        name: String,
        port: Int = 443,
        tls: Boolean,
        connectionTimeout: Duration = 15.seconds,
        readTimeout: Duration = connectionTimeout,
        writeTimeout: Duration = connectionTimeout,
        websocketEndpoint: String = "/",
        protocols: List<String> = emptyList(),
        requestCompression: Boolean = false,
        compressionOptions: CompressionOptions = CompressionOptions(),
    ) : this(
        name = name,
        port = port,
        tls = if (tls) TlsConfig.DEFAULT else null,
        connectionTimeout = connectionTimeout,
        readTimeout = readTimeout,
        writeTimeout = writeTimeout,
        websocketEndpoint = websocketEndpoint,
        protocols = protocols,
        requestCompression = requestCompression,
        compressionOptions = compressionOptions,
    )

    /** Whether TLS is enabled for this connection. */
    val isTls: Boolean get() = tls != null

    internal fun buildUrl(): String {
        val prefix =
            if (isTls) {
                "wss://"
            } else {
                "ws://"
            }
        val postfix = "$name:$port$websocketEndpoint"
        return prefix + postfix
    }
}
