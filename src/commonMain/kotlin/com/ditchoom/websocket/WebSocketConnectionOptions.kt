package com.ditchoom.websocket

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class WebSocketConnectionOptions(
    val name: String,
    val port: Int = 443,
    val tls: Boolean = port == 443,
    val connectionTimeout: Duration = 15.seconds,
    val readTimeout: Duration = connectionTimeout,
    val writeTimeout: Duration = connectionTimeout,
    val websocketEndpoint: String = "/",
    val protocols: List<String> = emptyList(),
    val requestCompression: Boolean = false,
) {
    internal fun buildUrl(): String {
        val prefix =
            if (tls) {
                "wss://"
            } else {
                "ws://"
            }
        val postfix = "$name:$port$websocketEndpoint"
        return prefix + postfix
    }
}
