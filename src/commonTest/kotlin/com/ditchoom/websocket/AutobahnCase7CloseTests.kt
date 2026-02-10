package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase7CloseTests {
    @Test
    fun category7() =
        runTestNoTimeSkipping {
            // 7.1.1: Echo message then close
            echoMessageAndClose(210)
            // 7.1.2-7.1.6: Server-driven close scenarios
            for (case in 211..215) prepareConnection(case)
            // 7.3.1: Client-initiated close (no code)
            closeConnection(prepareConnection(216, awaitClose = false))
            // 7.3.2: Invalid 1-byte close payload
            prepareConnection(217)
            // 7.3.3-7.3.5: Client-initiated close with code/reason
            for (case in 218..220) closeConnection(prepareConnection(case, awaitClose = false))
            // 7.3.6: Oversized close payload
            prepareConnection(221)
            // 7.5: Invalid UTF-8 in close reason
            prepareConnection(222)
            // 7.7: Valid close codes
            for (case in 223..235) prepareConnection(case)
            // 7.9: Invalid close codes
            for (case in 236..244) prepareConnection(case)
            // 7.13: Out-of-range close codes
            for (case in 245..246) prepareConnection(case)
        }
}
