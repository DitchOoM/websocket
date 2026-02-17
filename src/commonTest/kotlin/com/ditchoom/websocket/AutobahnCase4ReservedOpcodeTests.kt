package com.ditchoom.websocket

import kotlinx.coroutines.delay
import kotlin.test.Test

class AutobahnCase4ReservedOpcodeTests {
    @Test
    fun category4() =
        runTestNoTimeSkipping {
            for (case in 35..44) {
                prepareConnection(case)
            }
            delay(100)
        }
}
