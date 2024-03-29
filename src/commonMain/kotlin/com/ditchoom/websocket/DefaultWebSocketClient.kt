package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.FragmentedReadBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.toComposableBuffer
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.data.get
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SuspendingSocketInputStream
import com.ditchoom.socket.allocate
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

class DefaultWebSocketClient(
    private val connectionOptions: WebSocketConnectionOptions,
    allocationZone: AllocationZone,
    parentScope: CoroutineScope?
) : WebSocketClient {
    override val scope = if (parentScope == null) {
        CoroutineScope(
            Dispatchers.Default + CoroutineName(
                "Websocket Connection #${getCountForConnection(connectionOptions)}" +
                    ": ${connectionOptions.name}:${connectionOptions.port}"
            )
        )
    } else {
        parentScope + Dispatchers.Default + CoroutineName(
            "Websocket Connection #${getCountForConnection(connectionOptions)}" +
                ": ${connectionOptions.name}:${connectionOptions.port}"
        )
    }
    private val socket = ClientSocket.allocate(connectionOptions.tls, allocationZone)
    private var hasServerInitiatedClose = false
    internal val inputStream = SuspendingSocketInputStream(connectionOptions.readTimeout, socket)
    private val _connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    override val connectionState = _connectionStateFlow.asStateFlow()
    private val _incomingMessageSharedFlow = MutableSharedFlow<WebSocketMessage>()
    override val incomingMessages = _incomingMessageSharedFlow.asSharedFlow()
    val outgoingMessages = Channel<WebSocketMessage>()

    fun isOpen() = socket.isOpen() && connectionState.value is ConnectionState.Connected

    override suspend fun localPort(): Int = socket.localPort()
    override suspend fun remotePort(): Int = socket.remotePort()

    override suspend fun connect(): WebSocketClient {
        if (connectionState.value == ConnectionState.Connecting ||
            connectionState.value is ConnectionState.Connected
        ) {
            return this
        }
        _connectionStateFlow.value = ConnectionState.Connecting
        try {
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
                if ((connectionOptions.tls && connectionOptions.port == 443) ||
                    (!connectionOptions.tls && connectionOptions.port == 80)
                ) {
                    connectionOptions.name
                } else {
                    "${connectionOptions.name}:${connectionOptions.port}"
                }
            val request =
                "GET ${connectionOptions.websocketEndpoint} HTTP/1.1\r\n" +
                    "Host: $hostline\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36\r\n" +
                    "Upgrade: websocket\r\n" +
                    protocolString +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Key: ${generateWebSocketKey()}\r\n" +
                    "\r\n"

            withTimeout(connectionOptions.connectionTimeout) {
                socket.open(connectionOptions.port, connectionOptions.connectionTimeout, connectionOptions.name)
            }
            socket.writeString(request, Charset.UTF8, connectionOptions.writeTimeout)
            val readBuffer = inputStream.readBuffer()
            val endOfStartBuffer = "\r\n\r\n".toReadBuffer(Charset.UTF8)
            val websocketFrameIndex = indexOfBuffer(readBuffer, endOfStartBuffer) + endOfStartBuffer.remaining()
            val response = readBuffer.readString(websocketFrameIndex)
            if (!(
                response.contains("101 Switching Protocols", ignoreCase = true) &&
                    response.contains("Upgrade: websocket", ignoreCase = true) &&
                    response.contains("Connection: Upgrade", ignoreCase = true) &&
                    response.contains("Sec-WebSocket-Accept", ignoreCase = true)
                )
            ) {
                throw IllegalStateException(
                    "Invalid response from server when reading the result from " +
                        "websockets. Response:\r\n$response"
                )
            }
            _connectionStateFlow.value = ConnectionState.Connected
            processIncomingMessages()
            scope.launch {
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
        return this
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
            while (frameBuffer.hasRemaining()) {
                socket.write(frameBuffer, timeout)
            }
            remainingBytes
        } catch (e: Exception) {
            // probably disconnected
            _connectionStateFlow.value = ConnectionState.Disconnected(e)
            -1
        }
    }

    private fun processIncomingMessages() = scope.launch {
        readLoop@while (isOpen()) {
            val frames = mutableListOf<Frame>()
            val buffers = mutableListOf<ReadBuffer>()
            process@ do {
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
                    buffers += frame.payloadData
                }
            } while (!frame.fin || (frame.opcode.isControlFrame() && frame.opcode != Opcode.Close))
            val readPayload = buffers.toComposableBuffer()
            val firstFrame = frames.first()
            val payload = if (readPayload is FragmentedReadBuffer) {
                readPayload.toSingleBuffer()
            } else {
                readPayload
            }
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
                frame.payloadData.resetForRead()
                _incomingMessageSharedFlow.emit(WebSocketMessage.Ping(frame.payloadData))
            }
            Opcode.Pong -> {
                frame.payloadData.resetForRead()
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
                check(actualPayloadLength < Int.MAX_VALUE.toULong()) {
                    "Payloads larger than ${Int.MAX_VALUE} " +
                        "bytes is currently unsupported"
                }
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
            for (i in 0 until m) {
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
            code in 3000u..3999u ||
            code in 4000u..4999u

    companion object {
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
