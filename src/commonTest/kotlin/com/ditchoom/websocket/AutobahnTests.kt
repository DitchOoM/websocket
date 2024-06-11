package com.ditchoom.websocket

import agentName
import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.allocate
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test

@Ignore
class AutobahnTests {
    @Test
    fun case1_1_1() =
        runTestNoTimeSkipping {
            val webSocketClient = prepareConnection(1)
            sendMessageWithPayloadLengthOf(webSocketClient, 0)
            closeConnection(webSocketClient)
        }

    @Test
    fun case1_1_2() =
        runTestNoTimeSkipping {
            val webSocket1Client = prepareConnection(2)
            sendMessageWithPayloadLengthOf(webSocket1Client, 125)
            closeConnection(webSocket1Client)
        }

    @Test
    fun case1_1_3() =
        runTestNoTimeSkipping {
            val webSocket3Client = prepareConnection(3)
            sendMessageWithPayloadLengthOf(webSocket3Client, 126)
            closeConnection(webSocket3Client)
        }

    @Test
    fun case1_1_4() =
        runTestNoTimeSkipping {
            val webSocket4Client = prepareConnection(4)
            sendMessageWithPayloadLengthOf(webSocket4Client, 127)
            closeConnection(webSocket4Client)
        }

    @Test
    fun case1_1_5() =
        runTestNoTimeSkipping {
            val webSocket5Client = prepareConnection(5)
            sendMessageWithPayloadLengthOf(webSocket5Client, 128)
            closeConnection(webSocket5Client)
        }

    @Test
    fun case1_1_6() =
        runTestNoTimeSkipping {
            val webSocket6Client = prepareConnection(6)
            sendMessageWithPayloadLengthOf(webSocket6Client, 65535)
            closeConnection(webSocket6Client)
        }

    @Test
    fun case1_1_7() =
        runTestNoTimeSkipping {
            val webSocket7Client = prepareConnection(7)
            sendMessageWithPayloadLengthOf(webSocket7Client, 65536)
            closeConnection(webSocket7Client)
        }

    @Test
    fun case1_1_8() =
        runTestNoTimeSkipping {
            val webSocket8Client = prepareConnection(8)
            sendMessageWithPayloadLengthOf(webSocket8Client, 65536)
            closeConnection(webSocket8Client)
        }

    @Test
    fun case1_2_1() =
        runTestNoTimeSkipping {
            val webSocketClient = prepareConnection(9)
            sendBinaryWithPayloadLengthOf(webSocketClient, 0)
            closeConnection(webSocketClient)
        }

    @Test
    fun case1_2_2() =
        runTestNoTimeSkipping {
            val webSocket1Client = prepareConnection(10)
            sendBinaryWithPayloadLengthOf(webSocket1Client, 125)
            closeConnection(webSocket1Client)
        }

    @Test
    fun case1_2_3() =
        runTestNoTimeSkipping {
            val webSocket3Client = prepareConnection(11)
            sendBinaryWithPayloadLengthOf(webSocket3Client, 126)
            closeConnection(webSocket3Client)
        }

    @Test
    fun case1_2_4() =
        runTestNoTimeSkipping {
            val webSocket4Client = prepareConnection(12)
            sendBinaryWithPayloadLengthOf(webSocket4Client, 127)
            closeConnection(webSocket4Client)
        }

    @Test
    fun case1_2_5() =
        runTestNoTimeSkipping {
            val webSocket5Client = prepareConnection(13)
            sendBinaryWithPayloadLengthOf(webSocket5Client, 128)
            closeConnection(webSocket5Client)
        }

    @Test
    fun case1_2_6() =
        runTestNoTimeSkipping {
            val webSocket6Client = prepareConnection(14)
            sendBinaryWithPayloadLengthOf(webSocket6Client, 65535)
            closeConnection(webSocket6Client)
        }

    @Test
    fun case1_2_7() =
        runTestNoTimeSkipping {
            val webSocket7Client = prepareConnection(15)
            sendBinaryWithPayloadLengthOf(webSocket7Client, 65536)
            closeConnection(webSocket7Client)
        }

    @Test
    fun case1_2_8() =
        runTestNoTimeSkipping {
            val webSocket8Client = prepareConnection(16)
            sendBinaryWithPayloadLengthOf(webSocket8Client, 65536)
            closeConnection(webSocket8Client)
        }

    @Test
    fun case2_1() =
        runTestNoTimeSkipping {
            prepareConnection(17)
        }

    @Test
    fun case2_2() =
        runTestNoTimeSkipping {
            prepareConnection(18)
        }

