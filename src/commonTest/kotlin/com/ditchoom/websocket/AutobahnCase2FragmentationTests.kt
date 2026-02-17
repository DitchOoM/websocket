package com.ditchoom.websocket

import kotlin.test.Test

/**
 * Autobahn Category 2: Ping/Pong tests.
 *
 * The server sends Ping frames; the client framework auto-responds with Pong.
 * No text/binary data messages are exchanged — just connect and let the
 * framework handle pings, then the server closes the connection.
 */
class AutobahnCase2FragmentationTests {
    @Test
    fun category2() =
        runTestNoTimeSkipping {
            for (case in 17..27) prepareConnection(case)
        }
}
