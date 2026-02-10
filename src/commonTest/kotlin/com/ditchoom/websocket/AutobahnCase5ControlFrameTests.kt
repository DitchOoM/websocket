package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase5ControlFrameTests {
    @Test
    fun category5() =
        runTestNoTimeSkipping {
            // 5.1-5.2: Fragmented control frames (protocol error)
            prepareConnection(45)
            prepareConnection(46)
            // 5.3-5.5: Text fragmentation
            echoMessageAndClose(47)
            echoMessageAndClose(48)
            echoMessageAndClose(49)
            // 5.6-5.7: Fragmented text with interleaved Ping
            echoMessageWhenFoundText(50)
            echoMessageWhenFoundText(51)
            // 5.8: Text fragmentation (octet-wise)
            echoMessageAndClose(52)
            // 5.9-5.18: Protocol errors (spurious continuation, etc.)
            for (case in 53..62) closeConnection(prepareConnection(case, awaitClose = false))
            // 5.19-5.20: Multi-fragment with interleaved Pings
            echoMessageAndClose(63)
            echoMessageAndClose(64)
        }
}
