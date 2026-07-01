package com.ditchoom.websocket

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.Connection

internal expect suspend fun <B> connectBrowserPath(
    connectionOptions: WebSocketConnectionOptions,
    binaryCodec: Codec<B>,
): Connection<WebSocketMessage<B>>
