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
import com.ditchoom.buffer.stream.SuspendingStreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketException
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.allocate
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * Original WebSocket client implementation.
 *
 * @deprecated Use [ModularWebSocketClient] instead for better performance and RFC compliance.
 * This class will be removed in a future release.
 */
@Deprecated(
    "Use ModularWebSocketClient for better performance",
    ReplaceWith("ModularWebSocketClient(connectionOptions, pool, parentScope, allocationZone)"),
)
class DefaultWebSocketClient(
    private val connectionOptions: WebSocketConnectionOptions,
    private val pool: BufferPool,
    parentScope: CoroutineScope?,
    allocationZone: AllocationZone = AllocationZone.Direct,
) : WebSocketClient {
    override val scope =
        if (parentScope == null) {
            CoroutineScope(
                Dispatchers.Default +
                    CoroutineName(
                        "Websocket Connection #${getCountForConnection(connectionOptions)}" +
                            ": ${connectionOptions.name}:${connectionOptions.port}",
                    ),
            )
        } else {
            parentScope + Dispatchers.Default +
                CoroutineName(
                    "Websocket Connection #${getCountForConnection(connectionOptions)}" +
                        ": ${connectionOptions.name}:${connectionOptions.port}",
                )
        }
    private val socket = ClientSocket.allocate(allocationZone)
    private var hasServerInitiatedClose = false
    internal val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    override val connectionState = connectionStateFlow.asStateFlow()
    private val incomingMessageChannel = Channel<WebSocketMessage>(Channel.UNLIMITED)
    override val incomingMessages = incomingMessageChannel.receiveAsFlow()
    val outgoingMessages = Channel<WebSocketMessage>()
    internal var enableCompression = false
    private var outgoingCompressor: SuspendingStreamingCompressor? = null
    private var incomingDecompressor: SuspendingStreamingDecompressor? = null

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
            val protocolString =
                if (connectionOptions.protocols.isNotEmpty()) {
                    val sb = StringBuilder()
                    connectionOptions.protocols.forEach {
                        sb.append("Sec-WebSocket-Protocol: $it\r\n")
                    }
                    sb
                } else {
                    ""
                }
            val hostline =
                if ((connectionOptions.tls && connectionOptions.port == 443) ||
                    (!connectionOptions.tls && connectionOptions.port == 80)
                ) {
                    connectionOptions.name
                } else {
                    "${connectionOptions.name}:${connectionOptions.port}"
                }
            val deflateHeader =
                if (connectionOptions.requestCompression) {
                    HTTP_COMPRESSION_HEADER
                } else {
                    ""
                }
            val request =
                "GET ${connectionOptions.websocketEndpoint} HTTP/1.1\r\n" +
                    "Host: $hostline\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "User-Agent: Ditchoom/websockets\r\n" +
                    "Upgrade: websocket\r\n" +
                    protocolString +
                    deflateHeader +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Key: ${generateWebSocketKey()}\r\n" +
                    "\r\n"

            val socketOptions =
                if (connectionOptions.tls) SocketOptions.tlsDefault() else SocketOptions.LOW_LATENCY
            withTimeout(connectionOptions.connectionTimeout) {
                socket.open(connectionOptions.port, connectionOptions.connectionTimeout, connectionOptions.name, socketOptions)
            }
            socket.writeString(request, Charset.UTF8, connectionOptions.writeTimeout)

            // Read HTTP upgrade response using stream processor
            val streamProcessor = StreamProcessor.builder(pool).buildSuspending()
            val endOfHeaders = "\r\n\r\n".toReadBuffer(Charset.UTF8)

            // Read until we find the end of HTTP headers
            var foundHeaders = false
            while (!foundHeaders) {
                readIntoProcessor(streamProcessor)
                if (streamProcessor.available() >= endOfHeaders.remaining()) {
                    foundHeaders = streamProcessor.peekMatches(endOfHeaders) ||
                        findPattern(streamProcessor, endOfHeaders)
                }
            }

            // Read all available data as the HTTP response
            val responseSize = findPatternIndex(streamProcessor, endOfHeaders) + endOfHeaders.remaining()
            val responseBuffer = streamProcessor.readBuffer(responseSize)
            val response = responseBuffer.readString(responseBuffer.remaining())

            if (!(
                    response.contains("101 Switching Protocols", ignoreCase = true) &&
                        response.contains("Upgrade: websocket", ignoreCase = true) &&
                        response.contains("Connection: Upgrade", ignoreCase = true) &&
                        response.contains("Sec-WebSocket-Accept", ignoreCase = true)
                )
            ) {
                streamProcessor.release()
                throw IllegalStateException(
                    "Invalid response from server when reading the result from " +
                        "websockets. Response:\r\n$response",
                )
            }
            if (connectionOptions.requestCompression &&
                response.contains("Sec-WebSocket-Extensions:", ignoreCase = true) &&
                response.contains("permessage-deflate", ignoreCase = true)
            ) {
                enableCompression = true
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
            connectionStateFlow.value = ConnectionState.Connected
            processIncomingMessages(streamProcessor)
            scope.launch {
                outgoingMessages.consumeAsFlow().collect {
                    when (it) {
                        is WebSocketMessage.Binary -> writeWebsocketFrame(Opcode.Binary, it.value)
                        is WebSocketMessage.Close -> sendCloseFrame(it.code, it.reason)
                        is WebSocketMessage.Ping -> writeWebsocketFrame(Opcode.Ping, it.value)
                        is WebSocketMessage.Pong -> writeWebsocketFrame(Opcode.Pong, it.value)
                        is WebSocketMessage.Text ->
                            writeWebsocketFrame(
                                Opcode.Text,
                                it.value.toReadBuffer(Charset.UTF8),
                            )
                    }
                }
            }
        } catch (e: Exception) {
            connectionStateFlow.value = ConnectionState.Disconnected(e)
        }
        return this
    }

    private suspend fun findPattern(
        processor: SuspendingStreamProcessor,
        pattern: ReadBuffer,
    ): Boolean {
        val available = processor.available()
        val patternSize = pattern.remaining()
        if (available < patternSize) return false

        for (i in 0..(available - patternSize)) {
            var matches = true
            for (j in 0 until patternSize) {
                if (processor.peekByte(i + j) != pattern[pattern.position() + j]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private suspend fun findPatternIndex(
        processor: SuspendingStreamProcessor,
        pattern: ReadBuffer,
    ): Int {
        val available = processor.available()
        val patternSize = pattern.remaining()
        if (available < patternSize) return -1

        for (i in 0..(available - patternSize)) {
            var matches = true
            for (j in 0 until patternSize) {
                if (processor.peekByte(i + j) != pattern[pattern.position() + j]) {
                    matches = false
                    break
                }
            }
            if (matches) return i
        }
        return -1
    }

    override suspend fun write(string: String) {
        writeWebsocketFrame(Opcode.Text, string.toReadBuffer(Charset.UTF8), connectionOptions.writeTimeout)
    }

    override suspend fun write(buffer: ReadBuffer) {
        writeWebsocketFrame(Opcode.Binary, buffer, connectionOptions.writeTimeout)
    }

    suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int = writeWebsocketFrame(Opcode.Binary, buffer, timeout)

    override suspend fun ping(payloadData: ReadBuffer) {
        writeWebsocketFrame(Opcode.Ping, payloadData, connectionOptions.writeTimeout)
    }

    private suspend fun writeWebsocketFrame(
        opcode: Opcode,
        payloadData: ReadBuffer = EMPTY_BUFFER,
        timeout: Duration = connectionOptions.writeTimeout,
    ): Int =
        try {
            val frame = Frame(true, opcode, MaskingKey.FourByteMaskingKey(), payloadData)
            val frameBuffer =
                frame.toBuffer(
                    attemptDeflate = enableCompression,
                    compressor = outgoingCompressor,
                )
            frameBuffer.resetForRead()
            val remainingBytes = frameBuffer.remaining()
            while (frameBuffer.hasRemaining()) {
                socket.write(frameBuffer, timeout)
            }
            remainingBytes
        } catch (e: Exception) {
            connectionStateFlow.value = ConnectionState.Disconnected(e)
            -1
        }

    private suspend fun readIntoProcessor(processor: SuspendingStreamProcessor) {
        val readBuffer = socket.read(connectionOptions.readTimeout)
        readBuffer.resetForRead()
        if (readBuffer.hasRemaining()) {
            processor.append(readBuffer)
        }
    }

    internal fun processIncomingMessages(streamProcessor: SuspendingStreamProcessor) =
        scope.launch {
            try {
                readLoop@ while (isOpen()) {
                    // Ensure we have data available, reading from socket if needed
                    while (streamProcessor.available() < 2) {
                        readIntoProcessor(streamProcessor)
                    }

                    lateinit var firstFrame: Frame
                    val payloadBuffers = mutableListOf<ReadBuffer>()
                    var totalPayloadSize = 0

                    process@ do {
                        val frame = readAndProcessWebSocketFrame(streamProcessor) ?: return@launch
                        if (frame.opcode.isControlFrame()) {
                            if (!handleControlPacketFrameShouldContinue(frame)) {
                                return@launch
                            }
                        } else {
                            if (payloadBuffers.isEmpty()) {
                                firstFrame = frame
                            }
                            // Buffer is already in read mode from processor.readBuffer()
                            payloadBuffers += frame.payloadData
                            totalPayloadSize += frame.payloadData.remaining()
                        }
                    } while (!frame.fin || (frame.opcode.isControlFrame() && frame.opcode != Opcode.Close))

                    // Combine payloads into a single buffer
                    val payload =
                        if (payloadBuffers.size == 1) {
                            payloadBuffers[0]
                        } else if (totalPayloadSize == 0) {
                            EMPTY_BUFFER
                        } else {
                            val combined = PlatformBuffer.allocate(totalPayloadSize)
                            for (buf in payloadBuffers) {
                                combined.write(buf)
                            }
                            // Clear references to allow GC to reclaim fragment buffers
                            payloadBuffers.clear()
                            combined.resetForRead()
                            combined
                        }
                    // Buffer is already in read mode, no resetForRead() needed
                    when (firstFrame.opcode) {
                        Opcode.Text -> {
                            try {
                                val utf8StringRead =
                                    if (firstFrame.rsv1 && enableCompression) {
                                        if (incomingDecompressor != null) {
                                            // Stream decompress directly to string - reduces peak memory
                                            val result = decompressToString(payload, incomingDecompressor!!)
                                            incomingDecompressor!!.reset()
                                            result
                                        } else {
                                            val decompressed = payload.decompressWebsocketBuffer()
                                            decompressed.readString(decompressed.remaining())
                                        }
                                    } else {
                                        payload.readString(payload.remaining())
                                    }
                                incomingMessageChannel.trySend(WebSocketMessage.Text(utf8StringRead))
                            } catch (e: Throwable) {
                                sendCloseFrame(1007u, "Invalid UTF-8 Message. ${e.message}")
                                return@launch
                            }
                        }
                        Opcode.Binary -> {
                            val binaryPayload =
                                if (firstFrame.rsv1 && enableCompression) {
                                    if (incomingDecompressor != null) {
                                        // Stream decompress to single buffer - reduces peak memory
                                        val result = decompressToBuffer(payload, incomingDecompressor!!)
                                        incomingDecompressor!!.reset()
                                        result
                                    } else {
                                        payload.decompressWebsocketBuffer()
                                    }
                                } else {
                                    payload
                                }
                            incomingMessageChannel.trySend(WebSocketMessage.Binary(binaryPayload))
                        }
                        else -> {
                            sendCloseFrame(1002u, "Invalid opcode for frame")
                            return@launch
                        }
                    }
                }
            } catch (e: SocketClosedException) {
                // SocketClosedException is expected when the connection is closed
                // Don't treat as error if we're already closing or server initiated close
                if (!hasServerInitiatedClose && connectionState.value is ConnectionState.Connected) {
                    connectionStateFlow.value = ConnectionState.Disconnected(e)
                } else {
                    connectionStateFlow.value = ConnectionState.Disconnected(null)
                }
            } catch (e: SocketException) {
                // SocketException (connection reset, etc.) is expected when the connection is closed
                if (!hasServerInitiatedClose && connectionState.value is ConnectionState.Connected) {
                    connectionStateFlow.value = ConnectionState.Disconnected(e)
                } else {
                    connectionStateFlow.value = ConnectionState.Disconnected(null)
                }
            } finally {
                streamProcessor.release()
            }
        }

    // Legacy method for tests - creates a stream processor internally
    internal fun processIncomingMessages(
        isOpen: (() -> Boolean),
        readByte: (suspend () -> Byte),
        readBuffer: (suspend (Int?) -> ReadBuffer),
    ) = scope.launch {
        val testProcessor = StreamProcessor.builder(pool).buildSuspending()

        try {
            readLoop@ while (isOpen()) {
                lateinit var firstFrame: Frame
                val payloadBuffers = mutableListOf<ReadBuffer>()
                var totalPayloadSize = 0

                process@ do {
                    // Ensure we have enough data for frame header (2 bytes minimum)
                    while (testProcessor.available() < 2) {
                        val byte = readByte()
                        val singleByte = pool.acquire(1)
                        singleByte.writeByte(byte)
                        singleByte.resetForRead()
                        testProcessor.append(singleByte)
                    }

                    val frame = readAndProcessWebSocketFrameLegacy(testProcessor, readByte, readBuffer) ?: return@launch
                    if (frame.opcode.isControlFrame()) {
                        if (!handleControlPacketFrameShouldContinue(frame)) {
                            return@launch
                        }
                    } else {
                        if (payloadBuffers.isEmpty()) {
                            firstFrame = frame
                        }
                        // Buffer is already in read mode from legacy processor
                        payloadBuffers += frame.payloadData
                        totalPayloadSize += frame.payloadData.remaining()
                    }
                } while (!frame.fin || (frame.opcode.isControlFrame() && frame.opcode != Opcode.Close))

                // Combine payloads into a single buffer
                val payload =
                    if (payloadBuffers.size == 1) {
                        payloadBuffers[0]
                    } else if (totalPayloadSize == 0) {
                        EMPTY_BUFFER
                    } else {
                        val combined = PlatformBuffer.allocate(totalPayloadSize)
                        for (buf in payloadBuffers) {
                            combined.write(buf)
                        }
                        // Clear references to allow GC to reclaim fragment buffers
                        payloadBuffers.clear()
                        combined.resetForRead()
                        combined
                    }
                // Buffer is already in read mode, no resetForRead() needed
                when (firstFrame.opcode) {
                    Opcode.Text -> {
                        try {
                            val utf8StringRead =
                                if (firstFrame.rsv1 && enableCompression) {
                                    if (incomingDecompressor != null) {
                                        // Stream decompress directly to string - reduces peak memory
                                        val result = decompressToString(payload, incomingDecompressor!!)
                                        incomingDecompressor!!.reset()
                                        result
                                    } else {
                                        val decompressed = payload.decompressWebsocketBuffer()
                                        decompressed.readString(decompressed.remaining())
                                    }
                                } else {
                                    payload.readString(payload.remaining())
                                }
                            incomingMessageChannel.trySend(WebSocketMessage.Text(utf8StringRead))
                        } catch (e: Throwable) {
                            sendCloseFrame(1007u, "Invalid UTF-8 Message. ${e.message}")
                            return@launch
                        }
                    }
                    Opcode.Binary -> {
                        val binaryPayload =
                            if (firstFrame.rsv1 && enableCompression) {
                                if (incomingDecompressor != null) {
                                    // Stream decompress to single buffer - reduces peak memory
                                    val result = decompressToBuffer(payload, incomingDecompressor!!)
                                    incomingDecompressor!!.reset()
                                    result
                                } else {
                                    payload.decompressWebsocketBuffer()
                                }
                            } else {
                                payload
                            }
                        incomingMessageChannel.trySend(WebSocketMessage.Binary(binaryPayload))
                    }
                    else -> {
                        sendCloseFrame(1002u, "Invalid opcode for frame")
                        return@launch
                    }
                }
            }
        } catch (e: SocketClosedException) {
            // SocketClosedException is expected when the connection is closed
            if (!hasServerInitiatedClose && connectionState.value is ConnectionState.Connected) {
                connectionStateFlow.value = ConnectionState.Disconnected(e)
            } else {
                connectionStateFlow.value = ConnectionState.Disconnected(null)
            }
        } catch (e: SocketException) {
            // SocketException (connection reset, etc.) is expected when the connection is closed
            if (!hasServerInitiatedClose && connectionState.value is ConnectionState.Connected) {
                connectionStateFlow.value = ConnectionState.Disconnected(e)
            } else {
                connectionStateFlow.value = ConnectionState.Disconnected(null)
            }
        } finally {
            testProcessor.release()
        }
    }

    private suspend fun handleControlPacketFrameShouldContinue(frame: Frame): Boolean {
        when (frame.opcode) {
            Opcode.Ping -> {
                // Buffer is already in read mode from processor.readBuffer()
                incomingMessageChannel.trySend(WebSocketMessage.Ping(frame.payloadData))
            }
            Opcode.Pong -> {
                // Buffer is already in read mode from processor.readBuffer()
                incomingMessageChannel.trySend(WebSocketMessage.Pong(frame.payloadData))
            }
            Opcode.Close -> {
                if (frame.payloadData.hasRemaining()) {
                    // Buffer is already in read mode from processor.readBuffer()
                    val code =
                        if (frame.payloadLength == 1) {
                            1002u
                        } else {
                            frame.payloadData.readUnsignedShort()
                        }
                    val reason =
                        if (frame.payloadData.hasRemaining()) {
                            try {
                                frame.payloadData.readString(frame.payloadData.remaining())
                            } catch (e: Throwable) {
                                // Invalid UTF-8 in close reason - use empty string
                                ""
                            }
                        } else {
                            ""
                        }
                    incomingMessageChannel.trySend(WebSocketMessage.Close(code, reason))
                } else {
                    incomingMessageChannel.trySend(WebSocketMessage.Close(0u, "No Close Message"))
                }
                return false
            }
            else -> {} // Do nothing
        }
        return true
    }

    private suspend fun readAndProcessWebSocketFrame(processor: SuspendingStreamProcessor): Frame? {
        try {
            // Ensure we have at least 2 bytes for the header
            while (processor.available() < 2) {
                readIntoProcessor(processor)
            }

            // Read first two bytes as a short for efficiency
            val headerShort = processor.readShort()
            val byte1Int = (headerShort.toInt() ushr 8) and 0xFF
            val byte2Int = headerShort.toInt() and 0xFF

            val fin = byte1Int and 0x80 != 0
            val rsv1 = byte1Int and 0x40 != 0
            val isRsv1Valid = !rsv1 || (rsv1 && enableCompression)
            val rsv2 = byte1Int and 0x20 != 0
            val rsv3 = byte1Int and 0x10 != 0
            check(isRsv1Valid && !rsv2 && !rsv3) {
                sendCloseFrame(1002u, "Invalid RSV")
                "Invalid incoming RSV bits 0x${byte1Int.toString(16)}"
            }
            val opcode = Opcode.fromInt(byte1Int)
            if (!opcode.isValid()) {
                sendCloseFrame(1002u, "Invalid OpCode $opcode")
                return null
            } else if (!fin && (opcode == Opcode.Ping || opcode == Opcode.Pong)) {
                sendCloseFrame(1002u, "$opcode does not support fragmentation")
                return null
            }
            val mask = byte2Int and 0x80 != 0
            check(!mask) // websocket spec requires this to be a 0 or false
            val payloadLength = byte2Int and 0x7F

            val actualPayloadLength: ULong
            if (payloadLength <= 125) {
                actualPayloadLength = payloadLength.toULong()
            } else if (payloadLength == 126) {
                if (opcode == Opcode.Ping) {
                    sendCloseFrame(
                        1002u,
                        "Control frames are only allowed to have payload up to and including 125 octets",
                    )
                    return null
                }
                // Ensure we have 2 more bytes for extended length
                while (processor.available() < 2) {
                    readIntoProcessor(processor)
                }
                actualPayloadLength = processor.readShort().toUShort().toULong()
            } else if (payloadLength == 127) {
                if (opcode == Opcode.Ping) {
                    sendCloseFrame(
                        1002u,
                        "Control frames are only allowed to have payload up to and including 125 octets",
                    )
                    return null
                }
                // Ensure we have 8 more bytes for extended length
                while (processor.available() < 8) {
                    readIntoProcessor(processor)
                }
                actualPayloadLength = processor.readLong().toULong()
            } else {
                throw IllegalStateException("Invalid payload length $payloadLength")
            }

            val payload: ReadBuffer
            if (actualPayloadLength == 0uL) {
                payload = EMPTY_BUFFER
            } else {
                check(actualPayloadLength < Int.MAX_VALUE.toULong()) {
                    "Payloads larger than ${Int.MAX_VALUE} bytes is currently unsupported"
                }
                val payloadSize = actualPayloadLength.toInt()
                // Ensure we have enough data for the payload
                while (processor.available() < payloadSize) {
                    readIntoProcessor(processor)
                }
                payload = processor.readBuffer(payloadSize)
            }

            val frame = Frame(fin, rsv1, rsv2, rsv3, opcode, MaskingKey.NoMaskingKey, payload)
            if (frame.opcode == Opcode.Ping) {
                writeWebsocketFrame(Opcode.Pong, frame.payloadData, connectionOptions.writeTimeout)
            } else if (frame.opcode == Opcode.Close) {
                hasServerInitiatedClose = true
                // Buffer is already in read mode
                if (payloadLength == 1) {
                    sendCloseFrame(1002u, "Invalid close payload length (1)")
                } else if (payload.hasRemaining()) {
                    var code = payload.readUnsignedShort()
                    if (payloadLength >= 126) {
                        sendCloseFrame(1002u, "Invalid close payload length")
                    } else if (!isValidServerCloseCode(code)) {
                        sendCloseFrame(1002u, "Invalid Server close code $code")
                    } else if (payload.hasRemaining()) {
                        val decoded =
                            try {
                                payload.readString(payload.remaining())
                            } catch (t: Throwable) {
                                code = 1002u
                                "Invalid UTF-8 Message in payload"
                            }
                        sendCloseFrame(code, decoded)
                    } else {
                        sendCloseFrame(code)
                    }
                } else {
                    sendCloseFrame()
                }
                close()
            }
            return frame
        } catch (e: SocketClosedException) {
            // SocketClosedException is expected when the server closes the connection
            // after the close handshake or when we're already closing
            if (!hasServerInitiatedClose && connectionState.value is ConnectionState.Connected) {
                connectionStateFlow.value = ConnectionState.Disconnected(e)
            } else {
                // Clean close - don't treat as error
                connectionStateFlow.value = ConnectionState.Disconnected(null)
            }
            return null
        } catch (e: SocketException) {
            // SocketException (connection reset, etc.) is expected when the server closes
            if (!hasServerInitiatedClose && connectionState.value is ConnectionState.Connected) {
                connectionStateFlow.value = ConnectionState.Disconnected(e)
            } else {
                connectionStateFlow.value = ConnectionState.Disconnected(null)
            }
            return null
        } catch (e: Exception) {
            connectionStateFlow.value = ConnectionState.Disconnected(e)
            return null
        }
    }

    // Legacy frame reading for tests that provide byte-by-byte callbacks
    private suspend fun readAndProcessWebSocketFrameLegacy(
        processor: SuspendingStreamProcessor,
        readByte: suspend () -> Byte,
        readBuffer: suspend (Int?) -> ReadBuffer,
    ): Frame? {
        try {
            // Read header bytes via callback and feed to processor
            while (processor.available() < 2) {
                val byte = readByte()
                val singleByte = pool.acquire(1)
                singleByte.writeByte(byte)
                singleByte.resetForRead()
                processor.append(singleByte)
            }

            val headerShort = processor.readShort()
            val byte1Int = (headerShort.toInt() ushr 8) and 0xFF
            val byte2Int = headerShort.toInt() and 0xFF

            val fin = byte1Int and 0x80 != 0
            val rsv1 = byte1Int and 0x40 != 0
            val isRsv1Valid = !rsv1 || (rsv1 && enableCompression)
            val rsv2 = byte1Int and 0x20 != 0
            val rsv3 = byte1Int and 0x10 != 0
            check(isRsv1Valid && !rsv2 && !rsv3) {
                sendCloseFrame(1002u, "Invalid RSV")
                "Invalid incoming RSV bits 0x${byte1Int.toString(16)}"
            }
            val opcode = Opcode.fromInt(byte1Int)
            if (!opcode.isValid()) {
                sendCloseFrame(1002u, "Invalid OpCode $opcode")
                return null
            } else if (!fin && (opcode == Opcode.Ping || opcode == Opcode.Pong)) {
                sendCloseFrame(1002u, "$opcode does not support fragmentation")
                return null
            }
            val mask = byte2Int and 0x80 != 0
            check(!mask) // websocket spec requires this to be 0 for server-to-client
            val payloadLength = byte2Int and 0x7F

            val actualPayloadLength: ULong
            if (payloadLength <= 125) {
                actualPayloadLength = payloadLength.toULong()
            } else if (payloadLength == 126) {
                if (opcode == Opcode.Ping) {
                    sendCloseFrame(1002u, "Control frames are only allowed to have payload up to and including 125 octets")
                    return null
                }
                val extLenBuffer = readBuffer(UShort.SIZE_BYTES)
                actualPayloadLength = extLenBuffer.readUnsignedShort().toULong()
            } else if (payloadLength == 127) {
                if (opcode == Opcode.Ping) {
                    sendCloseFrame(1002u, "Control frames are only allowed to have payload up to and including 125 octets")
                    return null
                }
                val extLenBuffer = readBuffer(ULong.SIZE_BYTES)
                actualPayloadLength = extLenBuffer.readUnsignedLong()
            } else {
                throw IllegalStateException("Invalid payload length $payloadLength")
            }

            val payload: ReadBuffer
            if (actualPayloadLength == 0uL) {
                payload = EMPTY_BUFFER
            } else {
                check(actualPayloadLength < Int.MAX_VALUE.toULong()) {
                    "Payloads larger than ${Int.MAX_VALUE} bytes is currently unsupported"
                }
                // readBuffer returns a buffer ready to read (pos=0, lim=size)
                payload = readBuffer(actualPayloadLength.toInt())
            }

            val frame = Frame(fin, rsv1, rsv2, rsv3, opcode, MaskingKey.NoMaskingKey, payload)
            if (frame.opcode == Opcode.Ping) {
                writeWebsocketFrame(Opcode.Pong, frame.payloadData, connectionOptions.writeTimeout)
            } else if (frame.opcode == Opcode.Close) {
                hasServerInitiatedClose = true
                // Buffer is already in read mode
                if (payloadLength == 1) {
                    sendCloseFrame(1002u, "Invalid close payload length (1)")
                } else if (payload.hasRemaining()) {
                    var code = payload.readUnsignedShort()
                    if (payloadLength >= 126) {
                        sendCloseFrame(1002u, "Invalid close payload length")
                    } else if (!isValidServerCloseCode(code)) {
                        sendCloseFrame(1002u, "Invalid Server close code $code")
                    } else if (payload.hasRemaining()) {
                        val decoded =
                            try {
                                payload.readString(payload.remaining())
                            } catch (t: Throwable) {
                                code = 1002u
                                "Invalid UTF-8 Message in payload"
                            }
                        sendCloseFrame(code, decoded)
                    } else {
                        sendCloseFrame(code)
                    }
                } else {
                    sendCloseFrame()
                }
                close()
            }
            return frame
        } catch (e: SocketClosedException) {
            // SocketClosedException is expected when the server closes the connection
            // after the close handshake or when we're already closing
            if (!hasServerInitiatedClose && connectionState.value is ConnectionState.Connected) {
                connectionStateFlow.value = ConnectionState.Disconnected(e)
            } else {
                // Clean close - don't treat as error
                connectionStateFlow.value = ConnectionState.Disconnected(null)
            }
            return null
        } catch (e: SocketException) {
            // SocketException (connection reset, etc.) is expected when the server closes
            if (!hasServerInitiatedClose && connectionState.value is ConnectionState.Connected) {
                connectionStateFlow.value = ConnectionState.Disconnected(e)
            } else {
                connectionStateFlow.value = ConnectionState.Disconnected(null)
            }
            return null
        } catch (e: Exception) {
            connectionStateFlow.value = ConnectionState.Disconnected(e)
            return null
        }
    }

    private suspend fun sendCloseFrame(
        statusCode: UShort = 1000u,
        message: String? = null,
    ) {
        try {
            val utf8MessageBuffer = message?.toReadBuffer(Charset.UTF8) ?: EMPTY_BUFFER
            val closeBuffer = pool.acquire(UShort.SIZE_BYTES + utf8MessageBuffer.limit())
            try {
                closeBuffer.writeUShort(statusCode)
                closeBuffer.write(utf8MessageBuffer)
                closeBuffer.resetForRead()
                writeWebsocketFrame(Opcode.Close, closeBuffer, connectionOptions.writeTimeout)
            } finally {
                pool.release(closeBuffer)
            }
        } finally {
            cleanupResources()
        }
    }

    override suspend fun close() {
        if (!hasServerInitiatedClose) {
            sendCloseFrame()
        }
    }

    suspend fun cleanupResources() {
        outgoingMessages.close()
        socket.close()
        outgoingCompressor?.close()
        outgoingCompressor = null
        incomingDecompressor?.close()
        incomingDecompressor = null
    }

    private fun isValidServerCloseCode(code: UShort): Boolean =
        code in 1000u..1003u ||
            code in 1007u..1015u ||
            code in 3000u..3999u ||
            code in 4000u..4999u

    companion object {
        private const val HTTP_COMPRESSION_HEADER =
            "Sec-WebSocket-Extensions: permessage-deflate" +
                "; client_no_context_takeover; server_no_context_takeover\r\n"
        private val countMap = mutableMapOf<String, Int>()

        private fun getCountForConnection(connectionOptions: WebSocketConnectionOptions): Int {
            val key = "${connectionOptions.name}:${connectionOptions.port}"
            val value = countMap[key]
            return if (value == null) {
                countMap[key] = 1
                1
            } else {
                val newCount = value + 1
                countMap[key] = newCount
                newCount
            }
        }
    }
}
