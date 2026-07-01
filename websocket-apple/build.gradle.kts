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
                // buffer/buffer-codec/buffer-flow come transitively as `api` from the root module
                // (its public API returns Codec/Connection/buffer types), so no explicit adds here.
                api(project(":"))
                implementation(libs.kotlinx.coroutines.core)
            }
            appleTest.dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
