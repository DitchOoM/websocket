package com.ditchoom.websocket

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
    val tls: Boolean = port == 443,
    val connectionTimeout: Duration = 15.seconds,
    val readTimeout: Duration = connectionTimeout,
    val writeTimeout: Duration = connectionTimeout,
    val websocketEndpoint: String = "/",
    val protocols: List<String> = emptyList(),
    val requestCompression: Boolean = false,
    val compressionOptions: CompressionOptions = CompressionOptions(),
    val useNativePlatformClient: Boolean = true,
) {
    internal fun buildUrl(): String {
        val prefix =
            if (tls) {
                "wss://"
            } else {
                "ws://"
            }
        val postfix = "$name:$port$websocketEndpoint"
        return prefix + postfix
    }
}
