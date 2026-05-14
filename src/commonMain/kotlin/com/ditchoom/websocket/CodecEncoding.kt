package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.websocket.internal.GrowableWriteBuffer

/**
 * Encodes [value] into a fresh [PlatformBuffer] sized by a 2x-doubling
 * growable scratch, so codecs that report `WireSize.BackPatch` or unknown
 * sizes can't overflow a fixed pre-allocation. The returned buffer is
 * positioned at 0 with limit at the encoded byte count and is ready to read.
 * Caller owns the buffer and must release it via `freeIfNeeded()` /
 * `freeNativeMemory()`.
 *
 * Replaces `com.ditchoom.buffer.codec.encodeToBuffer`, which was removed
 * during the buffer-codec Phase 9 reset. Lives in the websocket module so
 * websocket-apple (and any other consumer) can encode a `Codec<B>` payload
 * into a platform-native buffer without re-publishing
 * [GrowableWriteBuffer] as public API.
 */
public fun <T> Codec<T>.encodeToBuffer(
    value: T,
    factory: BufferFactory,
    initialSize: Int = 256,
): PlatformBuffer {
    val scratch = GrowableWriteBuffer(factory, initialSize)
    encode(scratch, value, EncodeContext.Empty)
    val inner = scratch.underlying
    val size = inner.position()
    inner.position(0)
    inner.setLimit(size)
    return inner
}
