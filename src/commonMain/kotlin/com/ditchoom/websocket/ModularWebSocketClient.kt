package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.compression.BufferAllocator
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.SuspendingStreamingCompressor
import com.ditchoom.buffer.compression.SuspendingStreamingDecompressor
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : WebSocketClient {
    override val scope: CoroutineScope =
        if (parentScope == null) {
            CoroutineScope(
                Dispatchers.Default +
                    CoroutineName(
                        "ModularWebSocket: ${connectionOptions.name}:${connectionOptions.port}",
                    ),
            )
        } else {
            parentScope + Dispatchers.Default +
                CoroutineName(
                    "ModularWebSocket: ${connectionOptions.name}:${connectionOptions.port}",
                )
        }

    private val socket = ClientSocket.allocate(connectionOptions.tls, allocationZone)
    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    override val connectionState = connectionStateFlow.asStateFlow()
    private val incomingMessageSharedFlow = MutableSharedFlow<WebSocketMessage>()
    override val incomingMessages = incomingMessageSharedFlow.asSharedFlow()

    // Components initialized during connect()
    private var frameWriter: FrameWriter? = null
    private var outgoingCompressor: SuspendingStreamingCompressor? = null
    private var incomingDecompressor: SuspendingStreamingDecompressor? = null
    private var compressionEnabled = false
    private var hasServerInitiatedClose = false
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
        outgoingCompressor =
            SuspendingStreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                BufferAllocator.Heap,
            )
        incomingDecompressor =
            SuspendingStreamingDecompressor.create(
                CompressionAlgorithm.Raw,
                BufferAllocator.Direct,
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
            val buffer = PlatformBuffer.allocate(available, AllocationZone.Heap)
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

    private suspend fun readIntoProcessor(processor: com.ditchoom.buffer.stream.SuspendingStreamProcessor) {
        val readBuffer = socket.read(connectionOptions.readTimeout)
        readBuffer.resetForRead()
        if (readBuffer.hasRemaining()) {
            processor.append(readBuffer)
        }
    }

    private fun startReadLoop(streamProcessor: com.ditchoom.buffer.stream.SuspendingStreamProcessor) {
        scope.launch {
            val frameReader = FrameReader(streamProcessor)
            val messageAssembler = MessageAssembler()

            try {
                readLoop@ while (isOpen()) {
                    // Ensure minimum data for frame header
                    while (streamProcessor.available() < 2) {
                        readIntoProcessor(streamProcessor)
                    }

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
                        }
                        is AssemblyResult.NeedMoreFrames -> {
                            // Continue reading
                        }
                        is AssemblyResult.Error -> {
                            sendCloseFrame(result.code.code, result.reason)
                            return@launch
                        }
                    }
                }
            } catch (e: SocketClosedException) {
                // Expected on close
                if (!hasServerInitiatedClose && connectionState.value is ConnectionState.Connected) {
                    connectionStateFlow.value = ConnectionState.Disconnected(e)
                } else {
                    connectionStateFlow.value = ConnectionState.Disconnected()
                }
            } catch (e: SocketException) {
                // Socket error
                if (!hasServerInitiatedClose && connectionState.value is ConnectionState.Connected) {
                    connectionStateFlow.value = ConnectionState.Disconnected(e)
                } else {
                    connectionStateFlow.value = ConnectionState.Disconnected()
                }
            } finally {
                streamProcessor.release()
            }
        }
    }

    private suspend fun handleControlFrame(frame: ParsedFrame) {
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
                incomingMessageSharedFlow.emit(WebSocketMessage.Ping(frame.payload))
            }
            Opcode.Pong -> {
                incomingMessageSharedFlow.emit(WebSocketMessage.Pong(frame.payload))
            }
            Opcode.Close -> {
                hasServerInitiatedClose = true
                val (code, reason) = parseClosePayload(frame)

                // Send close response if we haven't already
                if (connectionState.value is ConnectionState.Connected) {
                    sendCloseFrame(code, reason)
                }

                incomingMessageSharedFlow.emit(WebSocketMessage.Close(code, reason))
                connectionStateFlow.value = ConnectionState.Disconnected(code = code, reason = reason)
            }
            else -> {
                // Unexpected control opcode
            }
        }
    }

    private fun parseClosePayload(frame: ParsedFrame): Pair<UShort, String> {
        if (frame.payloadLength == 0) {
            return 1005u.toUShort() to ""
        }
        if (frame.payloadLength == 1) {
            return 1002u.toUShort() to "Invalid close payload"
        }

        val code = frame.payload.readUnsignedShort()
        val reason =
            if (frame.payload.hasRemaining()) {
                try {
                    frame.payload.readString(frame.payload.remaining(), Charset.UTF8)
                } catch (e: Exception) {
                    ""
                }
            } else {
                ""
            }
        return code to reason
    }

    private suspend fun emitMessage(message: AssembledMessage) {
        val payload =
            if (message.compressed && compressionEnabled) {
                decompressPayload(message.payload)
            } else {
                message.payload
            }

        when (message.opcode) {
            Opcode.Text -> {
                val text =
                    try {
                        payload.readString(payload.remaining(), Charset.UTF8)
                    } catch (e: Exception) {
                        sendCloseFrame(CloseCode.INVALID_PAYLOAD.code, "Invalid UTF-8")
                        return
                    }
                incomingMessageSharedFlow.emit(WebSocketMessage.Text(text))
            }
            Opcode.Binary -> {
                incomingMessageSharedFlow.emit(WebSocketMessage.Binary(payload))
            }
            else -> {
                // Unexpected opcode for assembled message
            }
        }
    }

    private suspend fun decompressPayload(payload: ReadBuffer): ReadBuffer {
        val decompressor = incomingDecompressor ?: return payload

        return try {
            val chunks = decompressWithStreamingDecompressor(payload, decompressor)
            decompressor.reset()
            combineChunks(chunks, AllocationZone.Direct)
        } catch (e: Exception) {
            payload // Fallback to original on error
        }
    }

    override suspend fun write(string: String) {
        val writer = frameWriter ?: return
        val frameBuffer = writer.writeTextFrame(string)
        writeToSocket(frameBuffer)
    }

    override suspend fun write(buffer: ReadBuffer) {
        val writer = frameWriter ?: return
        val frameBuffer = writer.writeBinaryFrame(buffer)
        writeToSocket(frameBuffer)
    }

    override suspend fun ping(payloadData: ReadBuffer) {
        val writer = frameWriter ?: return
        val frameBuffer = writer.writePingFrame(payloadData)
        writeToSocket(frameBuffer)
    }

    private suspend fun writePongFrame(payloadData: ReadBuffer) {
        val writer = frameWriter ?: return
        val frameBuffer = writer.writePongFrame(payloadData)
        writeToSocket(frameBuffer)
    }

    private suspend fun sendCloseFrame(
        code: UShort,
        reason: String = "",
    ) {
        val writer = frameWriter ?: return
        try {
            val frameBuffer = writer.writeCloseFrame(code, reason)
            writeToSocket(frameBuffer)
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
        if (connectionState.value is ConnectionState.Connected && !hasServerInitiatedClose) {
            sendCloseFrame(CloseCode.NORMAL.code, "Client closing")
        }
        // Clean up compression resources
        outgoingCompressor?.close()
        outgoingCompressor = null
        incomingDecompressor?.close()
        incomingDecompressor = null
        // Close socket - this will cause the read loop to exit with SocketClosedException
        try {
            socket.close()
        } catch (e: Exception) {
            // Ignore
        }
        if (connectionState.value !is ConnectionState.Disconnected) {
            connectionStateFlow.value = ConnectionState.Disconnected()
        }
    }
}
