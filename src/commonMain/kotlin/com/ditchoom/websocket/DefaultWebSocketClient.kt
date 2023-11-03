package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.FragmentedReadBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.toComposableBuffer
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.data.get
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.EMPTY_BUFFER
import com.ditchoom.socket.allocate
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration

class DefaultWebSocketClient(
    private val connectionOptions: WebSocketConnectionOptions,
    allocationZone: AllocationZone
) : WebSocketClient {
    private var runningJob: Job? = null
    private val socket = ClientSocket.allocate(connectionOptions.tls, allocationZone)
    private var hasServerInitiatedClose = false
    private val inputStream = SuspendingSocketInputStreamWithPreBuffer(connectionOptions.readTimeout, socket)
    private val _connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    override val connectionState = _connectionStateFlow.asStateFlow()
    private val _incomingMessageSharedFlow = MutableSharedFlow<WebSocketMessage>()
    override val incomingMessages = _incomingMessageSharedFlow.asSharedFlow()
    val outgoingMessages = Channel<WebSocketMessage>()

    fun isOpen() = socket.isOpen() && connectionState.value is ConnectionState.Connected

    override suspend fun localPort(): Int = socket.localPort()
    override suspend fun remotePort(): Int = socket.remotePort()

    override fun connect(scope: CoroutineScope, tag: String) {
        scope.launch(Dispatchers.Default + CoroutineName("Websocket Connection: $tag")) {
            if (connectionState.value == ConnectionState.Connecting
                || connectionState.value is ConnectionState.Connected
            ) {
                return@launch
            }
            _connectionStateFlow.value = ConnectionState.Connecting
            try {
                socket.open(connectionOptions.port, connectionOptions.connectionTimeout, connectionOptions.name)
                val protocolString = if (connectionOptions.protocols.isNotEmpty()) {
                    val sb = StringBuilder()
                    connectionOptions.protocols.forEach {
                        sb.append("Sec-WebSocket-Protocol: $it\r\n")
                    }
                    sb
                } else {
                    ""
                }
                val hostline =
                    if ((connectionOptions.tls && connectionOptions.port == 443) || (!connectionOptions.tls && connectionOptions.port == 80)) {
                        connectionOptions.name
                    } else {
                        "${connectionOptions.name}:${connectionOptions.port}"
                    }
                val request =
                    "GET ${connectionOptions.websocketEndpoint} HTTP/1.1\r\n" +
                            "Host: $hostline\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Key: ${generateWebSocketKey()}\r\n" +
                            protocolString +
                            "Sec-WebSocket-Version: 13\r\n" +
                            "\r\n"
                socket.writeString(request, Charset.UTF8, connectionOptions.writeTimeout)
                val readBuffer = socket.read(connectionOptions.readTimeout)
                readBuffer.resetForRead()
                val endOfStartBuffer = "\r\n\r\n".toReadBuffer(Charset.UTF8)
                val websocketFrameIndex = indexOfBuffer(readBuffer, endOfStartBuffer)
                val response = readBuffer.readString(websocketFrameIndex)
                val frameData = readBuffer.readBytes(readBuffer.remaining())
                if (frameData.hasRemaining()) {
                    frameData.position(endOfStartBuffer.limit())
                }
                val preBuffer = if (frameData.hasRemaining()) {
                    frameData
                } else {
                    null
                }
                inputStream.preBuffer = preBuffer?.slice()
                if (!(
                            response.contains("101 Switching Protocols", ignoreCase = true) &&
                                    response.contains("Upgrade: websocket", ignoreCase = true) &&
                                    response.contains("Connection: Upgrade", ignoreCase = true) &&
                                    response.contains("Sec-WebSocket-Accept", ignoreCase = true)
                            )
                ) {
                    throw IllegalStateException("Invalid response from server when reading the result from websockets. Response:\r\n$response")
                }
                _connectionStateFlow.value = ConnectionState.Connected
                processIncomingMessages()
                launch {
                    outgoingMessages.consumeAsFlow().collect {
                        when (it) {
                            is WebSocketMessage.Binary -> writeWebsocketFrame(Opcode.Binary, it.value)
                            is WebSocketMessage.Close -> sendCloseFrame(it.code, it.reason)
                            is WebSocketMessage.Ping -> writeWebsocketFrame(Opcode.Ping, it.value)
                            is WebSocketMessage.Pong -> writeWebsocketFrame(Opcode.Pong, it.value)
                            is WebSocketMessage.Text -> writeWebsocketFrame(
                                Opcode.Text,
                                it.value.toReadBuffer(Charset.UTF8)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _connectionStateFlow.value = ConnectionState.Disconnected(e)
            }
        }
    }

    override suspend fun write(string: String) {
        writeWebsocketFrame(Opcode.Text, string.toReadBuffer(Charset.UTF8), connectionOptions.writeTimeout)
    }

    override suspend fun write(buffer: ReadBuffer) {
        writeWebsocketFrame(Opcode.Binary, buffer, connectionOptions.writeTimeout)
    }

    suspend fun write(buffer: ReadBuffer, timeout: Duration): Int =
        writeWebsocketFrame(Opcode.Binary, buffer, timeout)

    override suspend fun ping(payloadData: ReadBuffer) {
        writeWebsocketFrame(Opcode.Ping, payloadData, connectionOptions.writeTimeout)
    }

    private suspend fun writeWebsocketFrame(
        opcode: Opcode,
        payloadData: ReadBuffer = EMPTY_BUFFER,
        timeout: Duration = connectionOptions.writeTimeout
    ): Int {
        return try {
            val frame = Frame(true, opcode, MaskingKey.FourByteMaskingKey(), payloadData)
            val frameBuffer = frame.toBuffer()
            frameBuffer.resetForRead()
            val remainingBytes = frameBuffer.remaining()
            var bytesWritten = 0
            // TODO: Figure out why the framebuffer doesn't have an up to date "hasRemaining"
            while (frameBuffer.hasRemaining() && bytesWritten < remainingBytes) {
                val b = socket.write(frameBuffer, timeout)
                bytesWritten += b
            }
            remainingBytes
        } catch (e: Exception) {
            // probably disconnected
            _connectionStateFlow.value = ConnectionState.Disconnected(e)
            -1
        }
    }

    private fun CoroutineScope.processIncomingMessages() = launch {
        readLoop@while (isOpen()) {
            val frames = mutableListOf<Frame>()
            var readPayload: ReadBuffer? = null
            process@do {
                val frame = readAndProcessWebSocketFrame() ?: return@launch
                if (frame.opcode.isControlFrame()) {
                    if (!handleControlPacketFrameShouldContinue(frame)) {
                        // should throw and disconnect before we get to this
                        return@launch
                    }
                } else {
                    if (frames.isEmpty()) {
                        frames += frame
                    }
                    frame.payloadData.resetForRead()
                    readPayload = if (readPayload == null) {
                        frame.payloadData
                    } else {
                        FragmentedReadBuffer(readPayload, frame.payloadData).slice()
                    }
                }
            } while(!frame!!.fin || (frame.opcode.isControlFrame() && frame.opcode != Opcode.Close))

            val firstFrame = frames.first()
            val payload = checkNotNull(readPayload)
            when (firstFrame.opcode) {
                Opcode.Text -> {
                    try {
                        val utf8StringRead = payload.readString(payload.remaining())
                        _incomingMessageSharedFlow.emit(WebSocketMessage.Text(utf8StringRead))
                    } catch (e: Throwable) { // Failed to decode
                        sendCloseFrame(1007u, "Invalid UTF-8 Message")
                        return@launch
                    }
                }
                Opcode.Binary -> {
                    _incomingMessageSharedFlow.emit(WebSocketMessage.Binary(payload))
                }
                else -> {
                    sendCloseFrame(1002u, "Invalid opcode for frame")
                    return@launch
                }
            }
        }
    }

    private suspend fun handleControlPacketFrameShouldContinue(
        frame: Frame
    ): Boolean {
        when (frame.opcode) {
            Opcode.Ping -> {
                _incomingMessageSharedFlow.emit(WebSocketMessage.Ping(frame.payloadData))
            }
            Opcode.Pong -> {
                _incomingMessageSharedFlow.emit(WebSocketMessage.Pong(frame.payloadData))
            }
            Opcode.Close -> {
                if (frame.payloadData.hasRemaining()) {
                    frame.payloadData.resetForRead()
                    val code = if (frame.payloadLength == 1) {
                        1002u
                    } else {
                        frame.payloadData.readUnsignedShort()
                    }
                    val reason = if (frame.payloadData.hasRemaining()) {
                        frame.payloadData.readString(frame.payloadData.remaining())
                    } else {
                        ""
                    }
                    _incomingMessageSharedFlow.emit(WebSocketMessage.Close(code, reason))
                } else {
                    _incomingMessageSharedFlow.emit(WebSocketMessage.Close(0u, "No Close Message"))
                }
                return false
            }
            else -> {} // Do nothing
        }
        return true
    }

    private suspend fun readAndProcessWebSocketFrame(): Frame? {
        try {
            val byte1 = inputStream.readByte()
            val maskAndPayloadLengthByte = inputStream.readByte()
            val fin = byte1[0]
            val rsv1 = byte1[1]
            val rsv2 = byte1[2]
            val rsv3 = byte1[3]
            check(!rsv1 && !rsv2 && !rsv3) {
                sendCloseFrame(1002u, "Invalid RSV")
                "Invalid incoming RSV bits $byte1"
            }
            val opcode = Opcode.from(byte1)
            if (!opcode.isValid()) {
                sendCloseFrame(1002u, "Invalid OpCode $opcode")
                return null
            } else if (!fin && (opcode == Opcode.Ping || opcode == Opcode.Pong)) {
                sendCloseFrame(1002u, "$opcode does not support fragmentation")
                return null
            }
            val mask = maskAndPayloadLengthByte[0]
            check(!mask) // websocket spec requires this to be a 0 or false
            val payloadLength = maskAndPayloadLengthByte.toInt().shl(1).shr(1)
            val actualPayloadLength = if (payloadLength <= 125) {
                payloadLength.toULong()
            } else if (payloadLength == 126) {
                if (opcode == Opcode.Ping) {
                    sendCloseFrame(
                        1002u,
                        "Control frames are only allowed to have payload up to and including 125 octets"
                    )
                    return null
                }
                inputStream.readBuffer(UShort.SIZE_BYTES).readUnsignedShort().toULong()
            } else if (payloadLength == 127) {
                if (opcode == Opcode.Ping) {
                    sendCloseFrame(
                        1002u,
                        "Control frames are only allowed to have payload up to and including 125 octets"
                    )
                    return null
                }
                inputStream.readBuffer(ULong.SIZE_BYTES).readUnsignedLong()
            } else {
                throw IllegalStateException("Invalid payload length $payloadLength")
            }
            val payload = if (actualPayloadLength == 0uL) {
                EMPTY_BUFFER
            } else {
                check(actualPayloadLength < Int.MAX_VALUE.toULong()) { "Payloads larger than ${Int.MAX_VALUE} bytes is currently unsupported" }
                val buffer = inputStream.readBuffer(actualPayloadLength.toInt())
                buffer.position(buffer.position() + actualPayloadLength.toInt())
                buffer
            }
            val frame = Frame(fin, rsv1, rsv2, rsv3, opcode, MaskingKey.NoMaskingKey, payload)
            if (frame.opcode == Opcode.Ping) {
                writeWebsocketFrame(Opcode.Pong, frame.payloadData, connectionOptions.writeTimeout)
            } else if (frame.opcode == Opcode.Close) {
                hasServerInitiatedClose = true
                payload.resetForRead()
                if (payloadLength == 1) {
                    sendCloseFrame(1002u, "Invalid close payload length (1)")
                } else if (payload.hasRemaining()) {
                    var code = payload.readUnsignedShort()
                    if (payloadLength >= 126) {
                        sendCloseFrame(1002u, "Invalid close payload length")
                    } else if (!isValidServerCloseCode(code)) {
                        sendCloseFrame(1002u, "Invalid Server close code $code")
                    } else if (payload.hasRemaining()) {
                        val decoded = try {
                            payload.readString(payload.remaining())
                        } catch (e: Exception) {
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
        } catch (e: Exception) {
            _connectionStateFlow.value = ConnectionState.Disconnected(e)
            return null
        }
    }

    private suspend fun sendCloseFrame(statusCode: UShort = 1000u, message: String? = null) {
        try {
            val utf8MessageBuffer = message?.toReadBuffer(Charset.UTF8) ?: EMPTY_BUFFER
            val closeBuffer = PlatformBuffer.allocate(UShort.SIZE_BYTES + utf8MessageBuffer.limit())
            closeBuffer.writeUShort(statusCode)
            closeBuffer.write(utf8MessageBuffer)
            closeBuffer.resetForRead()
            writeWebsocketFrame(Opcode.Close, closeBuffer, connectionOptions.writeTimeout)
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
        runningJob?.cancel()
        runningJob = null
    }

    private fun indexOfBuffer(buffer: ReadBuffer, pattern: ReadBuffer): Int {
        val n = buffer.remaining()
        val m = pattern.remaining()
        val patternPos = pattern.position()
        val bufferPos = buffer.position()
        if (n < m) {
            return -1
        }
        for (s in 0..n - m) {
            var match = true
            for (i in 0..<m) {
                if (buffer[s + i + bufferPos] != pattern[patternPos + i]) {
                    match = false
                    break
                }
            }
            if (match) {
                return bufferPos + s
            }
        }
        return -1
    }

    private fun isValidServerCloseCode(code: UShort): Boolean =
                code in 1000u..1003u ||
                code in 1007u..1015u ||
                code in 3000u .. 3999u ||
                code in 4000u .. 4999u
}
