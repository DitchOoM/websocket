package com.ditchoom.websocket.codecs

import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.payload.PayloadReader
import com.ditchoom.websocket.PayloadCodec

/**
 * No-payload codec — appropriate when the application only cares about control frames
 * (ping/pong/close) and the data-frame body is either absent or irrelevant.
 *
 * Decode discards any remaining bytes and returns [Unit].
 * Encode writes nothing; the library still emits a zero-length data frame if called.
 */
object EmptyCodec : PayloadCodec<Unit> {
    override fun PayloadReader.decode() {
        // Discard any bytes in the payload slice without materializing them.
        val remaining = remaining()
        if (remaining > 0) {
            // readString advances the reader; cheaper than allocating a scratch buffer.
            readString(remaining)
        }
    }

    override fun WriteBuffer.encode(value: Unit) {
        // Nothing to write — the frame will have a zero-length payload.
    }
}
