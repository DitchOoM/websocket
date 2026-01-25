package com.ditchoom.websocket

import kotlinx.coroutines.delay
import kotlin.test.Test

class AutobahnCase4ReservedOpcodeTests {
    @Test
    fun case4_1_1() =
        runTestNoTimeSkipping {
            prepareConnection(35)
        }

    @Test
    fun case4_1_2() =
        runTestNoTimeSkipping {
            prepareConnection(36)
        }

    @Test
    fun case4_1_3() =
        runTestNoTimeSkipping {
            prepareConnection(37)
        }

    @Test
    fun case4_1_4() =
        runTestNoTimeSkipping {
            prepareConnection(38)
        }

    @Test
    fun case4_1_5() =
        runTestNoTimeSkipping {
            prepareConnection(39)
        }

    @Test
    fun case4_2_1() =
        runTestNoTimeSkipping {
            prepareConnection(40)
        }

    @Test
    fun case4_2_2() =
        runTestNoTimeSkipping {
            prepareConnection(41)
        }

    @Test
    fun case4_2_3() =
        runTestNoTimeSkipping {
            prepareConnection(42)
        }

    @Test
    fun case4_2_4() =
        runTestNoTimeSkipping {
            prepareConnection(43)
        }

    @Test
    fun case4_2_5() =
        runTestNoTimeSkipping {
            prepareConnection(44)
            delay(100)
        }
}
