import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    kotlin("multiplatform") version "1.8.0"
    kotlin("native.cocoapods") version "1.8.0"
    id("com.android.library")
    id("io.codearte.nexus-staging") version "0.30.0"
    `maven-publish`
    signing
    id("org.jlleitschuh.gradle.ktlint") version "11.0.0"
    id("org.jlleitschuh.gradle.ktlint-idea") version "11.0.0"
}
val libraryVersionPrefix: String by project
group = "com.ditchoom"
version = "$libraryVersionPrefix.0-SNAPSHOT"
val libraryVersion = if (System.getenv("GITHUB_RUN_NUMBER") != null) {
    "$libraryVersionPrefix${(Integer.parseInt(System.getenv("GITHUB_RUN_NUMBER")))}"
} else {
    "${libraryVersionPrefix}0-SNAPSHOT"
}
repositories {
    google()
    mavenCentral()
}

kotlin {
    android {
        publishLibraryVariants("release")
    }
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
        testRuns["test"].executionTask.configure {
            useJUnit()
        }
    }
    js(BOTH) {
        browser()
        nodejs()
    }
    macosArm64()
    macosX64()
    watchos()
    watchosSimulatorArm64()
    tvos()
    tvosSimulatorArm64()
    ios()
    iosSimulatorArm64()
    tasks.getByName<KotlinNativeSimulatorTest>("iosSimulatorArm64Test") {
        deviceId = "iPhone 14"
    }

    cocoapods {
        ios.deploymentTarget = "13.0"
        osx.deploymentTarget = "11.0"
        watchos.deploymentTarget = "6.0"
        tvos.deploymentTarget = "13.0"
        pod("SocketWrapper") {
            source = git("https://github.com/DitchOoM/apple-socket-wrapper.git") {
                tag = "0.1.1"
            }
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("com.ditchoom:buffer:1.1.10")
                implementation("com.ditchoom:socket:1.1.9")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            kotlin.srcDir("src/commonJvmMain/kotlin")
        }
        val jvmTest by getting {
            kotlin.srcDir("src/commonJvmTest/kotlin")
        }
        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlin-wrappers:kotlin-browser:1.0.0-pre.484")
            }
        }
        val jsTest by getting
        val macosX64Main by getting
        val macosX64Test by getting
        val macosArm64Main by getting
        val macosArm64Test by getting
        val iosMain by getting
        val iosTest by getting
        val iosSimulatorArm64Main by getting
        val iosSimulatorArm64Test by getting
        val watchosMain by getting
        val watchosTest by getting
        val watchosSimulatorArm64Main by getting
        val watchosSimulatorArm64Test by getting
        val tvosMain by getting
        val tvosTest by getting
        val tvosSimulatorArm64Main by getting
        val tvosSimulatorArm64Test by getting

        val appleMain by sourceSets.creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/appleMain/kotlin")
            macosX64Main.dependsOn(this)
            macosArm64Main.dependsOn(this)
            iosMain.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            tvosMain.dependsOn(this)
            tvosSimulatorArm64Main.dependsOn(this)
            watchosMain.dependsOn(this)
            watchosSimulatorArm64Main.dependsOn(this)
        }

        val appleTest by sourceSets.creating {
            dependsOn(commonTest)
            kotlin.srcDir("src/appleTest/kotlin")
            macosX64Test.dependsOn(this)
            macosArm64Test.dependsOn(this)
            iosTest.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)
            tvosTest.dependsOn(this)
            tvosSimulatorArm64Test.dependsOn(this)
            watchosTest.dependsOn(this)
            watchosSimulatorArm64Test.dependsOn(this)
        }

        val androidMain by getting {
            kotlin.srcDir("src/commonJvmMain/kotlin")
            dependsOn(commonMain)
        }
        val androidTest by getting {
            kotlin.srcDir("src/commonJvmTest/kotlin")
            dependsOn(commonTest)
            dependsOn(jvmTest)
        }
        val androidAndroidTest by getting {
            dependsOn(commonTest)
            kotlin.srcDir("src/commonJvmTest/kotlin")
        }
    }
}

android {
    compileSdk = 33
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 18
        targetSdk = 33
    }
    lint {
        abortOnError = false
    }
    namespace = "$group.${rootProject.name}"
}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

System.getenv("GITHUB_REPOSITORY")?.let {
    if (System.getenv("GITHUB_REF") == "refs/heads/main") {
        signing {
            useInMemoryPgpKeys(
                "56F1A973",
                System.getenv("GPG_SECRET"),
                System.getenv("GPG_SIGNING_PASSWORD")
            )
            sign(publishing.publications)
        }
    }

    val ossUser = System.getenv("SONATYPE_NEXUS_USERNAME")
    val ossPassword = System.getenv("SONATYPE_NEXUS_PASSWORD")

    val publishedGroupId: String by project
    val libraryName: String by project
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
    project.version = libraryVersion

    publishing {
        publications.withType(MavenPublication::class) {
            groupId = publishedGroupId
            version = libraryVersion

            artifact(tasks["javadocJar"])

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

        repositories {
            maven("https://oss.sonatype.org/service/local/staging/deploy/maven2/") {
                name = "sonatype"
                credentials {
                    username = ossUser
                    password = ossPassword
                }
            }
        }
    }

    nexusStaging {
        username = ossUser
        password = ossPassword
        packageGroup = publishedGroupId
    }
}

val echoWebsocket = tasks.register<EchoWebsocketTask>("echoWebsocket") {
    port.set(8081)
}

tasks.forEach { task ->
    val taskName = task.name
    if ((taskName.contains("test", ignoreCase = true) && !taskName.contains("clean", ignoreCase = true)) ||
        taskName == "check"
    ) {
        task.dependsOn(echoWebsocket)
    }
}
