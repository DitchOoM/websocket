package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase2FragmentationTests {
    @Test
    fun case2_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(17)
        }

    @Test
    fun case2_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(18)
        }

    @Test
    fun case2_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(19)
        }

    @Test
    fun case2_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(20)
        }

    @Test
    fun case2_5() =
        runTestNoTimeSkipping {
            // Case 21: Server sends invalid ping (payload > 125 bytes)
            // Client should reject with close code 1002 - just connect and wait for close
            prepareConnection(21)
        }

    @Test
    fun case2_6() =
        runTestNoTimeSkipping {
            echoMessageAndClose(22)
        }

    @Test
    fun case2_7() =
        runTestNoTimeSkipping {
            echoMessageAndClose(23)
        }

    @Test
    fun case2_8() =
        runTestNoTimeSkipping {
            echoMessageAndClose(24)
        }

    @Test
    fun case2_9() =
        runTestNoTimeSkipping {
            echoMessageAndClose(25)
        }

    @Test
    fun case2_10() =
        runTestNoTimeSkipping {
            echoMessageAndClose(26)
        }

    @Test
    fun case2_11() =
        runTestNoTimeSkipping {
            echoMessageAndClose(27)
        }
}
