package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase7CloseTests {
    @Test
    fun case7_1_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(210)
        }

    @Test
    fun case7_1_2() =
        runTestNoTimeSkipping {
            prepareConnection(211)
        }

    @Test
    fun case7_1_3() =
        runTestNoTimeSkipping {
            prepareConnection(212)
        }

    @Test
    fun case7_1_4() =
        runTestNoTimeSkipping {
            prepareConnection(213)
        }

    @Test
    fun case7_1_5() =
        runTestNoTimeSkipping {
            prepareConnection(214)
        }

    @Test
    fun case7_1_6() =
        runTestNoTimeSkipping {
            prepareConnection(215)
        }

    @Test
    fun case7_3_1() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(216))
        }

    @Test
    fun case7_3_2() =
        runTestNoTimeSkipping {
            prepareConnection(217)
        }

    @Test
    fun case7_3_3() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(218))
        }

    @Test
    fun case7_3_4() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(219))
        }

    @Test
    fun case7_3_5() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(220))
        }

    @Test
    fun case7_3_6() =
        runTestNoTimeSkipping {
            prepareConnection(221)
        }

    @Test
    fun case7_5() =
        runTestNoTimeSkipping {
            prepareConnection(222)
        }

    @Test
    fun case7_7_1() =
        runTestNoTimeSkipping {
            prepareConnection(223)
        }

    @Test
    fun case7_7_2() =
        runTestNoTimeSkipping {
            prepareConnection(224)
        }

    @Test
    fun case7_7_3() =
        runTestNoTimeSkipping {
            prepareConnection(225)
        }

    @Test
    fun case7_7_4() =
        runTestNoTimeSkipping {
            prepareConnection(226)
        }

    @Test
    fun case7_7_5() =
        runTestNoTimeSkipping {
            prepareConnection(227)
        }

    @Test
    fun case7_7_6() =
        runTestNoTimeSkipping {
            prepareConnection(228)
        }

    @Test
    fun case7_7_7() =
        runTestNoTimeSkipping {
            prepareConnection(229)
        }

    @Test
    fun case7_7_8() =
        runTestNoTimeSkipping {
            prepareConnection(230)
        }

    @Test
    fun case7_7_9() =
        runTestNoTimeSkipping {
            prepareConnection(231)
        }

    @Test
    fun case7_7_10() =
        runTestNoTimeSkipping {
            prepareConnection(232)
        }

    @Test
    fun case7_7_11() =
        runTestNoTimeSkipping {
            prepareConnection(233)
        }

    @Test
    fun case7_7_12() =
        runTestNoTimeSkipping {
            prepareConnection(234)
        }

    @Test
    fun case7_7_13() =
        runTestNoTimeSkipping {
            prepareConnection(235)
        }

    @Test
    fun case7_9_1() =
        runTestNoTimeSkipping {
            prepareConnection(236)
        }

    @Test
    fun case7_9_2() =
        runTestNoTimeSkipping {
            prepareConnection(237)
        }

    @Test
    fun case7_9_3() =
        runTestNoTimeSkipping {
            prepareConnection(238)
        }

    @Test
    fun case7_9_4() =
        runTestNoTimeSkipping {
            prepareConnection(239)
        }

    @Test
    fun case7_9_5() =
        runTestNoTimeSkipping {
            prepareConnection(240)
        }

    @Test
    fun case7_9_6() =
        runTestNoTimeSkipping {
            prepareConnection(241)
        }

    @Test
    fun case7_9_7() =
        runTestNoTimeSkipping {
            prepareConnection(242)
        }

    @Test
    fun case7_9_8() =
        runTestNoTimeSkipping {
            prepareConnection(243)
        }

    @Test
    fun case7_9_9() =
        runTestNoTimeSkipping {
            prepareConnection(244)
        }

    @Test
    fun case7_13_1() =
        runTestNoTimeSkipping {
            prepareConnection(245)
        }

    @Test
    fun case7_13_2() =
        runTestNoTimeSkipping {
            prepareConnection(246)
        }
}
