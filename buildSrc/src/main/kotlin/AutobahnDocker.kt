import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

abstract class AutobahnDockerTask : DefaultTask() {
    @Inject
    abstract fun getWorkerExecutor(): WorkerExecutor

    @TaskAction
    fun runDocker() {
        val workQueue = getWorkerExecutor().noIsolation()
        workQueue.submit(AutobahnDockerContainer::class.java) { parameters ->
            parameters.projectDir = project.projectDir
        }
    }
}

interface AutobahnDockerParams : WorkParameters {
    var projectDir: File
}

abstract class AutobahnDockerContainer : WorkAction<AutobahnDockerParams> {
    override fun execute() {
        val containerName = "fuzzingserver"
        val port = 9001
        val remoteHost = System.getenv("AUTOBAHN_HOST")

        // Check if remote server is available first (e.g., tailscale host)
        if (!remoteHost.isNullOrEmpty() && isServerReady(remoteHost, port)) {
            println("Using remote Autobahn server at $remoteHost:$port")
            return
        }

        // Check if local server is already running and healthy
        if (isServerReady("127.0.0.1", port)) {
            println("Autobahn fuzzing server already running on port $port")
            return
        }

        // Stop and remove existing container if it exists
        try {
            ProcessBuilder("docker", "stop", containerName)
                .redirectErrorStream(true)
                .start()
                .waitFor()
            ProcessBuilder("docker", "rm", containerName)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        } catch (e: Exception) {
            // Container might not exist, ignore
        }

        println("Starting Autobahn fuzzing server...")
        println("  config: ${parameters.projectDir.absolutePath}/.docker/config:/config")
        println("  reports: ${parameters.projectDir.absolutePath}/.docker/reports:/reports")

        // Start the container in detached mode with memory limits
        // Autobahn testsuite stores wire logs in memory - needs ~6GB for full compression suite
        // GitHub Actions runners have 7GB, so 8GB limit is safe with swap
        val process = ProcessBuilder(
            "docker", "run", "-d", "--rm",
            "--memory=8g", "--memory-swap=14g",
            "-v", "${parameters.projectDir.absolutePath}/.docker/config:/config",
            "-v", "${parameters.projectDir.absolutePath}/.docker/reports:/reports",
            "-p", "$port:9001",
            "--name", containerName,
            "crossbario/autobahn-testsuite"
        )
            .redirectErrorStream(true)
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val output = process.inputStream.bufferedReader().readText()
            println("Warning: Docker container may have failed to start: $output")
        }

        // Wait for server to be ready
        val maxWaitSeconds = 30
        val startTime = System.currentTimeMillis()
        while (!isServerReady("127.0.0.1", port)) {
            if (System.currentTimeMillis() - startTime > maxWaitSeconds * 1000) {
                throw RuntimeException("Autobahn server failed to start within $maxWaitSeconds seconds")
            }
            Thread.sleep(500)
        }
        println("Autobahn fuzzing server is ready on port $port")
    }

    private fun isServerReady(host: String, port: Int): Boolean {
        return try {
            val url = URL("http://$host:$port")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.requestMethod = "GET"
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..499 // Any response means server is up
        } catch (e: Exception) {
            false
        }
    }
}
