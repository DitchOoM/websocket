package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase3Utf8Tests {
    @Test
    fun category3() =
        runTestNoTimeSkipping {
            for (case in 28..34) prepareConnection(case)
        }
}
