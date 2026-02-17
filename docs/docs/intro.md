---
id: intro
title: Overview
slug: /
sidebar_position: 1
---

# WebSocket

A Kotlin Multiplatform WebSocket client library providing RFC 6455 compliant WebSocket functionality across JVM, Android, iOS, macOS, Linux, and JavaScript platforms.

## Features

- **RFC 6455 Compliant** - Full WebSocket protocol implementation
- **Kotlin Multiplatform** - Single API across all major platforms
- **permessage-deflate** - RFC 7692 compression extension support
- **Zero-Copy Pipeline** - Minimal allocations with direct buffer operations
- **Cancellation Support** - Proper coroutine cancellation with `suspendCancellableCoroutine`

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.ditchoom:websocket:<version>")
        }
    }
}
```

## Supported Platforms

| Platform | Target |
|----------|--------|
| JVM | `jvm` |
| Android | `android` |
| iOS | `iosArm64`, `iosSimulatorArm64`, `iosX64` |
| macOS | `macosArm64`, `macosX64` |
| Linux | `linuxX64`, `linuxArm64` |
| JavaScript | `js` (Node.js + Browser) |
| tvOS | `tvosArm64`, `tvosSimulatorArm64`, `tvosX64` |
| watchOS | `watchosArm64`, `watchosSimulatorArm64`, `watchosX64` |
