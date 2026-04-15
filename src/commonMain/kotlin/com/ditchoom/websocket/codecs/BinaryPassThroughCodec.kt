package com.ditchoom.websocket.codecs

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext

/**
 * Raw-bytes codec — the zero-copy escape hatch for callers that want buffer-level
 * access to payload contents (e.g. passthrough proxies, tests).
 *
 * Decode returns the library-owned payload buffer directly — no copy, no slice. The
 * buffer is positioned at payload start with its limit at payload end.
 *
 * **Lifetime:** the returned buffer is valid *only within the `Flow.collect { }` lambda*
 * that received this message. The library frees it after your collector returns.
 * Do not retain the buffer beyond that scope; copy bytes out if you need them later.
 *
 * Encode writes the entire source buffer to the wire via [WriteBuffer.write],
 * advancing the source buffer's position. The library then backpatches the
 * WebSocket header based on how many bytes were written.
 */
object BinaryPassThroughCodec : Codec<ReadBuffer> {
    override fun decode(buffer: ReadBuffer, context: DecodeContext): ReadBuffer = buffer

    override fun encode(buffer: WriteBuffer, value: ReadBuffer, context: EncodeContext) {
        buffer.write(value)
    }
}
