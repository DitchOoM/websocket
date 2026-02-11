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

    // 13.3: server sets client_max_window_bits=9, no context takeover flags.
    // Client offers client_max_window_bits (no value = willingness to accept any limit).
    // Server responds with client_max_window_bits=9. Both sides maintain LZ77 context.
    @Test
    fun category13_3_a() =
        runHeavyCompressionTest {
            val opts = CompressionOptions(clientNoContextTakeover = false, serverNoContextTakeover = false, clientMaxWindowBits = -1)
            for (case in 428..433) echoMessageAndClose(case, 1000, requestCompression = true, compressionOptions = opts)
        }

    @Test
    fun category13_3_b() =
        runHeavyCompressionTest {
            val opts = CompressionOptions(clientNoContextTakeover = false, serverNoContextTakeover = false, clientMaxWindowBits = -1)
            for (case in 434..439) echoMessageAndClose(case, 1000, requestCompression = true, compressionOptions = opts)
        }

    @Test
    fun category13_3_c() =
        runHeavyCompressionTest {
            val opts = CompressionOptions(clientNoContextTakeover = false, serverNoContextTakeover = false, clientMaxWindowBits = -1)
            for (case in 440..445) echoMessageAndClose(case, 1000, requestCompression = true, compressionOptions = opts)
        }

    // 13.4: server sets client_max_window_bits=15, no context takeover flags.
    @Test
    fun category13_4_a() =
        runHeavyCompressionTest {
            val opts = CompressionOptions(clientNoContextTakeover = false, serverNoContextTakeover = false, clientMaxWindowBits = -1)
            for (case in 446..451) echoMessageAndClose(case, 1000, requestCompression = true, compressionOptions = opts)
        }

    @Test
    fun category13_4_b() =
        runHeavyCompressionTest {
            val opts = CompressionOptions(clientNoContextTakeover = false, serverNoContextTakeover = false, clientMaxWindowBits = -1)
            for (case in 452..457) echoMessageAndClose(case, 1000, requestCompression = true, compressionOptions = opts)
        }

    @Test
    fun category13_4_c() =
        runHeavyCompressionTest {
            val opts = CompressionOptions(clientNoContextTakeover = false, serverNoContextTakeover = false, clientMaxWindowBits = -1)
            for (case in 458..463) echoMessageAndClose(case, 1000, requestCompression = true, compressionOptions = opts)
        }

    // 13.5: server sets client_max_window_bits=9 + both context takeover flags.
    @Test
    fun category13_5_a() =
        runHeavyCompressionTest {
            val opts = CompressionOptions(clientMaxWindowBits = -1)
            for (case in 464..469) echoMessageAndClose(case, 1000, requestCompression = true, compressionOptions = opts)
        }

    @Test
    fun category13_5_b() =
        runHeavyCompressionTest {
            val opts = CompressionOptions(clientMaxWindowBits = -1)
            for (case in 470..475) echoMessageAndClose(case, 1000, requestCompression = true, compressionOptions = opts)
        }

    @Test
    fun category13_5_c() =
        runHeavyCompressionTest {
            val opts = CompressionOptions(clientMaxWindowBits = -1)
            for (case in 476..481) echoMessageAndClose(case, 1000, requestCompression = true, compressionOptions = opts)
        }

    // 13.6: server sets client_max_window_bits=15 + both context takeover flags.
    @Test
    fun category13_6_a() =
        runHeavyCompressionTest {
            val opts = CompressionOptions(clientMaxWindowBits = -1)
            for (case in 482..487) echoMessageAndClose(case, 1000, requestCompression = true, compressionOptions = opts)
        }

    @Test
    fun category13_6_b() =
        runHeavyCompressionTest {
            val opts = CompressionOptions(clientMaxWindowBits = -1)
            for (case in 488..493) echoMessageAndClose(case, 1000, requestCompression = true, compressionOptions = opts)
        }

    @Test
    fun category13_6_c() =
        runHeavyCompressionTest {
            val opts = CompressionOptions(clientMaxWindowBits = -1)
            for (case in 494..499) echoMessageAndClose(case, 1000, requestCompression = true, compressionOptions = opts)
        }

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
