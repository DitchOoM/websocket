package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.compression.BufferAllocator
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.DEFAULT_NETWORK_BUFFER_SIZE
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.AutoFillingSuspendingStreamProcessor
import com.ditchoom.buffer.stream.EndOfStreamException
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.websocket.frame.AssembledMessage
import com.ditchoom.websocket.frame.AssemblyResult
import com.ditchoom.websocket.frame.CloseCode
import com.ditchoom.websocket.frame.FrameReader
import com.ditchoom.websocket.frame.FrameWriter
import com.ditchoom.websocket.frame.MessageAssembler
import com.ditchoom.websocket.frame.ParsedFrame
import com.ditchoom.websocket.handshake.HandshakeException
import com.ditchoom.websocket.handshake.HandshakeRequest
import com.ditchoom.websocket.handshake.HandshakeResponseParser
import com.ditchoom.websocket.handshake.HandshakeValidator
import com.ditchoom.websocket.handshake.ValidationResult
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * Modular WebSocket client implementation using composable components.
 *
 * This implementation uses:
 * - [FrameReader] for zero-copy frame parsing
 * - [FrameWriter] for frame serialization with SIMD masking
 * - [MessageAssembler] for fragmented message reassembly
 * - [HandshakeRequest]/[HandshakeResponseParser]/[HandshakeValidator] for connection setup
 *
 * The consumer provides a pre-connected [ByteStream] (e.g. TCP socket).
 * This client handles the HTTP upgrade handshake and WebSocket framing on top.
 */
