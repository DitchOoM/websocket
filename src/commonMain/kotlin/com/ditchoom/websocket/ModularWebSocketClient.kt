package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.compression.BufferAllocator
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketException
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeout

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
class ModularWebSocketClient(
    private val connectionOptions: WebSocketConnectionOptions,
    private val pool: BufferPool,
    parentScope: CoroutineScope?,
    private val allocationZone: AllocationZone = AllocationZone.Direct,
    private val readBufferSize: Int = DEFAULT_READ_BUFFER_SIZE,
) : WebSocketClient {
    // Create a dedicated Job for this client - NOT a child of parent scope.
    // This ensures the websocket can be cancelled without blocking parent scope completion.
    // The websocket runs independently; callers must explicitly call close().
    private val clientJob = kotlinx.coroutines.Job()

    override val scope: CoroutineScope =
        CoroutineScope(
            (parentScope?.coroutineContext ?: Dispatchers.Default) +
                clientJob +
                Dispatchers.Default +
                CoroutineName(
                    "ModularWebSocket: ${connectionOptions.name}:${connectionOptions.port}",
                ),
        )

    private val socket = ClientSocket.allocate(connectionOptions.tls, allocationZone)
    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    override val connectionState = connectionStateFlow.asStateFlow()

    private val incomingMessageChannel = Channel<WebSocketMessage>(Channel.UNLIMITED)
    override val incomingMessages = incomingMessageChannel.receiveAsFlow()

    // Components initialized during connect()
    // Note: These are set once during connect() and cleared during close().
    // The nullable checks (e.g., frameWriter ?: return) provide defensive access.
    private var frameWriter: FrameWriter? = null
    private var outgoingCompressor: StreamingCompressor? = null
    private var incomingDecompressor: StreamingDecompressor? = null
    private var compressionEnabled = false

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
                            requestCompression()
                        }
                    }.build()

            clientKey = handshakeRequest.key

            // Open socket connection
            withTimeout(connectionOptions.connectionTimeout) {
                socket.open(connectionOptions.port, connectionOptions.connectionTimeout, connectionOptions.name)
            }

            // Send handshake request
            val requestBuffer = handshakeRequest.toBuffer()
            socket.write(requestBuffer, connectionOptions.writeTimeout)

            // Read and parse handshake response
            val streamProcessor = StreamProcessor.builder(pool).buildSuspending()
            val response = readHandshakeResponse(streamProcessor)

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
                        setupCompression()
                    }

                    // Initialize frame writer
                    frameWriter =
                        FrameWriter(
                            compressor = outgoingCompressor,
                            compressionEnabled = compressionEnabled,
                            clientMode = true,
                            allocationZone = allocationZone,
                            pool = pool,
                        )

                    connectionStateFlow.value = ConnectionState.Connected

                    // Start read loop
                    startReadLoop(streamProcessor)
                }
                is ValidationResult.Failure -> {
                    streamProcessor.release()
                    throw HandshakeException(validationResult.message)
                }
            }
        } catch (e: Exception) {
            connectionStateFlow.value = ConnectionState.Disconnected(e)
        }

        return this
    }

    private fun setupCompression() {
        compressionEnabled = true
        val allocator = BufferAllocator.FromPool(pool)
        outgoingCompressor =
            StreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                allocator,
            )
        incomingDecompressor =
            StreamingDecompressor.create(
                CompressionAlgorithm.Raw,
                allocator,
            )
    }

    private suspend fun readHandshakeResponse(
        processor: com.ditchoom.buffer.stream.SuspendingStreamProcessor,
    ): com.ditchoom.websocket.handshake.HandshakeResponse {
        // Read until we have complete HTTP headers
        while (true) {
            readIntoProcessor(processor)

            // Try to parse - returns null if incomplete
            val available = processor.available()
            if (available < 12) continue // Minimum: "HTTP/1.1 101"

            // Read available data for parsing
            val buffer = pool.acquire(available)
            for (i in 0 until available) {
                buffer.writeByte(processor.peekByte(i))
            }
            buffer.resetForRead()

            // Find where headers end (position after \r\n\r\n)
            val headerEnd = HandshakeResponseParser.findHeaderEnd(buffer)
            if (headerEnd < 0) {
                continue // Need more data - headers not complete
            }

            val response =
                try {
                    HandshakeResponseParser.parse(buffer)
                } catch (e: HandshakeException) {
                    if (e.message?.contains("Incomplete") == true) {
                        continue // Need more data
                    }
                    throw e
                }

            // Successfully parsed - consume ONLY the HTTP headers, not any trailing frame data
            processor.skip(headerEnd)
            return response
        }
    }

    /**
     * Reads data from socket into the processor.
     * Uses zero-copy path when available: socket writes directly into our buffer.
     * Socket cancellation is handled via suspendCancellableCoroutine + invokeOnCancellation
     * in the underlying socket implementation.
     */
    private suspend fun readIntoProcessor(processor: com.ditchoom.buffer.stream.SuspendingStreamProcessor) {
        // Allocate buffer that we own (not from socket's internal pool).
        // This buffer will be owned by the processor after append.
        val buffer = pool.acquire(readBufferSize)

        try {
            // Zero-copy: socket writes directly into our buffer
            val bytesRead = socket.read(buffer, connectionOptions.readTimeout)

            if (bytesRead > 0) {
                // Buffer was written at position 0..bytesRead, position is now at bytesRead
                // Set limit to bytesRead and position to 0 for reading
                buffer.setLimit(buffer.position())
                buffer.position(0)
                processor.append(buffer)
            } else {
                // EOF or no data - free the unused buffer (Linux NativeBuffer leak fix)
                buffer.freeIfNeeded()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Do NOT free the buffer on cancellation. The kernel io_uring recv operation
            // may still be writing to this buffer — freeing it causes use-after-free heap
            // corruption. The buffer (64KB) is intentionally leaked on cancellation.
            // socket.close() will close the fd, causing the kernel to cancel the recv,
            // but we cannot safely free the buffer until that completes.
            throw e
        } catch (e: Exception) {
            // Socket error - free the unused buffer (Linux NativeBuffer leak fix)
            buffer.freeIfNeeded()
            throw e
        }
    }

    companion object {
        /** Default read buffer size (64KB) - matches typical socket receive buffer */
        const val DEFAULT_READ_BUFFER_SIZE = 65536
    }

    private fun startReadLoop(streamProcessor: com.ditchoom.buffer.stream.SuspendingStreamProcessor) {
        scope.launch {
            val frameReader = FrameReader(streamProcessor)
            val messageAssembler = MessageAssembler(compressionEnabled)

            try {
                readLoop@ while (isOpen() && isActive) {
                    // Ensure minimum data for frame header
                    while (streamProcessor.available() < 2 && isActive) {
                        readIntoProcessor(streamProcessor)
                    }

                    if (!isActive) break@readLoop

                    // Try to read a frame
                    val frame = frameReader.readFrame()

                    if (frame == null) {
                        // Need more data
                        readIntoProcessor(streamProcessor)
                        continue@readLoop
                    }

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
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Clean cancellation - update state atomically
                connectionStateFlow.update { current ->
                    if (current !is ConnectionState.Disconnected) {
                        ConnectionState.Disconnected()
                    } else {
                        current
                    }
                }
            } catch (e: SocketClosedException) {
                // Expected on close - update state atomically
                connectionStateFlow.update { current ->
                    when {
                        current is ConnectionState.Disconnected -> current
                        !serverInitiatedClose.value && current is ConnectionState.Connected ->
                            ConnectionState.Disconnected(e)
                        else -> ConnectionState.Disconnected()
                    }
                }
            } catch (e: SocketException) {
                // Socket error - update state atomically
                connectionStateFlow.update { current ->
                    when {
                        current is ConnectionState.Disconnected -> current
                        !serverInitiatedClose.value && current is ConnectionState.Connected ->
                            ConnectionState.Disconnected(e)
                        else -> ConnectionState.Disconnected()
                    }
                }
            } finally {
                streamProcessor.release()
            }
        }
    }

    private suspend fun handleControlFrame(frame: ParsedFrame) {
        // Note: FrameReader guarantees payload.position() == 0
        when (frame.opcode) {
            Opcode.Ping -> {
                // Auto-respond with pong
                val pongData =
                    if (frame.payloadLength > 0) {
                        frame.payload
                    } else {
                        EMPTY_BUFFER
                    }
                writePongFrame(pongData)
                // Reset position after pong write so consumers can read the payload
                if (frame.payloadLength > 0) {
                    frame.payload.position(0)
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
            decompressToBufferSync(payload, decompressor, allocationZone)
        } catch (e: Exception) {
            payload // Fallback to original on error
        } finally {
            // Reset decompressor state for next message.
            // The buffer library uses inflateReset() to avoid native heap churn.
            try {
                decompressor.reset()
            } catch (_: Exception) {
                // Ignore reset failures
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
            decompressToStringSync(payload, decompressor, pool)
        } finally {
            // Reset decompressor state for next message.
            // The buffer library uses inflateReset() to avoid native heap churn.
            try {
                decompressor.reset()
            } catch (_: Exception) {
                // Ignore reset failures
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
        // Close the incoming message channel so collectors complete
        incomingMessageChannel.close()
        // Clean up compression resources
        outgoingCompressor?.close()
        outgoingCompressor = null
        incomingDecompressor?.close()
        incomingDecompressor = null
        // Cancel job FIRST - this triggers CancellationException in read loop
        // which is properly handled via suspendCancellableCoroutine + invokeOnCancellation
        clientJob.cancel()
        // Then close socket (causes SocketClosedException if read is still in progress)
        try {
            socket.close()
        } catch (_: Exception) {
            // Ignore close errors
        }
        // Wait for cleanup with increased timeout for reliable close handshake
        try {
            kotlinx.coroutines.withTimeoutOrNull(1000) {
                clientJob.join()
            }
        } catch (_: Exception) {
            // Ignore join errors
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
