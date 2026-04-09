package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.stream.AutoFillingSuspendingStreamProcessor
import com.ditchoom.buffer.stream.EndOfStreamException
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.websocket.frame.FrameWriter
import com.ditchoom.websocket.handshake.HandshakeException
import com.ditchoom.websocket.handshake.HandshakeRequest
import com.ditchoom.websocket.handshake.HandshakeResponseParser
import com.ditchoom.websocket.handshake.HandshakeValidator
import com.ditchoom.websocket.handshake.ValidationResult
import kotlinx.coroutines.CoroutineScope

/**
 * Connects a WebSocket over the given [ByteStream] and returns a ready-to-use
 * [Connection]. The connection is guaranteed to be established when this returns —
 * impossible to send on an unconnected client.
 *
 * Performs the HTTP upgrade handshake, negotiates permessage-deflate compression,
 * and assembles a [WebSocketCodec] from composable components.
 *
 * ```kotlin
 * val ws: Connection<WebSocketMessage> = connectWebSocket(transport, options)
 * ws.send(WebSocketMessage.Text("hello")) // guaranteed connected
 * ```
 *
 * @param transport A pre-connected byte transport (e.g. TCP socket).
 * @param connectionOptions Connection configuration (host, compression, etc.)
 * @param parentScope Optional parent coroutine scope for structured concurrency.
 * @param bufferFactory Buffer factory for frame I/O allocations.
 * @param bufferPool Optional buffer pool for memory reuse.
 * @throws WebSocketException.HandshakeRejected if the server rejects the upgrade.
 * @throws WebSocketException.TransportFailed if the transport fails during handshake.
 */
suspend fun connectWebSocket(
    transport: ByteStream,
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope? = null,
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
): Connection<WebSocketMessage> {
    try {
        // Build handshake request
        val handshakeRequest =
            HandshakeRequest
                .builder(
                    host = connectionOptions.name,
                    port = connectionOptions.port,
                    path = connectionOptions.websocketEndpoint,
                ).apply {
                    useTls(connectionOptions.tls)
                    if (connectionOptions.protocols.isNotEmpty()) {
                        protocols(*connectionOptions.protocols.toTypedArray())
                    }
                    if (connectionOptions.requestCompression) {
                        val opts = connectionOptions.compressionOptions
                        val forceNoCtx = !supportsDeflateContextTakeover
                        requestCompression(
                            clientNoContextTakeover = opts.clientNoContextTakeover || forceNoCtx,
                            serverNoContextTakeover = opts.serverNoContextTakeover || forceNoCtx,
                            serverMaxWindowBits = opts.serverMaxWindowBits,
                            clientMaxWindowBits = opts.clientMaxWindowBits,
                        )
                    }
                }.build()

        // Send handshake request
        val requestBuffer = handshakeRequest.toBuffer()
        transport.write(requestBuffer, connectionOptions.writeTimeout)

        // Build auto-filling stream processor
        // StreamProcessor.builder requires a BufferPool; if the factory is already a pool, use it directly
        val pool =
            bufferFactory as? com.ditchoom.buffer.pool.BufferPool
                ?: com.ditchoom.buffer.pool.BufferPool(factory = bufferFactory)
        val autoFillingStream =
            StreamProcessor
                .builder(pool)
                .buildSuspendingWithAutoFill { stream ->
                    when (val result = transport.read(connectionOptions.readTimeout)) {
                        is ReadResult.Data -> stream.append(result.buffer)
                        is ReadResult.End -> throw EndOfStreamException()
                        is ReadResult.Reset -> throw EndOfStreamException("Connection reset")
                    }
                }

        // Read and parse handshake response
        val response = readHandshakeResponse(autoFillingStream, bufferFactory)

        // Validate response
        val offeredExtensions =
            if (connectionOptions.requestCompression) listOf("permessage-deflate") else emptyList()
        val validationResult =
            HandshakeValidator.validate(
                response = response,
                expectedAcceptKey = handshakeRequest.expectedAcceptKey,
                offeredProtocols = connectionOptions.protocols,
                offeredExtensions = offeredExtensions,
            )

        when (validationResult) {
            is ValidationResult.Success -> {
                // Build compression config from negotiation result
                val compression = buildCompressionConfig(response, connectionOptions, bufferFactory)

                // Initialize frame writer
                val outgoingEnabled = compression is CompressionConfig.Enabled && compression.compressor != null
                val clientNoCtx =
                    if (compression is CompressionConfig.Enabled) compression.clientNoContextTakeover else false
                val frameWriter =
                    FrameWriter(
                        compressor = (compression as? CompressionConfig.Enabled)?.compressor,
                        compressionEnabled = outgoingEnabled,
                        clientMode = true,
                        bufferFactory = bufferFactory,
                        resetCompressorPerMessage = clientNoCtx,
                    )

                // Assemble and start the codec connection
                val codec =
                    WebSocketCodec(
                        transport = transport,
                        frameWriter = frameWriter,
                        stream = autoFillingStream,
                        compression = compression,
                        bufferFactory = bufferFactory,
                        readTimeout = connectionOptions.readTimeout,
                        writeTimeout = connectionOptions.writeTimeout,
                        parentScope = parentScope,
                    )
                codec.startReadLoop()
                return codec
            }
            is ValidationResult.Failure -> {
                autoFillingStream.release()
                throw HandshakeException(validationResult.message)
            }
        }
    } catch (e: Exception) {
        throw wrapException(e)
    }
}

