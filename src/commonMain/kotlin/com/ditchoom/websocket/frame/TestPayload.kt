package com.ditchoom.websocket.frame

import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

/**
 * Example protocol message for zero-copy payload decoding in tests.
 * Demonstrates the intended [WsFrameCodec.decode] usage pattern:
 *
 * ```kotlin
 * val frame = WsFrameCodec.decode(buffer) { TestPayloadCodec.decode(this) }
 * ```
 */
@ProtocolMessage
data class TestPayload(
    @RemainingBytes val text: String,
) : Payload
