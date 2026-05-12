package com.ditchoom.websocket.codecs

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.CodecKey
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext

/**
 * Context key for the [BufferFactory] used by [BinaryPassThroughCodec] to allocate
 * a consumer-owned destination buffer during decode. The websocket library injects
 * its `bufferFactory` under this key; standalone codec users may set their own.
 */
object WsBufferFactoryKey : CodecKey<BufferFactory>

/**
 * Raw-bytes codec — surfaces the wire payload as a [ReadBuffer] for consumers
 * that want byte-level access (passthrough proxies, tests).
 *
 * Decode follows the canonical "consumer-owned `PlatformBuffer` via factory" pattern
 * (Pattern #2 from `buffer/CLAUDE.md`): allocates a fresh buffer from the factory
 * resolved via [WsBufferFactoryKey] (defaults to [BufferFactory.Default] when no key
 * is set) and copies the wire payload into it. The returned buffer is independent of
 * the wire-side allocator and outlives the codec scope.
 *
 * Encode writes the entire source buffer to the wire via [WriteBuffer.write],
 * advancing the source buffer's position. The library then backpatches the
 * WebSocket header based on how many bytes were written.
 */
object BinaryPassThroughCodec : Codec<ReadBuffer> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): ReadBuffer {
        val factory = context[WsBufferFactoryKey] ?: BufferFactory.Default
        val remaining = buffer.remaining()
        val dst = factory.allocate(remaining)
        if (remaining > 0) dst.write(buffer)
        dst.resetForRead()
        return dst
    }

    override fun encode(
        buffer: WriteBuffer,
        value: ReadBuffer,
        context: EncodeContext,
    ) {
        buffer.write(value)
    }
}
