package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase9CompressionTests {
    @Test
    fun case9_1_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(247, requestCompression = true)
        }

    @Test
    fun case9_1_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(248, requestCompression = true)
        }

    @Test
    fun case9_1_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(249, requestCompression = true)
        }

    @Test
    fun case9_1_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(250, requestCompression = true)
        }

    @Test
    fun case9_1_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(251, requestCompression = true)
        }

    @Test
    fun case9_1_6() =
        runTestNoTimeSkipping {
            echoMessageAndClose(252, requestCompression = true)
        }

    @Test
    fun case9_2_1() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(253, requestCompression = true)
        }

    @Test
    fun case9_2_2() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(254, requestCompression = true)
        }

    @Test
    fun case9_2_3() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(255, requestCompression = true)
        }

    @Test
    fun case9_2_4() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(256, requestCompression = true)
        }

    @Test
    fun case9_2_5() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(257, requestCompression = true)
        }

    @Test
    fun case9_2_6() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(258, requestCompression = true)
        }

    @Test
    fun case9_3_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(259, requestCompression = true)
        }

    @Test
    fun case9_3_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(260, requestCompression = true)
        }

    @Test
    fun case9_3_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(261, requestCompression = true)
        }

    @Test
    fun case9_3_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(262, requestCompression = true)
        }

    @Test
    fun case9_3_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(263, requestCompression = true)
        }

    @Test
    fun case9_3_6() =
        runTestNoTimeSkipping {
            echoMessageAndClose(264, requestCompression = true)
        }

    @Test
    fun case9_3_7() =
        runTestNoTimeSkipping {
            echoMessageAndClose(265, requestCompression = true)
        }

    @Test
    fun case9_3_8() =
        runTestNoTimeSkipping {
            echoMessageAndClose(266, requestCompression = true)
        }

    @Test
    fun case9_3_9() =
        runTestNoTimeSkipping {
            echoMessageAndClose(267, requestCompression = true)
        }

    @Test
    fun case9_4_1() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(268, requestCompression = true)
        }

    @Test
    fun case9_4_2() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(269, requestCompression = true)
        }

    @Test
    fun case9_4_3() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(270, requestCompression = true)
        }

    @Test
    fun case9_4_4() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(271, requestCompression = true)
        }

    @Test
    fun case9_4_5() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(272, requestCompression = true)
        }

    @Test
    fun case9_4_6() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(273, requestCompression = true)
        }

    @Test
    fun case9_4_7() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(274, requestCompression = true)
        }

    @Test
    fun case9_4_8() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(275, requestCompression = true)
        }

    @Test
    fun case9_4_9() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(276, requestCompression = true)
        }

    @Test
    fun case9_5_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(277, requestCompression = true)
        }

    @Test
    fun case9_5_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(278, requestCompression = true)
        }

    @Test
    fun case9_5_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(279, requestCompression = true)
        }

    @Test
    fun case9_5_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(280, requestCompression = true)
        }

    @Test
    fun case9_5_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(281, requestCompression = true)
        }

    @Test
    fun case9_5_6() =
        runTestNoTimeSkipping {
            echoMessageAndClose(282, requestCompression = true)
        }

    @Test
    fun case9_6_1() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(283, requestCompression = true)
        }

    @Test
    fun case9_6_2() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(284, requestCompression = true)
        }

    @Test
    fun case9_6_3() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(285, requestCompression = true)
        }

    @Test
    fun case9_6_4() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(286, requestCompression = true)
        }

    @Test
    fun case9_6_5() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(287, requestCompression = true)
        }

    @Test
    fun case9_6_6() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(288, requestCompression = true)
        }

    @Test
    fun case9_7_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(289, 1000, requestCompression = true)
        }

    @Test
    fun case9_7_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(290, 1000, requestCompression = true)
        }

    @Test
    fun case9_7_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(291, 1000, requestCompression = true)
        }

    @Test
    fun case9_7_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(292, 1000, requestCompression = true)
        }

    @Test
    fun case9_7_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(293, 1000, requestCompression = true)
        }

    @Test
    fun case9_7_6() =
        runTestNoTimeSkipping {
            echoMessageAndClose(294, 1000, requestCompression = true)
        }

    @Test
    fun case9_8_1() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(295, 1000, requestCompression = true)
        }

    @Test
    fun case9_8_2() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(296, 1000, requestCompression = true)
        }

    @Test
    fun case9_8_3() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(297, 1000, requestCompression = true)
        }

    @Test
    fun case9_8_4() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(298, 1000, requestCompression = true)
        }

    @Test
    fun case9_8_5() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(299, 1000, requestCompression = true)
        }

    @Test
    fun case9_8_6() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(300, 1000, requestCompression = true)
        }
}
