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
            appleMain.dependencies {
                api(project(":"))
                // The root module declares these as `implementation` (not exposed transitively), and
                // websocket-apple uses NSURLSession — so, unlike websocket-tcp, it doesn't pull them
                // in via socket. Declare them here: Codec/Connection/buffer types are in this module's
                // public API (connectAppleNativeWebSocket signature), so `api`.
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
