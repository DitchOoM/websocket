package com.ditchoom.websocket.tcp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.managed
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.Transport
import com.ditchoom.websocket.WebSocketException
import com.ditchoom.websocket.handshake.computeAcceptKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Real-time runner: the codec's read loop runs on real dispatchers, so no virtual-time skipping. */
private fun runRealTimeTest(block: suspend CoroutineScope.() -> Unit): TestResult =
    runTest(timeout = 30.seconds) { withContext(Dispatchers.Default) { block() } }

/**
 * A scripted server-side [ByteStream]: auto-completes the RFC 6455 handshake on the first client
 * write (with [handshakeStatus] controlling acceptance), captures every subsequent client frame,
 * and lets tests enqueue unmasked server frames for the client to read. Mirrors the root module's
 * MockWebSocketTransport, plus the handshake auto-responder this module's tests need.
 */
private class FakeServerByteStream : ByteStream {
    private var open = true
    private var handshakeDone = false
    private val readQueue = Channel<ReadBuffer>(Channel.UNLIMITED)

    /** Client frames captured after the handshake, as raw bytes. */
    val frames = mutableListOf<ByteArray>()

    /** HTTP status for the handshake response; 101 accepts, anything else rejects. */
    var handshakeStatus = 101

    fun enqueueServerFrame(
        opcode: Int,
        payload: ByteArray,
    ) {
        val frame = ByteArray(2 + payload.size)
        frame[0] = (0x80 or opcode).toByte() // FIN + opcode; server frames are unmasked
        frame[1] = payload.size.toByte() // tests keep payloads <= 125
        payload.copyInto(frame, 2)
        enqueueBytes(frame)
    }

    private fun enqueueBytes(bytes: ByteArray) {
        val buffer = BufferFactory.managed().allocate(bytes.size)
        buffer.writeBytes(bytes)
        readQueue.trySend(buffer) // left in write mode; read() resets for read
    }

    override val isOpen: Boolean get() = open

    override val readPolicy: ReadPolicy = ReadPolicy.Bounded(10.seconds)
    override val writePolicy: WritePolicy = WritePolicy.Bounded(10.seconds)

    override suspend fun read(deadline: Duration): ReadResult {
        if (!open) return ReadResult.End
        val buffer =
            try {
                withTimeout(deadline) { readQueue.receive() }
            } catch (_: ClosedReceiveChannelException) {
                return ReadResult.End
            }
        buffer.resetForRead()
        return ReadResult.Data(buffer)
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        val size = buffer.remaining()
        val bytes = ByteArray(size) { buffer.readByte() }
        if (!handshakeDone) {
            handshakeDone = true
            respondToHandshake(bytes.decodeToString())
        } else {
            frames.add(bytes)
            // Ack a client Close so the codec's bounded close-handshake wait completes promptly.
            if (size >= 1 && (bytes[0].toInt() and 0x0F) == 0x8) {
                enqueueServerFrame(opcode = 0x8, payload = byteArrayOf(0x03, 0xE8.toByte()))
            }
        }
        return BytesWritten(size)
    }

    private fun respondToHandshake(request: String) {
        if (handshakeStatus != 101) {
            enqueueBytes("HTTP/1.1 $handshakeStatus Nope\r\nContent-Length: 0\r\n\r\n".encodeToByteArray())
            return
        }
        val clientKey =
            request
                .lineSequence()
                .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                .substringAfter(":")
                .trim()
        val response =
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${computeAcceptKey(clientKey)}\r\n" +
                "\r\n"
        enqueueBytes(response.encodeToByteArray())
    }

    override suspend fun close() {
        open = false
        readQueue.close()
    }
}

private class FakeServerTransport : Transport {
    val stream = FakeServerByteStream()

    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream = stream
}

class WebSocketTransportTest {
    private val config = TransportConfig(bufferFactory = BufferFactory.managed())

    private suspend fun connectPipe(fake: FakeServerTransport): ByteStream =
        WebSocketTransport(websocketEndpoint = "/pipe", underlying = fake)
            .connect("localhost", 80, config)

