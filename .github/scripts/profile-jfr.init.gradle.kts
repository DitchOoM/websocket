// JFR injector for the Autobahn profiling workflow (.github/workflows/autobahn-profile.yaml).
//
// Applied ONLY via `--init-script` from that workflow — never part of the committed build — so
// normal `./gradlew check` / PR runs carry no profiling overhead. It attaches a JDK Flight
// Recorder recording to the FORKED test-worker JVM (the one that actually runs the Autobahn
// client), not the Gradle daemon, which is where the ditchoom stack executes.
//
// Mirrors how build.gradle.kts already sets jvmArgs on Test tasks (the -XX:HeapDumpPath line).
// `settings=profile` gives method sampling + allocation profiling; `dumponexit=true` flushes the
// recording when the short-lived worker exits after the filtered case finishes.
//
// The output path can be overridden with -PjfrFile=/abs/path.jfr; it defaults under the root
// build dir. maxParallelForks=1 guarantees a single worker writes a single .jfr (no clobber).

import org.gradle.api.tasks.testing.Test

allprojects {
    tasks.withType(Test::class.java).configureEach {
        val jfrFile =
            (project.findProperty("jfrFile") as String?)
                ?: layout.buildDirectory.file("profiles/autobahn.jfr").get().asFile.absolutePath
        maxParallelForks = 1
        jvmArgs(
            "-XX:StartFlightRecording=name=autobahn,settings=profile," +
                "filename=$jfrFile,dumponexit=true",
        )
        doFirst { java.io.File(jfrFile).parentFile?.mkdirs() }
    }
}
