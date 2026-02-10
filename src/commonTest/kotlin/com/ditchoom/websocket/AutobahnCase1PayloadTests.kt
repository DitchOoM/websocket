package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase1PayloadTests {
    @Test
    fun category1_text() =
        runTestNoTimeSkipping {
            for (case in 1..8) echoMessageAndClose(case)
        }

    @Test
    fun category1_binary() =
        runTestNoTimeSkipping {
            for (case in 9..16) echoBinaryMessageAndClose(case)
        }
}
