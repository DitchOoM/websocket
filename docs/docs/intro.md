---
id: intro
title: Overview
slug: /
sidebar_position: 1
---

# WebSocket

A Kotlin Multiplatform WebSocket client library providing RFC 6455 compliant WebSocket functionality across JVM, Android, iOS, macOS, Linux, and JavaScript platforms.

## Features

- **RFC 6455 Compliant** - Full WebSocket protocol implementation with frame parsing, masking, and fragmentation
- **Kotlin Multiplatform** - Single API across all major platforms
- **permessage-deflate** - RFC 7692 compression extension with configurable window bits and context takeover
- **Auto-Reconnection** - Built-in `ReconnectingWebSocketClient` with pluggable retry classification
- **Sealed Exceptions** - `WebSocketException` hierarchy for deterministic error handling
- **Zero-Copy Pipeline** - Minimal allocations with direct buffer operations and SIMD-optimized masking
- **Native Platform Support** - Apple Network.framework (NWConnection) and browser WebSocket API
- **Flow-Based Messaging** - Kotlin Flow for incoming messages with typed channels (text, binary, all)

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.ditchoom:websocket:2.0.0")
        }
    }
}
```

## Supported Platforms

| Platform | Targets | Implementation |
|----------|---------|----------------|
| JVM | `jvm` | RFC 6455 (DefaultWebSocketClient) |
| Android | `android` | RFC 6455 (DefaultWebSocketClient) |
| iOS | `iosArm64`, `iosSimulatorArm64`, `iosX64` | Network.framework or RFC 6455 |
| macOS | `macosArm64`, `macosX64` | Network.framework or RFC 6455 |
| tvOS | `tvosArm64`, `tvosSimulatorArm64`, `tvosX64` | Network.framework or RFC 6455 |
| watchOS | `watchosArm64`, `watchosSimulatorArm64`, `watchosX64` | Network.framework or RFC 6455 |
| Linux | `linuxX64`, `linuxArm64` | RFC 6455 (DefaultWebSocketClient) |
| JavaScript | `js` (Node.js) | RFC 6455 (DefaultWebSocketClient) |
| JavaScript | `js` (Browser) | Browser WebSocket API |

## Dependencies

This library builds on:

- [`com.ditchoom:buffer`](https://github.com/DitchOoM/buffer) - Platform buffer abstraction with compression
- [`com.ditchoom:socket`](https://github.com/DitchOoM/socket) - Platform socket implementation with TLS

These are pulled in transitively - you only need to add the websocket dependency.
