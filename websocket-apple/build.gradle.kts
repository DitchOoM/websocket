plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

apply(from = "../gradle/setup.gradle.kts")

group = "com.ditchoom"
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
project.version = getNextVersion(!isRunningOnGithub).toString()

repositories {
    mavenLocal()
    mavenCentral()
}

val hostOs = org.jetbrains.kotlin.konan.target.HostManager.host

kotlin {
    jvmToolchain(21)

    if (hostOs.family.isAppleFamily) {
        macosX64()
        macosArm64()
        iosArm64()
        iosSimulatorArm64()
        iosX64()
        tvosArm64()
        tvosSimulatorArm64()
        tvosX64()
        // watchOS intentionally excluded: watchosArm64 uses ILP32 ABI so
        // NSInteger/NSUInteger differ in width from LP64 (iOS/macOS/tvOS), and
        // NSURLSessionWebSocketDelegate overrides require bit-width-variant
        // types that can't be reconciled in the shared appleMain metadata.
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        if (hostOs.family.isAppleFamily) {
            // Do NOT put the shared Apple sources in the intermediate `appleMain` source set:
            // `compileAppleMainKotlinMetadata` cannot resolve `com.ditchoom.buffer.codec.*` because
            // buffer-codec 6.0.0's published kotlin-project-structure-metadata.json declares no Apple
            // variants (only common/jvm/linux/web), so the appleMain metadata transform extracts no
            // klib for it. buffer/buffer-flow ship correct Apple PSM entries, which is why only the
            // codec package fails. The per-target Apple klibs are fine, so attach the shared sources
            // to each leaf target instead — mirroring the root module (see ../build.gradle.kts).
            val appleSharedDir = file("src/appleShared/kotlin")
            listOf(
                "macosX64Main",
                "macosArm64Main",
                "iosArm64Main",
                "iosSimulatorArm64Main",
                "iosX64Main",
                "tvosArm64Main",
                "tvosSimulatorArm64Main",
                "tvosX64Main",
            ).forEach { sourceSetName ->
                findByName(sourceSetName)?.kotlin?.srcDir(appleSharedDir)
            }
            // Dependencies still live on the `appleMain` parent (no sources ⇒ no metadata compile);
            // they propagate to every leaf target above. Codec/Connection/buffer types are in this
            // module's public API (connectAppleNativeWebSocket signature), so `api`.
            appleMain.dependencies {
                api(project(":"))
                api(libs.buffer)
                api(libs.buffer.codec)
                api(libs.buffer.flow)
                implementation(libs.kotlinx.coroutines.core)
            }
            appleTest.dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
