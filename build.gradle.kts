import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.buffer.codec.schema)
    signing
}

apply(from = "gradle/setup.gradle.kts")

group = "com.ditchoom"
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val hostOs = org.jetbrains.kotlin.konan.target.HostManager.host

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
project.version = getNextVersion(!isRunningOnGithub).toString()

logger.lifecycle("Version: ${project.version}, isRunningOnGithub: $isRunningOnGithub, isMainBranchGithub: $isMainBranchGithub")

repositories {
    mavenLocal()
    mavenCentral()
    google()
    maven { setUrl("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-js-wrappers/") }
}

kotlin {
    // Ensure consistent JDK version across all developer machines and CI
    jvmToolchain(21)

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    js {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        nodejs {
            testTask {
                useMocha {
                    timeout = if (project.hasProperty("integrationTests")) "660s" else "15s"
                }
            }
        }
    }

    // Apple targets (only on macOS host)
    if (hostOs.family.isAppleFamily) {
        macosX64()
        macosArm64()
        iosArm64()
        iosSimulatorArm64()
        iosX64()
        tvosArm64()
        tvosSimulatorArm64()
        tvosX64()
        watchosArm64()
        watchosSimulatorArm64()
        watchosX64()
    }

    // Linux targets (only on Linux host)
    if (hostOs == org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_X64) {
        linuxX64()
        linuxArm64()
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        // Cannot use appleMain directly because compileAppleMainKotlinMetadata
        // can't resolve Apple-specific types (NSData, nw_connection_t, etc.) from dependencies.
        if (hostOs.family.isAppleFamily) {
            val appleNativeImplDir = file("src/appleNativeImpl/kotlin")
            listOf(
                "macosArm64Main",
                "macosX64Main",
                "iosArm64Main",
                "iosSimulatorArm64Main",
                "iosX64Main",
                "tvosArm64Main",
                "tvosSimulatorArm64Main",
                "tvosX64Main",
                "watchosArm64Main",
                "watchosSimulatorArm64Main",
                "watchosX64Main",
            ).forEach { sourceSetName ->
                findByName(sourceSetName)?.kotlin?.srcDir(appleNativeImplDir)
            }
        }
        commonMain.dependencies {
            // buffer/buffer-codec/buffer-flow are `api`: this module's public API returns their types
            // (BufferFactory/PlatformBuffer, Codec, Connection/WebSocketMessage), so consumers of
            // com.ditchoom:websocket need them on their compile classpath. buffer-compression is
            // internal (permessage-deflate), so it stays `implementation`.
            api(libs.buffer)
            implementation(libs.buffer.compression)
            api(libs.buffer.codec)
            api(libs.buffer.flow)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.socket) // For integration tests (real TCP transport)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.debug)
        }
        jsMain.dependencies {
            implementation(libs.kotlin.web)
            implementation(libs.kotlin.js)
        }
        val androidInstrumentedTest by getting
        val commonJvmMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(commonJvmMain)
        androidMain.get().dependsOn(commonJvmMain)
        val commonJvmTest by creating {
            dependsOn(commonTest.get())
        }
        jvmTest.get().dependsOn(commonJvmTest)
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // atomicfu runtime is needed because the atomicfu compiler plugin
                // doesn't transform Android unit test bytecode — kotlinx-coroutines
                // references AtomicFU at runtime
                implementation(libs.atomicfu)
            }
        }
        androidUnitTest.dependsOn(commonJvmTest)
        androidInstrumentedTest.dependsOn(commonJvmTest)

        androidInstrumentedTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(libs.androidx.test.core.ktx)
        }
    }
}

// KSP: generate codecs for commonMain (visible to all targets)
dependencies {
    add("kspCommonMainMetadata", libs.buffer.codec.processor)
}

