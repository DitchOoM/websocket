package com.ditchoom.websocket

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.ARRAYBUFFER
import org.w3c.dom.BinaryType
import org.w3c.dom.WebSocket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

class BrowserWebSocketController(
    connectionOptions: WebSocketConnectionOptions,
) : WebSocketClient {
    private val closeMutex = Mutex(true)
    private val url = connectionOptions.buildUrl()
    private val webSocket: WebSocket = if (connectionOptions.protocols.isNotEmpty()) {
        WebSocket(url, connectionOptions.protocols.first())
    } else {
        WebSocket(url)
    }

    private var isConnected = false
    private val incomingFlow = callbackFlow {
        webSocket.onmessage = {
            when (val data = it.data) {
                is ArrayBuffer -> {
                    val array = Uint8Array(data)
                    val buffer = JsBuffer(array)
                    buffer.setLimit(array.length)
                    buffer.setPosition(array.length)
                    check(trySend(DataRead.BinaryDataRead(buffer)).isSuccess) {
                        "failed to emit incoming message"
                    }
                }

                is String -> check(trySend(DataRead.StringDataRead(data)).isSuccess) {
                    "failed to emit incoming message"
                }

                else -> throw IllegalArgumentException("Received invalid message type!")
            }
        }
        closeMutex.lock()
        channel.close()
    }

    init {
        webSocket.binaryType = BinaryType.ARRAYBUFFER
    }

    override fun isOpen() = isConnected
    override suspend fun localPort(): Int = throw UnsupportedOperationException("Unavailable on browser")
    override suspend fun remotePort(): Int = throw UnsupportedOperationException("Unavailable on browser")

    override suspend fun connect() = suspendCancellableCoroutine { continuation ->
        webSocket.onclose = {
            isConnected = false
            console.error("\r\nonclose $it")
            if (!continuation.isCompleted) {
                continuation.resumeWithException(Exception(it.toString()))
            }
            closeInternal()
            closeMutex.unlock()
        }
        webSocket.onerror = {
            isConnected = false
            console.error("\r\nws error", it)
        }
        webSocket.onopen = { event ->
            isConnected = true
            continuation.resume(Unit)
        }
        continuation.invokeOnCancellation {
            webSocket.close(1000)
        }
    }

    override fun readFlow(timeout: Duration) = incomingFlow.mapNotNull {
        (it as? DataRead.BinaryDataRead)?.data
    }

    override suspend fun read(): DataRead = incomingFlow.first()

    override suspend fun read(timeout: Duration): ReadBuffer =
        incomingFlow.filterIsInstance<DataRead.BinaryDataRead>().first().data

    override suspend fun write(string: String) {
        webSocket.send(string)
    }

    override suspend fun write(buffer: ReadBuffer) {
        val arrayBuffer = (buffer as JsBuffer).buffer.buffer
        webSocket.send(arrayBuffer)
    }

    override suspend fun write(buffer: ReadBuffer, timeout: Duration): Int {
        val arrayBuffer = (buffer as JsBuffer).buffer.buffer
        val startBufferAmount = webSocket.bufferedAmount.toInt()
        webSocket.send(arrayBuffer)
        return webSocket.bufferedAmount.toInt() - startBufferAmount
    }

    override suspend fun isPingSupported(): Boolean = false
    override suspend fun ping(payloadData: ReadBuffer) { /*Not surfaced on browser*/
    }

    override suspend fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        webSocket.close()
    }
}
