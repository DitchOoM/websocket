package com.ditchoom.websocket.tcp

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.IoTuning
import com.ditchoom.socket.TlsConfig
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.TcpTransport
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketMessage
import com.ditchoom.websocket.codecs.EmptyCodec
import com.ditchoom.websocket.connectWebSocket
import kotlinx.coroutines.CoroutineScope

/**
 * Opens a TCP connection and establishes a WebSocket over it using the
 * library's full RFC 6455 implementation (handshake, framing, masking,
 * permessage-deflate).
 *
 * This is the convenience entry point for platforms with raw socket access
 * (JVM, Android, iOS/macOS, Linux, Node.js). It combines [TcpTransport]
 * with [connectWebSocket] in a single call.
 *
 * For browser JS, use `connectBrowserWebSocket` from the core module.
 * For Apple-native WebSocket (NSURLSessionWebSocketTask), use
 * `connectAppleNativeWebSocket` from `com.ditchoom:websocket-apple`.
 */
suspend fun <B> connectTcpWebSocket(
    connectionOptions: WebSocketConnectionOptions,
    binaryCodec: Codec<B>,
    parentScope: CoroutineScope? = null,
): Connection<WebSocketMessage<B>> {
    val transport =
        TcpTransport().connect(
            hostname = connectionOptions.name,
            port = connectionOptions.port,
            config =
                TransportConfig(
                    bufferFactory = connectionOptions.bufferFactory,
                    connectTimeout = connectionOptions.connectionTimeout,
                    readPolicy = ReadPolicy.Bounded(connectionOptions.readTimeout),
                    writePolicy = WritePolicy.Bounded(connectionOptions.writeTimeout),
                    // TLS uses default certificate validation; tcpNoDelay for low-latency framing.
                    tls = if (connectionOptions.tls) TlsConfig.DEFAULT else null,
                    io = IoTuning(tcpNoDelay = true),
                ),
        )
    return connectWebSocket(
        transport = transport,
        connectionOptions = connectionOptions,
        binaryCodec = binaryCodec,
        parentScope = parentScope,
    )
}

/** No-binary overload — binary frames surface as `Binary(Unit)`. */
suspend fun connectTcpWebSocket(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope? = null,
): Connection<WebSocketMessage<Unit>> = connectTcpWebSocket(connectionOptions, EmptyCodec, parentScope)
