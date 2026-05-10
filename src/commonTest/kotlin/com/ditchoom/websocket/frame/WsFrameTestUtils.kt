package com.ditchoom.websocket.frame

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext

/**
 * Test helper: decodes a WsFrame with [TestPayloadCodec] for all data variants.
 * Demonstrates the intended consumer pattern — bring your codec, decode zero-copy.
 *
 * `WsFrameCodec(TestPayloadCodec)` is the per-payload-type codec instantiation
 * sealed-PP form requires. Wire bytes are identical regardless of payload codec;
 * only the typed payload model differs.
 */
internal fun decodeTestFrame(buffer: ReadBuffer): WsFrame<TestPayload> = WsFrameCodec(TestPayloadCodec).decode(buffer, DecodeContext.Empty)

/**
 * Test helper: decodes a WsFrame as a buffer-aliasing payload (for binary / length tests
 * where the bytes don't matter or are checked directly). Returns a `WsFrame<BufferPayload>`
 * whose `payload.buffer` is positioned at the start of the payload region.
 */
internal fun decodeSkipPayload(buffer: ReadBuffer): WsFrame<BufferPayload> = WsFrameCodec(BufferPayloadCodec).decode(buffer, DecodeContext.Empty)
