package com.ditchoom.websocket.codecs

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext

/**
 * No-payload codec — appropriate when the application only cares about control frames
 * (ping/pong/close) and the data-frame body is either absent or irrelevant.
 *
 * Decode advances past any remaining bytes and returns [Unit].
 * Encode writes nothing; the library still emits a zero-length data frame if called.
 */
object EmptyCodec : Codec<Unit> {
    override fun decode(buffer: ReadBuffer, context: DecodeContext) {
        val remaining = buffer.remaining()
        if (remaining > 0) {
            buffer.position(buffer.position() + remaining)
        }
    }

    override fun encode(buffer: WriteBuffer, value: Unit, context: EncodeContext) {
        // Nothing to write — the frame will have a zero-length payload.
    }
}
