package com.ditchoom.websocket

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

@OptIn(ExperimentalEncodingApi::class)
actual fun generateWebSocketKey(): String {
    val bytes = Random.nextBytes(16)
    return Base64.encode(bytes)
}
