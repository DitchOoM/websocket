package com.ditchoom.websocket.frame

/**
 * Common-property accessors for [WsFrame] variants.
 *
 * Each variant inlines the header structure (byte1 + byte2 + optional ext lengths +
 * optional masking key) rather than nesting a shared [WsFrameHeader] field — that's the
 * cost of the value-class discriminator constraint on `@DispatchOn` in this snapshot of
 * buffer-codec. Until the data-class-discriminator + `framing=` form lands and the
 * variants can collapse to `header: WsFrameHeader`, callers reach the per-frame header
 * fields through these extensions.
 *
 * The smart-cast `when` blocks are cheap (one type check per access on JVM), and the
 * file is intentionally narrow so it can be deleted in one commit once the upstream
 * collapse arrives.
 */

val WsFrame<*>.byte1: FrameHeaderByte1
    get() =
        when (this) {
            is WsFrame.Text<*> -> byte1
            is WsFrame.Binary<*> -> byte1
            is WsFrame.Continuation<*> -> byte1
            is WsFrame.Ping<*> -> byte1
            is WsFrame.Pong<*> -> byte1
            is WsFrame.Close -> byte1
        }

val WsFrame<*>.byte2: WsHeaderByte2
    get() =
        when (this) {
            is WsFrame.Text<*> -> byte2
            is WsFrame.Binary<*> -> byte2
            is WsFrame.Continuation<*> -> byte2
            is WsFrame.Ping<*> -> byte2
            is WsFrame.Pong<*> -> byte2
            is WsFrame.Close -> byte2
        }

val WsFrame<*>.extendedLength16: UShort?
    get() =
        when (this) {
            is WsFrame.Text<*> -> extendedLength16
            is WsFrame.Binary<*> -> extendedLength16
            is WsFrame.Continuation<*> -> extendedLength16
            is WsFrame.Ping<*> -> extendedLength16
            is WsFrame.Pong<*> -> extendedLength16
            is WsFrame.Close -> extendedLength16
        }

val WsFrame<*>.extendedLength64: Long?
    get() =
        when (this) {
            is WsFrame.Text<*> -> extendedLength64
            is WsFrame.Binary<*> -> extendedLength64
            is WsFrame.Continuation<*> -> extendedLength64
            is WsFrame.Ping<*> -> extendedLength64
            is WsFrame.Pong<*> -> extendedLength64
            is WsFrame.Close -> extendedLength64
        }

val WsFrame<*>.maskingKey: WsMaskingKey?
    get() =
        when (this) {
            is WsFrame.Text<*> -> maskingKey
            is WsFrame.Binary<*> -> maskingKey
            is WsFrame.Continuation<*> -> maskingKey
            is WsFrame.Ping<*> -> maskingKey
            is WsFrame.Pong<*> -> maskingKey
            is WsFrame.Close -> maskingKey
        }

/** Resolved payload length regardless of byte 2's encoding. RFC 6455 §5.2. */
val WsFrame<*>.payloadLength: Long
    get() = extendedLength64 ?: extendedLength16?.toLong() ?: byte2.lengthIndicator.toLong()

/** Header-on-wire size in bytes: byte1(1) + byte2(1) + extLen(0|2|8) + mask(0|4). */
val WsFrame<*>.headerWireSize: Int
    get() =
        2 +
            (if (extendedLength16 != null) 2 else 0) +
            (if (extendedLength64 != null) 8 else 0) +
            (if (maskingKey != null) 4 else 0)

/**
 * Materialize a [WsFrameHeader] from the variant's inline header fields. Used by code
 * paths that still want the structured header (e.g. backpatch encoding via
 * [WsFrameHeaderCodec.encode]). Allocates a small data-class instance per call —
 * acceptable for the close-handshake / control-frame path; the hot data-frame send path
 * builds wire bytes directly from the variant fields.
 */
fun WsFrame<*>.toHeader(): WsFrameHeader =
    WsFrameHeader(
        byte1 = byte1,
        byte2 = byte2,
        extendedLength16 = extendedLength16,
        extendedLength64 = extendedLength64,
        maskingKey = maskingKey,
    )

// ──────────────── Variant constructors from a structured WsFrameHeader ────────────────
//
// Until the variants collapse to `header: WsFrameHeader`, encode-path call sites that
// already compute a structured header (close-handshake, control-frame-with-appData)
// can use these to spray the inlined fields into a variant in one line.

internal fun WsFrameHeader.toCloseFrame(body: WsCloseBody?): WsFrame.Close =
    WsFrame.Close(
        byte1 = byte1,
        byte2 = byte2,
        extendedLength16 = extendedLength16,
        extendedLength64 = extendedLength64,
        maskingKey = maskingKey,
        body = body,
    )

internal fun WsFrameHeader.toTextFrame(payload: BufferPayload): WsFrame.Text<BufferPayload> =
    WsFrame.Text(
        byte1 = byte1,
        byte2 = byte2,
        extendedLength16 = extendedLength16,
        extendedLength64 = extendedLength64,
        maskingKey = maskingKey,
        payload = payload,
    )

internal fun WsFrameHeader.toBinaryFrame(payload: BufferPayload): WsFrame.Binary<BufferPayload> =
    WsFrame.Binary(
        byte1 = byte1,
        byte2 = byte2,
        extendedLength16 = extendedLength16,
        extendedLength64 = extendedLength64,
        maskingKey = maskingKey,
        payload = payload,
    )

internal fun WsFrameHeader.toContinuationFrame(payload: BufferPayload): WsFrame.Continuation<BufferPayload> =
    WsFrame.Continuation(
        byte1 = byte1,
        byte2 = byte2,
        extendedLength16 = extendedLength16,
        extendedLength64 = extendedLength64,
        maskingKey = maskingKey,
        payload = payload,
    )

internal fun WsFrameHeader.toPingFrame(payload: BufferPayload): WsFrame.Ping<BufferPayload> =
    WsFrame.Ping(
        byte1 = byte1,
        byte2 = byte2,
        extendedLength16 = extendedLength16,
        extendedLength64 = extendedLength64,
        maskingKey = maskingKey,
        payload = payload,
    )

internal fun WsFrameHeader.toPongFrame(payload: BufferPayload): WsFrame.Pong<BufferPayload> =
    WsFrame.Pong(
        byte1 = byte1,
        byte2 = byte2,
        extendedLength16 = extendedLength16,
        extendedLength64 = extendedLength64,
        maskingKey = maskingKey,
        payload = payload,
    )