    @Test
    fun case2_3() =
        runTestNoTimeSkipping {
            prepareConnection(19)
        }

    @Test
    fun case2_4() =
        runTestNoTimeSkipping {
            prepareConnection(20)
        }

    @Test
    fun case2_5() =
        runTestNoTimeSkipping {
            prepareConnection(21)
        }

    @Test
    fun case2_6() =
        runTestNoTimeSkipping {
            prepareConnection(22)
        }

    @Test
    fun case2_7() =
        runTestNoTimeSkipping {
            prepareConnection(23)
        }

    @Test
    fun case2_8() =
        runTestNoTimeSkipping {
            prepareConnection(24)
        }

    @Test
    fun case2_9() =
        runTestNoTimeSkipping {
            prepareConnection(25)
        }

    @Test
    fun case2_10() =
        runTestNoTimeSkipping {
            prepareConnection(26)
        }

    @Test
    fun case2_11() =
        runTestNoTimeSkipping {
            prepareConnection(27)
        }

    @Test
    fun case3_1() =
        runTestNoTimeSkipping {
            prepareConnection(28)
        }

    @Test
    fun case3_2() =
        runTestNoTimeSkipping {
            prepareConnection(29)
        }

    @Test
    fun case3_3() =
        runTestNoTimeSkipping {
            prepareConnection(30)
        }

    @Test
    fun case3_4() =
        runTestNoTimeSkipping {
            prepareConnection(31)
        }

    @Test
    fun case3_5() =
        runTestNoTimeSkipping {
            prepareConnection(32)
        }

    @Test
    fun case3_6() =
        runTestNoTimeSkipping {
            prepareConnection(33)
        }

    @Test
    fun case3_7() =
        runTestNoTimeSkipping {
            prepareConnection(34)
        }

    @Test
    fun case4_1_1() =
        runTestNoTimeSkipping {
            prepareConnection(35)
        }

    @Test
    fun case4_1_2() =
        runTestNoTimeSkipping {
            prepareConnection(36)
        }

    @Test
    fun case4_1_3() =
        runTestNoTimeSkipping {
            prepareConnection(37)
        }

    @Test
    fun case4_1_4() =
        runTestNoTimeSkipping {
            prepareConnection(38)
        }

    @Test
    fun case4_1_5() =
        runTestNoTimeSkipping {
            prepareConnection(39)
        }

    @Test
    fun case4_2_1() =
        runTestNoTimeSkipping {
            prepareConnection(40)
        }

    @Test
    fun case4_2_2() =
        runTestNoTimeSkipping {
            prepareConnection(41)
        }

    @Test
    fun case4_2_3() =
        runTestNoTimeSkipping {
            prepareConnection(42)
        }

    @Test
    fun case4_2_4() =
        runTestNoTimeSkipping {
            prepareConnection(43)
        }

    @Test
    fun case4_2_5() =
        runTestNoTimeSkipping {
            prepareConnection(44)
            delay(100) // need to wait for the remote to close the connection
        }

    @Test
    fun case5_1() =
        runTestNoTimeSkipping {
            prepareConnection(45)
        }

    @Test
    fun case5_2() =
        runTestNoTimeSkipping {
            prepareConnection(46)
        }

