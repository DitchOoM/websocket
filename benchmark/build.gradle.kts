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

kotlin {
    jvmToolchain(21)

    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    js {
        nodejs()
    }

    macosArm64()
    macosX64()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.benchmark.runtime)
                implementation(libs.buffer)
            }
        }
    }
}

benchmark {
    targets {
        register("jvm")
        register("js")
        register("macosArm64")
        register("macosX64")
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
    }
}
