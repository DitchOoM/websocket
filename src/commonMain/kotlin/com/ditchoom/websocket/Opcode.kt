package com.ditchoom.websocket

enum class Opcode(
    val value: Byte,
) {
    Continuation(0x0),
    Text(0x1),
    Binary(0x2),

    // 0x3-7 are reserved for further non-control frames
    ReservedBit3(0x3),
    ReservedBit4(0x4),
    ReservedBit5(0x5),
    ReservedBit6(0x6),
    ReservedBit7(0x7),
    Close(0x8),
    Ping(0x9),
    Pong(0xA),

    // 0xB-F are reserved for further control frames
    ReservedBitB(0xB),
    ReservedBitC(0xC),
    ReservedBitD(0xD),
    ReservedBitE(0xE),
    ReservedBitF(0xF),
    ;

    fun isControlFrame(): Boolean = this == Close || this == Ping || this == Pong

    fun isValid(): Boolean =
        this == Close ||
            this == Ping ||
            this == Pong ||
            this == Binary ||
            this == Text ||
            this == Continuation

    companion object {
        private val VALUES = entries.toTypedArray()

        fun from(byte: Byte) = fromInt(byte.toInt() and 0x0F)

        fun fromInt(byteInt: Int): Opcode = VALUES[byteInt and 0x0F]
    }
}
