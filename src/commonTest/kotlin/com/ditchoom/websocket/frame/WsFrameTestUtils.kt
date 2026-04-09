package com.ditchoom.websocket.frame

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.payload.PayloadReader

/**
 * Test helper: decodes a WsFrame with [TestPayloadCodec] for all payload variants.
 * Demonstrates the intended consumer pattern — bring your codec, decode zero-copy.
 */
internal fun decodeTestFrame(buffer: ReadBuffer): WsFrame {
    val decode: Any.(PayloadReader) -> TestPayload = { pr ->
        TestPayloadCodec.decode(pr.copyToBuffer(BufferFactory.Default))
    }
    @Suppress("UNCHECKED_CAST")
    return WsFrameCodec.decode(
        buffer,
        decodeBinaryPayload = decode as WsFrameBinaryContext.(PayloadReader) -> TestPayload,
        decodeContinuationPayload = decode as WsFrameContinuationContext.(PayloadReader) -> TestPayload,
        decodePingPayload = decode as WsFramePingContext.(PayloadReader) -> TestPayload,
        decodePongPayload = decode as WsFramePongContext.(PayloadReader) -> TestPayload,
        decodeTextPayload = decode as WsFrameTextContext.(PayloadReader) -> TestPayload,
    )
}

/**
 * Test helper: decodes a WsFrame skipping payload content (for binary/length tests).
 */
internal fun decodeSkipPayload(buffer: ReadBuffer): WsFrame {
    val skip: Any.(PayloadReader) -> Int = { pr ->
        pr.remaining()
    }
    @Suppress("UNCHECKED_CAST")
    return WsFrameCodec.decode(
        buffer,
        decodeBinaryPayload = skip as WsFrameBinaryContext.(PayloadReader) -> Int,
        decodeContinuationPayload = skip as WsFrameContinuationContext.(PayloadReader) -> Int,
        decodePingPayload = skip as WsFramePingContext.(PayloadReader) -> Int,
        decodePongPayload = skip as WsFramePongContext.(PayloadReader) -> Int,
        decodeTextPayload = skip as WsFrameTextContext.(PayloadReader) -> Int,
    )
}
