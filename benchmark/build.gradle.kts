import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
}

repositories {
    mavenCentral()
    google()
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
    annotation("kotlinx.benchmark.State")
}

val hostOs = org.jetbrains.kotlin.konan.target.HostManager.host

kotlin {
    jvmToolchain(21)

    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    js {
        nodejs()
    }

    if (org.jetbrains.kotlin.konan.target.HostManager.hostIsMac) {
        macosArm64()
        macosX64()
    }

    if (hostOs == org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_X64) {
        linuxX64()
        linuxArm64()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.benchmark.runtime)
                implementation(libs.buffer)
                implementation(libs.buffer.compression)
                implementation(project(":"))
            }
        }
    }
}

benchmark {
    targets {
        register("jvm")
        register("js")
        if (org.jetbrains.kotlin.konan.target.HostManager.hostIsMac) {
            register("macosArm64")
            register("macosX64")
        }
        if (hostOs == org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_X64) {
            register("linuxX64")
            register("linuxArm64")
        }
    }
    configurations {
        named("main") {
            iterations = 5
            warmups = 3
            iterationTime = 1000
            iterationTimeUnit = "ms"
        }
        register("quick") {
            iterations = 3
            warmups = 2
            iterationTime = 500
            iterationTimeUnit = "ms"
        }
        register("decompress") {
            iterations = 5
            warmups = 3
            iterationTime = 1000
            iterationTimeUnit = "ms"
            include("decompress|fullRoundTrip")
        }
    }
}
