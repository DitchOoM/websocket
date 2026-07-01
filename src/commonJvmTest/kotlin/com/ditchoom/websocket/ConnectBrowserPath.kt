package com.ditchoom.websocket

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.Connection

internal actual suspend fun <B> connectBrowserPath(
    connectionOptions: WebSocketConnectionOptions,
    binaryCodec: Codec<B>,
): Connection<WebSocketMessage<B>> = throw UnsupportedOperationException("Browser WebSocket not available on JVM/Android")
