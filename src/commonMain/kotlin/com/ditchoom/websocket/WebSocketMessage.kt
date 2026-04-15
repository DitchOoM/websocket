package com.ditchoom.websocket

/**
 * A WebSocket message. Text frames always carry [String] (UTF-8 per RFC 6455 §5.6),
 * so [Text] is not parameterized. Binary frames carry [B], whose type is determined
 * by the binary [com.ditchoom.buffer.codec.Codec] supplied to [connectWebSocket].
 * Control frames ([Ping], [Pong], [Close]) carry fixed RFC 6455 structures and are
 * not parameterized.
 *
 * Migration from v1: the `value` field was renamed to `payload`. Text.value (String)
 * became Text.payload (still String). Binary.value (ReadBuffer) became Binary.payload
 * (B). Ping/Pong.value (ReadBuffer) became Ping/Pong.appData (String — decoded from
 * UTF-8, since RFC 6455 caps control frame application data at 125 bytes).
 */
sealed interface WebSocketMessage<out B> {
    /** Data message carrying text. Always a UTF-8 [String] per RFC 6455 §5.6. */
    class Text(
        val payload: String,
    ) : WebSocketMessage<Nothing>

    /** Data message carrying binary of type [B]. */
    class Binary<out B>(
        val payload: B,
    ) : WebSocketMessage<B>

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
