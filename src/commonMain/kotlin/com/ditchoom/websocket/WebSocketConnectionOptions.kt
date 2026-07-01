package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
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
    /**
     * Internal buffer allocator for frame read/write + compression chunks. Advanced
     * performance knob — most callers should leave it at the default.
     *
     * The default [BufferFactory.deterministic] is off-heap (zero-copy NIO) and
     * `freeNativeMemory()` synchronously closes the backing Arena, so buffer-pool
     * release actually reclaims memory instead of waiting for GC. This matches the
     * pool lifecycle the socket transport and codec use internally.
     *
     * Alternatives only worth considering for measurement or tuning:
     * - [BufferFactory.managed] — heap-backed; avoids direct-memory accounting but
     *   forces JDK internal copy on NIO reads/writes (breaks zero-copy).
     * - A pre-constructed [BufferPool] — to share a pool across multiple connections.
     */
    val bufferFactory: BufferFactory = BufferFactory.deterministic(),
) {
    fun buildUrl(): String {
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
