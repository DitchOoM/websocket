package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase1PayloadTests {
    @Test
    fun case1_1_1() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(1)
            sendMessageWithPayloadLengthOf(ws, 0)
            closeConnection(ws)
        }

    @Test
    fun case1_1_2() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(2)
            sendMessageWithPayloadLengthOf(ws, 125)
            closeConnection(ws)
        }

    @Test
    fun case1_1_3() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(3)
            sendMessageWithPayloadLengthOf(ws, 126)
            closeConnection(ws)
        }

    @Test
    fun case1_1_4() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(4)
            sendMessageWithPayloadLengthOf(ws, 127)
            closeConnection(ws)
        }

    @Test
    fun case1_1_5() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(5)
            sendMessageWithPayloadLengthOf(ws, 128)
            closeConnection(ws)
        }

    @Test
    fun case1_1_6() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(6)
            sendMessageWithPayloadLengthOf(ws, 65535)
            closeConnection(ws)
        }

    @Test
    fun case1_1_7() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(7)
            sendMessageWithPayloadLengthOf(ws, 65536)
            closeConnection(ws)
        }

    @Test
    fun case1_1_8() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(8)
            sendMessageWithPayloadLengthOf(ws, 65536)
            closeConnection(ws)
        }

    @Test
    fun case1_2_1() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(9)
            sendBinaryWithPayloadLengthOf(ws, 0)
            closeConnection(ws)
        }

    @Test
    fun case1_2_2() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(10)
            sendBinaryWithPayloadLengthOf(ws, 125)
            closeConnection(ws)
        }

    @Test
    fun case1_2_3() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(11)
            sendBinaryWithPayloadLengthOf(ws, 126)
            closeConnection(ws)
        }

    @Test
    fun case1_2_4() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(12)
            sendBinaryWithPayloadLengthOf(ws, 127)
            closeConnection(ws)
        }

    @Test
    fun case1_2_5() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(13)
            sendBinaryWithPayloadLengthOf(ws, 128)
            closeConnection(ws)
        }

    @Test
    fun case1_2_6() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(14)
            sendBinaryWithPayloadLengthOf(ws, 65535)
            closeConnection(ws)
        }

    @Test
    fun case1_2_7() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(15)
            sendBinaryWithPayloadLengthOf(ws, 65536)
            closeConnection(ws)
        }

    @Test
    fun case1_2_8() =
        runTestNoTimeSkipping {
            val ws = prepareConnection(16)
            sendBinaryWithPayloadLengthOf(ws, 65536)
            closeConnection(ws)
        }
}
