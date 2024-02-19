package com.ditchoom.websocket

import agentName
import block
import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.allocate
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.connect
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class AutobahnTests {
    enum class AutobahnConnectivityState {
        UNTESTED,
        AVAILABLE,
        UNAVAILABLE
    }

    private var autobahnState = AutobahnConnectivityState.UNTESTED

    fun <T> maybeRun(lambda: suspend CoroutineScope.() -> T) = block {
        var shouldRun = false
        when (autobahnState) {
            AutobahnConnectivityState.UNTESTED -> {
                autobahnState = try {
                    ClientSocket.connect(9001, "localhost", tls = false, 3.seconds)
                    shouldRun = true
                    AutobahnConnectivityState.AVAILABLE
                } catch (e: Exception) {
                    e.printStackTrace()
                    AutobahnConnectivityState.UNAVAILABLE
                }
            }

            AutobahnConnectivityState.AVAILABLE -> {
                shouldRun = true
            }

            AutobahnConnectivityState.UNAVAILABLE -> {
                println("Autobahn Docker Image Unavailable, ignoring test.")
            } // Do nothing
        }
        if (shouldRun) {
            lambda()
        }
    }

    @Test
    fun case1_1_1() = maybeRun {
        val webSocketClient = prepareConnection(1)
        sendMessageWithPayloadLengthOf(webSocketClient, 0)
        closeConnection(webSocketClient)
    }

    @Test
    fun case1_1_2() = maybeRun {
        val webSocket1Client = prepareConnection(2)
        sendMessageWithPayloadLengthOf(webSocket1Client, 125)
        closeConnection(webSocket1Client)
    }

    @Test
    fun case1_1_3() = maybeRun {
        val webSocket3Client = prepareConnection(3)
        sendMessageWithPayloadLengthOf(webSocket3Client, 126)
        closeConnection(webSocket3Client)
    }

    @Test
    fun case1_1_4() = maybeRun {
        val webSocket4Client = prepareConnection(4)
        sendMessageWithPayloadLengthOf(webSocket4Client, 127)
        closeConnection(webSocket4Client)
    }

    @Test
    fun case1_1_5() = maybeRun {
        val webSocket5Client = prepareConnection(5)
        sendMessageWithPayloadLengthOf(webSocket5Client, 128)
        closeConnection(webSocket5Client)
    }

    @Test
    fun case1_1_6() = maybeRun {
        val webSocket6Client = prepareConnection(6)
        sendMessageWithPayloadLengthOf(webSocket6Client, 65535)
        closeConnection(webSocket6Client)
    }

    @Test
    fun case1_1_7() = maybeRun {
        val webSocket7Client = prepareConnection(7)
        sendMessageWithPayloadLengthOf(webSocket7Client, 65536)
        closeConnection(webSocket7Client)
    }

    @Test
    fun case1_1_8() = maybeRun {
        val webSocket8Client = prepareConnection(8)
        sendMessageWithPayloadLengthOf(webSocket8Client, 65536)
        closeConnection(webSocket8Client)
    }

    @Test
    fun case1_2_1() = maybeRun {
        val webSocketClient = prepareConnection(9)
        sendBinaryWithPayloadLengthOf(webSocketClient, 0)
        closeConnection(webSocketClient)
    }

    @Test
    fun case1_2_2() = maybeRun {
        val webSocket1Client = prepareConnection(10)
        sendBinaryWithPayloadLengthOf(webSocket1Client, 125)
        closeConnection(webSocket1Client)
    }

    @Test
    fun case1_2_3() = maybeRun {
        val webSocket3Client = prepareConnection(11)
        sendBinaryWithPayloadLengthOf(webSocket3Client, 126)
        closeConnection(webSocket3Client)
    }

    @Test
    fun case1_2_4() = maybeRun {
        val webSocket4Client = prepareConnection(12)
        sendBinaryWithPayloadLengthOf(webSocket4Client, 127)
        closeConnection(webSocket4Client)
    }

    @Test
    fun case1_2_5() = maybeRun {
        val webSocket5Client = prepareConnection(13)
        sendBinaryWithPayloadLengthOf(webSocket5Client, 128)
        closeConnection(webSocket5Client)
    }

    @Test
    fun case1_2_6() = maybeRun {
        val webSocket6Client = prepareConnection(14)
        sendBinaryWithPayloadLengthOf(webSocket6Client, 65535)
        closeConnection(webSocket6Client)
    }

    @Test
    fun case1_2_7() = maybeRun {
        val webSocket7Client = prepareConnection(15)
        sendBinaryWithPayloadLengthOf(webSocket7Client, 65536)
        closeConnection(webSocket7Client)
    }

    @Test
    fun case1_2_8() = maybeRun {
        val webSocket8Client = prepareConnection(16)
        sendBinaryWithPayloadLengthOf(webSocket8Client, 65536)
        closeConnection(webSocket8Client)
    }

    @Test
    fun case2_1() = maybeRun {
        prepareConnection(17)
    }

    @Test
    fun case2_2() = maybeRun {
        prepareConnection(18)
    }

    @Test
    fun case2_3() = maybeRun {
        prepareConnection(19)
    }

    @Test
    fun case2_4() = maybeRun {
        prepareConnection(20)
    }

    @Test
    fun case2_5() = maybeRun {
        prepareConnection(21)
    }

    @Test
    fun case2_6() = maybeRun {
        prepareConnection(22)
    }

    @Test
    fun case2_7() = maybeRun {
        prepareConnection(23)
    }

    @Test
    fun case2_8() = maybeRun {
        prepareConnection(24)
    }

    @Test
    fun case2_9() = maybeRun {
        prepareConnection(25)
    }

    @Test
    fun case2_10() = maybeRun {
        prepareConnection(26)
    }

    @Test
    fun case2_11() = maybeRun {
        prepareConnection(27)
    }

    @Test
    fun case3_1() = maybeRun {
        prepareConnection(28)
    }

    @Test
    fun case3_2() = maybeRun {
        prepareConnection(29)
    }

    @Test
    fun case3_3() = maybeRun {
        prepareConnection(30)
    }

    @Test
    fun case3_4() = maybeRun {
        prepareConnection(31)
    }

    @Test
    fun case3_5() = maybeRun {
        prepareConnection(32)
    }

    @Test
    fun case3_6() = maybeRun {
        prepareConnection(33)
    }

    @Test
    fun case3_7() = maybeRun {
        prepareConnection(34)
    }

    @Test
    fun case4_1_1() = maybeRun {
        prepareConnection(35)
    }

    @Test
    fun case4_1_2() = maybeRun {
        prepareConnection(36)
    }

    @Test
    fun case4_1_3() = maybeRun {
        prepareConnection(37)
    }

    @Test
    fun case4_1_4() = maybeRun {
        prepareConnection(38)
    }

    @Test
    fun case4_1_5() = maybeRun {
        prepareConnection(39)
    }

    @Test
    fun case4_2_1() = maybeRun {
        prepareConnection(40)
    }

    @Test
    fun case4_2_2() = maybeRun {
        prepareConnection(41)
    }

    @Test
    fun case4_2_3() = maybeRun {
        prepareConnection(42)
    }

    @Test
    fun case4_2_4() = maybeRun {
        prepareConnection(43)
    }

    @Test
    fun case4_2_5() = maybeRun {
        prepareConnection(44)
        delay(100) // need to wait for the remote to close the connection
    }

    @Test
    fun case5_1() = maybeRun {
        prepareConnection(45)
    }

    @Test
    fun case5_2() = maybeRun {
        prepareConnection(46)
    }

    @Test
    fun case5_3() = maybeRun {
        echoMessageAndClose(47)
    }

    @Test
    fun case5_4() = maybeRun {
        echoMessageAndClose(48)
    }

    @Test
    fun case5_5() = maybeRun {
        echoMessageAndClose(49)
    }

    @Test
    fun case5_6() = maybeRun {
        echoMessageWhenFoundText(50)
    }

    @Test
    fun case5_7() = maybeRun {
        echoMessageWhenFoundText(51)
    }

    @Test
    fun case5_8() = maybeRun {
        echoMessageWhenFoundText(52)
    }

    @Test
    fun case5_9() = maybeRun {
        closeConnection(prepareConnection(53))
    }

    @Test
    fun case5_10() = maybeRun {
        closeConnection(prepareConnection(54))
    }

    @Test
    fun case5_11() = maybeRun {
        closeConnection(prepareConnection(55))
    }

    @Test
    fun case5_12() = maybeRun {
        closeConnection(prepareConnection(56))
    }

    @Test
    fun case5_13() = maybeRun {
        closeConnection(prepareConnection(57))
    }

    @Test
    fun case5_14() = maybeRun {
        closeConnection(prepareConnection(58))
    }

    @Test
    fun case5_15() = maybeRun {
        closeConnection(prepareConnection(59))
    }

    @Test
    fun case5_16() = maybeRun {
        closeConnection(prepareConnection(60))
    }

    @Test
    fun case5_17() = maybeRun {
        closeConnection(prepareConnection(61))
    }

    @Test
    fun case5_18() = maybeRun {
        closeConnection(prepareConnection(62))
    }

    @Test
    fun case5_19() = maybeRun {
        echoMessageWhenFoundText(63)
    }

    @Test
    fun case5_20() = maybeRun {
        echoMessageWhenFoundText(64)
    }

    @Test
    fun case6_1_1() = maybeRun {
        echoMessageAndClose(65)
    }

    @Test
    fun case6_1_2() = maybeRun {
        echoMessageAndClose(66)
    }

    @Test
    fun case6_1_3() = maybeRun {
        echoMessageAndClose(67)
    }

    @Test
    fun case6_2_1() = maybeRun {
        echoMessageAndClose(68)
    }

    @Test
    fun case6_2_2() = maybeRun {
        echoMessageAndClose(69)
    }

    @Test
    fun case6_2_3() = maybeRun {
        echoMessageAndClose(70)
    }

    @Test
    fun case6_2_4() = maybeRun {
        echoMessageAndClose(71)
    }

    @Test
    fun case6_3_1() = maybeRun {
        prepareConnection(72)
    }

    @Test
    fun case6_3_2() = maybeRun {
        prepareConnection(73)
    }

    @Test
    fun case6_4_1() = maybeRun {
        prepareConnection(74)
    }

    @Test
    fun case6_4_2() = maybeRun {
        prepareConnection(75)
    }

    @Test
    fun case6_4_3() = maybeRun {
        prepareConnection(76)
    }

    @Test
    fun case6_4_4() = maybeRun {
        prepareConnection(77)
    }

    @Test
    fun case6_5_1() = maybeRun {
        echoMessageAndClose(78)
    }

    @Test
    fun case6_5_2() = maybeRun {
        echoMessageAndClose(79)
    }

    @Test
    fun case6_5_3() = maybeRun {
        echoMessageAndClose(80)
    }

    @Test
    fun case6_5_4() = maybeRun {
        echoMessageAndClose(81)
    }

    @Test
    fun case6_5_5() = maybeRun {
        echoMessageAndClose(82)
    }

    @Test
    fun case6_6_1() = maybeRun {
        prepareConnection(83)
    }

    @Test
    fun case6_6_2() = maybeRun {
        echoMessageAndClose(84)
    }

    @Test
    fun case6_6_3() = maybeRun {
        prepareConnection(85)
    }

    @Test
    fun case6_6_4() = maybeRun {
        prepareConnection(86)
    }

    @Test
    fun case6_6_5() = maybeRun {
        echoMessageAndClose(87)
    }

    @Test
    fun case6_6_6() = maybeRun {
        prepareConnection(88)
    }

    @Test
    fun case6_6_7() = maybeRun {
        echoMessageAndClose(89)
    }

    @Test
    fun case6_6_8() = maybeRun {
        prepareConnection(90)
    }

    @Test
    fun case6_6_9() = maybeRun {
        echoMessageAndClose(91)
    }

    @Test
    fun case6_6_10() = maybeRun {
        prepareConnection(92)
    }

    @Test
    fun case6_6_11() = maybeRun {
        echoMessageAndClose(93)
    }

    @Test
    fun case6_7_1() = maybeRun {
        echoMessageAndClose(94)
    }

    @Test
    fun case6_7_2() = maybeRun {
        echoMessageAndClose(95)
    }

    @Test
    fun case6_7_3() = maybeRun {
        echoMessageAndClose(96)
    }

    @Test
    fun case6_7_4() = maybeRun {
        echoMessageAndClose(97)
    }

    @Test
    fun case6_8_1() = maybeRun {
        prepareConnection(98)
    }

    @Test
    fun case6_8_2() = maybeRun {
        prepareConnection(99)
    }

    @Test
    fun case6_9_1() = maybeRun {
        echoMessageAndClose(100)
    }

    @Test
    fun case6_9_2() = maybeRun {
        echoMessageAndClose(101)
    }

    @Test
    fun case6_9_3() = maybeRun {
        echoMessageAndClose(102)
    }

    @Test
    fun case6_9_4() = maybeRun {
        echoMessageAndClose(103)
    }

    @Test
    fun case6_10_1() = maybeRun {
        prepareConnection(104)
    }

    @Test
    fun case6_10_2() = maybeRun {
        prepareConnection(105)
    }

    @Test
    fun case6_10_3() = maybeRun {
        prepareConnection(106)
    }

    @Test
    fun case6_11_1() = maybeRun {
        echoMessageAndClose(107)
    }

    @Test
    fun case6_11_2() = maybeRun {
        echoMessageAndClose(108)
    }

    @Test
    fun case6_11_3() = maybeRun {
        echoMessageAndClose(109)
    }

    @Test
    fun case6_11_4() = maybeRun {
        echoMessageAndClose(110)
    }

    @Test
    fun case6_11_5() = maybeRun {
        prepareConnection(111)
    }

    @Test
    fun case6_12_1() = maybeRun {
        prepareConnection(112)
    }

    @Test
    fun case6_12_2() = maybeRun {
        prepareConnection(113)
    }

    @Test
    fun case6_12_3() = maybeRun {
        prepareConnection(114)
    }

    @Test
    fun case6_12_4() = maybeRun {
        prepareConnection(115)
    }

    @Test
    fun case6_12_5() = maybeRun {
        prepareConnection(116)
    }

    @Test
    fun case6_12_6() = maybeRun {
        prepareConnection(117)
    }

    @Test
    fun case6_12_7() = maybeRun {
        prepareConnection(118)
    }

    @Test
    fun case6_12_8() = maybeRun {
        prepareConnection(119)
    }

    @Test
    fun case6_13_1() = maybeRun {
        prepareConnection(120)
    }

    @Test
    fun case6_13_2() = maybeRun {
        prepareConnection(121)
    }

    @Test
    fun case6_13_3() = maybeRun {
        prepareConnection(122)
    }

    @Test
    fun case6_13_4() = maybeRun {
        prepareConnection(123)
    }

    @Test
    fun case6_13_5() = maybeRun {
        prepareConnection(124)
    }

    @Test
    fun case6_14_1() = maybeRun {
        prepareConnection(125)
    }

    @Test
    fun case6_14_2() = maybeRun {
        prepareConnection(126)
    }

    @Test
    fun case6_14_3() = maybeRun {
        prepareConnection(127)
    }

    @Test
    fun case6_14_4() = maybeRun {
        prepareConnection(128)
    }

    @Test
    fun case6_14_5() = maybeRun {
        prepareConnection(129)
    }

    @Test
    fun case6_14_6() = maybeRun {
        prepareConnection(130)
    }

    @Test
    fun case6_14_7() = maybeRun {
        prepareConnection(131)
    }

    @Test
    fun case6_14_8() = maybeRun {
        prepareConnection(132)
    }

    @Test
    fun case6_14_9() = maybeRun {
        prepareConnection(133)
    }

    @Test
    fun case6_14_10() = maybeRun {
        prepareConnection(134)
    }

    @Test
    fun case6_15_1() = maybeRun {
        prepareConnection(135)
    }

    @Test
    fun case6_16_1() = maybeRun {
        prepareConnection(136)
    }

    @Test
    fun case6_16_2() = maybeRun {
        prepareConnection(137)
    }

    @Test
    fun case6_16_3() = maybeRun {
        prepareConnection(138)
    }

    @Test
    fun case6_17_1() = maybeRun {
        prepareConnection(139)
    }

    @Test
    fun case6_17_2() = maybeRun {
        prepareConnection(140)
    }

    @Test
    fun case6_17_3() = maybeRun {
        prepareConnection(141)
    }

    @Test
    fun case6_17_4() = maybeRun {
        prepareConnection(142)
    }

    @Test
    fun case6_17_5() = maybeRun {
        prepareConnection(143)
    }

    @Test
    fun case6_18_1() = maybeRun {
        prepareConnection(144)
    }

    @Test
    fun case6_18_2() = maybeRun {
        prepareConnection(145)
    }

    @Test
    fun case6_18_3() = maybeRun {
        prepareConnection(146)
    }

    @Test
    fun case6_18_4() = maybeRun {
        prepareConnection(147)
    }

    @Test
    fun case6_18_5() = maybeRun {
        prepareConnection(148)
    }

    @Test
    fun case6_19_1() = maybeRun {
        prepareConnection(149)
    }

    @Test
    fun case6_19_2() = maybeRun {
        prepareConnection(150)
    }

    @Test
    fun case6_19_3() = maybeRun {
        prepareConnection(151)
    }

    @Test
    fun case6_19_4() = maybeRun {
        prepareConnection(152)
    }

    @Test
    fun case6_19_5() = maybeRun {
        prepareConnection(153)
    }

    @Test
    fun case6_20_1() = maybeRun {
        prepareConnection(154)
    }

    @Test
    fun case6_20_2() = maybeRun {
        prepareConnection(155)
    }

    @Test
    fun case6_20_3() = maybeRun {
        prepareConnection(156)
    }

    @Test
    fun case6_20_4() = maybeRun {
        prepareConnection(157)
    }

    @Test
    fun case6_20_5() = maybeRun {
        prepareConnection(158)
    }

    @Test
    fun case6_20_6() = maybeRun {
        prepareConnection(159)
    }

    @Test
    fun case6_20_7() = maybeRun {
        prepareConnection(160)
    }

    @Test
    fun case6_21_1() = maybeRun {
        prepareConnection(161)
    }

    @Test
    fun case6_21_2() = maybeRun {
        prepareConnection(162)
    }

    @Test
    fun case6_21_3() = maybeRun {
        prepareConnection(163)
    }

    @Test
    fun case6_21_4() = maybeRun {
        prepareConnection(164)
    }

    @Test
    fun case6_21_5() = maybeRun {
        prepareConnection(165)
    }

    @Test
    fun case6_21_6() = maybeRun {
        prepareConnection(166)
    }

    @Test
    fun case6_21_7() = maybeRun {
        prepareConnection(167)
    }

    @Test
    fun case6_21_8() = maybeRun {
        prepareConnection(168)
    }

    @Test
    fun case6_22_1() = maybeRun {
        echoMessageAndClose(169)
    }

    @Test
    fun case6_22_2() = maybeRun {
        echoMessageAndClose(170)
    }

    @Test
    fun case6_22_3() = maybeRun {
        echoMessageAndClose(171)
    }

    @Test
    fun case6_22_4() = maybeRun {
        echoMessageAndClose(172)
    }

    @Test
    fun case6_22_5() = maybeRun {
        echoMessageAndClose(173)
    }

    @Test
    fun case6_22_6() = maybeRun {
        echoMessageAndClose(174)
    }

    @Test
    fun case6_22_7() = maybeRun {
        echoMessageAndClose(175)
    }

    @Test
    fun case6_22_8() = maybeRun {
        echoMessageAndClose(176)
    }

    @Test
    fun case6_22_9() = maybeRun {
        echoMessageAndClose(177)
    }

    @Test
    fun case6_22_10() = maybeRun {
        echoMessageAndClose(178)
    }

    @Test
    fun case6_22_11() = maybeRun {
        echoMessageAndClose(179)
    }

    @Test
    fun case6_22_12() = maybeRun {
        echoMessageAndClose(180)
    }

    @Test
    fun case6_22_13() = maybeRun {
        echoMessageAndClose(181)
    }

    @Test
    fun case6_22_14() = maybeRun {
        echoMessageAndClose(182)
    }

    @Test
    fun case6_22_15() = maybeRun {
        echoMessageAndClose(183)
    }

    @Test
    fun case6_22_16() = maybeRun {
        echoMessageAndClose(184)
    }

    @Test
    fun case6_22_17() = maybeRun {
        echoMessageAndClose(185)
    }

    @Test
    fun case6_22_18() = maybeRun {
        echoMessageAndClose(186)
    }

    @Test
    fun case6_22_19() = maybeRun {
        echoMessageAndClose(187)
    }

    @Test
    fun case6_22_20() = maybeRun {
        echoMessageAndClose(188)
    }

    @Test
    fun case6_22_21() = maybeRun {
        echoMessageAndClose(189)
    }

    @Test
    fun case6_22_22() = maybeRun {
        echoMessageAndClose(190)
    }

    @Test
    fun case6_22_23() = maybeRun {
        echoMessageAndClose(191)
    }

    @Test
    fun case6_22_24() = maybeRun {
        echoMessageAndClose(192)
    }

    @Test
    fun case6_22_25() = maybeRun {
        echoMessageAndClose(193)
    }

    @Test
    fun case6_22_26() = maybeRun {
        echoMessageAndClose(194)
    }

    @Test
    fun case6_22_27() = maybeRun {
        echoMessageAndClose(195)
    }

    @Test
    fun case6_22_28() = maybeRun {
        echoMessageAndClose(196)
    }

    @Test
    fun case6_22_29() = maybeRun {
        echoMessageAndClose(197)
    }

    @Test
    fun case6_22_30() = maybeRun {
        echoMessageAndClose(198)
    }

    @Test
    fun case6_22_31() = maybeRun {
        echoMessageAndClose(199)
    }

    @Test
    fun case6_22_32() = maybeRun {
        echoMessageAndClose(200)
    }

    @Test
    fun case6_22_33() = maybeRun {
        echoMessageAndClose(201)
    }

    @Test
    fun case6_22_34() = maybeRun {
        echoMessageAndClose(202)
    }

    @Test
    fun case6_23_1() = maybeRun {
        echoMessageAndClose(203)
    }

    @Test
    fun case6_23_2() = maybeRun {
        echoMessageAndClose(204)
    }

    @Test
    fun case6_23_3() = maybeRun {
        echoMessageAndClose(205)
    }

    @Test
    fun case6_23_4() = maybeRun {
        echoMessageAndClose(206)
    }

    @Test
    fun case6_23_5() = maybeRun {
        echoMessageAndClose(207)
    }

    @Test
    fun case6_23_6() = maybeRun {
        echoMessageAndClose(208)
    }

    @Test
    fun case6_23_7() = maybeRun {
        echoMessageAndClose(209)
    }

    @Test
    fun case7_1_1() = maybeRun {
        echoMessageAndClose(210)
    }

    @Test
    fun case7_1_2() = maybeRun {
        prepareConnection(211)
    }

    @Test
    fun case7_1_3() = maybeRun {
        prepareConnection(212)
    }

    @Test
    fun case7_1_4() = maybeRun {
        prepareConnection(213)
    }

    @Test
    fun case7_1_5() = maybeRun {
        prepareConnection(214)
    }

    @Test
    fun case7_1_6() = maybeRun {
        prepareConnection(215)
    }

    @Test
    fun case7_3_1() = maybeRun {
        closeConnection(prepareConnection(216))
    }

    @Test
    fun case7_3_2() = maybeRun {
        prepareConnection(217)
    }

    @Test
    fun case7_3_3() = maybeRun {
        closeConnection(prepareConnection(218))
    }

    @Test
    fun case7_3_4() = maybeRun {
        closeConnection(prepareConnection(219))
    }

    @Test
    fun case7_3_5() = maybeRun {
        closeConnection(prepareConnection(220))
    }

    @Test
    fun case7_3_6() = maybeRun {
        prepareConnection(221)
    }

    @Test
    fun case7_5() = maybeRun {
        prepareConnection(222)
    }

    @Test
    fun case7_7_1() = maybeRun {
        prepareConnection(223)
    }

    @Test
    fun case7_7_2() = maybeRun {
        prepareConnection(224)
    }

    @Test
    fun case7_7_3() = maybeRun {
        prepareConnection(225)
    }

    @Test
    fun case7_7_4() = maybeRun {
        prepareConnection(226)
    }

    @Test
    fun case7_7_5() = maybeRun {
        prepareConnection(227)
    }

    @Test
    fun case7_7_6() = maybeRun {
        prepareConnection(228)
    }

    @Test
    fun case7_7_7() = maybeRun {
        prepareConnection(229)
    }

    @Test
    fun case7_7_8() = maybeRun {
        prepareConnection(230)
    }

    @Test
    fun case7_7_9() = maybeRun {
        prepareConnection(231)
    }

    @Test
    fun case7_7_10() = maybeRun {
        prepareConnection(232)
    }

    @Test
    fun case7_7_11() = maybeRun {
        prepareConnection(233)
    }

    @Test
    fun case7_7_12() = maybeRun {
        prepareConnection(234)
    }

    @Test
    fun case7_7_13() = maybeRun {
        prepareConnection(235)
    }

    @Test
    fun case7_9_1() = maybeRun {
        prepareConnection(236)
    }

    @Test
    fun case7_9_2() = maybeRun {
        prepareConnection(237)
    }

    @Test
    fun case7_9_3() = maybeRun {
        prepareConnection(238)
    }

    @Test
    fun case7_9_4() = maybeRun {
        prepareConnection(239)
    }

    @Test
    fun case7_9_5() = maybeRun {
        prepareConnection(240)
    }

    @Test
    fun case7_9_6() = maybeRun {
        prepareConnection(241)
    }

    @Test
    fun case7_9_7() = maybeRun {
        prepareConnection(242)
    }

    @Test
    fun case7_9_8() = maybeRun {
        prepareConnection(243)
    }

    @Test
    fun case7_9_9() = maybeRun {
        prepareConnection(244)
    }

    @Test
    fun case7_13_1() = maybeRun {
        prepareConnection(245)
    }

    @Test
    fun case7_13_2() = maybeRun {
        prepareConnection(246)
    }

    @Test
    fun case9_1_1() = maybeRun {
        echoMessageAndClose(247)
    }

    @Test
    fun case9_1_2() = maybeRun {
        echoMessageAndClose(248)
    }

    @Test
    fun case9_1_3() = maybeRun {
        echoMessageAndClose(249)
    }

    @Test
    fun case9_1_4() = maybeRun {
        echoMessageAndClose(250)
    }

    @Test
    fun case9_1_5() = maybeRun {
        echoMessageAndClose(251)
    }

    @Test
    fun case9_1_6() = maybeRun {
        echoMessageAndClose(252)
    }

    @Test
    fun case9_2_1() = maybeRun {
        echoBinaryMessageAndClose(253)
    }

    @Test
    fun case9_2_2() = maybeRun {
        echoBinaryMessageAndClose(254)
    }

    @Test
    fun case9_2_3() = maybeRun {
        echoBinaryMessageAndClose(255)
    }

    @Test
    fun case9_2_4() = maybeRun {
        echoBinaryMessageAndClose(256)
    }

    @Test
    fun case9_2_5() = maybeRun {
        echoBinaryMessageAndClose(257)
    }

    @Test
    fun case9_2_6() = maybeRun {
        echoBinaryMessageAndClose(258)
    }

    @Test
    fun case9_3_1() = maybeRun {
        echoMessageAndClose(259)
    }

    @Test
    fun case9_3_2() = maybeRun {
        echoMessageAndClose(260)
    }

    @Test
    fun case9_3_3() = maybeRun {
        echoMessageAndClose(261)
    }

    @Test
    fun case9_3_4() = maybeRun {
        echoMessageAndClose(262)
    }

    @Test
    fun case9_3_5() = maybeRun {
        echoMessageAndClose(263)
    }

    @Test
    fun case9_3_6() = maybeRun {
        echoMessageAndClose(264)
    }

    @Test
    fun case9_3_7() = maybeRun {
        echoMessageAndClose(265)
    }

    @Test
    fun case9_3_8() = maybeRun {
        echoMessageAndClose(266)
    }

    @Test
    fun case9_3_9() = maybeRun {
        echoMessageAndClose(267)
    }

    @Test
    fun case9_4_1() = maybeRun {
        echoBinaryMessageAndClose(268)
    }

    @Test
    fun case9_4_2() = maybeRun {
        echoBinaryMessageAndClose(269)
    }

    @Test
    fun case9_4_3() = maybeRun {
        echoBinaryMessageAndClose(270)
    }

    @Test
    fun case9_4_4() = maybeRun {
        echoBinaryMessageAndClose(271)
    }

    @Test
    fun case9_4_5() = maybeRun {
        echoBinaryMessageAndClose(272)
    }

    @Test
    fun case9_4_6() = maybeRun {
        echoBinaryMessageAndClose(273)
    }

    @Test
    fun case9_4_7() = maybeRun {
        echoBinaryMessageAndClose(274)
    }

    @Test
    fun case9_4_8() = maybeRun {
        echoBinaryMessageAndClose(275)
    }

    @Test
    fun case9_4_9() = maybeRun {
        echoBinaryMessageAndClose(276)
    }

    @Test
    fun case9_5_1() = maybeRun {
        echoMessageAndClose(277)
    }

    @Test
    fun case9_5_2() = maybeRun {
        echoMessageAndClose(278)
    }

    @Test
    fun case9_5_3() = maybeRun {
        echoMessageAndClose(279)
    }

    @Test
    fun case9_5_4() = maybeRun {
        echoMessageAndClose(280)
    }

    @Test
    fun case9_5_5() = maybeRun {
        echoMessageAndClose(281)
    }

    @Test
    fun case9_5_6() = maybeRun {
        echoMessageAndClose(282)
    }

    @Test
    fun case9_6_1() = maybeRun {
        echoBinaryMessageAndClose(283)
    }

    @Test
    fun case9_6_2() = maybeRun {
        echoBinaryMessageAndClose(284)
    }

    @Test
    fun case9_6_3() = maybeRun {
        echoBinaryMessageAndClose(285)
    }

    @Test
    fun case9_6_4() = maybeRun {
        echoBinaryMessageAndClose(286)
    }

    @Test
    fun case9_6_5() = maybeRun {
        echoBinaryMessageAndClose(287)
    }

    @Test
    fun case9_6_6() = maybeRun {
        echoBinaryMessageAndClose(288)
    }

    @Test
    fun case9_7_1() = maybeRun {
        echoMessageAndClose(289, 1000)
    }

    @Test
    fun case9_7_2() = maybeRun {
        echoMessageAndClose(290, 1000)
    }

    @Test
    fun case9_7_3() = maybeRun {
        echoMessageAndClose(291, 1000)
    }

    @Test
    fun case9_7_4() = maybeRun {
        echoMessageAndClose(292, 1000)
    }

    @Test
    fun case9_7_5() = maybeRun {
        echoMessageAndClose(293, 1000)
    }

    @Test
    fun case9_7_6() = maybeRun {
        echoMessageAndClose(294, 1000)
    }

    @Test
    fun case9_8_1() = maybeRun {
        echoBinaryMessageAndClose(295, 1000)
    }

    @Test
    fun case9_8_2() = maybeRun {
        echoBinaryMessageAndClose(296, 1000)
    }

    @Test
    fun case9_8_3() = maybeRun {
        echoBinaryMessageAndClose(297, 1000)
    }

    @Test
    fun case9_8_4() = maybeRun {
        echoBinaryMessageAndClose(298, 1000)
    }

    @Test
    fun case9_8_5() = maybeRun {
        echoBinaryMessageAndClose(299, 1000)
    }

    @Test
    fun case9_8_6() = maybeRun {
        echoBinaryMessageAndClose(300, 1000)
    }

    @Test
    fun case10_1_1() = maybeRun {
        echoMessageAndClose(301)
    }

    private suspend fun CoroutineScope.prepareConnection(case: Int): WebSocketClient {
        val connectionOptions = WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}"
        )
        val websocket = WebSocketClient.allocate(
            connectionOptions,
            AllocationZone.SharedMemory,
            this + CoroutineName(case.toString())
        )
        websocket.connect()
        websocket.connectionState.first { it is ConnectionState.Connected }
        return websocket
    }

    private suspend fun CoroutineScope.echoMessageAndClose(case: Int, count: Int = 1) {
        val connectionOptions = WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}",
        )
        val ws = WebSocketClient.allocate(
            connectionOptions,
            AllocationZone.SharedMemory,
            this
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
        val connectionOptions = WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}"
        )
        val ws = WebSocketClient.allocate(
            connectionOptions,
            AllocationZone.SharedMemory,
            this + CoroutineName(case.toString())
        )
        ws.scope.launch {
            ws.connect()
            ws.connectionState.first { it is ConnectionState.Connected }
        }
        val msg = ws.incomingMessages.first { it is WebSocketMessage.Text } as WebSocketMessage.Text
        ws.write(msg.value)
        ws.close()
    }

    private suspend fun CoroutineScope.echoBinaryMessageAndClose(case: Int, count: Int = 1) {
        val connectionOptions = WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}"
        )
        val ws = WebSocketClient.allocate(
            connectionOptions,
            AllocationZone.SharedMemory,
            this + CoroutineName(case.toString())
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

    suspend fun sendMessageWithPayloadLengthOf(websocket: WebSocketClient, length: Int) {
        val string = if (length < 1) {
            ""
        } else {
            randomStringByKotlinRandom(length)
        }
        websocket.write(string)
    }

    suspend fun sendBinaryWithPayloadLengthOf(websocket: WebSocketClient, length: Int) {
        val binary = if (length < 1) {
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
    fun validateResponse() = block {
        if (autobahnState != AutobahnConnectivityState.AVAILABLE) {
            return@block
        }
        val connectionOptions = WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            websocketEndpoint = "/updateReports?agent=${agentName()}",
        )
        val websocket = WebSocketClient.allocate(
            connectionOptions,
            AllocationZone.SharedMemory,
            this
        )
        websocket.connect()
        websocket.connectionState.first { it is ConnectionState.Connected }
        websocket.close()
    }

    private val charPool: List<Char> = listOf('*') // ('a'..'z') + ('A'..'Z') + ('0'..'9')
    private fun randomStringByKotlinRandom(len: Int) = (1..len)
        .map { Random.nextInt(0, charPool.size).let { charPool[it] } }
        .joinToString("")
}