    @Test
    fun case5_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(47)
        }

    @Test
    fun case5_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(48)
        }

    @Test
    fun case5_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(49)
        }

    @Test
    fun case5_6() =
        runTestNoTimeSkipping {
            echoMessageWhenFoundText(50)
        }

    @Test
    fun case5_7() =
        runTestNoTimeSkipping {
            echoMessageWhenFoundText(51)
        }

    @Test
    fun case5_8() =
        runTestNoTimeSkipping {
            echoMessageWhenFoundText(52)
        }

    @Test
    fun case5_9() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(53))
        }

    @Test
    fun case5_10() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(54))
        }

    @Test
    fun case5_11() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(55))
        }

    @Test
    fun case5_12() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(56))
        }

    @Test
    fun case5_13() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(57))
        }

    @Test
    fun case5_14() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(58))
        }

    @Test
    fun case5_15() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(59))
        }

    @Test
    fun case5_16() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(60))
        }

    @Test
    fun case5_17() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(61))
        }

    @Test
    fun case5_18() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(62))
        }

    @Test
    fun case5_19() =
        runTestNoTimeSkipping {
            echoMessageWhenFoundText(63)
        }

    @Test
    fun case5_20() =
        runTestNoTimeSkipping {
            echoMessageWhenFoundText(64)
        }

    @Test
    fun case6_1_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(65)
        }

    @Test
    fun case6_1_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(66)
        }

    @Test
    fun case6_1_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(67)
        }

    @Test
    fun case6_2_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(68)
        }

    @Test
    fun case6_2_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(69)
        }

    @Test
    fun case6_2_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(70)
        }

    @Test
    fun case6_2_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(71)
        }

    @Test
    fun case6_3_1() =
        runTestNoTimeSkipping {
            prepareConnection(72)
        }

    @Test
    fun case6_3_2() =
        runTestNoTimeSkipping {
            prepareConnection(73)
        }

    @Test
    fun case6_4_1() =
        runTestNoTimeSkipping {
            prepareConnection(74)
        }

    @Test
    fun case6_4_2() =
        runTestNoTimeSkipping {
            prepareConnection(75)
        }

    @Test
    fun case6_4_3() =
        runTestNoTimeSkipping {
            prepareConnection(76)
        }

    @Test
    fun case6_4_4() =
        runTestNoTimeSkipping {
            prepareConnection(77)
        }

    @Test
    fun case6_5_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(78)
        }

    @Test
    fun case6_5_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(79)
        }

    @Test
    fun case6_5_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(80)
        }

    @Test
    fun case6_5_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(81)
        }

    @Test
    fun case6_5_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(82)
        }

    @Test
    fun case6_6_1() =
        runTestNoTimeSkipping {
            prepareConnection(83)
        }

    @Test
    fun case6_6_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(84)
        }

    @Test
    fun case6_6_3() =
        runTestNoTimeSkipping {
            prepareConnection(85)
        }

    @Test
    fun case6_6_4() =
        runTestNoTimeSkipping {
            prepareConnection(86)
        }

    @Test
    fun case6_6_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(87)
        }

    @Test
    fun case6_6_6() =
        runTestNoTimeSkipping {
            prepareConnection(88)
        }

    @Test
    fun case6_6_7() =
        runTestNoTimeSkipping {
            echoMessageAndClose(89)
        }

    @Test
    fun case6_6_8() =
        runTestNoTimeSkipping {
            prepareConnection(90)
        }

    @Test
    fun case6_6_9() =
        runTestNoTimeSkipping {
            echoMessageAndClose(91)
        }

    @Test
    fun case6_6_10() =
        runTestNoTimeSkipping {
            prepareConnection(92)
        }

    @Test
    fun case6_6_11() =
        runTestNoTimeSkipping {
            echoMessageAndClose(93)
        }

    @Test
    fun case6_7_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(94)
        }

    @Test
    fun case6_7_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(95)
        }

    @Test
    fun case6_7_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(96)
        }

    @Test
    fun case6_7_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(97)
        }

    @Test
    fun case6_8_1() =
        runTestNoTimeSkipping {
            prepareConnection(98)
        }

    @Test
    fun case6_8_2() =
        runTestNoTimeSkipping {
            prepareConnection(99)
        }

    @Test
    fun case6_9_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(100)
        }

    @Test
    fun case6_9_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(101)
        }

    @Test
    fun case6_9_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(102)
        }

    @Test
    fun case6_9_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(103)
        }

    @Test
    fun case6_10_1() =
        runTestNoTimeSkipping {
            prepareConnection(104)
        }

    @Test
    fun case6_10_2() =
        runTestNoTimeSkipping {
            prepareConnection(105)
        }

    @Test
    fun case6_10_3() =
        runTestNoTimeSkipping {
            prepareConnection(106)
        }

    @Test
    fun case6_11_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(107)
        }

    @Test
    fun case6_11_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(108)
        }

    @Test
    fun case6_11_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(109)
        }

    @Test
    fun case6_11_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(110)
        }

    @Test
    fun case6_11_5() =
        runTestNoTimeSkipping {
            prepareConnection(111)
        }

    @Test
    fun case6_12_1() =
        runTestNoTimeSkipping {
            prepareConnection(112)
        }

    @Test
    fun case6_12_2() =
        runTestNoTimeSkipping {
            prepareConnection(113)
        }

    @Test
    fun case6_12_3() =
        runTestNoTimeSkipping {
            prepareConnection(114)
        }

    @Test
    fun case6_12_4() =
        runTestNoTimeSkipping {
            prepareConnection(115)
        }

    @Test
    fun case6_12_5() =
        runTestNoTimeSkipping {
            prepareConnection(116)
        }

    @Test
    fun case6_12_6() =
        runTestNoTimeSkipping {
            prepareConnection(117)
        }

    @Test
    fun case6_12_7() =
        runTestNoTimeSkipping {
            prepareConnection(118)
        }

    @Test
    fun case6_12_8() =
        runTestNoTimeSkipping {
            prepareConnection(119)
        }

    @Test
    fun case6_13_1() =
        runTestNoTimeSkipping {
            prepareConnection(120)
        }

    @Test
    fun case6_13_2() =
        runTestNoTimeSkipping {
            prepareConnection(121)
        }

    @Test
    fun case6_13_3() =
        runTestNoTimeSkipping {
            prepareConnection(122)
        }

    @Test
    fun case6_13_4() =
        runTestNoTimeSkipping {
            prepareConnection(123)
        }

    @Test
    fun case6_13_5() =
        runTestNoTimeSkipping {
            prepareConnection(124)
        }

    @Test
    fun case6_14_1() =
        runTestNoTimeSkipping {
            prepareConnection(125)
        }

    @Test
    fun case6_14_2() =
        runTestNoTimeSkipping {
            prepareConnection(126)
        }

    @Test
    fun case6_14_3() =
        runTestNoTimeSkipping {
            prepareConnection(127)
        }

    @Test
    fun case6_14_4() =
        runTestNoTimeSkipping {
            prepareConnection(128)
        }

    @Test
    fun case6_14_5() =
        runTestNoTimeSkipping {
            prepareConnection(129)
        }

    @Test
    fun case6_14_6() =
        runTestNoTimeSkipping {
            prepareConnection(130)
        }

    @Test
    fun case6_14_7() =
        runTestNoTimeSkipping {
            prepareConnection(131)
        }

    @Test
    fun case6_14_8() =
        runTestNoTimeSkipping {
            prepareConnection(132)
        }

    @Test
    fun case6_14_9() =
        runTestNoTimeSkipping {
            prepareConnection(133)
        }

    @Test
    fun case6_14_10() =
        runTestNoTimeSkipping {
            prepareConnection(134)
        }

    @Test
    fun case6_15_1() =
        runTestNoTimeSkipping {
            prepareConnection(135)
        }

    @Test
    fun case6_16_1() =
        runTestNoTimeSkipping {
            prepareConnection(136)
        }

    @Test
    fun case6_16_2() =
        runTestNoTimeSkipping {
            prepareConnection(137)
        }

    @Test
    fun case6_16_3() =
        runTestNoTimeSkipping {
            prepareConnection(138)
        }

    @Test
    fun case6_17_1() =
        runTestNoTimeSkipping {
            prepareConnection(139)
        }

    @Test
    fun case6_17_2() =
        runTestNoTimeSkipping {
            prepareConnection(140)
        }

    @Test
    fun case6_17_3() =
        runTestNoTimeSkipping {
            prepareConnection(141)
        }

    @Test
    fun case6_17_4() =
        runTestNoTimeSkipping {
            prepareConnection(142)
        }

    @Test
    fun case6_17_5() =
        runTestNoTimeSkipping {
            prepareConnection(143)
        }

    @Test
    fun case6_18_1() =
        runTestNoTimeSkipping {
            prepareConnection(144)
        }

    @Test
    fun case6_18_2() =
        runTestNoTimeSkipping {
            prepareConnection(145)
        }

    @Test
    fun case6_18_3() =
        runTestNoTimeSkipping {
            prepareConnection(146)
        }

    @Test
    fun case6_18_4() =
        runTestNoTimeSkipping {
            prepareConnection(147)
        }

    @Test
    fun case6_18_5() =
        runTestNoTimeSkipping {
            prepareConnection(148)
        }

    @Test
    fun case6_19_1() =
        runTestNoTimeSkipping {
            prepareConnection(149)
        }

    @Test
    fun case6_19_2() =
        runTestNoTimeSkipping {
            prepareConnection(150)
        }

    @Test
    fun case6_19_3() =
        runTestNoTimeSkipping {
            prepareConnection(151)
        }

    @Test
    fun case6_19_4() =
        runTestNoTimeSkipping {
            prepareConnection(152)
        }

    @Test
    fun case6_19_5() =
        runTestNoTimeSkipping {
            prepareConnection(153)
        }

    @Test
    fun case6_20_1() =
        runTestNoTimeSkipping {
            prepareConnection(154)
        }

    @Test
    fun case6_20_2() =
        runTestNoTimeSkipping {
            prepareConnection(155)
        }

    @Test
    fun case6_20_3() =
        runTestNoTimeSkipping {
            prepareConnection(156)
        }

    @Test
    fun case6_20_4() =
        runTestNoTimeSkipping {
            prepareConnection(157)
        }

    @Test
    fun case6_20_5() =
        runTestNoTimeSkipping {
            prepareConnection(158)
        }

    @Test
    fun case6_20_6() =
        runTestNoTimeSkipping {
            prepareConnection(159)
        }

    @Test
    fun case6_20_7() =
        runTestNoTimeSkipping {
            prepareConnection(160)
        }

    @Test
    fun case6_21_1() =
        runTestNoTimeSkipping {
            prepareConnection(161)
        }

    @Test
    fun case6_21_2() =
        runTestNoTimeSkipping {
            prepareConnection(162)
        }

    @Test
    fun case6_21_3() =
        runTestNoTimeSkipping {
            prepareConnection(163)
        }

    @Test
    fun case6_21_4() =
        runTestNoTimeSkipping {
            prepareConnection(164)
        }

    @Test
    fun case6_21_5() =
        runTestNoTimeSkipping {
            prepareConnection(165)
        }

    @Test
    fun case6_21_6() =
        runTestNoTimeSkipping {
            prepareConnection(166)
        }

    @Test
    fun case6_21_7() =
        runTestNoTimeSkipping {
            prepareConnection(167)
        }

    @Test
    fun case6_21_8() =
        runTestNoTimeSkipping {
            prepareConnection(168)
        }

    @Test
    fun case6_22_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(169)
        }

    @Test
    fun case6_22_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(170)
        }

    @Test
    fun case6_22_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(171)
        }

    @Test
    fun case6_22_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(172)
        }

    @Test
    fun case6_22_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(173)
        }

    @Test
    fun case6_22_6() =
        runTestNoTimeSkipping {
            echoMessageAndClose(174)
        }

    @Test
    fun case6_22_7() =
        runTestNoTimeSkipping {
            echoMessageAndClose(175)
        }

    @Test
    fun case6_22_8() =
        runTestNoTimeSkipping {
            echoMessageAndClose(176)
        }

    @Test
    fun case6_22_9() =
        runTestNoTimeSkipping {
            echoMessageAndClose(177)
        }

    @Test
    fun case6_22_10() =
        runTestNoTimeSkipping {
            echoMessageAndClose(178)
        }

    @Test
    fun case6_22_11() =
        runTestNoTimeSkipping {
            echoMessageAndClose(179)
        }

    @Test
    fun case6_22_12() =
        runTestNoTimeSkipping {
            echoMessageAndClose(180)
        }

    @Test
    fun case6_22_13() =
        runTestNoTimeSkipping {
            echoMessageAndClose(181)
        }

    @Test
    fun case6_22_14() =
        runTestNoTimeSkipping {
            echoMessageAndClose(182)
        }

    @Test
    fun case6_22_15() =
        runTestNoTimeSkipping {
            echoMessageAndClose(183)
        }

    @Test
    fun case6_22_16() =
        runTestNoTimeSkipping {
            echoMessageAndClose(184)
        }

    @Test
    fun case6_22_17() =
        runTestNoTimeSkipping {
            echoMessageAndClose(185)
        }

    @Test
    fun case6_22_18() =
        runTestNoTimeSkipping {
            echoMessageAndClose(186)
        }

    @Test
    fun case6_22_19() =
        runTestNoTimeSkipping {
            echoMessageAndClose(187)
        }

    @Test
    fun case6_22_20() =
        runTestNoTimeSkipping {
            echoMessageAndClose(188)
        }

    @Test
    fun case6_22_21() =
        runTestNoTimeSkipping {
            echoMessageAndClose(189)
        }

    @Test
    fun case6_22_22() =
        runTestNoTimeSkipping {
            echoMessageAndClose(190)
        }

    @Test
    fun case6_22_23() =
        runTestNoTimeSkipping {
            echoMessageAndClose(191)
        }

    @Test
    fun case6_22_24() =
        runTestNoTimeSkipping {
            echoMessageAndClose(192)
        }

    @Test
    fun case6_22_25() =
        runTestNoTimeSkipping {
            echoMessageAndClose(193)
        }

    @Test
    fun case6_22_26() =
        runTestNoTimeSkipping {
            echoMessageAndClose(194)
        }

    @Test
    fun case6_22_27() =
        runTestNoTimeSkipping {
            echoMessageAndClose(195)
        }

    @Test
    fun case6_22_28() =
        runTestNoTimeSkipping {
            echoMessageAndClose(196)
        }

    @Test
    fun case6_22_29() =
        runTestNoTimeSkipping {
            echoMessageAndClose(197)
        }

    @Test
    fun case6_22_30() =
        runTestNoTimeSkipping {
            echoMessageAndClose(198)
        }

    @Test
    fun case6_22_31() =
        runTestNoTimeSkipping {
            echoMessageAndClose(199)
        }

    @Test
    fun case6_22_32() =
        runTestNoTimeSkipping {
            echoMessageAndClose(200)
        }

    @Test
    fun case6_22_33() =
        runTestNoTimeSkipping {
            echoMessageAndClose(201)
        }

    @Test
    fun case6_22_34() =
        runTestNoTimeSkipping {
            echoMessageAndClose(202)
        }

    @Test
    fun case6_23_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(203)
        }

    @Test
    fun case6_23_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(204)
        }

    @Test
    fun case6_23_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(205)
        }

    @Test
    fun case6_23_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(206)
        }

    @Test
    fun case6_23_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(207)
        }

    @Test
    fun case6_23_6() =
        runTestNoTimeSkipping {
            echoMessageAndClose(208)
        }

    @Test
    fun case6_23_7() =
        runTestNoTimeSkipping {
            echoMessageAndClose(209)
        }

    @Test
    fun case7_1_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(210)
        }

    @Test
    fun case7_1_2() =
        runTestNoTimeSkipping {
            prepareConnection(211)
        }

    @Test
    fun case7_1_3() =
        runTestNoTimeSkipping {
            prepareConnection(212)
        }

    @Test
    fun case7_1_4() =
        runTestNoTimeSkipping {
            prepareConnection(213)
        }

    @Test
    fun case7_1_5() =
        runTestNoTimeSkipping {
            prepareConnection(214)
        }

    @Test
    fun case7_1_6() =
        runTestNoTimeSkipping {
            prepareConnection(215)
        }

    @Test
    fun case7_3_1() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(216))
        }

    @Test
    fun case7_3_2() =
        runTestNoTimeSkipping {
            prepareConnection(217)
        }

    @Test
    fun case7_3_3() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(218))
        }

    @Test
    fun case7_3_4() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(219))
        }

    @Test
    fun case7_3_5() =
        runTestNoTimeSkipping {
            closeConnection(prepareConnection(220))
        }

    @Test
    fun case7_3_6() =
        runTestNoTimeSkipping {
            prepareConnection(221)
        }

    @Test
    fun case7_5() =
        runTestNoTimeSkipping {
            prepareConnection(222)
        }

    @Test
    fun case7_7_1() =
        runTestNoTimeSkipping {
            prepareConnection(223)
        }

    @Test
    fun case7_7_2() =
        runTestNoTimeSkipping {
            prepareConnection(224)
        }

    @Test
    fun case7_7_3() =
        runTestNoTimeSkipping {
            prepareConnection(225)
        }

    @Test
    fun case7_7_4() =
        runTestNoTimeSkipping {
            prepareConnection(226)
        }

    @Test
    fun case7_7_5() =
        runTestNoTimeSkipping {
            prepareConnection(227)
        }

    @Test
    fun case7_7_6() =
        runTestNoTimeSkipping {
            prepareConnection(228)
        }

    @Test
    fun case7_7_7() =
        runTestNoTimeSkipping {
            prepareConnection(229)
        }

    @Test
    fun case7_7_8() =
        runTestNoTimeSkipping {
            prepareConnection(230)
        }

    @Test
    fun case7_7_9() =
        runTestNoTimeSkipping {
            prepareConnection(231)
        }

    @Test
    fun case7_7_10() =
        runTestNoTimeSkipping {
            prepareConnection(232)
        }

    @Test
    fun case7_7_11() =
        runTestNoTimeSkipping {
            prepareConnection(233)
        }

    @Test
    fun case7_7_12() =
        runTestNoTimeSkipping {
            prepareConnection(234)
        }

    @Test
    fun case7_7_13() =
        runTestNoTimeSkipping {
            prepareConnection(235)
        }

    @Test
    fun case7_9_1() =
        runTestNoTimeSkipping {
            prepareConnection(236)
        }

    @Test
    fun case7_9_2() =
        runTestNoTimeSkipping {
            prepareConnection(237)
        }

    @Test
    fun case7_9_3() =
        runTestNoTimeSkipping {
            prepareConnection(238)
        }

    @Test
    fun case7_9_4() =
        runTestNoTimeSkipping {
            prepareConnection(239)
        }

    @Test
    fun case7_9_5() =
        runTestNoTimeSkipping {
            prepareConnection(240)
        }

    @Test
    fun case7_9_6() =
        runTestNoTimeSkipping {
            prepareConnection(241)
        }

    @Test
    fun case7_9_7() =
        runTestNoTimeSkipping {
            prepareConnection(242)
        }

    @Test
    fun case7_9_8() =
        runTestNoTimeSkipping {
            prepareConnection(243)
        }

    @Test
    fun case7_9_9() =
        runTestNoTimeSkipping {
            prepareConnection(244)
        }

    @Test
    fun case7_13_1() =
        runTestNoTimeSkipping {
            prepareConnection(245)
        }

    @Test
    fun case7_13_2() =
        runTestNoTimeSkipping {
            prepareConnection(246)
        }

    @Test
    fun case9_1_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(247)
        }

    @Test
    fun case9_1_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(248)
        }

    @Test
    fun case9_1_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(249)
        }

    @Test
    fun case9_1_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(250)
        }

    @Test
    fun case9_1_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(251)
        }

    @Test
    fun case9_1_6() =
        runTestNoTimeSkipping {
            echoMessageAndClose(252)
        }

    @Test
    fun case9_2_1() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(253)
        }

    @Test
    fun case9_2_2() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(254)
        }

    @Test
    fun case9_2_3() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(255)
        }

    @Test
    fun case9_2_4() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(256)
        }

    @Test
    fun case9_2_5() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(257)
        }

    @Test
    fun case9_2_6() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(258)
        }

    @Test
    fun case9_3_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(259)
        }

    @Test
    fun case9_3_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(260)
        }

    @Test
    fun case9_3_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(261)
        }

    @Test
    fun case9_3_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(262)
        }

    @Test
    fun case9_3_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(263)
        }

    @Test
    fun case9_3_6() =
        runTestNoTimeSkipping {
            echoMessageAndClose(264)
        }

    @Test
    fun case9_3_7() =
        runTestNoTimeSkipping {
            echoMessageAndClose(265)
        }

    @Test
    fun case9_3_8() =
        runTestNoTimeSkipping {
            echoMessageAndClose(266)
        }

    @Test
    fun case9_3_9() =
        runTestNoTimeSkipping {
            echoMessageAndClose(267)
        }

    @Test
    fun case9_4_1() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(268)
        }

    @Test
    fun case9_4_2() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(269)
        }

    @Test
    fun case9_4_3() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(270)
        }

    @Test
    fun case9_4_4() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(271)
        }

    @Test
    fun case9_4_5() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(272)
        }

    @Test
    fun case9_4_6() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(273)
        }

    @Test
    fun case9_4_7() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(274)
        }

    @Test
    fun case9_4_8() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(275)
        }

    @Test
    fun case9_4_9() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(276)
        }

    @Test
    fun case9_5_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(277)
        }

    @Test
    fun case9_5_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(278)
        }

    @Test
    fun case9_5_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(279)
        }

    @Test
    fun case9_5_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(280)
        }

    @Test
    fun case9_5_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(281)
        }

    @Test
    fun case9_5_6() =
        runTestNoTimeSkipping {
            echoMessageAndClose(282)
        }

    @Test
    fun case9_6_1() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(283)
        }

    @Test
    fun case9_6_2() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(284)
        }

    @Test
    fun case9_6_3() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(285)
        }

    @Test
    fun case9_6_4() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(286)
        }

    @Test
    fun case9_6_5() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(287)
        }

    @Test
    fun case9_6_6() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(288)
        }

    @Test
    fun case9_7_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(289, 1000)
        }

    @Test
    fun case9_7_2() =
        runTestNoTimeSkipping {
            echoMessageAndClose(290, 1000)
        }

    @Test
    fun case9_7_3() =
        runTestNoTimeSkipping {
            echoMessageAndClose(291, 1000)
        }

    @Test
    fun case9_7_4() =
        runTestNoTimeSkipping {
            echoMessageAndClose(292, 1000)
        }

    @Test
    fun case9_7_5() =
        runTestNoTimeSkipping {
            echoMessageAndClose(293, 1000)
        }

    @Test
    fun case9_7_6() =
        runTestNoTimeSkipping {
            echoMessageAndClose(294, 1000)
        }

    @Test
    fun case9_8_1() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(295, 1000)
        }

    @Test
    fun case9_8_2() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(296, 1000)
        }

    @Test
    fun case9_8_3() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(297, 1000)
        }

    @Test
    fun case9_8_4() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(298, 1000)
        }

    @Test
    fun case9_8_5() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(299, 1000)
        }

    @Test
    fun case9_8_6() =
        runTestNoTimeSkipping {
            echoBinaryMessageAndClose(300, 1000)
        }

    @Test
    fun case10_1_1() =
        runTestNoTimeSkipping {
            echoMessageAndClose(301)
        }

    private suspend fun CoroutineScope.prepareConnection(case: Int): WebSocketClient {
        val connectionOptions =
            WebSocketConnectionOptions(
                name = "localhost",
                port = 9001,
                websocketEndpoint = "/runCase?case=$case&agent=${agentName()}",
            )
        val websocket =
            WebSocketClient.allocate(
                connectionOptions,
                AllocationZone.Direct,
                this + CoroutineName(case.toString()),
            )
        websocket.connect()
        websocket.connectionState.first { it is ConnectionState.Connected }
        return websocket
    }

    private suspend fun CoroutineScope.echoMessageAndClose(
        case: Int,
        count: Int = 1,
    ) {
        val connectionOptions =
            WebSocketConnectionOptions(
                name = "localhost",
                port = 9001,
                websocketEndpoint = "/runCase?case=$case&agent=${agentName()}",
            )
        val ws =
            WebSocketClient.allocate(
                connectionOptions,
                AllocationZone.Direct,
                this,
            )
        ws.scope.launch {
            ws.connect()
            ws.connectionState.first { it is ConnectionState.Connected }
        }
        ws.incomingMessages.take(count).collect {
            val m = it as WebSocketMessage.Text
            ws.write(m.value)
        }
        ws.close()
    }

    private suspend fun CoroutineScope.echoMessageWhenFoundText(case: Int) {
        val connectionOptions =
            WebSocketConnectionOptions(
                name = "localhost",
                port = 9001,
                websocketEndpoint = "/runCase?case=$case&agent=${agentName()}",
            )
        val ws =
            WebSocketClient.allocate(
                connectionOptions,
                AllocationZone.Direct,
                this + CoroutineName(case.toString()),
            )
        ws.scope.launch {
            ws.connect()
            ws.connectionState.first { it is ConnectionState.Connected }
        }
        val msg = ws.incomingMessages.first { it is WebSocketMessage.Text } as WebSocketMessage.Text
        ws.write(msg.value)
        ws.close()
    }

    private suspend fun CoroutineScope.echoBinaryMessageAndClose(
        case: Int,
        count: Int = 1,
    ) {
        val connectionOptions =
            WebSocketConnectionOptions(
                name = "localhost",
                port = 9001,
                websocketEndpoint = "/runCase?case=$case&agent=${agentName()}",
            )
        val ws =
            WebSocketClient.allocate(
                connectionOptions,
                AllocationZone.Direct,
                this + CoroutineName(case.toString()),
            )
        ws.scope.launch {
            ws.connect()
            ws.connectionState.first { it is ConnectionState.Connected }
        }
        ws.incomingMessages.take(count).collect {
            val m = it as WebSocketMessage.Binary
            ws.write(m.value)
        }
        ws.close()
    }

    suspend fun closeConnection(websocket: WebSocketClient) {
        websocket.close()
    }

    suspend fun sendMessageWithPayloadLengthOf(
        websocket: WebSocketClient,
        length: Int,
    ) {
        val string =
            if (length < 1) {
                ""
            } else {
                randomStringByKotlinRandom(length)
            }
        websocket.write(string)
    }

    suspend fun sendBinaryWithPayloadLengthOf(
        websocket: WebSocketClient,
        length: Int,
    ) {
        val binary =
            if (length < 1) {
                EMPTY_BUFFER
            } else {
                val b = PlatformBuffer.allocate(length)
                repeat(length) {
                    b.writeByte(0xfe.toByte())
                }
                b.position(0)
                b
            }
        websocket.write(binary)
    }

    @AfterTest
    fun validateResponse() =
        runTestNoTimeSkipping {
            val connectionOptions =
                WebSocketConnectionOptions(
                    name = "localhost",
                    port = 9001,
                    websocketEndpoint = "/updateReports?agent=${agentName()}",
                )
            val websocket =
                WebSocketClient.allocate(
                    connectionOptions,
                    AllocationZone.Direct,
                    this,
                )
            websocket.connect()
            websocket.connectionState.first { it is ConnectionState.Connected }
            websocket.close()
        }

    private val charPool: List<Char> = listOf('*') // ('a'..'z') + ('A'..'Z') + ('0'..'9')

    private fun randomStringByKotlinRandom(len: Int) =
        (1..len)
            .map { Random.nextInt(0, charPool.size).let { charPool[it] } }
            .joinToString("")
}