/**
 * Build [CompressionConfig] from the handshake response and connection options.
 */
private fun buildCompressionConfig(
    response: com.ditchoom.websocket.handshake.HandshakeResponse,
    connectionOptions: WebSocketConnectionOptions,
    bufferFactory: BufferFactory,
): CompressionConfig {
    if (!response.compressionEnabled) return CompressionConfig.None

    val negotiatedClientWindowBits = response.compressionParams?.clientMaxWindowBits ?: 0

    val needsCustomWindowBits = negotiatedClientWindowBits in 8..14
    val outgoingEnabled = !needsCustomWindowBits || supportsCustomDeflateWindowBits

    val compressor =
        if (outgoingEnabled) {
            StreamingCompressor.create(
                algorithm = CompressionAlgorithm.Raw,
                level = CompressionLevel.Default,
                bufferFactory = bufferFactory,
                windowBits = if (negotiatedClientWindowBits in 8..15) -negotiatedClientWindowBits else 0,
            )
        } else {
            null
        }

    val decompressor =
        StreamingDecompressor.create(CompressionAlgorithm.Raw, bufferFactory = bufferFactory)

    val serverNoContextTakeover = response.compressionParams?.serverNoContextTakeover ?: false
    // client_no_context_takeover is a unilateral client promise (RFC 7692 Section 7.1.1.2):
    // honor it regardless of whether the server echoes it.
    val clientNoContextTakeover =
        connectionOptions.compressionOptions.clientNoContextTakeover ||
            (response.compressionParams?.clientNoContextTakeover ?: false)

    return CompressionConfig.Enabled(
        decompressor = decompressor,
        compressor = compressor,
        serverNoContextTakeover = serverNoContextTakeover,
        clientNoContextTakeover = clientNoContextTakeover,
    )
}

/**
 * Reads the HTTP handshake response from the auto-filling stream processor.
 */
private suspend fun readHandshakeResponse(
    processor: AutoFillingSuspendingStreamProcessor,
    bufferFactory: BufferFactory,
): com.ditchoom.websocket.handshake.HandshakeResponse {
    while (true) {
        val available = processor.available()
        if (available < 12) {
            processor.peekByte(11)
            continue
        }

        val buffer = bufferFactory.allocate(available)
        for (i in 0 until available) {
            buffer.writeByte(processor.peekByte(i))
        }
        buffer.resetForRead()

        val headerEnd = HandshakeResponseParser.findHeaderEnd(buffer)
        if (headerEnd < 0) {
            processor.peekByte(available)
            continue
        }

        val response =
            try {
                HandshakeResponseParser.parse(buffer)
            } catch (e: HandshakeException) {
                if (e.message?.contains("Incomplete") == true) {
                    processor.peekByte(available)
                    continue
                }
                throw e
            }

        processor.skip(headerEnd)
        return response
    }
}

/** Wraps platform/transport exceptions in domain-specific [WebSocketException] subtypes. */
internal fun wrapException(e: Exception): Exception =
    when (e) {
        is WebSocketException -> e
        is HandshakeException -> WebSocketException.HandshakeRejected(e.message ?: "Handshake failed", cause = e)
        else -> WebSocketException.TransportFailed(e.message ?: "Transport error", e)
    }

/**
 * Create a platform-native WebSocket connection.
 *
 * On platforms with native WebSocket support (browser), this creates a
 * native client that handles transport internally. On other platforms
 * (JVM, Linux, Apple native), this throws [UnsupportedOperationException] —
 * use [connectWebSocket] with a [ByteStream] instead.
 */
expect suspend fun connectNativeWebSocket(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope? = null,
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
): Connection<WebSocketMessage>
