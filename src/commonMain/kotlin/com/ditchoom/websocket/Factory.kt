package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.WindowBits
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.stream.AutoFillingSuspendingStreamProcessor
import com.ditchoom.buffer.stream.EndOfStreamException
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.websocket.codecs.EmptyCodec
import com.ditchoom.websocket.handshake.HandshakeException
import com.ditchoom.websocket.handshake.HandshakeRequest
import com.ditchoom.websocket.handshake.HandshakeResponseParser
import com.ditchoom.websocket.handshake.HandshakeValidator
import com.ditchoom.websocket.handshake.ValidationResult
import kotlinx.coroutines.CoroutineScope

/**
 * Socket reads at or below this size are copied into a right-sized buffer instead of
 * appending the (full-pool-sized) read buffer to the frame stream. See the refill
 * lambda in [connectWebSocket]. 4 KiB keeps the copy cost trivial while covering the
 * pathological small-segment patterns (Autobahn chops at 64-2048 octets).
 */
private const val COMPACT_READ_THRESHOLD = 4096

/**
 * Connects a WebSocket over the given [ByteStream] and returns a ready-to-use
 * [Connection]. The connection is guaranteed to be established when this returns —
 * impossible to send on an unconnected client.
 *
 * Text frames always surface as [WebSocketMessage.Text] with a [String] payload
 * (UTF-8 per RFC 6455 §5.6). Binary frames are decoded via [binaryCodec], whose
 * output type [B] parameterizes [WebSocketMessage.Binary].
 *
 * Performs the HTTP upgrade handshake, negotiates permessage-deflate compression,
 * and assembles a [WebSocketCodec] from composable components.
 */
suspend fun <B> connectWebSocket(
    transport: ByteStream,
    connectionOptions: WebSocketConnectionOptions,
    binaryCodec: Codec<B>,
    parentScope: CoroutineScope? = null,
): Connection<WebSocketMessage<B>> {
    val bufferFactory = connectionOptions.bufferFactory
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
        val pool =
            bufferFactory as? com.ditchoom.buffer.pool.BufferPool
                ?: com.ditchoom.buffer.pool
                    .BufferPool(factory = bufferFactory)
        val autoFillingStream =
            StreamProcessor
                .builder(pool)
                .buildSuspendingWithAutoFill { stream ->
                    when (val result = transport.read(connectionOptions.readTimeout)) {
                        is ReadResult.Data -> {
                            val buffer = result.buffer
                            // Compact tiny reads before appending. The stream retains every appended
                            // chunk until the frame is parsed, and each socket read hands us a full
                            // pool-sized buffer (hundreds of KB) no matter how few bytes it carries.
                            // A peer trickling small TCP segments (Autobahn's 9.5/9.6 chopped-send
                            // cases deliver 1 MiB as 16k x 64-octet writes; a malicious server could
                            // do the same deliberately) would otherwise pin thousands of pool buffers
                            // within a single message and exhaust direct memory. Copying the few
                            // bytes into a right-sized buffer frees the pool buffer for immediate
                            // reuse. The copy comes from the seed factory because the pool is
                            // single-bucket: pool.acquire would hand back another full-size buffer.
                            if (buffer.remaining() <= COMPACT_READ_THRESHOLD) {
                                val compact = bufferFactory.allocate(buffer.remaining())
                                compact.write(buffer)
                                compact.resetForRead()
                                buffer.freeIfNeeded()
                                stream.append(compact)
                            } else {
                                stream.append(buffer)
                            }
                        }
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
                val compression = buildCompressionConfig(response, connectionOptions, bufferFactory)

                val outgoingEnabled = compression is CompressionConfig.Enabled && compression.compressor != null
                val clientNoCtx =
                    if (compression is CompressionConfig.Enabled) compression.clientNoContextTakeover else false

                val codec =
                    WebSocketCodec(
                        transport = transport,
                        stream = autoFillingStream,
                        compression = compression,
                        bufferFactory = bufferFactory,
                        binaryCodec = binaryCodec,
                        readTimeout = connectionOptions.readTimeout,
                        writeTimeout = connectionOptions.writeTimeout,
                        parentScope = parentScope,
                        compressor = (compression as? CompressionConfig.Enabled)?.compressor,
                        compressionEnabled = outgoingEnabled,
                        resetCompressorPerMessage = clientNoCtx,
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
                windowBits = if (negotiatedClientWindowBits in 9..15) WindowBits(negotiatedClientWindowBits) else WindowBits.Default,
            )
        } else {
            null
        }

    val decompressor =
        StreamingDecompressor.create(CompressionAlgorithm.Raw, bufferFactory = bufferFactory)

    val serverNoContextTakeover = response.compressionParams?.serverNoContextTakeover ?: false
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
 * Convenience overload for connections that only consume text and control frames.
 * Any received binary frames are silently discarded (payload = [Unit]). Use the
 * [binaryCodec] overload to materialize binary payloads, or supply
 * [com.ditchoom.websocket.codecs.RejectingCodec] to fail loudly on unexpected binary.
 */
suspend fun connectWebSocket(
    transport: ByteStream,
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope? = null,
): Connection<WebSocketMessage<Unit>> = connectWebSocket(transport, connectionOptions, EmptyCodec, parentScope)
