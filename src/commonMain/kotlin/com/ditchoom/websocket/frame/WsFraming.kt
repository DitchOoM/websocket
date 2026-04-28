package com.ditchoom.websocket.frame

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.DispatchFraming
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.SuspendingStreamProcessor

/**
 * Peek-only [DispatchFraming] for WebSocket frames.
 *
 * RFC 6455 frames carry their payload length inside the header bytes themselves
 * (byte 2 plus an optional 16- or 64-bit extension), so there is no separate
 * body-length prefix between the dispatcher's discriminator and the variant body.
 * The dispatcher only needs the framer to peek a complete frame's size; once a
 * frame has been read into a sized buffer, the variant codec consumes the
 * payload via `@RemainingBytes`.
 *
 * Semantics:
 * - [peekFrameSize]: full frame size (header + payload), delegating to
 *   [WsFrameHeaderCodec.peekFrameSize] for the variable-length header and adding
 *   the payload length parsed from byte 2 + optional extended length bytes.
 * - [readBodyLength]: returns `buffer.remaining()` — the dispatcher slices the
 *   entire post-header buffer, which (when the buffer has been pre-sized to one
 *   frame) equals the payload length encoded in the header.
 * - [writeBodyLength]: no-op — the payload length is already part of the header
 *   the variant codec writes.
 * - [bodyLengthSize]: 0 — no separate length prefix on the wire.
 */
object WsFraming : DispatchFraming<WsFrameHeader> {
    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult {
        val headerPeek = WsFrameHeaderCodec.peekFrameSize(stream, baseOffset)
        if (headerPeek !is PeekResult.Size) return headerPeek
        val headerSize = headerPeek.bytes
        if (stream.available() < baseOffset + 2) return PeekResult.NeedsMoreData
        val len7 = stream.peekByte(baseOffset + 1).toInt() and 0x7F
        val payloadLength: Int =
            when (len7) {
                126 -> {
                    if (stream.available() < baseOffset + 4) return PeekResult.NeedsMoreData
                    stream.peekShort(baseOffset + 2).toInt() and 0xFFFF
                }
                127 -> {
                    if (stream.available() < baseOffset + 10) return PeekResult.NeedsMoreData
                    val len64 = stream.peekLong(baseOffset + 2)
                    if (len64 < 0 || len64 > Int.MAX_VALUE) {
                        throw FrameParseException("Payload length out of range: $len64")
                    }
                    len64.toInt()
                }
                else -> len7
            }
        return PeekResult.Size(headerSize + payloadLength)
    }

    suspend fun peekFrameSize(
        stream: SuspendingStreamProcessor,
        baseOffset: Int = 0,
    ): PeekResult {
        val headerPeek = WsFrameHeaderCodec.peekFrameSize(stream, baseOffset)
        if (headerPeek !is PeekResult.Size) return headerPeek
        val headerSize = headerPeek.bytes
        if (stream.available() < baseOffset + 2) return PeekResult.NeedsMoreData
        val len7 = stream.peekByte(baseOffset + 1).toInt() and 0x7F
        val payloadLength: Int =
            when (len7) {
                126 -> {
                    if (stream.available() < baseOffset + 4) return PeekResult.NeedsMoreData
                    stream.peekShort(baseOffset + 2).toInt() and 0xFFFF
                }
                127 -> {
                    if (stream.available() < baseOffset + 10) return PeekResult.NeedsMoreData
                    val len64 = stream.peekLong(baseOffset + 2)
                    if (len64 < 0 || len64 > Int.MAX_VALUE) {
                        throw FrameParseException("Payload length out of range: $len64")
                    }
                    len64.toInt()
                }
                else -> len7
            }
        return PeekResult.Size(headerSize + payloadLength)
    }

    override fun readBodyLength(buffer: ReadBuffer): Int = buffer.remaining()

    override fun writeBodyLength(
        buffer: WriteBuffer,
        n: Int,
    ) {
        // No-op: WebSocket payload length lives inside the WsFrameHeader bytes,
        // not as a separate prefix.
    }

    override fun bodyLengthSize(n: Int): Int = 0
}
