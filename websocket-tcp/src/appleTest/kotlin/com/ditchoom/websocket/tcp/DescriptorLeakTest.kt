package com.ditchoom.websocket.tcp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.allocate
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketException
import com.ditchoom.websocket.WebSocketMessage
import com.ditchoom.websocket.handshake.computeAcceptKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * A client connection the peer ended must not keep its file descriptor: counts the entries of
 * `/dev/fd` before and after repeated server-initiated closes and rejected handshakes, without
 * the client ever calling `close()`.
 */
class DescriptorLeakTest {
    private val rounds = 40

    private fun runRealTimeTest(block: suspend CoroutineScope.() -> Unit): TestResult =
        runTest(timeout = 120.seconds) { withContext(Dispatchers.Default) { block() } }

    @OptIn(ExperimentalForeignApi::class)
    private fun openDescriptorCount(): Int {
        val dir = opendir("/dev/fd") ?: error("cannot list /dev/fd")
        var count = 0
        while (readdir(dir) != null) count++
        closedir(dir)
        return count
    }

    private fun optionsFor(port: Int) = WebSocketConnectionOptions(name = "127.0.0.1", port = port, tls = false)

    private fun text(value: String): ReadBuffer {
        val buffer = BufferFactory.Default.allocate(value.length * 4)
        buffer.writeString(value, Charset.UTF8)
        buffer.resetForRead()
        return buffer
    }

    private fun serverCloseFrame(): ReadBuffer {
        val frame = BufferFactory.Default.allocate(4)
        frame.writeByte(0x88.toByte()) // FIN + Close
        frame.writeByte(0x02) // status code only
        frame.writeShort(1000)
        frame.resetForRead()
        return frame
    }

    /** Serves each connection with [respond] after reading its upgrade request. */
    private suspend fun CoroutineScope.serve(
        server: ServerSocket,
        respond: suspend (client: ClientSocket, clientKey: String) -> Unit,
    ) = launch(Dispatchers.Default) {
        server.bind().collect { client ->
            launch {
                try {
                    val request = (client.read(5.seconds) as ReadResult.Data).buffer
                    val requestText = request.readString(request.remaining(), Charset.UTF8)
                    val key =
                        requestText
                            .lineSequence()
                            .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                            .substringAfter(":")
                            .trim()
                    respond(client, key)
                } catch (_: Exception) {
                } finally {
                    try {
                        client.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private suspend fun settle() {
        // The peer's FIN and the library's teardown arrive asynchronously.
        delay(1500)
    }

    @Test
    fun serverInitiatedCloseReleasesTheDescriptor() =
        runRealTimeTest {
            val server = ServerSocket.allocate()
            val serving =
                serve(server) { client, key ->
                    client.write(
                        text(
                            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: ${computeAcceptKey(key)}\r\n\r\n",
                        ),
                        5.seconds,
                    )
                    client.write(serverCloseFrame(), 5.seconds)
                    // Hold the socket until the client hangs up so it is the client's close that is measured.
                    try {
                        while (client.read(5.seconds) is ReadResult.Data) delay(10)
                    } catch (_: Exception) {
                    }
                }
            delay(200)
            val port = server.port()
            val baseline = openDescriptorCount()
            repeat(rounds) {
                val connection = connectTcpWebSocket(optionsFor(port))
                withTimeout(10.seconds) {
                    connection.receive().first { it is WebSocketMessage.Close }
                }
                // Dropped without close(): the peer already closed.
            }
            settle()
            val after = openDescriptorCount()
            serving.cancel()
            server.close()
            assertEquals(baseline, after, "open descriptors after $rounds server-initiated closes")
        }

    @Test
    fun rejectedHandshakeReleasesTheDescriptor() =
        runRealTimeTest {
            val server = ServerSocket.allocate()
            val serving =
                serve(server) { client, _ ->
                    client.write(text("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"), 5.seconds)
                    try {
                        while (client.read(5.seconds) is ReadResult.Data) delay(10)
                    } catch (_: Exception) {
                    }
                }
            delay(200)
            val port = server.port()
            val baseline = openDescriptorCount()
            repeat(rounds) {
                assertFailsWith<WebSocketException> { connectTcpWebSocket(optionsFor(port)) }
            }
            settle()
            val after = openDescriptorCount()
            serving.cancel()
            server.close()
            assertEquals(baseline, after, "open descriptors after $rounds rejected handshakes")
        }
}
