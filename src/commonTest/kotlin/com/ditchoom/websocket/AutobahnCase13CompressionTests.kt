package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase13CompressionTests {
    @Test
    fun category13_1_a() =
        runHeavyCompressionTest {
            for (case in 392..397) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category13_1_b() =
        runHeavyCompressionTest {
            for (case in 398..403) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category13_1_c() =
        runHeavyCompressionTest {
            for (case in 404..409) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category13_2_a() =
        runHeavyCompressionTest {
            for (case in 410..415) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category13_2_b() =
        runHeavyCompressionTest {
            for (case in 416..421) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category13_2_c() =
        runHeavyCompressionTest {
            for (case in 422..427) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    // Cases 13.3-13.6 (case numbers 428-499) require different compression parameter offers
    // that our client doesn't currently support.

    @Test
    fun category13_7_a() =
        runHeavyCompressionTest {
            for (case in 500..505) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category13_7_b() =
        runHeavyCompressionTest {
            for (case in 506..511) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category13_7_c() =
        runHeavyCompressionTest {
            for (case in 512..517) echoMessageAndClose(case, 1000, requestCompression = true)
        }
}
