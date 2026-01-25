package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase10MiscTests {
    @Test
    fun case10_1_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(301)
        }
}
