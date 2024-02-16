import io.ktor.application.install
import io.ktor.http.content.default
import io.ktor.http.content.files
import io.ktor.http.content.static
import io.ktor.http.content.staticRootFolder
import io.ktor.routing.IgnoreTrailingSlash
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import javax.inject.Inject

abstract class AutobahnServeReports : DefaultTask() {
    @get:Input
    abstract val port: Property<Int>


    @Inject
    abstract fun getWorkerExecutor(): WorkerExecutor


    @TaskAction
    fun startHttpServer() {
        val workQueue = getWorkerExecutor().noIsolation()
        workQueue.submit(AutobahnHttpServer::class.java) { parameters ->
            parameters.port = port.get()
            parameters.projectDir = project.projectDir
        }
    }
}


interface AutobahnServerParameters : WorkParameters {
    var port: Int
    var projectDir: File
}

private var serverGlobal: NettyApplicationEngine? = null

abstract class AutobahnHttpServer : WorkAction<AutobahnServerParameters> {

    override fun execute() {
        val path = File("${parameters.projectDir}/.docker/reports/clients")
        println("Starting http server port: ${parameters.port} @ ${path.absolutePath} exists: ${path.exists()}")
        val server = embeddedServer(Netty, port = parameters.port) {
            install(IgnoreTrailingSlash)
            routing {
                static {
                    files(path)
                    default(File(path, "index.html"))
                }
            }
        }
        try {
            server.start(false)
            println("$server started")
        } catch (t: Throwable) {
            println("Failed to start autobahn server, it might already be running")
        }
        serverGlobal = server
    }
}
