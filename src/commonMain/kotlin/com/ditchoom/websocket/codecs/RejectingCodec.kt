package com.ditchoom.websocket.codecs

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext

/**
 * Loud-failure codec for callers that don't expect any binary frames on a connection.
 *
 * Unlike [EmptyCodec] (which silently discards binary payloads and emits
 * `WebSocketMessage.Binary(Unit)`), this codec throws on every decode/encode call.
 * Use it as the binary codec when receiving an unexpected binary frame should be
 * treated as a bug in the protocol.
 */
object RejectingCodec : Codec<Nothing> {
    override fun decode(buffer: ReadBuffer, context: DecodeContext): Nothing =
        error("RejectingCodec: received unexpected binary frame; configure a binary codec to accept it")

    override fun encode(buffer: WriteBuffer, value: Nothing, context: EncodeContext): Nothing =
        error("RejectingCodec: cannot encode — no binary codec configured")
}
