package com.ditchoom.websocket.frame

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
 * [peekFrameSize] returns the full frame size (header + payload), delegating to
 * [WsFrameHeaderCodec.peekFrameSize] for the variable-length header and adding the
 * payload length parsed from byte 2 + optional extended length bytes. Once a frame
 * has been read into a sized buffer, the variant codec consumes the payload via
 * `@RemainingBytes`.
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
}
