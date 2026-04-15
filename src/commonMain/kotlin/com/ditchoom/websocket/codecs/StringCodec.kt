package com.ditchoom.websocket.codecs

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext

/**
 * UTF-8 text payload codec.
 *
 * Decode reads `remaining()` bytes from the payload buffer as UTF-8 text — a single
 * String materialization with no intermediate buffer. Encode writes the string directly
 * into the wire buffer via [WriteBuffer.writeString] (UTF-8 by default); the library
 * backpatches the RFC 6455 header once it knows the encoded byte count from the
 * buffer's final position, so no upfront size computation is required.
 *
 * This is the right choice for most text-based WebSocket protocols (chat, JSON over
 * text frames, human-readable telemetry).
 */
internal object StringCodec : Codec<String> {
    override fun decode(buffer: ReadBuffer, context: DecodeContext): String =
        buffer.readString(buffer.remaining(), Charset.UTF8)

    override fun encode(buffer: WriteBuffer, value: String, context: EncodeContext) {
        buffer.writeString(value)
    }
}
