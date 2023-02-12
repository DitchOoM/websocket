package com.ditchoom.websocket

import com.ditchoom.buffer.ReadBuffer

sealed class DataRead {
    class Ping(val buffer: ReadBuffer) : DataRead()
    class Pong(val buffer: ReadBuffer) : DataRead()
    class BinaryDataRead(val data: ReadBuffer) : DataRead()
    class StringDataRead(val string: String) : DataRead()
}
