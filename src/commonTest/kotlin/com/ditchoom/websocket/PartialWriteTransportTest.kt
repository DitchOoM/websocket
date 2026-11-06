package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.managed
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A sink may accept fewer bytes than offered and report the count (a QUIC stream at a flow-control
 * window boundary does exactly this). Every byte the client sends must still reach the peer.
 */
class PartialWriteTransportTest {
    private val options =
        WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            tls = false,
            websocketEndpoint = "/ws",
            bufferFactory = BufferFactory.managed(),
        )

    @Test
    fun handshakeIsDeliveredWhole() =
        runStrictTest {
            val transport = PartialWriteTransport(MAX_BYTES_PER_WRITE)

            val connection = connectWebSocket(transport, options)

            val request = transport.receivedText()
            assertTrue(request.startsWith("GET /ws HTTP/1.1\r\n"), request)
            assertTrue(request.endsWith("\r\n\r\n"), request)
            assertTrue(request.length > MAX_BYTES_PER_WRITE * 2, "request must need several writes")
            connection.close()
        }

    @Test
    fun frameIsDeliveredWhole() =
        runStrictTest {
            val transport = PartialWriteTransport(MAX_BYTES_PER_WRITE)
            val connection = connectWebSocket(transport, options)
            val handshakeBytes = transport.receivedByteCount()
            val text = "a".repeat(100)

            connection.send(WebSocketMessage.Text(text))

            val maskedHeaderBytes = 2 + 4
            assertEquals(handshakeBytes + maskedHeaderBytes + text.length, transport.receivedByteCount())
            connection.close()
        }

    private companion object {
        const val MAX_BYTES_PER_WRITE = 7
    }
}

/**
 * Accepts at most [maxBytesPerWrite] bytes per `write`, advancing the caller's buffer by exactly that
 * many and reporting the count. Each buffer the client hands over is one frame, so a write that starts a
 * fresh buffer starts a frame. Answers the upgrade request with a valid 101 once the full request
 * (terminated by an empty line) has arrived, and ends the stream if asked to read before then.
 */
private class PartialWriteTransport(
    private val maxBytesPerWrite: Int,
) : ByteStream {
    private val received = StringBuilder()
    private val responses = Channel<ReadBuffer>(Channel.UNLIMITED)
    private var handshakeAnswered = false
    private var atFrameStart = true
    private var open = true

    override val isOpen: Boolean get() = open
    override val readPolicy: ReadPolicy = ReadPolicy.Bounded(5.seconds)
    override val writePolicy: WritePolicy = WritePolicy.Bounded(5.seconds)

    fun receivedText(): String = received.toString()

    fun receivedByteCount(): Int = received.length

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        val accepted = minOf(maxBytesPerWrite, buffer.remaining())
        val frameStart = received.length
        repeat(accepted) { received.append((buffer.readByte().toInt() and 0xFF).toChar()) }
        if (handshakeAnswered && atFrameStart && accepted > 0 && isCloseOpcode(received[frameStart])) answerClose()
        atFrameStart = buffer.remaining() == 0
        return BytesWritten(accepted)
    }

    override suspend fun read(deadline: Duration): ReadResult {
        if (!handshakeAnswered) {
            val request = received.toString()
            if (!request.endsWith("\r\n\r\n")) return ReadResult.End
            handshakeAnswered = true
            return ReadResult.Data(handshakeResponseFor(request))
        }
        val next = responses.receiveCatching().getOrNull() ?: return ReadResult.End
        return ReadResult.Data(next)
    }

    override suspend fun close() {
        open = false
        responses.close()
    }

    private fun handshakeResponseFor(request: String): ReadBuffer {
        val requestBuffer = BufferFactory.Default.allocate(request.length)
        requestBuffer.writeString(request, Charset.UTF8)
        val response = MockHandshakeHelper.buildHandshakeResponse(MockHandshakeHelper.extractClientKey(requestBuffer))
        response.resetForRead()
        return response
    }

    private fun isCloseOpcode(firstFrameByte: Char): Boolean = (firstFrameByte.code and 0x0F) == CLOSE_OPCODE

    private fun answerClose() {
        val closeAck = MockHandshakeHelper.buildServerCloseFrame()
        closeAck.resetForRead()
        responses.trySend(closeAck)
    }

    private companion object {
        const val CLOSE_OPCODE = 0x8
    }
}