// Wire KSP commonMain output into each target's source set
kotlin.sourceSets.commonMain {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

// Ensure KSP runs before compilation and source jar tasks for all targets
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}
// Gradle 8.14 strict mode flags tasks that read build/generated/ksp/metadata/commonMain/kotlin
// without an explicit dependency on the KSP task that produces it. Cover the downstream tasks
// that consume commonMain sources: source JARs + ktlint check/format variants on commonMain/commonTest.
tasks
    .matching {
        it.name.contains("SourcesJar", ignoreCase = true) ||
            (it.name.startsWith("runKtlint") && it.name.contains("Common"))
    }.configureEach {
        dependsOn("kspCommonMainKotlinMetadata")
    }

// The codec-schema wire-drift gate (com.ditchoom.buffer.codec-schema) defaults to depending on
// every `ksp*` task. This project only runs the processor once, in commonMain-metadata mode, so the
// descriptor comes solely from kspCommonMainKotlinMetadata. Narrow the tasks to that one so wiring
// checkCodecSchema into `check` doesn't drag in per-target (esp. Android instrumented) KSP tasks and
// their Android test-manifest processing (which needs -PinstrumentedTestsMinSdk34 on non-Android hosts).
listOf("checkCodecSchema", "updateCodecSchema").forEach { taskName ->
    tasks.named(taskName) {
        setDependsOn(listOf(tasks.named("kspCommonMainKotlinMetadata")))
    }
}

val integrationTestPatterns =
    listOf(
        "com.ditchoom.websocket.Autobahn*",
        "com.ditchoom.websocket.WebSocketTests",
        "com.ditchoom.websocket.PublicWssValidationTest",
        "com.ditchoom.websocket.NativeWebSocketClientTest",
    )

// Profiling tests are excluded from CI - they're diagnostic tools for local profiling,
// not conformance tests. Run manually with: ./gradlew jvmTest --tests "*ProfilingTest*"
val profilingTestPatterns =
    listOf(
        "com.ditchoom.websocket.ProfilingTest",
        "com.ditchoom.websocket.TimingProfilingTest",
        // Heavy large-message/compression throughput benchmark; run manually:
        //   ./gradlew jvmTest --tests "*ChoppedReadBenchmark*"
        "com.ditchoom.websocket.ChoppedReadBenchmark",
    )

val runIntegrationTests = project.hasProperty("integrationTests")

// Filter JVM/Android tests
tasks.withType<Test>().configureEach {
    failFast = true
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Always exclude profiling tests from CI - run manually when needed
    filter {
        profilingTestPatterns.forEach { excludeTestsMatching(it) }
    }
    if (runIntegrationTests) {
        // Stress tests with 1MB+ compressed payloads need adequate heap
        maxHeapSize = "1g"
        // CI post-mortem: dump the heap on ANY OutOfMemoryError — including direct-buffer-memory
        // OOM (that's what Autobahn 9.6.1 hit) — so a failed run uploads a .hprof to diagnose with,
        // instead of needing a local repro. The workflow uploads build/oom-dumps on failure.
        val oomDir =
            layout.buildDirectory
                .dir("oom-dumps")
                .get()
                .asFile
        jvmArgs("-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=$oomDir")
        doFirst { oomDir.mkdirs() }
    } else {
        filter {
            integrationTestPatterns.forEach { excludeTestsMatching(it) }
        }
    }
}

// Filter Kotlin/Native tests
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    // Always exclude profiling tests from CI
    profilingTestPatterns.forEach { this.filter.excludeTestsMatching(it) }
    if (!runIntegrationTests) {
        integrationTestPatterns.forEach { this.filter.excludeTestsMatching(it) }
    }
}

