package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.managed
import com.ditchoom.socket.ConnectionState
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Adapts [NativeWebSocketConnection] (Apple Network.framework) to the [WebSocketClient] interface.
 *
 * Bridges the pull-based opcode API into Flow-based push model by running a read loop
 * coroutine that calls [NativeWebSocketConnection.receiveMessage] and emits to the channel.
 */
internal class NativeWebSocketClientAdapter(
    private val connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope?,
) : WebSocketClient {
    private val job = Job()
    override val scope: CoroutineScope =
        CoroutineScope(
            (parentScope?.coroutineContext ?: Dispatchers.Default) +
                job +
                CoroutineName("NativeWebSocket: ${connectionOptions.name}:${connectionOptions.port}"),
        )

    private var connection: NativeWebSocketConnection? = null
    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    override val connectionState: StateFlow<ConnectionState> = connectionStateFlow.asStateFlow()

    private val incomingMessageChannel = Channel<WebSocketMessage>(Channel.UNLIMITED)

    override fun receive(): Flow<WebSocketMessage> = incomingMessageChannel.receiveAsFlow()

    override suspend fun connect(): WebSocketClient {
        connectionStateFlow.value = ConnectionState.Connecting
        val conn =
            try {
                connectNativeWebSocket(
                    url = connectionOptions.buildUrl(),
                    tls = connectionOptions.isTls,
                    verifyCertificates = connectionOptions.tls?.verifyCertificates ?: true,
                    autoReplyPing = true,
                    subprotocols = connectionOptions.protocols.ifEmpty { null },
                    timeoutSeconds = connectionOptions.connectionTimeout.inWholeSeconds.toInt(),
                )
            } catch (e: Exception) {
                connectionStateFlow.value = ConnectionState.Disconnected(e)
                throw WebSocketException.TransportFailed(e.message ?: "Native connection failed", e)
            }
        connection = conn
        connectionStateFlow.value = ConnectionState.Connected
        startReadLoop(conn)
        return this
    }

    private fun startReadLoop(conn: NativeWebSocketConnection) {
        scope.launch {
            try {
                while (conn.isOpen) {
                    val msg = conn.receiveMessage()
                    when (msg.opcode) {
                        NativeWebSocketConnection.OPCODE_TEXT -> {
                            val text =
                                if (msg.data != null) {
                                    msg.data.readString(msg.data.remaining(), Charset.UTF8)
                                } else {
                                    ""
                                }
                            incomingMessageChannel.trySend(WebSocketMessage.Text(text))
                        }
                        NativeWebSocketConnection.OPCODE_BINARY -> {
                            incomingMessageChannel.trySend(WebSocketMessage.Binary(msg.data ?: EMPTY_BUFFER))
                        }
                        NativeWebSocketConnection.OPCODE_PING -> {
                            incomingMessageChannel.trySend(WebSocketMessage.Ping(msg.data ?: EMPTY_BUFFER))
                        }
                        NativeWebSocketConnection.OPCODE_PONG -> {
                            incomingMessageChannel.trySend(WebSocketMessage.Pong(msg.data ?: EMPTY_BUFFER))
                        }
                        NativeWebSocketConnection.OPCODE_CLOSE -> {
                            incomingMessageChannel.trySend(
                                WebSocketMessage.Close(
                                    code = msg.closeCode.toUShort(),
                                    reason = msg.data?.let { it.readString(it.remaining(), Charset.UTF8) } ?: "",
                                ),
                            )
                            connectionStateFlow.value =
                                WebSocketDisconnected(
                                    t =
                                        if (msg.closeCode != 1000) {
                                            WebSocketException.ConnectionClosed(
                                                "Server closed",
                                                code = msg.closeCode.toUShort(),
                                            )
                                        } else {
                                            null
                                        },
                                    code = msg.closeCode.toUShort(),
                                )
                            return@launch
                        }
                    }
                }
                connectionStateFlow.value = ConnectionState.Disconnected()
            } catch (e: Exception) {
                connectionStateFlow.value =
                    ConnectionState.Disconnected(WebSocketException.TransportFailed(e.message ?: "Connection lost", e))
            } finally {
                incomingMessageChannel.close()
            }
        }
    }

    override suspend fun send(message: WebSocketMessage) {
        val conn = connection
            ?: throw WebSocketException.ConnectionClosed("Not connected")
        when (message) {
            is WebSocketMessage.Text -> {
                val buf = BufferFactory.managed().allocate(message.value.length * 3)
                buf.writeString(message.value, Charset.UTF8)
                buf.resetForRead()
                conn.sendMessage(buf, NativeWebSocketConnection.OPCODE_TEXT)
            }
            is WebSocketMessage.Binary -> conn.sendMessage(message.value, NativeWebSocketConnection.OPCODE_BINARY)
            is WebSocketMessage.Ping -> conn.sendMessage(message.value, NativeWebSocketConnection.OPCODE_PING)
            is WebSocketMessage.Pong -> conn.sendMessage(message.value, NativeWebSocketConnection.OPCODE_PONG)
            is WebSocketMessage.Close -> close()
        }
    }

    override suspend fun isPingSupported(): Boolean = true

    override suspend fun localPort(): Int = throw UnsupportedOperationException("Port info not available on native WebSocket")

    override suspend fun remotePort(): Int = throw UnsupportedOperationException("Port info not available on native WebSocket")

    override suspend fun close() {
        try {
            connection?.close()
        } catch (_: Exception) {
            // Ignore close errors
        }
        connection = null
        connectionStateFlow.value = WebSocketDisconnected(code = 1000u)
        incomingMessageChannel.close()
        job.cancel()
    }
}
