package com.ditchoom.websocket

import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class AutobahnCase9CompressionTests {
    // Extended timeout: ~20 cases echoing up to 4MB compressed messages
    @Test
    fun category9_text() =
        runTestNoTimeSkipping(timeout = 120.seconds) {
            // 9.1: Large text (single message echo)
            for (case in 247..252) echoMessageAndClose(case, requestCompression = true)
            // 9.3: Fragmented text 4MB
            for (case in 259..267) echoMessageAndClose(case, requestCompression = true)
            // 9.5: Text 1MB, varying chop sizes
            for (case in 277..282) echoMessageAndClose(case, requestCompression = true)
        }

    // Extended timeout: ~20 cases echoing up to 4MB compressed messages
    @Test
    fun category9_binary() =
        runTestNoTimeSkipping(timeout = 120.seconds) {
            // 9.2: Large binary (single message echo)
            for (case in 253..258) echoBinaryMessageAndClose(case, requestCompression = true)
            // 9.4: Fragmented binary 4MB
            for (case in 268..276) echoBinaryMessageAndClose(case, requestCompression = true)
            // 9.6: Binary 1MB, varying chop sizes
            for (case in 283..288) echoBinaryMessageAndClose(case, requestCompression = true)
        }

    @Test
    fun category9_text_latency() =
        runHeavyCompressionTest {
            // 9.7: 1000 text messages, varying sizes
            for (case in 289..294) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category9_binary_latency() =
        runHeavyCompressionTest {
            // 9.8: 1000 binary messages, varying sizes
            for (case in 295..300) echoBinaryMessageAndClose(case, 1000, requestCompression = true)
        }
}
