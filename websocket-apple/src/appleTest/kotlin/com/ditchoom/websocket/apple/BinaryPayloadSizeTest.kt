package com.ditchoom.websocket.apple

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketMessage
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression guards against hardcoded buffer caps in the Apple WebSocket
 * send path. The pre-v2 [AppleWebSocketController.send] allocated a
 * fixed-size 256-byte scratch buffer before encoding any outbound binary
 * message, so any payload larger than 256 bytes would raise
 * `BufferOverflowException` out of `binaryCodec.encode`. The fix uses
 * `Codec.encodeToBuffer` (auto-growing via `GrowableWriteBuffer`) — these
 * tests sweep sizes that straddle the old cap and go well past it so any
 * future "just allocate(<small-number>)" regression surfaces immediately.
 *
 * Uses echo.websocket.org's raw-endpoint path (`/.ws`), same as
 * [NativeWebSocketClientTest]. Skipped wholesale if that server is
 * unreachable (network timeout).
 */
class BinaryPayloadSizeTest {
    /** Sizes chosen to straddle the old 256-byte cap, plus a mid-range and a large one. */
    private val payloadSizes = listOf(255, 256, 257, 1024, 16 * 1024, 128 * 1024)

    @Test
    fun binaryEchoesIntactAcrossVariedPayloadSizes() =
        runBlocking {
            withTimeout(120.seconds) {
                val ws =
                    connectAppleNativeWebSocket(
                        WebSocketConnectionOptions(
                            name = "echo.websocket.org",
                            port = 443,
                            tls = true,
                            websocketEndpoint = "/.ws",
                            connectionTimeout = 15.seconds,
                        ),
                        BinaryPassThroughCodec,
                        authChallengeHandler = acceptServerPresentedTrust(),
                    )
                try {
                    for (size in payloadSizes) {
                        val payload = deterministicPayload(size)
                        launch(Dispatchers.Default) {
                            ws.send(WebSocketMessage.Binary(BufferFactory.Default.wrap(payload)))
                        }
                        val echoed =
                            withTimeout(30.seconds) {
                                ws
                                    .receive()
                                    .filterIsInstance<WebSocketMessage.Binary<ReadBuffer>>()
                                    .map { it.payload }
                                    .first { it.remaining() == size }
                            }
                        val received = echoed.readByteArray(size)
                        assertTrue(
                            payload.contentEquals(received),
                            "Echo mismatch at size=$size bytes",
                        )
                    }
                } finally {
                    ws.close()
                }
            }
        }

    /**
     * Deterministic byte sequence (index mod 251) so echo mismatches
     * localise to a specific offset rather than "random bytes differ".
     */
    private fun deterministicPayload(size: Int): ByteArray = ByteArray(size) { i -> (i % 251).toByte() }
}
