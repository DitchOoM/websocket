package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase12CompressionTests {
    @Test
    fun category12_1_text_a() =
        runHeavyCompressionTest {
            for (case in 302..307) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category12_1_text_b() =
        runHeavyCompressionTest {
            for (case in 308..313) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category12_1_text_c() =
        runHeavyCompressionTest {
            for (case in 314..319) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category12_2_binary_a() =
        runHeavyCompressionTest {
            for (case in 320..325) echoBinaryMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category12_2_binary_b() =
        runHeavyCompressionTest {
            for (case in 326..331) echoBinaryMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category12_2_binary_c() =
        runHeavyCompressionTest {
            for (case in 332..337) echoBinaryMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category12_3_binary_a() =
        runHeavyCompressionTest {
            for (case in 338..343) echoBinaryMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category12_3_binary_b() =
        runHeavyCompressionTest {
            for (case in 344..349) echoBinaryMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category12_3_binary_c() =
        runHeavyCompressionTest {
            for (case in 350..355) echoBinaryMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category12_4_text_a() =
        runHeavyCompressionTest {
            for (case in 356..361) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category12_4_text_b() =
        runHeavyCompressionTest {
            for (case in 362..367) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category12_4_text_c() =
        runHeavyCompressionTest {
            for (case in 368..373) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category12_5_binary_a() =
        runHeavyCompressionTest {
            for (case in 374..379) echoBinaryMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category12_5_binary_b() =
        runHeavyCompressionTest {
            for (case in 380..385) echoBinaryMessageAndClose(case, 1000, requestCompression = true)
        }

    @Test
    fun category12_5_binary_c() =
        runHeavyCompressionTest {
            for (case in 386..391) echoBinaryMessageAndClose(case, 1000, requestCompression = true)
        }
}