// Filter Kotlin/JS tests
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    // Always exclude profiling tests from CI
    profilingTestPatterns.forEach { this.filter.excludeTestsMatching(it) }
    if (!runIntegrationTests) {
        integrationTestPatterns.forEach { this.filter.excludeTestsMatching(it) }
    }
    // Browser exclusions — see docs/docs/platforms/javascript.md for the full rationale.
    // After v2's connectForTest flip to connectNativeWebSocket, the browser path
    // exercises BrowserWebSocketController (native `WebSocket`) end-to-end, so most
    // autobahn coverage works natively. The exclusions below fall into three buckets:
    //   (1) tests that drive the TCP-path codec directly (not applicable on browser),
    //   (2) tests of browser-unsupported features (frame-level ping/pong, invalid
    //       close codes, permessage-deflate window-bits control),
    //   (3) known-failing infrastructure (SharedArrayBuffer + Chrome API friction).
    if (name.contains("Browser", ignoreCase = true)) {
        // (1) TCP-path unit tests — not relevant on browser's native WS.
        this.filter.excludeTestsMatching("com.ditchoom.websocket.handshake.*")
        this.filter.excludeTestsMatching("com.ditchoom.websocket.WebSocketCodecMockTest")
        // (1) Direct-to-codec compression tests construct StreamingCompressor/Decompressor
        // explicitly; the buffer library's sync compression needs Node's zlib and will
        // throw on browser construction. Real browser consumers never hit this code path
        // because the native WebSocket runs permessage-deflate itself.
        this.filter.excludeTestsMatching("com.ditchoom.websocket.CompressionPathTest")
        this.filter.excludeTestsMatching("com.ditchoom.websocket.CompressionEchoTest")
        this.filter.excludeTestsMatching("com.ditchoom.websocket.CompressionRoundTripTest")
        this.filter.excludeTestsMatching("com.ditchoom.websocket.JsCompressionIsolationTest")
        this.filter.excludeTestsMatching("com.ditchoom.websocket.JsBugRegressionTests")
        this.filter.excludeTestsMatching("com.ditchoom.websocket.DecompressToStringTest")
        this.filter.excludeTestsMatching("com.ditchoom.websocket.MockAutobahnCat9CompressionTest")
        // (2) Browser `WebSocket` API constraints.
        // Autobahn case 7.9.x tests sending invalid/reserved close codes (1004, 1005,
        // 1006, 1012…). The browser API rejects these before they hit the wire.
        this.filter.excludeTestsMatching("com.ditchoom.websocket.AutobahnCase7CloseTests")
        // Autobahn case 13 requires control over permessage-deflate window bits and
        // context takeover, neither of which the browser `WebSocket` API exposes.
        this.filter.excludeTestsMatching("com.ditchoom.websocket.AutobahnCase13*")
        // Browser WebSocket has no app-level ping/pong — send() silently no-ops and
        // the browser handles protocol-level keepalive internally.
        this.filter.excludeTestsMatching("com.ditchoom.websocket.WebSocketTests.pingPongWorks")
        // test.mosquitto.org:8081 WSS is flaky from headless Chrome specifically
        // (other WSS endpoints in this suite — HiveMQ 8884, echo.websocket.org:443,
        // websocket-echo.com:443 — all work). Non-platform, endpoint-specific.
        this.filter.excludeTestsMatching("com.ditchoom.websocket.PublicWssValidationTest.mosquittoWssConnect")
    }
}

