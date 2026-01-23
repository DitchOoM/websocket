import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

apply(from = "gradle/setup.gradle.kts")

group = "com.ditchoom"
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val isMacOS = System.getProperty("os.name").lowercase().contains("mac")

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
project.version = getNextVersion(!isRunningOnGithub).toString()

println("Version: ${project.version}\nisRunningOnGithub: $isRunningOnGithub\nisMainBranchGithub: $isMainBranchGithub")

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
        browser()
        nodejs {
            testTask {
                useMocha {
                    timeout = "15s"
                }
            }
        }
    }

    // Apple targets
    if (isMacOS) {
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

        // Configure linker opts for SocketWrapper from socket module
        // This is needed because the klib doesn't embed the static library
        val socketSwiftLibDir = file("../socket/build/swift/lib")
        targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
            val libSubdir =
                when (name) {
                    "macosArm64", "macosX64" -> "macos"
                    "iosArm64" -> "iphoneos"
                    "iosSimulatorArm64", "iosX64" -> "iphonesimulator"
                    "tvosArm64" -> "appletvos"
                    "tvosSimulatorArm64", "tvosX64" -> "appletvsimulator"
                    "watchosArm64" -> "watchos"
                    "watchosSimulatorArm64", "watchosX64" -> "watchsimulator"
                    else -> return@configureEach
                }
            val swiftPlatform =
                when (name) {
                    "macosArm64", "macosX64" -> "macosx"
                    "iosArm64" -> "iphoneos"
                    "iosSimulatorArm64", "iosX64" -> "iphonesimulator"
                    "tvosArm64" -> "appletvos"
                    "tvosSimulatorArm64", "tvosX64" -> "appletvsimulator"
                    "watchosArm64" -> "watchos"
                    "watchosSimulatorArm64", "watchosX64" -> "watchsimulator"
                    else -> return@configureEach
                }
            val libPath = socketSwiftLibDir.resolve(libSubdir).absolutePath
            binaries.all {
                linkerOpts("-L$libPath", "-lSocketWrapper")
                linkerOpts(
                    "-L/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/$swiftPlatform",
                    "-L/usr/lib/swift",
                )
            }
        }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.buffer)
            implementation(libs.buffer.compression)
            implementation(libs.socket)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
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
            }
        }
        androidUnitTest.dependsOn(commonJvmTest)

        androidInstrumentedTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(libs.androidx.test.core.ktx)
        }
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
        exclude("**/generated/**")
    }
}

tasks.register("nextVersion") {
    println(getNextVersion(false))
}

val echoWebsocket =
    tasks.register<EchoWebsocketTask>("echoWebsocket") {
        port.set(8081)
    }
val autobahnContainer = tasks.register<AutobahnDockerTask>("startAutobahnDockerContainer")
val validateAutobahnResults =
    task("validateAutobahnResults") {
        doLast {
            // Autobahn validation can be added here if needed
        }
    }
tasks.forEach { task ->
    val taskName = task.name
    if ((taskName.contains("test", ignoreCase = true) && !taskName.contains("clean", ignoreCase = true)) ||
        taskName == "check"
    ) {
        task.dependsOn(echoWebsocket)
        task.dependsOn(autobahnContainer)
        task.finalizedBy(validateAutobahnResults)
    }
}