class DefaultWebSocketClient(
    private val transport: ByteStream,
    private val connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope? = null,
    private val bufferFactory: BufferFactory = BufferFactory.deterministic(),
    private val readBufferSize: Int = DEFAULT_READ_BUFFER_SIZE,
    externalPool: BufferPool? = null,
) : WebSocketClient {
    // Lock-free pool for thread-safe buffer reuse across read loop and caller coroutines.
    // Must be multi-threaded: the read loop runs on Dispatchers.Default while
    // write()/close() are called from the caller's coroutine context.
    private val pool =
        externalPool ?: BufferPool(
            threadingMode = ThreadingMode.MultiThreaded,
            defaultBufferSize = DEFAULT_NETWORK_BUFFER_SIZE,
            factory = bufferFactory,
        )

    // Child of parentScope's Job so parent cancellation propagates (structured concurrency).
    // Can still be cancelled independently via close().
    private val clientJob = kotlinx.coroutines.Job(parentScope?.coroutineContext?.get(kotlinx.coroutines.Job))

    init {
        // Sync closed flag when the job completes for any reason
        // (parent cancellation, explicit close, failure).
        clientJob.invokeOnCompletion { closed = true }
    }

    internal val scope: CoroutineScope =
        CoroutineScope(
            (parentScope?.coroutineContext ?: Dispatchers.Default) +
                clientJob +
                CoroutineName(
                    "ModularWebSocket: ${connectionOptions.name}:${connectionOptions.port}",
                ),
        )

    override val id: Long = 0L

    @Volatile
    private var closed = false
    private var connected = false

    private val incomingMessageChannel = Channel<WebSocketMessage>(Channel.UNLIMITED)

    override fun receive(): Flow<WebSocketMessage> = incomingMessageChannel.receiveAsFlow()

    // Components initialized during connect()
    // Note: These are set once during connect() and cleared during close().
    // The nullable checks (e.g., frameWriter ?: return) provide defensive access.
    private var frameWriter: FrameWriter? = null
    private var outgoingCompressor: StreamingCompressor? = null
    private var incomingDecompressor: StreamingDecompressor? = null
    private var compressionEnabled = false
    private var outgoingCompressionEnabled = false
    private var serverNoContextTakeover = false
    private var clientNoContextTakeover = false

    // Reusable decoder for streaming UTF-8 decoding of decompressed text messages
    private val stringDecoder = StreamingStringDecoder()

    private var serverInitiatedClose = false
    private var clientKey: String = ""

    fun isOpen() = !closed && transport.isOpen

    override suspend fun connect(): WebSocketClient {
        if (connected || closed) return this

        try {
            // Build handshake request using modular component
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
                            // On platforms without context takeover support (JS Node.js),
                            // always include no_context_takeover in the offer so the server
                            // doesn't use back-references to prior messages.
                            val forceNoCtx = !supportsDeflateContextTakeover
                            requestCompression(
                                clientNoContextTakeover = opts.clientNoContextTakeover || forceNoCtx,
                                serverNoContextTakeover = opts.serverNoContextTakeover || forceNoCtx,
                                serverMaxWindowBits = opts.serverMaxWindowBits,
                                clientMaxWindowBits = opts.clientMaxWindowBits,
                            )
                        }
                    }.build()

            clientKey = handshakeRequest.key

            // Send handshake request (transport is already connected)
            val requestBuffer = handshakeRequest.toBuffer()
            transport.write(requestBuffer, connectionOptions.writeTimeout)

            // Build auto-filling stream processor.
            // Peek/read operations automatically read from the transport when more data is needed.
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
            val response = readHandshakeResponse(autoFillingStream)

            // Validate response
            val offeredExtensions =
                if (connectionOptions.requestCompression) {
                    listOf("permessage-deflate")
                } else {
                    emptyList()
                }
            val validationResult =
                HandshakeValidator.validate(
                    response = response,
                    expectedAcceptKey = handshakeRequest.expectedAcceptKey,
                    offeredProtocols = connectionOptions.protocols,
                    offeredExtensions = offeredExtensions,
                )

            when (validationResult) {
                is ValidationResult.Success -> {
                    // Setup compression if negotiated
                    if (response.compressionEnabled) {
                        val negotiatedClientWindowBits =
                            response.compressionParams?.clientMaxWindowBits ?: 0
                        setupCompression(negotiatedClientWindowBits)
                        response.compressionParams?.let {
                            serverNoContextTakeover = it.serverNoContextTakeover
                        }
                        // client_no_context_takeover is a unilateral client promise
                        // (RFC 7692 Section 7.1.1.2): the client must honor it regardless
                        // of whether the server echoes it. Also honor if the server requires it.
                        clientNoContextTakeover =
                            connectionOptions.compressionOptions.clientNoContextTakeover ||
                            (response.compressionParams?.clientNoContextTakeover ?: false)
                    }

                    // Initialize frame writer
                    frameWriter =
                        FrameWriter(
                            compressor = outgoingCompressor,
                            compressionEnabled = outgoingCompressionEnabled,
                            clientMode = true,
                            bufferFactory = bufferFactory,
                            pool = pool,
                            resetCompressorPerMessage = clientNoContextTakeover,
                        )

                    connected = true

                    // Start read loop with auto-filling processor
                    startReadLoop(autoFillingStream)
                }
                is ValidationResult.Failure -> {
                    autoFillingStream.release()
                    throw HandshakeException(validationResult.message)
                }
            }
        } catch (e: Exception) {
            closed = true
            throw wrapException(e)
        }

        return this
    }

    private fun setupCompression(clientWindowBits: Int = 0) {
        val allocator = BufferAllocator.FromPool(pool)
        compressionEnabled = true
        // Disable outgoing compression when the server negotiated client_max_window_bits < 15
        // and the platform can't honor it (JVM Deflater only supports window=15).
        // Incoming decompression still works (Inflater handles any window size).
        val needsCustomWindowBits = clientWindowBits in 8..14
        outgoingCompressionEnabled = !needsCustomWindowBits || supportsCustomDeflateWindowBits
        if (outgoingCompressionEnabled) {
            outgoingCompressor =
                StreamingCompressor.create(
                    algorithm = CompressionAlgorithm.Raw,
                    level = CompressionLevel.Default,
                    allocator = allocator,
                    windowBits = if (clientWindowBits in 8..15) -clientWindowBits else 0,
                )
        }
        incomingDecompressor =
            StreamingDecompressor.create(
                CompressionAlgorithm.Raw,
                allocator,
            )
    }

    private suspend fun readHandshakeResponse(
        processor: AutoFillingSuspendingStreamProcessor,
    ): com.ditchoom.websocket.handshake.HandshakeResponse {
        // Read until we have complete HTTP headers.
        // The auto-filling processor reads from the socket as needed.
        while (true) {
            val available = processor.available()
            if (available < 12) {
                // Auto-fill to get at least 12 bytes (minimum "HTTP/1.1 101")
                processor.peekByte(11)
                continue
            }

            // Copy available data for header-end detection without consuming.
            // This is O(n²) from peekByte per byte, but the handshake response
            // is typically ~200 bytes — negligible one-time cost at connect.
            val buffer = pool.acquire(available)
            for (i in 0 until available) {
                buffer.writeByte(processor.peekByte(i))
            }
            buffer.resetForRead()

            // Find where headers end (position after \r\n\r\n)
            val headerEnd = HandshakeResponseParser.findHeaderEnd(buffer)
            if (headerEnd < 0) {
                // Need more data — auto-fill at least 1 more byte
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

            // Successfully parsed - consume ONLY the HTTP headers, not any trailing frame data
            processor.skip(headerEnd)
            return response
        }
    }

    companion object {
        /** Default read buffer size (64KB) - matches typical socket receive buffer */
        const val DEFAULT_READ_BUFFER_SIZE = 65536

        /** Wraps platform/transport exceptions in domain-specific [WebSocketException] subtypes. */
        internal fun wrapException(e: Exception): Exception =
            when (e) {
                is WebSocketException -> e
                is HandshakeException -> WebSocketException.HandshakeRejected(e.message ?: "Handshake failed", cause = e)
                else -> WebSocketException.TransportFailed(e.message ?: "Transport error", e)
            }
    }

    /**
     * Reads the next complete frame, using auto-fill to ensure data availability.
     *
     * FrameReader.readFrame() checks `available()` (synchronous, no auto-fill) before
     * parsing. If the full frame isn't available yet, it returns null. We handle this
     * by auto-filling one more chunk and retrying until a complete frame is read.
     */
    private suspend fun readNextFrame(
        frameReader: FrameReader,
        stream: AutoFillingSuspendingStreamProcessor,
    ): ParsedFrame? {
        while (coroutineContext.isActive) {
            if (stream.available() < 2) {
                stream.peekByte(0) // auto-fill at least 1 byte
            }
            val frame = frameReader.readFrame()
            if (frame != null) return frame
            // Not enough data for complete frame — auto-fill one more chunk
            stream.peekByte(stream.available())
        }
        return null
    }

    private fun startReadLoop(autoFillingStream: AutoFillingSuspendingStreamProcessor) {
        scope.launch {
            val frameReader = FrameReader(autoFillingStream, pool)
            val messageAssembler = MessageAssembler(compressionEnabled, pool)

            try {
                readLoop@ while (isOpen() && isActive) {
                    val frame = readNextFrame(frameReader, autoFillingStream) ?: break@readLoop

                    // Process frame through assembler
                    when (val result = messageAssembler.addFrame(frame)) {
                        is AssemblyResult.ControlFrame -> {
                            handleControlFrame(result.frame)
                            if (result.frame.opcode == Opcode.Close) {
                                return@launch
                            }
                        }
                        is AssemblyResult.CompleteMessage -> {
                            emitMessage(result.message)
                            // Free fragment NativeBuffers after processing (Linux native heap).
                            // For multi-fragment messages, combineBuffers() copied data to a new
                            // buffer, so originals can be freed. Empty for single-frame messages.
                            result.fragmentsToClose.freeAll()
                        }
                        is AssemblyResult.NeedMoreFrames -> {
                            // Continue reading
                        }
                        is AssemblyResult.Error -> {
                            sendCloseFrame(result.code.code, result.reason)
                            closed = true
                            return@launch
                        }
                    }
                }
            } catch (_: EndOfStreamException) {
                // Clean EOF
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Transport or protocol error — close frame already sent if possible
            } finally {
                messageAssembler.reset()
                autoFillingStream.release()
                // Close channel so any collectors (e.g. take(N).collect) unblock.
                // Without this, server TCP close without a WS close frame leaves
                // collectors hanging forever.
                incomingMessageChannel.close()
            }
        }
    }

    private suspend fun handleControlFrame(frame: ParsedFrame) {
        when (frame.opcode) {
            Opcode.Ping -> {
                // Auto-respond with pong, preserving payload position for consumers
                val pongData =
                    if (frame.payloadLength > 0) {
                        frame.payload
                    } else {
                        EMPTY_BUFFER
                    }
                val payloadStart = frame.payload.position()
                writePongFrame(pongData)
                // Restore position after pong write so consumers can read the payload
                if (frame.payloadLength > 0) {
                    frame.payload.position(payloadStart)
                }
                incomingMessageChannel.trySend(WebSocketMessage.Ping(frame.payload))
            }
            Opcode.Pong -> {
                incomingMessageChannel.trySend(WebSocketMessage.Pong(frame.payload))
            }
            Opcode.Close -> {
                serverInitiatedClose = true
                // Use pre-parsed close code and reason from FrameReader
                val closeFrame = frame as ParsedFrame.ControlFrame.Close
                val code = closeFrame.closeCode.code
                val reason = closeFrame.closeReason

                // Send close response if we haven't already
                if (connected && !closed) {
                    // If our parser detected a protocol error (e.g., 1-byte close payload),
                    // respond with the error code. Otherwise, respond with 1000 Normal Closure.
                    val responseCode =
                        if (closeFrame.closeCode == CloseCode.PROTOCOL_ERROR) {
                            CloseCode.PROTOCOL_ERROR.code
                        } else {
                            CloseCode.NORMAL.code
                        }
                    sendCloseFrame(responseCode, reason)
                }

                incomingMessageChannel.trySend(WebSocketMessage.Close(code, reason))
                closed = true
            }
            else -> {
                // Unexpected control opcode
            }
        }
    }

    private suspend fun emitMessage(message: AssembledMessage) {
        when (message.opcode) {
            Opcode.Text -> {
                val text =
                    try {
                        if (message.compressed && compressionEnabled) {
                            decompressPayloadToString(message.payload)
                        } else {
                            message.payload.readString(message.payload.remaining(), Charset.UTF8)
                        }
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Throwable,
                    ) {
                        // Use Throwable to catch JS TypeError from TextDecoder on invalid UTF-8
                        sendCloseFrame(CloseCode.INVALID_PAYLOAD.code, "Invalid UTF-8")
                        message.payload.freeIfNeeded()
                        return
                    }
                // Free the compressed/raw payload after extracting text
                message.payload.freeIfNeeded()
                incomingMessageChannel.trySend(WebSocketMessage.Text(text))
            }
            Opcode.Binary -> {
                val payload =
                    if (message.compressed && compressionEnabled) {
                        val decompressed = decompressPayload(message.payload)
                        // Free compressed payload after decompression (decompressed is a new buffer)
                        if (decompressed !== message.payload) {
                            message.payload.freeIfNeeded()
                        }
                        decompressed
                    } else {
                        message.payload
                    }
                incomingMessageChannel.trySend(WebSocketMessage.Binary(payload))
            }
            else -> {
                // Unexpected opcode for assembled message
            }
        }
    }

    private fun decompressPayload(payload: ReadBuffer): ReadBuffer {
        val decompressor = incomingDecompressor ?: return payload

        return try {
            // Stream decompress to single buffer - reduces peak memory
            // Use Direct zone so output buffer has NativeMemoryAccess for re-compression
            decompressToBufferSync(payload, decompressor, bufferFactory, pool)
        } catch (e: Exception) {
            payload // Fallback to original on error
        } finally {
            // Reset decompressor state for next message when server_no_context_takeover
            // was negotiated. Without it, the server maintains its LZ77 sliding window
            // across messages and the client must do the same for decompression.
            if (serverNoContextTakeover) {
                try {
                    decompressor.reset()
                } catch (_: Exception) {
                    // Ignore reset failures
                }
            }
        }
    }

    /**
     * Decompresses payload directly to string with streaming UTF-8 handling.
     * Reduces peak memory by not holding all decompressed chunks at once.
     */
    private fun decompressPayloadToString(payload: ReadBuffer): String {
        val decompressor =
            incomingDecompressor
                ?: return payload.readString(payload.remaining(), Charset.UTF8)

        return try {
            // Stream decompress directly to string - reduces peak memory
            decompressToStringSync(payload, decompressor, stringDecoder)
        } catch (e: Throwable) {
            throw e
        } finally {
            if (serverNoContextTakeover) {
                try {
                    decompressor.reset()
                } catch (_: Exception) {
                    // Ignore reset failures
                }
            }
        }
    }

    override suspend fun send(message: WebSocketMessage) {
        if (closed || !connected) {
            throw WebSocketException.ConnectionClosed("Cannot send: not connected")
        }
        // frameWriter is guaranteed non-null while connected; the null-check
        // is a safety net for the TOCTOU race with close().
        val writer = frameWriter ?: throw WebSocketException.ConnectionClosed("WebSocket is closing")
        val frameBuffer =
            when (message) {
                is WebSocketMessage.Text -> writer.writeTextFrame(message.value)
                is WebSocketMessage.Binary -> writer.writeBinaryFrame(message.value)
                is WebSocketMessage.Ping -> writer.writePingFrame(message.value)
                is WebSocketMessage.Pong -> writer.writePongFrame(message.value)
                is WebSocketMessage.Close -> writer.writeCloseFrame(message.code, message.reason)
            }
        writeToTransport(frameBuffer)
        frameBuffer.freeIfNeeded()
    }

    private suspend fun writePongFrame(payloadData: ReadBuffer) {
        val writer = frameWriter ?: return
        val frameBuffer = writer.writePongFrame(payloadData)
        writeToTransport(frameBuffer)
        frameBuffer.freeIfNeeded()
    }

    private suspend fun sendCloseFrame(
        code: UShort,
        reason: String = "",
    ) {
        val writer = frameWriter ?: return
        try {
            val frameBuffer = writer.writeCloseFrame(code, reason)
            writeToTransport(frameBuffer)
            frameBuffer.freeIfNeeded()
        } catch (e: Exception) {
            // Ignore errors when sending close
        }
    }

    private suspend fun writeToTransport(buffer: ReadBuffer) {
        try {
            // Note: buffer is already in read mode from FrameWriter, don't call resetForRead()
            while (buffer.hasRemaining()) {
                transport.write(buffer, connectionOptions.writeTimeout)
            }
        } catch (e: Exception) {
            // Write failed — transport likely closed
            closed = true
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true

        if (connected && !serverInitiatedClose) {
            try {
                sendCloseFrame(CloseCode.NORMAL.code, "Client closing")
            } catch (_: Exception) {
                // Ignore errors when sending close frame
            }
        }
        // Clear frameWriter to prevent writes after close (avoids infinite loop
        // when transport.write returns -1 on a closed transport)
        frameWriter = null
        // Close the incoming message channel so collectors complete
        incomingMessageChannel.close()
        // Clean up compression resources
        outgoingCompressor?.close()
        outgoingCompressor = null
        incomingDecompressor?.close()
        incomingDecompressor = null
        // Close transport first to break any pending I/O, then cancelAndJoin.
        // Order matters for K/N Darwin: cancel() on a coroutine suspended in a
        // GCD-dispatched I/O operation crashes because GCD still holds a reference
        // to the continuation. Closing the transport first completes the I/O (with an
        // error), the coroutine exits its suspension point, and cancelAndJoin() is
        // safe — no in-flight GCD blocks remain.
        try {
            transport.close()
        } catch (_: Exception) {
            // Ignore close errors
        }
        try {
            clientJob.cancelAndJoin()
        } catch (_: Exception) {
            // Ignore cancel/join errors
        }
        // Free all pooled NativeBuffers (no-op on JVM/JS)
        pool.clear()
    }
}
