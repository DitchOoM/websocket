package com.ditchoom.websocket

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.Connection
import kotlinx.coroutines.CoroutineScope

internal actual val supportsCustomDeflateWindowBits: Boolean = true
internal actual val supportsDeflateContextTakeover: Boolean = true

actual suspend fun <P> connectNativeWebSocket(
    connectionOptions: WebSocketConnectionOptions,
    payloadCodec: Codec<P>,
    parentScope: CoroutineScope?,
): Connection<WebSocketMessage<P>> =
    throw UnsupportedOperationException(
        "Linux: Use connectWebSocket(transport, options, payloadCodec) with a pre-connected ByteStream",
    )
