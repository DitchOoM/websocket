package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.compression.BufferAllocator
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.DEFAULT_NETWORK_BUFFER_SIZE
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.AutoFillingSuspendingStreamProcessor
import com.ditchoom.buffer.stream.EndOfStreamException
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketException
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.allocate
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
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
 * Designed for high performance and testability with RFC 6455 compliance.
 */
class DefaultWebSocketClient(
    private val connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope?,
    private val bufferFactory: BufferFactory = BufferFactory.Default,
    private val readBufferSize: Int = DEFAULT_READ_BUFFER_SIZE,
    internal val socketOverride: ClientToServerSocket? = null,
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

    // Create a dedicated Job for this client - NOT a child of parent scope.
    // This ensures the websocket can be cancelled without blocking parent scope completion.
    // The websocket runs independently; callers must explicitly call close().
    private val clientJob = kotlinx.coroutines.Job()

    override val scope: CoroutineScope =
        CoroutineScope(
            (parentScope?.coroutineContext ?: Dispatchers.Default) +
                clientJob +
                CoroutineName(
                    "ModularWebSocket: ${connectionOptions.name}:${connectionOptions.port}",
                ),
        )

    private val socket = socketOverride ?: ClientSocket.allocate()
    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    override val connectionState = connectionStateFlow.asStateFlow()

    // Main message channel (all types)
    private val incomingMessageChannel = Channel<WebSocketMessage>(Channel.UNLIMITED)
    override val incomingMessages = incomingMessageChannel.receiveAsFlow()

    // Typed message channels — avoid filterIsInstance overhead for typed consumers
    private val incomingTextChannel = Channel<String>(Channel.UNLIMITED)
    override val incomingTextMessages: Flow<String> = incomingTextChannel.receiveAsFlow()

    private val incomingBinaryChannel = Channel<ReadBuffer>(Channel.UNLIMITED)
    override val incomingBinaryMessages: Flow<ReadBuffer> = incomingBinaryChannel.receiveAsFlow()

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

    // Use StateFlow for thread-safe access between read loop and close()
    private val serverInitiatedClose = MutableStateFlow(false)
    private var clientKey: String = ""

    fun isOpen() = socket.isOpen() && connectionState.value is ConnectionState.Connected

    override suspend fun localPort(): Int = socket.localPort()

    override suspend fun remotePort(): Int = socket.remotePort()

    override suspend fun connect(): WebSocketClient {
        if (connectionState.value == ConnectionState.Connecting ||
            connectionState.value is ConnectionState.Connected
        ) {
            return this
        }
        connectionStateFlow.value = ConnectionState.Connecting

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

            // Open socket connection
            val socketOptions =
                if (connectionOptions.tls) SocketOptions.tlsDefault() else SocketOptions.LOW_LATENCY
            withTimeout(connectionOptions.connectionTimeout) {
                socket.open(connectionOptions.port, connectionOptions.connectionTimeout, connectionOptions.name, socketOptions)
            }

            // Send handshake request
            val requestBuffer = handshakeRequest.toBuffer()
            socket.write(requestBuffer, connectionOptions.writeTimeout)

            // Build auto-filling stream processor.
            // This replaces the manual readIntoProcessor + ensureAvailable pattern.
            // Peek/read operations automatically read from the socket when more data is needed.
            val autoFillingStream =
                StreamProcessor
                    .builder(pool)
                    .buildSuspendingWithAutoFill { stream ->
                        val buffer = pool.acquire(readBufferSize)
                        try {
                            val bytesRead = socket.read(buffer, connectionOptions.readTimeout)
                            if (bytesRead <= 0) {
                                buffer.freeIfNeeded()
                                throw EndOfStreamException()
                            }
                            buffer.setLimit(buffer.position())
                            buffer.position(0)
                            stream.append(buffer)
                        } catch (e: EndOfStreamException) {
                            throw e
                        } catch (e: Exception) {
                            buffer.freeIfNeeded()
                            throw e
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

                    connectionStateFlow.value = ConnectionState.Connected

                    // Start read loop with auto-filling processor
                    startReadLoop(autoFillingStream)
                }
                is ValidationResult.Failure -> {
                    autoFillingStream.release()
                    throw HandshakeException(validationResult.message)
                }
            }
        } catch (e: Exception) {
            connectionStateFlow.value = ConnectionState.Disconnected(e)
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

            // Read available data for parsing
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
                            // Update state to prevent close() from sending another close frame
                            connectionStateFlow.value =
                                ConnectionState.Disconnected(
                                    code = result.code.code,
                                    reason = result.reason,
                                )
                            return@launch
                        }
                    }
                }
                // Read loop exited normally (isOpen() returned false or !isActive).
                // Ensure state transitions to Disconnected so waiters don't hang.
                connectionStateFlow.update { current ->
                    if (current !is ConnectionState.Disconnected) {
                        ConnectionState.Disconnected()
                    } else {
                        current
                    }
                }
            } catch (e: EndOfStreamException) {
                // Clean disconnection — socket closed or read returned 0 bytes
                connectionStateFlow.update { current ->
                    if (current !is ConnectionState.Disconnected) {
                        ConnectionState.Disconnected()
                    } else {
                        current
                    }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                connectionStateFlow.update { current ->
                    when {
                        current is ConnectionState.Disconnected -> current
                        // Cancellation is a clean shutdown, not an error
                        e is kotlinx.coroutines.CancellationException -> ConnectionState.Disconnected()
                        // Socket errors: only report as error for unexpected disconnects
                        e is SocketException && (!serverInitiatedClose.value && current is ConnectionState.Connected) ->
                            ConnectionState.Disconnected(e)
                        e is SocketException -> ConnectionState.Disconnected()
                        // Catch-all for unexpected exceptions (e.g., ClosedReceiveChannelException
                        // when socket channel closes during read)
                        else -> ConnectionState.Disconnected(e)
                    }
                }
            } finally {
                messageAssembler.reset()
                autoFillingStream.release()
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
                serverInitiatedClose.value = true
                // Use pre-parsed close code and reason from FrameReader
                val closeFrame = frame as ParsedFrame.ControlFrame.Close
                val code = closeFrame.closeCode.code
                val reason = closeFrame.closeReason

                // Send close response if we haven't already
                if (connectionState.value is ConnectionState.Connected) {
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
                connectionStateFlow.value = ConnectionState.Disconnected(code = code, reason = reason)
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
                incomingTextChannel.trySend(text)
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
                incomingBinaryChannel.trySend(payload)
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

    override suspend fun write(string: String) {
        val writer = frameWriter ?: return
        val frameBuffer = writer.writeTextFrame(string)
        writeToSocket(frameBuffer)
        frameBuffer.freeIfNeeded() // Free native frame buffer after sending
    }

    override suspend fun write(buffer: ReadBuffer) {
        val writer = frameWriter ?: return
        val frameBuffer = writer.writeBinaryFrame(buffer)
        writeToSocket(frameBuffer)
        frameBuffer.freeIfNeeded() // Free native frame buffer after sending
    }

    override suspend fun ping(payloadData: ReadBuffer) {
        val writer = frameWriter ?: return
        val frameBuffer = writer.writePingFrame(payloadData)
        writeToSocket(frameBuffer)
        frameBuffer.freeIfNeeded()
    }

    private suspend fun writePongFrame(payloadData: ReadBuffer) {
        val writer = frameWriter ?: return
        val frameBuffer = writer.writePongFrame(payloadData)
        writeToSocket(frameBuffer)
        frameBuffer.freeIfNeeded()
    }

    private suspend fun sendCloseFrame(
        code: UShort,
        reason: String = "",
    ) {
        val writer = frameWriter ?: return
        try {
            val frameBuffer = writer.writeCloseFrame(code, reason)
            writeToSocket(frameBuffer)
            frameBuffer.freeIfNeeded()
        } catch (e: Exception) {
            // Ignore errors when sending close
        }
    }

    private suspend fun writeToSocket(buffer: ReadBuffer) {
        try {
            // Note: buffer is already in read mode from FrameWriter, don't call resetForRead()
            while (buffer.hasRemaining()) {
                socket.write(buffer, connectionOptions.writeTimeout)
            }
        } catch (e: Exception) {
            connectionStateFlow.value = ConnectionState.Disconnected(e)
        }
    }

    override suspend fun close() {
        if (connectionState.value is ConnectionState.Connected && !serverInitiatedClose.value) {
            sendCloseFrame(CloseCode.NORMAL.code, "Client closing")
        }
        // Clear frameWriter to prevent writes after close (avoids infinite loop
        // when socket.write returns -1 on a closed socket)
        frameWriter = null
        // Close the incoming message channels so collectors complete
        incomingMessageChannel.close()
        incomingTextChannel.close()
        incomingBinaryChannel.close()
        // Clean up compression resources
        outgoingCompressor?.close()
        outgoingCompressor = null
        incomingDecompressor?.close()
        incomingDecompressor = null
        // Cancel and join the read loop coroutine. This ensures the read loop's
        // finally block (which releases the stream processor) completes before we
        // close the socket or clear the pool. Without joining, pool.clear() could
        // free buffers the stream processor still references (use-after-free on K/N).
        try {
            clientJob.cancelAndJoin()
        } catch (_: Exception) {
            // Ignore cancel/join errors
        }
        // Close socket after read loop is done
        try {
            socket.close()
        } catch (_: Exception) {
            // Ignore close errors
        }
        // Free all pooled NativeBuffers (no-op on JVM/JS)
        pool.clear()
        // Atomic state update to avoid race with read loop
        connectionStateFlow.update { current ->
            if (current !is ConnectionState.Disconnected) {
                ConnectionState.Disconnected()
            } else {
                current
            }
        }
    }
}
