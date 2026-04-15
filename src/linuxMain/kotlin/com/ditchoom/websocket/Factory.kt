package com.ditchoom.websocket

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.Connection
import kotlinx.coroutines.CoroutineScope

internal actual val supportsCustomDeflateWindowBits: Boolean = true
internal actual val supportsDeflateContextTakeover: Boolean = true

actual suspend fun <B> connectNativeWebSocket(
    connectionOptions: WebSocketConnectionOptions,
    binaryCodec: Codec<B>,
    parentScope: CoroutineScope?,
): Connection<WebSocketMessage<B>> =
    throw UnsupportedOperationException(
        "Linux: Use connectWebSocket(transport, options, binaryCodec) with a pre-connected ByteStream",
    )
