package com.ditchoom.websocket

/**
 * Applies a repeating 4-byte XOR mask from source to destination using bulk Long operations.
 * Processes 8 bytes at a time, falling back to byte-at-a-time for the remainder.
 *
 * The inline + lambda pattern eliminates virtual dispatch overhead on Native/JS:
 * at the call site the concrete buffer type is known, so read/write operations
 * are inlined directly without vtable lookups.
 *
 * @param srcPos Starting byte index in source
 * @param dstPos Starting byte index in destination
 * @param length Number of bytes to process
 * @param maskLong The 4-byte mask repeated to fill a Long
 * @param getSrcLong Read 8 bytes from source at given byte index
 * @param setDstLong Write 8 bytes to destination at given byte index
 * @param getSrcByte Read 1 byte from source at given byte index
 * @param setDstByte Write 1 byte to destination at given byte index
 * @param getMaskByte Get mask byte for remainder (called with index 0-3)
 */
internal inline fun bulkXor(
    srcPos: Int,
    dstPos: Int,
    length: Int,
    maskLong: Long,
    getSrcLong: (Int) -> Long,
    setDstLong: (Int, Long) -> Unit,
    getSrcByte: (Int) -> Byte,
    setDstByte: (Int, Byte) -> Unit,
    getMaskByte: (Int) -> Byte,
) {
    var i = 0
    val longLimit = length - 7

    while (i < longLimit) {
        setDstLong(dstPos + i, getSrcLong(srcPos + i) xor maskLong)
        i += 8
    }

    while (i < length) {
        val src = getSrcByte(srcPos + i)
        setDstByte(dstPos + i, (src.toInt() xor getMaskByte(i and 3).toInt()).toByte())
        i++
    }
}
