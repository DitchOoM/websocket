package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase1PayloadTests {
    // Category 1.1: Text message payloads of various sizes
    // Server sends text, client echoes it back

    @Test
    fun case1_1_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(1)
        }

    @Test
    fun case1_1_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(2)
        }

    @Test
    fun case1_1_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(3)
        }

    @Test
    fun case1_1_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(4)
        }

    @Test
    fun case1_1_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(5)
        }

    @Test
    fun case1_1_6() =
        runTestNoTimeSkipping {
            echoMessageAndClose(6)
        }

    @Test
    fun case1_1_7() =
        runTestNoTimeSkipping {
            echoMessageAndClose(7)
        }

    @Test
    fun case1_1_8() =
        runTestNoTimeSkipping {
            echoMessageAndClose(8)
        }

    // Category 1.2: Binary message payloads of various sizes
    // Server sends binary, client echoes it back

    @Test
    fun case1_2_1() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(9)
        }

    @Test
    fun case1_2_2() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(10)
        }

    @Test
    fun case1_2_3() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(11)
        }

    @Test
    fun case1_2_4() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(12)
        }

    @Test
    fun case1_2_5() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(13)
        }

    @Test
    fun case1_2_6() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(14)
        }

    @Test
    fun case1_2_7() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(15)
        }

    @Test
    fun case1_2_8() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(16)
        }
}
