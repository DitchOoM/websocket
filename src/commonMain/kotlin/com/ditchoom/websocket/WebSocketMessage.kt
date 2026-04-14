package com.ditchoom.websocket

/**
 * A WebSocket message whose data-frame payload is of type [P].
 *
 * The payload type [P] is determined by the [PayloadCodec] supplied to
 * [connectWebSocket] — users only see their own materialized values, never a
 * raw buffer. Control frames ([Ping], [Pong], [Close]) carry fixed RFC 6455
 * structures and are not parameterized.
 *
 * Migration from v1: the `value` field was renamed to `payload`. Text.value
 * (String) became Text.payload (whatever the codec produces). Binary.value
 * (ReadBuffer) became Binary.payload (P). Ping/Pong.value (ReadBuffer) became
 * Ping/Pong.appData (String — decoded from UTF-8, since RFC 6455 caps control
 * frame application data at 125 bytes and it's typically a small identifier).
 */
sealed interface WebSocketMessage<out P> {
    /** Data message fragment carrying text (rsv1=0 or decoded from permessage-deflate). */
    class Text<out P>(
        val payload: P,
    ) : WebSocketMessage<P>

    /** Data message fragment carrying binary (rsv1=0 or decoded from permessage-deflate). */
    class Binary<out P>(
        val payload: P,
    ) : WebSocketMessage<P>

    /** RFC 6455 ping. [appData] is the optional application data (≤ 125 bytes UTF-8). */
    class Ping(
        val appData: String = "",
    ) : WebSocketMessage<Nothing>

    /** RFC 6455 pong. [appData] is the optional application data (≤ 125 bytes UTF-8). */
    class Pong(
        val appData: String = "",
    ) : WebSocketMessage<Nothing>

    /** RFC 6455 close frame. */
    class Close(
        val code: UShort,
        val reason: String = "",
    ) : WebSocketMessage<Nothing>
}