android {
    compileSdk = 36
    // AGP 9 + legacy-DSL opt-out: the `sourceSets[...]` Kotlin accessor casts to the
    // removed old API. Reach the source set via the new DSL interface instead.
    (this as com.android.build.api.dsl.LibraryExtension)
        .sourceSets
        .getByName("main")
        .manifest
        .srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        // Library minSdk is 23 (androidx.core 1.18+ requires >= 23). Tests bump to 34 so D8 emits
        // a dex version allowing spaces in identifiers (Kotlin backtick test names) — enabled via
        // -PinstrumentedTestsMinSdk34 when building the instrumentation test APK.
        minSdk = if (project.hasProperty("instrumentedTestsMinSdk34")) 34 else 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AGP's connectedDebugAndroidTest is not a gradle Test task, so the
        // profilingTestPatterns / integrationTestPatterns filters applied above
        // via tasks.withType<Test> do not affect it. Filter at the runner instead.
        val profilingClasses =
            listOf(
                "com.ditchoom.websocket.ProfilingTest",
                "com.ditchoom.websocket.TimingProfilingTest",
            )
        val integrationClasses =
            listOf(
                "com.ditchoom.websocket.AutobahnCase1PayloadTests",
                "com.ditchoom.websocket.AutobahnCase2FragmentationTests",
                "com.ditchoom.websocket.AutobahnCase3Utf8Tests",
                "com.ditchoom.websocket.AutobahnCase4ReservedOpcodeTests",
                "com.ditchoom.websocket.AutobahnCase5ControlFrameTests",
                "com.ditchoom.websocket.AutobahnCase6PingPongTests",
                "com.ditchoom.websocket.AutobahnCase7CloseTests",
                "com.ditchoom.websocket.AutobahnCase9CompressionTests",
                "com.ditchoom.websocket.AutobahnCase10MiscTests",
                "com.ditchoom.websocket.AutobahnCase12CompressionTests",
                "com.ditchoom.websocket.AutobahnCase13CompressionTests",
                "com.ditchoom.websocket.AutobahnStressDefaultTests",
                "com.ditchoom.websocket.AutobahnStressManagedTests",
                "com.ditchoom.websocket.AutobahnStressDeterministicTests",
                "com.ditchoom.websocket.AutobahnStressSharedTests",
                "com.ditchoom.websocket.AutobahnStressPooledTests",
                "com.ditchoom.websocket.WebSocketTests",
                "com.ditchoom.websocket.PublicWssValidationTest",
            )
        val notClasses =
            buildList {
                addAll(profilingClasses)
                if (!project.hasProperty("integrationTests")) addAll(integrationClasses)
            }
        if (notClasses.isNotEmpty()) {
            testInstrumentationRunnerArguments["notClass"] = notClasses.joinToString(",")
        }
    }
    namespace = "$group.${rootProject.name}"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

val publishedGroupId: String by project
val libraryName: String by project
val artifactName: String by project
val libraryDescription: String by project
val siteUrl: String by project
val gitUrl: String by project
val licenseName: String by project
val licenseUrl: String by project
val developerOrg: String by project
val developerName: String by project
val developerEmail: String by project
val developerId: String by project

project.group = publishedGroupId

val signingInMemoryKey = project.findProperty("signingInMemoryKey")
val signingInMemoryKeyPassword = project.findProperty("signingInMemoryKeyPassword")
val shouldSignAndPublish = isMainBranchGithub && signingInMemoryKey is String && signingInMemoryKeyPassword is String

if (shouldSignAndPublish) {
    signing {
        useInMemoryPgpKeys(
            signingInMemoryKey as String,
            signingInMemoryKeyPassword as String,
        )
        sign(publishing.publications)
    }
}

mavenPublishing {
    if (shouldSignAndPublish) {
        publishToMavenCentral()
        signAllPublications()
    }

    coordinates(publishedGroupId, artifactName, project.version.toString())

    pom {
        name.set(libraryName)
        description.set(libraryDescription)
        url.set(siteUrl)

        licenses {
            license {
                name.set(licenseName)
                url.set(licenseUrl)
            }
        }
        developers {
            developer {
                id.set(developerId)
                name.set(developerName)
                email.set(developerEmail)
            }
        }
        organization {
            name.set(developerOrg)
        }
        scm {
            connection.set(gitUrl)
            developerConnection.set(gitUrl)
            url.set(siteUrl)
        }
    }
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    android.set(true)
    filter {
        exclude { element ->
            element.file.path.contains("/build/")
        }
    }
}

