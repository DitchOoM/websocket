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
    val protocols: List<String> = emptyList()
) {
    internal fun buildUrl(): String {
        val prefix = if (tls) {
            "wss://"
        } else {
            "ws://"
        }
        val postfix = "$name:$port$websocketEndpoint"
        return prefix + postfix
    }

    companion object {
        fun build(
            name: String,
            port: Int = 443,
            tls: Boolean = port == 443,
            connectionTimeout: Duration = 15.seconds,
            readTimeout: Duration = connectionTimeout,
            writeTimeout: Duration = connectionTimeout,
            websocketEndpoint: String = "/",
            protocols: List<String> = emptyList()
        ): WebSocketConnectionOptions {
            return WebSocketConnectionOptions(
                name,
                port,
                tls,
                connectionTimeout,
                readTimeout,
                writeTimeout,
                websocketEndpoint,
                protocols
            )
        }
    }
}
