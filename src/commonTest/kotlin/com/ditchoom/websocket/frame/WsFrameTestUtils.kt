package com.ditchoom.websocket.frame

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext

/**
 * Test helper: decodes a WsFrame with [TestPayloadCodec] for all payload variants.
 * Demonstrates the intended consumer pattern — bring your codec, decode zero-copy.
 */
internal fun decodeTestFrame(buffer: ReadBuffer): WsFrame {
    val decode: Any.(ReadBuffer) -> TestPayload = { slice ->
        TestPayloadCodec.decode(slice, DecodeContext.Empty)
    }
    @Suppress("UNCHECKED_CAST")
    return WsFrameCodec.decode(
        buffer,
        decodeBinaryPayload = decode as WsFrameBinaryContext.(ReadBuffer) -> TestPayload,
        decodeContinuationPayload = decode as WsFrameContinuationContext.(ReadBuffer) -> TestPayload,
        decodePingPayload = decode as WsFramePingContext.(ReadBuffer) -> TestPayload,
        decodePongPayload = decode as WsFramePongContext.(ReadBuffer) -> TestPayload,
        decodeTextPayload = decode as WsFrameTextContext.(ReadBuffer) -> TestPayload,
    )
}

/**
 * Test helper: decodes a WsFrame skipping payload content (for binary/length tests).
 */
internal fun decodeSkipPayload(buffer: ReadBuffer): WsFrame {
    val skip: Any.(ReadBuffer) -> Int = { slice ->
        slice.remaining()
    }
    @Suppress("UNCHECKED_CAST")
    return WsFrameCodec.decode(
        buffer,
        decodeBinaryPayload = skip as WsFrameBinaryContext.(ReadBuffer) -> Int,
        decodeContinuationPayload = skip as WsFrameContinuationContext.(ReadBuffer) -> Int,
        decodePingPayload = skip as WsFramePingContext.(ReadBuffer) -> Int,
        decodePongPayload = skip as WsFramePongContext.(ReadBuffer) -> Int,
        decodeTextPayload = skip as WsFrameTextContext.(ReadBuffer) -> Int,
    )
}
