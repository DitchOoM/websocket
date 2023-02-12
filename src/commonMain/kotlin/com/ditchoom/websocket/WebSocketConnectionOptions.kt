package com.ditchoom.websocket

import com.ditchoom.buffer.PlatformBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class WebSocketConnectionOptions(
    val name: String,
    val port: Int = 443,
    val websocketEndpoint: String = "/",
    val protocols: List<String> = emptyList(),
    val connectionTimeout: Duration = 15.seconds,
    val readTimeout: Duration = connectionTimeout,
    val writeTimeout: Duration = connectionTimeout,
    val tls: Boolean = port == 443,
    val bufferFactory: (() -> PlatformBuffer)? = null,
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
            websocketEndpoint: String = "/",
            protocols: List<String> = emptyList(),
            connectionTimeout: Duration = 15.seconds,
            readTimeout: Duration = connectionTimeout,
            writeTimeout: Duration = connectionTimeout,
            tls: Boolean = port == 443,
            bufferFactory: (() -> PlatformBuffer)? = null
        ): WebSocketConnectionOptions {
            return WebSocketConnectionOptions(
                name,
                port,
                websocketEndpoint,
                protocols,
                connectionTimeout,
                readTimeout,
                writeTimeout,
                tls,
                bufferFactory
            )
        }
    }
}
