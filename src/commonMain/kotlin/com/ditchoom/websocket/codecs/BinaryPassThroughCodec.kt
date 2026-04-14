package com.ditchoom.websocket.codecs

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.payload.PayloadReader
import com.ditchoom.websocket.PayloadCodec

/**
 * Raw-bytes codec — the escape hatch for callers that genuinely need buffer-level
 * access to payload contents (e.g. tests asserting on arbitrary byte patterns,
 * passthrough proxies that don't need to parse the payload).
 *
 * Decode allocates a fresh [ReadBuffer] and copies the payload bytes out (via
 * [PayloadReader.copyToBuffer]). Callers own the returned buffer's lifetime and
 * must call `freeIfNeeded()` / `close()` when done. The copy is required because
 * the payload slice is only valid for the duration of `decode`; the library
 * releases the frame buffer immediately after this codec returns.
 *
 * Encode writes the entire source buffer to the wire via [WriteBuffer.write],
 * advancing the source buffer's position. The library then backpatches the
 * WebSocket header based on how many bytes were written.
 *
 * **This is not the zero-copy path.** If you're reaching for this codec, consider
 * defining a `@ProtocolMessage` type for your payload and using its KSP-generated
 * codec instead — decode-by-value into a POJO skips the copy.
 */
object BinaryPassThroughCodec : PayloadCodec<ReadBuffer> {
    override fun PayloadReader.decode(): ReadBuffer = copyToBuffer()

    override fun WriteBuffer.encode(value: ReadBuffer) {
        write(value)
    }
}
