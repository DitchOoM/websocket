package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase10MiscTests {
    @Test
    fun category10() =
        runTestNoTimeSkipping {
            echoMessageAndClose(301)
        }
}
