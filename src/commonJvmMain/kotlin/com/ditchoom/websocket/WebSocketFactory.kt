package com.ditchoom.websocket

import com.ditchoom.buffer.flow.Connection
import kotlinx.coroutines.CoroutineScope

internal actual val supportsCustomDeflateWindowBits: Boolean = false
internal actual val supportsDeflateContextTakeover: Boolean = true

actual suspend fun <P> connectNativeWebSocket(
    connectionOptions: WebSocketConnectionOptions,
    payloadCodec: PayloadCodec<P>,
    parentScope: CoroutineScope?,
): Connection<WebSocketMessage<P>> =
    throw UnsupportedOperationException(
        "JVM/Android: Use connectWebSocket(transport, options, payloadCodec) with a pre-connected ByteStream",
    )
