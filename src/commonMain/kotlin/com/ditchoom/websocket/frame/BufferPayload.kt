package com.ditchoom.websocket.frame

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.jvm.JvmInline

/**
 * Internal zero-copy [Payload] wrapper around a [ReadBuffer] used by the per-frame codec
 * layer. **Bounded to the WebSocketCodec read-loop scope** — the aliased buffer is freed
 * by the assembler / `emitMessage` path before any value crosses the consumer boundary;
 * [BufferPayload] instances must never escape the read loop.
 *
 * The connection-layer pipeline:
 *  1. [com.ditchoom.websocket.WebSocketCodec.readNextFrame] peeks the frame size via
 *     [WsFraming], reads the bounded frame buffer, and calls
 *     `WsFrameCodec(BufferPayloadCodec).decode(buffer, ctx)`.
 *  2. The generated codec reads the variable-length header, then invokes
 *     [BufferPayloadCodec.decode], which aliases the buffer at the payload window
 *     (position = payload start, limit = payload end) — no copy.
 *  3. [com.ditchoom.websocket.frame.MessageAssembler] reads bytes from
 *     [BufferPayload.buffer] for fragmentation reassembly. Multi-fragment messages
 *     are combined into a fresh allocation; per-frame source buffers are freed
 *     inside the assembler.
 *  4. After reassembly, the user-supplied `binaryCodec` / [com.ditchoom.websocket.codecs.StringCodec]
 *     runs on the assembled payload — that's where the application-level type ([B] or
 *     [String]) is produced. The buffer is freed immediately after that decode returns;
 *     the emitted [com.ditchoom.websocket.WebSocketMessage] carries only the typed value.
 *
 * Splitting the per-frame payload type from the application payload type is required:
 * fragmented Text frames may split mid-UTF-8 codepoint, and structured binary may split
 * anywhere — both demand assembly *before* user-level decode.
 */
@JvmInline
internal value class BufferPayload(
    val buffer: ReadBuffer,
) : Payload

/**
 * Hand-written `Codec<BufferPayload>` for the per-frame payload slot. Decode aliases
 * the bounded payload window (zero-copy); encode writes the wrapped buffer's bytes to
 * the output. Wire size is the wrapped buffer's `remaining()` — the per-variant codec's
 * [WireSize.BackPatch] still drives the outer `WsFrameCodec.wireSize`, but
 * [WsFrameCodec.encode] uses this when writing the masked-frame body.
 */
internal object BufferPayloadCodec : Codec<BufferPayload> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): BufferPayload = BufferPayload(buffer)

    override fun encode(
        buffer: WriteBuffer,
        value: BufferPayload,
        context: EncodeContext,
    ) {
        if (value.buffer.hasRemaining()) buffer.write(value.buffer)
    }

    override fun wireSize(
        value: BufferPayload,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(value.buffer.remaining())

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult = PeekResult.NoFraming
}
