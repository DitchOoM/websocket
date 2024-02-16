import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
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
        println("config \"${parameters.projectDir.absolutePath}/.docker/config:/config\"" +
                "reports \"${parameters.projectDir.absolutePath}/.docker/reports:/reports\"")
        ProcessBuilder("docker", "run", "-t", "--rm",
            "-v", "${parameters.projectDir.absolutePath}/.docker/config:/config",
            "-v", "${parameters.projectDir.absolutePath}/.docker/reports:/reports",
            "-p", "9001:9001", "--name", "fuzzingserver", "crossbario/autobahn-testsuite")
            .redirectErrorStream(true)
            .inheritIO()
            .start()
    }
}
