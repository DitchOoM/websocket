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
        // Image + runtime overrides, mirroring .github/scripts/start_fuzzingserver.sh.
        // AUTOBAHN_IMAGE selects the multi-arch Alpine image built from .docker/autobahn
        // (native arm64); default stays the historical amd64-only Docker Hub image.
        // CONTAINER_RUNTIME supports `container` (Apple Containerization) and podman.
        val image = System.getenv("AUTOBAHN_IMAGE") ?: "crossbario/autobahn-testsuite"
        val runtime = System.getenv("CONTAINER_RUNTIME") ?: "docker"

        // Check if remote server is available first (e.g., tailscale host)
        if (!remoteHost.isNullOrEmpty() && isServerReady(remoteHost, port)) {
            println("Using remote Autobahn server at $remoteHost:$port")
            return
        }

        // Always restart the container with clean reports.
        // Gradle executes this task once per invocation, so multiple platforms
        // (JVM + Linux) share the same fresh container within a single build.

        // 1. Stop and remove existing container (if any)
        try {
            ProcessBuilder(runtime, "stop", containerName)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        } catch (e: Exception) { /* container may not exist */ }
        try {
            ProcessBuilder(runtime, "rm", containerName)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        } catch (e: Exception) { /* container may not exist */ }

        // 2. Clean stale reports (files owned by root from Docker bind mount)
        val reportsDir = "${parameters.projectDir.absolutePath}/.docker/reports"
        try {
            val cleanProcess = ProcessBuilder(
                runtime, "run", "--rm",
                "-v", "$reportsDir:/reports",
                "alpine", "sh", "-c", "rm -rf /reports/clients/*"
            )
                .redirectErrorStream(true)
                .start()
            cleanProcess.waitFor()
            println("Cleaned stale Autobahn reports")
        } catch (e: Exception) {
            println("Warning: Could not clean reports: ${e.message}")
        }

        // 3. Start fresh container
        println("Starting Autobahn fuzzing server...")
        println("  config: ${parameters.projectDir.absolutePath}/.docker/config:/config")
        println("  reports: ${parameters.projectDir.absolutePath}/.docker/reports:/reports")

        // Start the container in detached mode with memory limits, mirroring
        // .github/scripts/start_fuzzingserver.sh: the PyPy-based crossbario image keeps the
        // proven 8g (+6g swap) for its in-memory wire logs; the in-repo Alpine/CPython image
        // peaks at ~340 MiB for a full 517-case single-agent run, so 2g is ~6x headroom.
        // Apple `container` sizes a per-container VM with -m and has no --memory-swap.
        val isUpstreamImage = image.startsWith("crossbario/")
        val memory = System.getenv("AUTOBAHN_MEMORY") ?: if (isUpstreamImage) "8g" else "2g"
        val memorySwap = System.getenv("AUTOBAHN_MEMORY_SWAP") ?: if (isUpstreamImage) "14g" else "3g"
        val memoryArgs =
            if (runtime == "container") {
                listOf("-m", memory)
            } else {
                listOf("--memory=$memory", "--memory-swap=$memorySwap")
            }
        val process = ProcessBuilder(
            listOf(runtime, "run", "-d", "--rm") + memoryArgs + listOf(
                "-v", "${parameters.projectDir.absolutePath}/.docker/config:/config",
                "-v", "${parameters.projectDir.absolutePath}/.docker/reports:/reports",
                "-p", "$port:9001",
                "--name", containerName,
                image
            )
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
