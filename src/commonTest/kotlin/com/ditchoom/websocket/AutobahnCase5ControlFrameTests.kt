package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase5ControlFrameTests {
    @Test
    fun case5_1() =
        runTestNoTimeSkipping {
            prepareConnection(45)
        }

    @Test
    fun case5_2() =
        runTestNoTimeSkipping {
            prepareConnection(46)
        }

    @Test
    fun case5_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(47)
        }

    @Test
    fun case5_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(48)
        }

    @Test
    fun case5_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(49)
        }

    @Test
    fun case5_6() =
        runTestNoTimeSkipping {
            echoMessageWhenFoundText(50)
        }

    @Test
    fun case5_7() =
        runTestNoTimeSkipping {
            echoMessageWhenFoundText(51)
        }

    @Test
    fun case5_8() =
        runTestNoTimeSkipping {
            echoMessageWhenFoundText(52)
        }

    @Test
    fun case5_9() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(53, awaitClose = false))
        }

    @Test
    fun case5_10() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(54, awaitClose = false))
        }

    @Test
    fun case5_11() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(55, awaitClose = false))
        }

    @Test
    fun case5_12() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(56, awaitClose = false))
        }

    @Test
    fun case5_13() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(57, awaitClose = false))
        }

    @Test
    fun case5_14() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(58, awaitClose = false))
        }

    @Test
    fun case5_15() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(59, awaitClose = false))
        }

    @Test
    fun case5_16() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(60, awaitClose = false))
        }

    @Test
    fun case5_17() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(61, awaitClose = false))
        }

    @Test
    fun case5_18() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(62, awaitClose = false))
        }

    @Test
    fun case5_19() =
        runTestNoTimeSkipping {
            echoMessageWhenFoundText(63)
        }

    @Test
    fun case5_20() =
        runTestNoTimeSkipping {
            echoMessageWhenFoundText(64)
        }
}
