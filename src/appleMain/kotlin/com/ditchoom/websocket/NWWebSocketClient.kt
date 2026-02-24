@file:OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.websocket

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.NSDataBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.toNativeData
import com.ditchoom.websocket.nwhelpers.nw_helper_ws_receive_message
import com.ditchoom.websocket.nwhelpers.nw_helper_ws_send
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Network._nw_parameters_configure_protocol_disable
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_create
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_waiting
import platform.Network.nw_connection_t
import platform.Network.nw_endpoint_create_url
import platform.Network.nw_error_domain_dns
import platform.Network.nw_error_domain_posix
import platform.Network.nw_error_domain_tls
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_get_error_domain
import platform.Network.nw_parameters_copy_default_protocol_stack
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_protocol_stack_prepend_application_protocol
import platform.Network.nw_ws_close_code_normal_closure
import platform.Network.nw_ws_create_options
import platform.Network.nw_ws_opcode_binary
import platform.Network.nw_ws_opcode_close
import platform.Network.nw_ws_opcode_ping
import platform.Network.nw_ws_opcode_pong
import platform.Network.nw_ws_opcode_text
import platform.Network.nw_ws_options_add_subprotocol
import platform.Network.nw_ws_options_set_auto_reply_ping
import platform.Network.nw_ws_version_13
import platform.darwin.dispatch_get_global_queue
import platform.posix.QOS_CLASS_USER_INITIATED
import platform.posix.strerror
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnsafeNumber::class)
class NWWebSocketClient(
    private val connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope?,
) : WebSocketClient {
    override val scope =
        if (parentScope == null) {
            CoroutineScope(
                Dispatchers.Default +
                    CoroutineName(
                        "NWWebSocket #${getCountForConnection(connectionOptions)}" +
                            ": ${connectionOptions.name}:${connectionOptions.port}",
                    ),
            )
        } else {
            parentScope + Dispatchers.Default +
                CoroutineName(
                    "NWWebSocket #${getCountForConnection(connectionOptions)}" +
                        ": ${connectionOptions.name}:${connectionOptions.port}",
                )
        }

    private var connection: nw_connection_t = null

    @Volatile
    private var closedLocally = false

    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    override val connectionState = connectionStateFlow.asStateFlow()

    private val incomingMessageChannel = Channel<WebSocketMessage>(Channel.UNLIMITED)
    override val incomingMessages = incomingMessageChannel.receiveAsFlow()

    private val incomingTextChannel = Channel<String>(Channel.UNLIMITED)
    override val incomingTextMessages: Flow<String> = incomingTextChannel.receiveAsFlow()

    private val incomingBinaryChannel = Channel<ReadBuffer>(Channel.UNLIMITED)
    override val incomingBinaryMessages: Flow<ReadBuffer> = incomingBinaryChannel.receiveAsFlow()

    override suspend fun localPort(): Int = -1

    override suspend fun remotePort(): Int = connectionOptions.port

    override suspend fun connect(): WebSocketClient {
        if (connectionState.value == ConnectionState.Connecting ||
            connectionState.value is ConnectionState.Connected
        ) {
            return this
        }
        connectionStateFlow.value = ConnectionState.Connecting

        // Use wss/ws scheme so NW's WebSocket layer uses the URL path for the
        // HTTP upgrade request. NW won't double-add WS since we manually prepend it.
        val url = connectionOptions.buildUrl()
        val endpoint = nw_endpoint_create_url(url)
            ?: throw IllegalArgumentException("Invalid endpoint URL: $url")

        val wsOptions = nw_ws_create_options(nw_ws_version_13)
        nw_ws_options_set_auto_reply_ping(wsOptions, true)
        for (protocol in connectionOptions.protocols) {
            nw_ws_options_add_subprotocol(wsOptions, protocol)
        }

        val parameters = if (connectionOptions.tls) {
            nw_parameters_create_secure_tcp(
                { _ -> }, // Default TLS config
                { _ -> }, // Default TCP config
            )
        } else {
            nw_parameters_create_secure_tcp(
                _nw_parameters_configure_protocol_disable, // No TLS
                { _ -> }, // Default TCP config
            )
        }

        if (parameters == null) {
            connectionStateFlow.value = ConnectionState.Disconnected(Exception("Failed to create NW parameters"))
            return this
        }

        val stack = nw_parameters_copy_default_protocol_stack(parameters)
        nw_protocol_stack_prepend_application_protocol(stack, wsOptions)

        val conn = nw_connection_create(endpoint, parameters)
        if (conn == null) {
            connectionStateFlow.value = ConnectionState.Disconnected(Exception("Failed to create NW connection"))
            return this
        }
        connection = conn

        val queue = dispatch_get_global_queue(QOS_CLASS_USER_INITIATED.toLong(), 0u)

        try {
            withTimeout(connectionOptions.connectionTimeout) {
                suspendCancellableCoroutine<Unit> { cont ->
                    nw_connection_set_state_changed_handler(conn) { state, error ->
                        when (state) {
                            nw_connection_state_ready -> {
                                connectionStateFlow.value = ConnectionState.Connected
                                if (cont.isActive) cont.resume(Unit)
                            }
                            nw_connection_state_failed -> {
                                val errorMsg = describeNWError(error)
                                connectionStateFlow.update { current ->
                                    if (current !is ConnectionState.Disconnected) {
                                        ConnectionState.Disconnected(Exception(errorMsg))
                                    } else {
                                        current
                                    }
                                }
                                if (cont.isActive) {
                                    cont.resumeWithException(Exception("Connection failed: $errorMsg"))
                                }
                            }
                            nw_connection_state_waiting -> {
                                val errorMsg = describeNWError(error)
                                connectionStateFlow.update { current ->
                                    if (current !is ConnectionState.Disconnected) {
                                        ConnectionState.Disconnected(Exception("Waiting: $errorMsg"))
                                    } else {
                                        current
                                    }
                                }
                                nw_connection_cancel(conn)
                                if (cont.isActive) {
                                    cont.resumeWithException(Exception("Connection waiting: $errorMsg"))
                                }
                            }
                            nw_connection_state_cancelled -> {
                                connectionStateFlow.update { current ->
                                    if (current !is ConnectionState.Disconnected) {
                                        ConnectionState.Disconnected()
                                    } else {
                                        current
                                    }
                                }
                                closeChannels()
                            }
                        }
                    }

                    nw_connection_set_queue(conn, queue)
                    nw_connection_start(conn)

                    cont.invokeOnCancellation {
                        nw_connection_cancel(conn)
                    }
                }
            }
        } catch (e: Exception) {
            connectionStateFlow.value = ConnectionState.Disconnected(e)
            return this
        }

        receiveNextMessage()
        return this
    }

    private fun receiveNextMessage() {
        val conn = connection ?: return
        if (closedLocally) return

        nw_helper_ws_receive_message(conn) { data, opcode, closeCode, isCompleteNumber, error ->
            if (error != null) {
                connectionStateFlow.update { current ->
                    if (current !is ConnectionState.Disconnected) {
                        ConnectionState.Disconnected(Exception(error.localizedDescription))
                    } else {
                        current
                    }
                }
                closeChannels()
                return@nw_helper_ws_receive_message
            }

            // Process WebSocket opcode
            val shouldContinue = handleOpcode(opcode, closeCode, data)

            // Continue receiving unless we got a close frame or closed locally.
            // Note: isComplete from nw_connection_receive_message means the current
            // message is fully assembled, NOT that the connection is done.
            if (shouldContinue && !closedLocally) {
                receiveNextMessage()
            }
        }
    }

    /**
     * Process a received WebSocket opcode. Returns true if we should continue receiving.
     */
    private fun handleOpcode(opcode: Int, closeCode: Int, data: NSData?): Boolean {
        when (opcode) {
            nw_ws_opcode_text -> {
                if (data != null && data.length.toInt() > 0) {
                    val text = NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
                    if (text != null) {
                        incomingMessageChannel.trySend(WebSocketMessage.Text(text))
                        incomingTextChannel.trySend(text)
                    }
                }
            }
            nw_ws_opcode_binary -> {
                if (data != null && data.length.toInt() > 0) {
                    val buffer = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
                    incomingMessageChannel.trySend(WebSocketMessage.Binary(buffer))
                    incomingBinaryChannel.trySend(buffer)
                }
            }
            nw_ws_opcode_close -> {
                val reason = if (data != null && data.length.toInt() > 0) {
                    NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString() ?: ""
                } else {
                    ""
                }
                incomingMessageChannel.trySend(
                    WebSocketMessage.Close(closeCode.toUShort(), reason),
                )
                connectionStateFlow.update { current ->
                    if (current !is ConnectionState.Disconnected) {
                        ConnectionState.Disconnected(
                            code = closeCode.toUShort(),
                            reason = reason,
                        )
                    } else {
                        current
                    }
                }
                closeChannels()
                return false
            }
            nw_ws_opcode_ping -> {
                if (data != null && data.length.toInt() > 0) {
                    val buffer = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
                    incomingMessageChannel.trySend(WebSocketMessage.Ping(buffer))
                } else {
                    incomingMessageChannel.trySend(
                        WebSocketMessage.Ping(ReadBuffer.EMPTY_BUFFER),
                    )
                }
            }
            nw_ws_opcode_pong -> {
                if (data != null && data.length.toInt() > 0) {
                    val buffer = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
                    incomingMessageChannel.trySend(WebSocketMessage.Pong(buffer))
                } else {
                    incomingMessageChannel.trySend(
                        WebSocketMessage.Pong(ReadBuffer.EMPTY_BUFFER),
                    )
                }
            }
        }
        return true
    }

    /**
     * Async send via C helper — creates WS metadata + context in C,
     * then calls nw_connection_send and suspends until completion.
     */
    private suspend fun sendSuspend(
        conn: nw_connection_t,
        nsData: NSData?,
        opcode: Int,
        closeCode: Int = 0,
    ) {
        suspendCancellableCoroutine<Unit> { cont ->
            nw_helper_ws_send(conn, nsData, opcode, closeCode) { error ->
                if (error != null) {
                    if (cont.isActive) cont.resumeWithException(Exception(error.localizedDescription))
                } else {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    override suspend fun write(string: String) {
        val conn = connection ?: return
        val nsData = NSString.create(string = string).dataUsingEncoding(NSUTF8StringEncoding)
            ?: return
        sendSuspend(conn, nsData, nw_ws_opcode_text)
    }

    override suspend fun write(buffer: ReadBuffer) {
        val conn = connection ?: return
        val nsData = buffer.toNativeData().nsData
        sendSuspend(conn, nsData, nw_ws_opcode_binary)
    }

    override suspend fun isPingSupported(): Boolean = true

    override suspend fun ping(payloadData: ReadBuffer) {
        val conn = connection ?: return
        val nsData = if (payloadData.remaining() > 0) {
            payloadData.toNativeData().nsData
        } else {
            null
        }
        sendSuspend(conn, nsData, nw_ws_opcode_ping)
    }

    override suspend fun close() {
        if (closedLocally) return
        closedLocally = true

        val conn = connection
        if (conn != null) {
            // Try to send a clean WebSocket close frame, then cancel
            try {
                sendSuspend(conn, null, nw_ws_opcode_close, nw_ws_close_code_normal_closure.toInt())
            } catch (_: Exception) {
                // Ignore send errors during close — connection may already be gone
            }
            nw_connection_cancel(conn)
        }

        closeChannels()
        connectionStateFlow.update { current ->
            if (current !is ConnectionState.Disconnected) {
                ConnectionState.Disconnected()
            } else {
                current
            }
        }
        connection = null
    }

    private fun closeChannels() {
        incomingMessageChannel.close()
        incomingTextChannel.close()
        incomingBinaryChannel.close()
    }

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

        private fun describeNWError(error: platform.Network.nw_error_t): String {
            if (error == null) return "Unknown error"
            val domain = nw_error_get_error_domain(error)
            val code = nw_error_get_error_code(error)
            return when (domain) {
                nw_error_domain_posix -> "POSIX error $code: ${strerror(code)?.toKString() ?: "unknown"}"
                nw_error_domain_dns -> "DNS error $code"
                nw_error_domain_tls -> "TLS error $code"
                else -> "Network error (domain=$domain, code=$code)"
            }
        }
    }
}
