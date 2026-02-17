import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

abstract class EchoWebsocketTask : DefaultTask() {
    @get:Input
    abstract val port: Property<Int>

    @Inject
    abstract fun getWorkerExecutor(): WorkerExecutor

    @TaskAction
    fun startWebsocketServer() {
        val workQueue = getWorkerExecutor().noIsolation()
        workQueue.submit(EchoWebsocketServer::class.java) { parameters ->
            parameters.port = port.get()
        }
    }
}

interface EchoWebsocketParameters : WorkParameters {
    var port: Int
}

private var serverGlobal: ApplicationEngine? = null

abstract class EchoWebsocketServer : WorkAction<EchoWebsocketParameters> {

    override fun execute() {
        // Check if server is already running
        if (serverGlobal != null || isServerReady(parameters.port)) {
            println("Echo websocket server already running on port ${parameters.port}")
            return
        }

        val path = "/echo"
        println("Starting echo websocket server with path \"$path\" port: ${parameters.port}")
        val server = embeddedServer(Netty, port = parameters.port) {
            install(WebSockets)
            routing {
                webSocket(path) {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val receivedText = frame.readText()
                                if (receivedText.equals("bye", ignoreCase = true)) {
                                    close(CloseReason(CloseReason.Codes.NORMAL, "Client said BYE"))
                                } else {
                                    send(Frame.Text(receivedText))
                                }
                            }
                            is Frame.Binary -> {
                                val receivedBinary = frame.data
                                send(Frame.Binary(true, receivedBinary))
                            }
                            is Frame.Ping -> {
                                send(Frame.Pong(frame.data))
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
        try {
            server.start(false)
            serverGlobal = server

            // Wait for server to be ready
            val maxWaitSeconds = 10
            val startTime = System.currentTimeMillis()
            while (!isServerReady(parameters.port)) {
                if (System.currentTimeMillis() - startTime > maxWaitSeconds * 1000) {
                    println("Warning: Echo server may not be fully ready")
                    break
                }
                Thread.sleep(100)
            }
            println("Echo websocket server is ready on port ${parameters.port}")
        } catch (t: Throwable) {
            println("Failed to start echo websocket server: ${t.message}")
        }
    }

    private fun isServerReady(port: Int): Boolean {
        return try {
            val url = URL("http://127.0.0.1:$port/echo")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 500
            connection.readTimeout = 500
            connection.requestMethod = "GET"
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..499 // Any response means server is up
        } catch (e: Exception) {
            false
        }
    }
}
