package com.ditchoom.websocket.tcp

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.TcpTransport
import com.ditchoom.socket.transport.Transport
import com.ditchoom.websocket.CompressionOptions
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketMessage
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec
import com.ditchoom.websocket.connectWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * WebSocket as a [Transport] rung — the inverse of [connectWebSocket]: instead of the caller
 * bringing a [ByteStream] and getting WebSocket messages, the caller asks for a connection and
 * gets a [ByteStream] whose bytes ride binary WebSocket frames. This is what lets WebSocket sit
 * in a socket `FallbackTransport` chain (QUIC → WebTransport → TCP → **WebSocket**) as the
 * universal floor: a protocol library binds to the returned [ByteStream] and never learns the
 * bytes were framed.
 *
 * Per-transport addressing lives on the instance (the fallback RFC's resolved convention):
 * [websocketEndpoint], [protocols] and compression are fixed at construction; `connect()` supplies
 * only the uniform (host, port, config) surface. TLS follows [TransportConfig.tls] — `wss` when
 * present, plain `ws` otherwise — so the chain's TLS-uniformity invariant is the caller's config,
 * stated once.
 *
 * Semantics of the returned [ByteStream]:
 * - `write()` sends one binary frame per call; `read()` yields one data payload per call.
 *   Byte-stream consumers must not assume frame boundaries survive proxies — they don't need to,
 *   since the consumer reassembles from a byte stream anyway.
 * - Text frames are surfaced as their UTF-8 bytes: a byte pipe has no message-type channel, and
 *   dropping data a permissive peer sent as text would corrupt the stream.
 * - Ping/Pong are invisible (the library auto-pongs); a Close frame or EOF is [ReadResult.End].
 * - The WebSocket-internal read loop waits forever on a quiet connection ([Duration.INFINITE]
 *   read timeout): liveness/keepalive belongs to the protocol riding the pipe, while each
 *   `read()`/`write()` call is still bounded by its own deadline.
 */
class WebSocketTransport(
    private val websocketEndpoint: String = "/",
    private val protocols: List<String> = emptyList(),
    private val requestCompression: Boolean = false,
    private val compressionOptions: CompressionOptions = CompressionOptions(),
    private val underlying: Transport = TcpTransport(),
    private val parentScope: CoroutineScope? = null,
) : Transport {
    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream {
        val stream = underlying.connect(hostname, port, config)
        val options =
            WebSocketConnectionOptions(
                name = hostname,
                port = port,
                tls = config.tls != null,
                connectionTimeout = config.connectTimeout,
                // A quiet WebSocket is not a dead one: the frame read loop treats a read timeout as
                // a transport error and tears down, so the pipe must wait indefinitely for the next
                // frame. Callers bound each ByteStream.read() with its own deadline instead.
                readTimeout = Duration.INFINITE,
                websocketEndpoint = websocketEndpoint,
                protocols = protocols,
                requestCompression = requestCompression,
                compressionOptions = compressionOptions,
                bufferFactory = config.bufferFactory,
            )
        val connection =
            try {
                connectWebSocket(stream, options, BinaryPassThroughCodec, parentScope)
            } catch (t: Throwable) {
                try {
                    stream.close()
                } catch (_: Exception) {
                }
                throw t
            }
        return WebSocketByteStream(connection, config)
    }
}

/**
 * The byte-pipe projection over a WebSocket [Connection]: write = one binary frame, read = the
 * next data payload (control frames handled/skipped), Close/EOF = [ReadResult.End]. Closing the
 * stream runs the full RFC 6455 close handshake and tears down the underlying transport.
 */
private class WebSocketByteStream(
    private val connection: Connection<WebSocketMessage<ReadBuffer>>,
    private val config: TransportConfig,
) : ByteStream {
    private var open = true

    override val isOpen: Boolean get() = open

    override val readPolicy: ReadPolicy get() = config.readPolicy

    override val writePolicy: WritePolicy get() = config.writePolicy

    override suspend fun read(deadline: Duration): ReadResult {
        if (!open) return ReadResult.End
        val message =
            try {
                withTimeout(deadline) {
                    connection.receive().first {
                        it !is WebSocketMessage.Ping && it !is WebSocketMessage.Pong
                    }
                }
            } catch (_: NoSuchElementException) {
                // Message channel closed (peer EOF / transport failure) — clean end of stream.
                open = false
                return ReadResult.End
            }
        return when (message) {
            is WebSocketMessage.Binary -> ReadResult.Data(message.payload)
            is WebSocketMessage.Text -> ReadResult.Data(message.payload.toUtf8Buffer())
            is WebSocketMessage.Close -> {
                open = false
                ReadResult.End
            }
            is WebSocketMessage.Ping, is WebSocketMessage.Pong ->
                error("control frames are filtered before dispatch")
        }
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        if (!open) throw SocketClosedException.General("WebSocket byte stream is closed")
        val bytes = buffer.remaining()
        withTimeout(deadline) { connection.send(WebSocketMessage.Binary(buffer)) }
        return BytesWritten(bytes)
    }

    override suspend fun close() {
        if (!open) return
        open = false
        connection.close()
    }

    private fun String.toUtf8Buffer(): ReadBuffer {
        val buffer = config.bufferFactory.allocate(length * 4)
        buffer.writeString(this, Charset.UTF8)
        buffer.resetForRead()
        return buffer
    }
}
