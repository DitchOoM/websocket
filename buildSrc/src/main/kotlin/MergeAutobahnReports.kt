import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Merges Autobahn index.json files from multiple platform report directories
 * into a single combined index.json.
 *
 * Each input directory should contain an index.json produced by the Autobahn
 * fuzzing server, keyed by agent name (e.g., "JVM", "LinuxX64", "NodeJS").
 */
abstract class MergeAutobahnReports : DefaultTask() {
    @get:Input
    abstract val reportDirs: ListProperty<String>

    @get:Input
    abstract val reportVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun merge() {
        val merged = mutableMapOf<String, Any>()

        reportDirs.get().forEach { dirPath ->
            val dir = project.file(dirPath)
            val indexFile = File(dir, "index.json")
            if (!indexFile.exists()) {
                logger.warn("No index.json found in ${dir.absolutePath}, skipping")
                return@forEach
            }
            @Suppress("UNCHECKED_CAST")
            val json = groovy.json.JsonSlurper().parseText(indexFile.readText()) as Map<String, Any>
            json.forEach { (agent, cases) ->
                merged[agent] = cases
            }
            logger.lifecycle("Merged ${json.size} agent(s) from ${dir.name}: ${json.keys.joinToString(", ")}")
        }

        if (merged.isEmpty()) {
            logger.warn("No Autobahn report data found in any input directory")
            return
        }

        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        val indexFile = File(outDir, "index.json")
        indexFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(merged)))
        logger.lifecycle("Wrote merged index.json with ${merged.size} agent(s) to ${indexFile.absolutePath}")
    }
}