dokka {
    dokkaSourceSets.configureEach {
        externalDocumentationLinks.register("kotlin-stdlib") {
            url("https://kotlinlang.org/api/latest/jvm/stdlib/")
        }
        externalDocumentationLinks.register("kotlinx-coroutines") {
            url("https://kotlinlang.org/api/kotlinx.coroutines/")
        }
        reportUndocumented.set(false)
    }

    // Suppress duplicate Apple source sets that share appleNativeImpl - keep only macosArm64
    val suppressedAppleTargets =
        listOf(
            "macosX64",
            "iosArm64",
            "iosSimulatorArm64",
            "iosX64",
            "tvosArm64",
            "tvosSimulatorArm64",
            "tvosX64",
            "watchosArm64",
            "watchosSimulatorArm64",
            "watchosX64",
        )
    dokkaSourceSets {
        suppressedAppleTargets.forEach { target ->
            findByName("${target}Main")?.suppress?.set(true)
        }
    }
}

tasks.register<Copy>("copyDokkaToDocusaurus") {
    dependsOn("dokkaGenerateHtml")
    from(layout.buildDirectory.dir("dokka/html")) { into("websocket") }
    into(layout.projectDirectory.dir("docs/static/api"))
}

// Split publishing: both Linux and Apple hosts publish root metadata (.module file)
// with their respective variants. The validate-artifacts workflow merges them with
// jq deduplication. No build-time injection or suppression needed.

tasks.register("nextVersion") {
    doLast {
        println(getNextVersion(false))
    }
}

val echoWebsocket =
    tasks.register<EchoWebsocketTask>("echoWebsocket") {
        port.set(8081)
    }
val autobahnContainer = tasks.register<AutobahnDockerTask>("startAutobahnDockerContainer")

/**
 * `adb reverse` for the Autobahn fuzzingserver + echo server ports so the Android
 * emulator can reach the host's loopback at the same `localhost:9001` / `:8081`
 * the JVM / Native / Node agents use. Without this the emulator's `localhost`
 * resolves to the emulator's own loopback (where nothing listens) and every
 * integration test under `:connectedDebugAndroidTest -PintegrationTests` fails to
 * connect; the Autobahn report records zero Android cases, so the validator
 * vacuously "passes."
 *
 * `adb reverse` is preferred over hardcoding `10.0.2.2` because it preserves the
 * shared `autobahnHost() = "localhost"` actual across JVM / Android and works for
 * USB-tethered physical devices too (where `10.0.2.2` would not route).
 */
val adbReverseForAndroidTests =
    tasks.register("adbReverseForAndroidTests") {
        description = "adb reverse :9001 and :8081 so the emulator can reach the host's Autobahn + echo servers."
        group = "verification"
        doLast {
            // Find adb via ANDROID_SDK_ROOT/platform-tools, then ANDROID_HOME, then PATH.
            val adb =
                listOfNotNull(
                    System.getenv("ANDROID_SDK_ROOT")?.let { "$it/platform-tools/adb" },
                    System.getenv("ANDROID_HOME")?.let { "$it/platform-tools/adb" },
                    "adb",
                ).firstOrNull { exec ->
                    try {
                        ProcessBuilder(exec, "version").redirectErrorStream(true).start().waitFor() == 0
                    } catch (_: Exception) {
                        false
                    }
                } ?: run {
                    logger.warn(
                        "adb not found on PATH or under ANDROID_SDK_ROOT — Android integration tests may fail to connect to the host.",
                    )
                    return@doLast
                }
            listOf(9001, 8081).forEach { port ->
                val rc =
                    ProcessBuilder(adb, "reverse", "tcp:$port", "tcp:$port")
                        .redirectErrorStream(true)
                        .start()
                        .also { it.waitFor() }
                        .exitValue()
                if (rc != 0) logger.warn("adb reverse tcp:$port returned exit code $rc")
            }
        }
    }

