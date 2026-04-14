package com.ditchoom.websocket.codecs

import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.payload.PayloadReader
import com.ditchoom.websocket.PayloadCodec

/**
 * UTF-8 text payload codec.
 *
 * Decode reads `remaining()` bytes from the payload slice as UTF-8 text — a single
 * String materialization with no intermediate buffer.
 *
 * Encode writes the string directly into the wire buffer via [WriteBuffer.writeString]
 * (UTF-8 by default). The library backpatches the RFC 6455 header once it knows the
 * encoded byte count from the buffer's final position — so no upfront size computation
 * is required.
 *
 * This is the right choice for most text-based WebSocket protocols (chat, JSON over
 * text frames, human-readable telemetry).
 */
object StringCodec : PayloadCodec<String> {
    override fun PayloadReader.decode(): String = readString(remaining())

    override fun WriteBuffer.encode(value: String) {
        writeString(value)
    }
}
