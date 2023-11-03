package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.data.Reader
import com.ditchoom.socket.SuspendingSocketInputStream
import kotlin.time.Duration

class SuspendingSocketInputStreamWithPreBuffer(
    readTimeout: Duration,
    reader: Reader
) {
    var preBuffer: ReadBuffer? = null
    private val suspendingSocketInputStream = SuspendingSocketInputStream(readTimeout, reader)

    suspend fun readByte(): Byte {
        val preBuffer = preBuffer
        if (preBuffer?.hasRemaining() == true) {
            return preBuffer.readByte()
        }
        this.preBuffer = null
        return suspendingSocketInputStream.readByte()
    }

    suspend fun readBuffer(size: Int): ReadBuffer {
        // TODO: Clearly we can improve performance here
        val preBuffer = preBuffer
        val returnBuffer = PlatformBuffer.allocate(size, AllocationZone.Direct)
        while (returnBuffer.hasRemaining()) {
            if (preBuffer?.hasRemaining() == true) {
                returnBuffer.writeByte(preBuffer.readByte())
            } else {
                this.preBuffer = null
                returnBuffer.writeByte(readByte())
            }
        }
        returnBuffer.resetForRead()
        return returnBuffer
    }

}