// Shared logic for Autobahn validation tasks. When agentsToValidate is null, all agents are checked.
fun createAutobahnValidationAction(agentsToValidate: Set<String>?) =
    Action<Task> {
        val autobahnHost = System.getenv("AUTOBAHN_HOST") ?: "localhost"
        val allAgents = listOf("JVM", "NodeJS", "BrowserJS", "macOS", "LinuxX64", "Android")
        allAgents.forEach { agent ->
            try {
                val socket = Socket(autobahnHost, 9001)
                socket.soTimeout = 5000
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                val path = "/updateReports?agent=$agent"
                val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val key = Base64.getEncoder().encodeToString(keyBytes)
                val request =
                    "GET $path HTTP/1.1\r\n" +
                        "Host: $autobahnHost:9001\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\n" +
                        "Sec-WebSocket-Version: 13\r\n" +
                        "\r\n"
                output.write(request.toByteArray())
                output.flush()
                // Read response headers
                val buffer = StringBuilder()
                while (!buffer.endsWith("\r\n\r\n")) {
                    val b = input.read()
                    if (b == -1) break
                    buffer.append(b.toChar())
                }
                // Send close frame (opcode 0x88, mask bit set, 2-byte payload with code 1000)
                val closeFrame =
                    byteArrayOf(
                        0x88.toByte(),
                        0x82.toByte(),
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x03,
                        0xE8.toByte(),
                    )
                output.write(closeFrame)
                output.flush()
                Thread.sleep(200)
                socket.close()
                println("Updated Autobahn reports for agent: $agent")
            } catch (e: Exception) {
                println("Warning: Could not update reports for agent $agent: ${e.message}")
            }
        }

        // Read index.json from the container via docker exec (avoids file permission issues)
        var indexJson: String? = null
        try {
            val execProcess =
                ProcessBuilder("docker", "exec", "fuzzingserver", "cat", "/reports/clients/index.json")
                    .start()
            indexJson = execProcess.inputStream.bufferedReader().readText()
            val exitCode = execProcess.waitFor()
            if (exitCode != 0 || indexJson.isNullOrBlank()) {
                val err = execProcess.errorStream.bufferedReader().readText()
                println("Warning: Could not read index.json from container (exit=$exitCode): $err")
                indexJson = null
            }
        } catch (e: Exception) {
            println("Warning: Could not read index.json from container: ${e.message}")
        }
        // Fall back to host file if container read failed
        if (indexJson == null) {
            val hostFile = File(file(".docker/reports/clients"), "index.json")
            if (hostFile.exists()) {
                indexJson = hostFile.readText()
            }
        }

        // Parse results and fail on failures (filtered to agentsToValidate if specified)
        val scope = agentsToValidate?.joinToString(", ") ?: "all agents"
        // Only enforce presence of a report when the Autobahn integration suite was actually run —
        // a plain `./gradlew check` (no -PintegrationTests) excludes those tests, produces no report,
        // and must not be failed by this finalizer.
        val expectReport = project.hasProperty("integrationTests")
        if (!indexJson.isNullOrBlank()) {
            @Suppress("UNCHECKED_CAST")
            val json = groovy.json.JsonSlurper().parseText(indexJson) as Map<String, Any>
            val failures = mutableListOf<String>()
            var casesSeen = 0
            json.forEach { (agent, cases) ->
                // Skip stress agents — they exist for factory-matrix coverage and
                // their pass/fail is judged by the JUnit assertion completing without
                // exception, not by per-case fuzzingserver validation.
                if (agent.contains("-stress-")) return@forEach
                // Skip agents not in the filter set (when a filter is specified)
                if (agentsToValidate != null && agent !in agentsToValidate) return@forEach
                @Suppress("UNCHECKED_CAST")
                (cases as? Map<String, Any>)?.forEach { (caseId, result) ->
                    casesSeen++
                    @Suppress("UNCHECKED_CAST")
                    val r = result as? Map<String, Any>
                    val behavior = r?.get("behavior")
                    // Gate on BOTH the data verdict and the close-handshake verdict: a case can be
                    // behavior=OK yet behaviorClose=FAILED (Case 7.x close-code regressions), which
                    // must not slip through. Non-FAILED verdicts (OK / NON-STRICT / INFORMATIONAL) pass.
                    val behaviorClose = r?.get("behaviorClose")
                    if (behavior == "FAILED" || behaviorClose == "FAILED") {
                        failures.add("$agent case $caseId: behavior=$behavior behaviorClose=$behaviorClose")
                    }
                }
            }
            // A report that recorded no cases for the requested agent means the test client never
            // connected — a real failure when we ran the integration suite (matches validate_autobahn.py).
            if (expectReport && casesSeen == 0) {
                throw GradleException("No Autobahn cases recorded for $scope — the test agent did not connect.")
            }
            if (failures.isNotEmpty()) {
                throw GradleException(
                    "Autobahn test failures (${failures.size}):\n" +
                        failures.joinToString("\n") { "  - $it" },
                )
            }
            println("All Autobahn tests passed for $scope! ($casesSeen cases)")
        } else if (expectReport) {
            throw GradleException("No Autobahn report index.json found for $scope — the integration suite produced no report.")
        } else {
            println("Warning: No Autobahn report index.json found (no -PintegrationTests; skipping validation)")
        }
    }

