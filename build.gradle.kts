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
        // linuxArm64() // disabled until buffer-flow publishes arm64 artifacts
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
            implementation(libs.buffer)
            implementation(libs.buffer.compression)
            implementation(libs.buffer.codec)
            implementation(libs.buffer.flow)
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

// Ensure KSP runs before compilation for all targets
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
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
        // (Streaming decompression reduced requirement from 2GB to ~640MB)
        maxHeapSize = "1g"
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
    // Exclude tests from browser that require Node.js APIs or raw socket access
    // Browser WebSocket handles handshake/compression internally via native API
    if (name.contains("Browser", ignoreCase = true)) {
        this.filter.excludeTestsMatching("com.ditchoom.websocket.handshake.*")
        this.filter.excludeTestsMatching("com.ditchoom.websocket.DecompressToStringTest")
        this.filter.excludeTestsMatching("com.ditchoom.websocket.DefaultWebSocketClientMockTest")
        // Browser WebSocket API doesn't support custom windowBits or context takeover control
        this.filter.excludeTestsMatching("com.ditchoom.websocket.AutobahnCase13*")
        // Browser WebSocket API controls close frame behavior; can't handle invalid close codes correctly (7.9.x)
        this.filter.excludeTestsMatching("com.ditchoom.websocket.AutobahnCase7CloseTests")
    }
}

android {
    compileSdk = 36
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

// Shared logic for Autobahn validation tasks. When agentsToValidate is null, all agents are checked.
fun createAutobahnValidationAction(agentsToValidate: Set<String>?) =
    Action<Task> {
        val autobahnHost = System.getenv("AUTOBAHN_HOST") ?: "localhost"
        val allAgents = listOf("JVM", "NodeJS", "BrowserJS", "macOS", "LinuxX64")
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
        if (!indexJson.isNullOrBlank()) {
            @Suppress("UNCHECKED_CAST")
            val json = groovy.json.JsonSlurper().parseText(indexJson) as Map<String, Any>
            val failures = mutableListOf<String>()
            json.forEach { (agent, cases) ->
                // Skip agents not in the filter set (when a filter is specified)
                if (agentsToValidate != null && agent !in agentsToValidate) return@forEach
                @Suppress("UNCHECKED_CAST")
                (cases as? Map<String, Any>)?.forEach { (caseId, result) ->
                    @Suppress("UNCHECKED_CAST")
                    val r = result as? Map<String, Any>
                    if (r?.get("behavior") == "FAILED") {
                        failures.add("$agent case $caseId: behaviorClose=${r["behaviorClose"]}")
                    }
                }
            }
            if (failures.isNotEmpty()) {
                throw GradleException(
                    "Autobahn test failures (${failures.size}):\n" +
                        failures.joinToString("\n") { "  - $it" },
                )
            }
            val scope = agentsToValidate?.joinToString(", ") ?: "all agents"
            println("All Autobahn tests passed for $scope!")
        } else {
            println("Warning: No Autobahn report index.json found")
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