    private fun ReadResult.payloadBytes(): ByteArray {
        val data = assertIs<ReadResult.Data>(this)
        val buffer = data.buffer
        return ByteArray(buffer.remaining()) { buffer.readByte() }
    }

    /** Decode a captured client frame: (opcode, unmasked payload). Client frames must be masked. */
    private fun decodeClientFrame(frame: ByteArray): Pair<Int, ByteArray> {
        val opcode = frame[0].toInt() and 0x0F
        assertTrue((frame[1].toInt() and 0x80) != 0, "client frames must set the MASK bit")
        val len = frame[1].toInt() and 0x7F
        assertTrue(len <= 125, "test frames stay under the extended-length threshold")
        val mask = frame.copyOfRange(2, 6)
        val payload = ByteArray(len) { i -> (frame[6 + i].toInt() xor mask[i % 4].toInt()).toByte() }
        return opcode to payload
    }

    @Test
    fun binaryPayloadsRoundTripAsBytes() =
        runRealTimeTest {
            val fake = FakeServerTransport()
            val pipe = connectPipe(fake)
            assertTrue(pipe.isOpen)

            fake.stream.enqueueServerFrame(opcode = 0x2, payload = byteArrayOf(1, 2, 3))
            assertContentEquals(byteArrayOf(1, 2, 3), pipe.read(5.seconds).payloadBytes())

            val out = BufferFactory.managed().allocate(3)
            out.writeBytes(byteArrayOf(4, 5, 6))
            out.resetForRead()
            assertEquals(3, pipe.write(out, 5.seconds).count)

            val (opcode, payload) = decodeClientFrame(fake.stream.frames.first())
            assertEquals(0x2, opcode, "writes must go out as binary frames")
            assertContentEquals(byteArrayOf(4, 5, 6), payload)
            pipe.close()
        }

    @Test
    fun pingFramesAreInvisibleToTheBytePipe() =
        runRealTimeTest {
            val fake = FakeServerTransport()
            val pipe = connectPipe(fake)

            fake.stream.enqueueServerFrame(opcode = 0x9, payload = byteArrayOf()) // ping
            fake.stream.enqueueServerFrame(opcode = 0x2, payload = byteArrayOf(7))
            assertContentEquals(byteArrayOf(7), pipe.read(5.seconds).payloadBytes())
            pipe.close()
        }

    @Test
    fun textFramesSurfaceAsUtf8Bytes() =
        runRealTimeTest {
            val fake = FakeServerTransport()
            val pipe = connectPipe(fake)

            fake.stream.enqueueServerFrame(opcode = 0x1, payload = "hi".encodeToByteArray())
            assertContentEquals("hi".encodeToByteArray(), pipe.read(5.seconds).payloadBytes())
            pipe.close()
        }

    @Test
    fun serverCloseEndsTheStream() =
        runRealTimeTest {
            val fake = FakeServerTransport()
            val pipe = connectPipe(fake)

            fake.stream.enqueueServerFrame(opcode = 0x8, payload = byteArrayOf(0x03, 0xE8.toByte()))
            assertIs<ReadResult.End>(pipe.read(5.seconds))
            assertFalse(pipe.isOpen)

            val buffer = BufferFactory.managed().allocate(1)
            buffer.writeByte(1)
            buffer.resetForRead()
            assertFailsWith<SocketClosedException> { pipe.write(buffer, 1.seconds) }
        }

    @Test
    fun rejectedHandshakeThrowsAndClosesTheUnderlyingStream() =
        runRealTimeTest {
            val fake = FakeServerTransport()
            fake.stream.handshakeStatus = 404
            assertFailsWith<WebSocketException> { connectPipe(fake) }
            assertFalse(fake.stream.isOpen, "underlying transport must not leak on a failed upgrade")
        }

    @Test
    fun closeRunsTheWebSocketCloseHandshake() =
        runRealTimeTest {
            val fake = FakeServerTransport()
            val pipe = connectPipe(fake)
            pipe.close()
            assertFalse(pipe.isOpen)
            val closeFrame = fake.stream.frames.firstOrNull { (it[0].toInt() and 0x0F) == 0x8 }
            assertTrue(closeFrame != null, "close() must send an RFC 6455 Close frame, not just drop TCP")
        }
}