// Per-platform validation tasks: each only checks its own agent in the report
val validateAutobahnResultsJvm =
    tasks.register("validateAutobahnResultsJvm") {
        doLast(createAutobahnValidationAction(setOf("JVM")))
    }
val validateAutobahnResultsLinuxX64 =
    tasks.register("validateAutobahnResultsLinuxX64") {
        doLast(createAutobahnValidationAction(setOf("LinuxX64")))
    }
val validateAutobahnResultsJs =
    tasks.register("validateAutobahnResultsJs") {
        doLast(createAutobahnValidationAction(setOf("NodeJS")))
    }
val validateAutobahnResultsBrowser =
    tasks.register("validateAutobahnResultsBrowser") {
        doLast(createAutobahnValidationAction(setOf("BrowserJS")))
    }
val validateAutobahnResultsAndroid =
    tasks.register("validateAutobahnResultsAndroid") {
        doLast(createAutobahnValidationAction(setOf("Android")))
    }
// Validates all agents (used by the convenience integrationTest task)
val validateAutobahnResults =
    tasks.register("validateAutobahnResults") {
        doLast(createAutobahnValidationAction(null))
    }

// Wire Docker/echo server dependencies only when integration tests are requested
if (runIntegrationTests) {
    // Wire actual test execution tasks (not compile/process tasks) to validation
    tasks.withType<Test>().configureEach {
        dependsOn(echoWebsocket)
        dependsOn(autobahnContainer)
        finalizedBy(validateAutobahnResultsJvm)
    }
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
        dependsOn(echoWebsocket)
        dependsOn(autobahnContainer)
        finalizedBy(validateAutobahnResultsLinuxX64)
    }
    // AGP's connectedDebugAndroidTest is not a gradle Test task - wire it by name.
    tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
        dependsOn(echoWebsocket)
        dependsOn(autobahnContainer)
        dependsOn(adbReverseForAndroidTests)
        finalizedBy(validateAutobahnResultsAndroid)
    }
    tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
        dependsOn(echoWebsocket)
        dependsOn(autobahnContainer)
        if (name.contains("Browser", ignoreCase = true)) {
            finalizedBy(validateAutobahnResultsBrowser)
        } else {
            finalizedBy(validateAutobahnResultsJs)
        }
    }
    tasks.named("check") {
        dependsOn(echoWebsocket)
        dependsOn(autobahnContainer)
        finalizedBy(validateAutobahnResults)
    }
}

// Convenience lifecycle task
tasks.register("integrationTest") {
    group = "verification"
    description = "Run integration tests (requires Docker and echo server)"
    dependsOn(echoWebsocket)
    dependsOn(autobahnContainer)
    finalizedBy(validateAutobahnResults)
}
