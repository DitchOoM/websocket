package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase3Utf8Tests {
    @Test
    fun case3_1() =
        runTestNoTimeSkipping {
            prepareConnection(28)
        }

    @Test
    fun case3_2() =
        runTestNoTimeSkipping {
            prepareConnection(29)
        }

    @Test
    fun case3_3() =
        runTestNoTimeSkipping {
            prepareConnection(30)
        }

    @Test
    fun case3_4() =
        runTestNoTimeSkipping {
            prepareConnection(31)
        }

    @Test
    fun case3_5() =
        runTestNoTimeSkipping {
            prepareConnection(32)
        }

    @Test
    fun case3_6() =
        runTestNoTimeSkipping {
            prepareConnection(33)
        }

    @Test
    fun case3_7() =
        runTestNoTimeSkipping {
            prepareConnection(34)
        }
}
