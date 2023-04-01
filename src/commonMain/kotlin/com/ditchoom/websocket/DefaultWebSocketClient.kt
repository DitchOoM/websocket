package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.data.get
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.EMPTY_BUFFER
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SuspendingSocketInputStream
import com.ditchoom.socket.allocate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

class DefaultWebSocketClient(
    private val connectionOptions: WebSocketConnectionOptions,
    allocationZone: AllocationZone,
) : WebSocketClient {
    private val socket = ClientSocket.allocate(connectionOptions.tls, allocationZone)
    private var hasServerInitiatedClose = false

    private val inputStream = SuspendingSocketInputStream(connectionOptions.readTimeout, socket)

    override fun isOpen() = socket.isOpen()
    override suspend fun localPort(): Int = socket.localPort()
    override suspend fun remotePort(): Int = socket.remotePort()

    override suspend fun connect() {
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
        val response = socket.readString(Charset.UTF8)
        if (!(
            response.contains("101 Switching Protocols", ignoreCase = true) &&
                response.contains("Upgrade: websocket", ignoreCase = true) &&
                response.contains("Connection: Upgrade", ignoreCase = true) &&
                response.contains("Sec-WebSocket-Accept", ignoreCase = true)
            )
        ) {
            throw IllegalStateException("Invalid response from server when reading the result from websockets. Response:\r\n$response")
        }
    }

    override suspend fun write(string: String) {
        writeWebsocketFrame(Opcode.Text, string.toReadBuffer(Charset.UTF8), connectionOptions.writeTimeout)
    }

    override suspend fun write(buffer: ReadBuffer) {
        writeWebsocketFrame(Opcode.Binary, buffer, connectionOptions.writeTimeout)
    }

    override suspend fun write(buffer: ReadBuffer, timeout: Duration): Int =
        writeWebsocketFrame(Opcode.Binary, buffer, timeout)

    override suspend fun ping(payloadData: ReadBuffer) {
        writeWebsocketFrame(Opcode.Ping, payloadData, connectionOptions.writeTimeout)
    }

    private suspend fun writeWebsocketFrame(
        opcode: Opcode,
        payloadData: ReadBuffer = EMPTY_BUFFER,
        timeout: Duration
    ): Int {
        val frame = Frame(true, opcode, MaskingKey.FourByteMaskingKey(), payloadData)
        val frameBuffer = frame.toBuffer()
        frameBuffer.resetForRead()
        val remainingBytes = frameBuffer.remaining()
        socket.write(frameBuffer, timeout)
        return remainingBytes
    }

    override suspend fun read(timeout: Duration): ReadBuffer {
        var dataRead = withTimeout(timeout) { read() }
        while (dataRead !is DataRead.BinaryDataRead) {
            dataRead = withTimeout(timeout) { read() }
        }
        return dataRead.data
    }

    override suspend fun readString(charset: Charset, timeout: Duration): String {
        var dataRead = withTimeout(timeout) { read() }
        while (dataRead !is DataRead.StringDataRead) {
            dataRead = withTimeout(timeout) { read() }
        }
        return dataRead.string
    }

    override fun readFlowString(charset: Charset, timeout: Duration) = flow {
        while (isOpen()) {
            try {
                emit(readString(charset, timeout))
            } catch (e: SocketClosedException) {
                return@flow
            }
        }
    }

    override suspend fun read(): DataRead {
        val frame = readAndProcessWebSocketFrame()
        return when (frame.opcode) {
            Opcode.Binary -> DataRead.BinaryDataRead(frame.payloadData)
            Opcode.Text -> {
                frame.payloadData.resetForRead()
                DataRead.StringDataRead(frame.payloadData.readString(frame.payloadData.limit(), Charset.UTF8))
            }

            Opcode.Ping -> DataRead.Ping(frame.payloadData)
            Opcode.Pong -> DataRead.Pong(frame.payloadData)
            Opcode.Close -> throw SocketClosedException("Socket closed while reading websocket")
            // should not be any opcode, need to be smarter
            else -> read()
        }
    }

    private suspend fun readAndProcessWebSocketFrame(): Frame {
        val byte1 = inputStream.readByte()
        val fin = byte1[0]
        val rsv1 = byte1[1]
        val rsv2 = byte1[2]
        val rsv3 = byte1[3]
        check(!rsv1 && !rsv2 && !rsv3) { "Invalid incoming RSV bits" }
        val opcode = Opcode.from(byte1)
        val maskAndPayloadLengthByte = inputStream.readByte()
        val mask = maskAndPayloadLengthByte[0]
        check(!mask) // websocket spec requires this to be a 0 or false
        val payloadLength = maskAndPayloadLengthByte.toInt().shl(1).shr(1)
        val actualPayloadLength = if (payloadLength <= 125) {
            payloadLength.toULong()
        } else if (payloadLength == 126) {
            inputStream.ensureBufferSize(UShort.SIZE_BYTES).readUnsignedShort().toULong()
        } else if (payloadLength == 127) {
            inputStream.ensureBufferSize(ULong.SIZE_BYTES).readUnsignedLong()
        } else {
            throw IllegalStateException("Invalid payload length $payloadLength")
        }
        val payload = if (actualPayloadLength == 0uL) {
            EMPTY_BUFFER
        } else {
            check(actualPayloadLength < Int.MAX_VALUE.toULong()) { "Payloads larger than ${Int.MAX_VALUE} bytes is currently unsupported" }
            val buffer = inputStream.readBuffer(actualPayloadLength.toInt())
            buffer.position(buffer.limit())
            buffer
        }
        val frame = Frame(fin, rsv1, rsv2, rsv3, opcode, MaskingKey.NoMaskingKey, payload)
        if (frame.opcode == Opcode.Ping) {
            writeWebsocketFrame(Opcode.Pong, frame.payloadData, connectionOptions.writeTimeout)
        } else if (frame.opcode == Opcode.Close) {
            hasServerInitiatedClose = true
            close()
        }
        return frame
    }

    private suspend fun sendCloseFrame(statusCode: UShort = 1000u, message: String? = null) {
        val utf8MessageBuffer = message?.toReadBuffer(Charset.UTF8) ?: EMPTY_BUFFER
        val closeBuffer = PlatformBuffer.allocate(UShort.SIZE_BYTES + utf8MessageBuffer.limit())
        closeBuffer.writeUShort(statusCode)
        closeBuffer.write(utf8MessageBuffer)
        closeBuffer.resetForRead()
        writeWebsocketFrame(Opcode.Close, closeBuffer, connectionOptions.writeTimeout)
    }

    override suspend fun close() {
        if (!hasServerInitiatedClose) {
            sendCloseFrame()
        }
        socket.close()
    }
}
