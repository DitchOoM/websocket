package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.ReconnectionClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Opt-in reconnecting wrapper around [DefaultWebSocketClient].
 *
 * Creates a fresh [DefaultWebSocketClient] on each connection attempt.
 * When the underlying client disconnects with an error, the [classifier]
 * decides whether to retry (with delay) or give up. Incoming messages from
 * successive connections are merged into a single [incomingMessages] flow.
 *
 * Usage:
 * ```kotlin
 * val ws = ReconnectingWebSocketClient(options)
 * ws.connect()
 * ws.incomingTextMessages.collect { text -> ... }
 * ```
 */
class ReconnectingWebSocketClient(
    private val connectionOptions: WebSocketConnectionOptions,
    private val classifier: ReconnectionClassifier = WebSocketReconnectionClassifier(),
    private val bufferFactory: BufferFactory = BufferFactory.deterministic(),
    private val parentScope: CoroutineScope? = null,
) : WebSocketClient {
    override val scope: CoroutineScope =
        parentScope ?: CoroutineScope(Dispatchers.Default + Job())

    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    override val connectionState: StateFlow<ConnectionState> = connectionStateFlow.asStateFlow()

    private val incomingMessageChannel = Channel<WebSocketMessage>(Channel.UNLIMITED)
    override val incomingMessages: Flow<WebSocketMessage> = incomingMessageChannel.receiveAsFlow()

    private val incomingTextChannel = Channel<String>(Channel.UNLIMITED)
    override val incomingTextMessages: Flow<String> = incomingTextChannel.receiveAsFlow()

    private val incomingBinaryChannel = Channel<ReadBuffer>(Channel.UNLIMITED)
    override val incomingBinaryMessages: Flow<ReadBuffer> = incomingBinaryChannel.receiveAsFlow()

    @kotlin.concurrent.Volatile
    private var currentClient: DefaultWebSocketClient? = null
    private var reconnectJob: Job? = null
    private var stopped = false

    override suspend fun connect(): WebSocketClient {
        stopped = false
        startReconnectLoop()
        return this
    }

    private fun startReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob =
            scope.launch {
                while (isActive && !stopped) {
                    connectionStateFlow.value = ConnectionState.Connecting

                    val client = createClient()
                    currentClient = client
                    client.connect()

                    val clientState = client.connectionState.value
                    if (clientState is ConnectionState.Connected) {
                        connectionStateFlow.value = ConnectionState.Connected
                        if (classifier is WebSocketReconnectionClassifier) classifier.reset()

                        // Forward messages from this client
                        val forwardJob = launch { forwardMessages(client) }

                        // Wait for disconnection
                        client.connectionState.collect { state ->
                            if (state is ConnectionState.Disconnected) {
                                forwardJob.cancel()
                                return@collect
                            }
                        }
                    }

                    // Client is now disconnected — classify the error
                    val disconnectedState = client.connectionState.value
                    val error =
                        (disconnectedState as? ConnectionState.Disconnected)?.t
                            ?: WebSocketException.ConnectionClosed("Connection lost")

                    try {
                        client.close()
                    } catch (_: Exception) {
                        // Ignore close errors
                    }

                    when (val decision = classifier.classify(error)) {
                        is ReconnectDecision.GiveUp -> {
                            connectionStateFlow.value = ConnectionState.Disconnected(error)
                            break
                        }
                        is ReconnectDecision.RetryAfter -> {
                            connectionStateFlow.value = ConnectionState.Disconnected(error)
                            delay(decision.delay)
                        }
                    }
                }
            }
    }

    private suspend fun forwardMessages(client: DefaultWebSocketClient) {
        client.incomingMessages.collect { msg ->
            incomingMessageChannel.trySend(msg)
            when (msg) {
                is WebSocketMessage.Text -> incomingTextChannel.trySend(msg.value)
                is WebSocketMessage.Binary -> incomingBinaryChannel.trySend(msg.value)
                else -> {} // Ping/Pong/Close handled internally
            }
        }
    }

    private fun createClient(): DefaultWebSocketClient =
        DefaultWebSocketClient(
            connectionOptions = connectionOptions,
            parentScope = scope,
            bufferFactory = bufferFactory,
        )

    override suspend fun localPort(): Int = currentClient?.localPort() ?: -1

    override suspend fun remotePort(): Int = currentClient?.remotePort() ?: -1

    override suspend fun write(string: String) {
        val client = currentClient
            ?: throw WebSocketException.ConnectionClosed("Cannot write: WebSocket is reconnecting or closed")
        client.write(string)
    }

    override suspend fun write(buffer: ReadBuffer) {
        val client = currentClient
            ?: throw WebSocketException.ConnectionClosed("Cannot write: WebSocket is reconnecting or closed")
        client.write(buffer)
    }

    override suspend fun ping(payloadData: ReadBuffer) {
        val client = currentClient
            ?: throw WebSocketException.ConnectionClosed("Cannot ping: WebSocket is reconnecting or closed")
        client.ping(payloadData)
    }

    override suspend fun close() {
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            currentClient?.close()
        } catch (_: Exception) {
            // Ignore close errors
        }
        currentClient = null
        incomingMessageChannel.close()
        incomingTextChannel.close()
        incomingBinaryChannel.close()
        connectionStateFlow.value = ConnectionState.Disconnected()
    }
}
