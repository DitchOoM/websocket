package com.ditchoom.websocket

import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.payload.PayloadReader

/**
 * Decodes a WebSocket data-frame payload into a consumer type [P].
 *
 * The [PayloadReader] receiver provides zero-copy access to the network buffer slice.
 * The reader is **scoped to the decode call** — implementations must not capture it
 * or retain any slice of its underlying bytes after `decode` returns. Return a fully
 * materialized value (POJO, String, etc.); the library will release the frame buffer
 * as soon as `decode` returns.
 *
 * Covariant: a `PayloadDecoder<ChatMessage>` can be used where `PayloadDecoder<Any>` is expected.
 */
fun interface PayloadDecoder<out P> {
    fun PayloadReader.decode(): P
}

/**
 * Encodes a consumer type [P] into a WebSocket data-frame payload.
 *
 * The [WriteBuffer] receiver is the wire buffer (already positioned past the reserved
 * header region). Implementations write payload bytes directly. The library discovers
 * the encoded size from the buffer's position after `encode` returns, then backpatches
 * the RFC 6455 header bytes into the reserved prefix — so no `size` method is required.
 *
 * The buffer is scoped to the encode call; implementations must not retain it.
 *
 * Contravariant: a `PayloadEncoder<Any>` can be used where `PayloadEncoder<ChatMessage>` is expected.
 */
fun interface PayloadEncoder<in P> {
    fun WriteBuffer.encode(value: P)
}

/**
 * A bidirectional payload codec combining [PayloadDecoder] and [PayloadEncoder].
 *
 * Most v2 connections use a single `PayloadCodec<P>` for both directions. Use the
 * standalone `PayloadDecoder`/`PayloadEncoder` interfaces only when a connection is
 * one-way or the decode/encode types differ.
 *
 * Built-ins shipped with the library:
 * - [StringCodec] — UTF-8 text, the common case.
 * - [EmptyCodec] — `Unit` payloads, for apps that only handle control frames.
 * - [BinaryPassThroughCodec] — raw `ReadBuffer` escape hatch for callers who genuinely
 *   need byte-level access. Not the recommended default, but not restricted either.
 *
 * For structured payloads, define a `@ProtocolMessage` data class and implement a
 * small adapter that delegates to the KSP-generated codec — see `WEBSOCKET_V2_DESIGN.md`.
 */
interface PayloadCodec<P> : PayloadDecoder<P>, PayloadEncoder<P>
