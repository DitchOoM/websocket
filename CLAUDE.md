# WebSocket Library Architecture

## Overview

This is a Kotlin Multiplatform WebSocket library providing RFC 6455 compliant WebSocket client functionality across JVM, Android, iOS, macOS, Linux, and JavaScript platforms.

## No ByteArray in Production Code

Production source sets (`*Main/`) must not allocate or accept `kotlin.ByteArray`. WebSocket frame paths run per-message at high volume; any `ByteArray` allocation compounds quickly. Use `ReadBuffer` / `WriteBuffer` / `PlatformBuffer` from the `com.ditchoom:buffer` dependency, or the buffer-native primitives shipped with it (`String.utf8ByteCount()`, `WriteBuffer.writeRandomBytes`, `WriteBuffer.writeSha1Of`, `ReadBuffer.toNativeData()`).

**Platform boundaries** (`kotlin.io.encoding.Base64`, `NSURLSessionWebSocketMessage`, Node `socket.write` fallback, JDBC persistence, Android AIDL) sometimes genuinely require a `ByteArray` at the edge. For those, annotate the allocation with `@Suppress("NoByteArrayInProd")` and a one-line inline comment naming the specific driver / API surface — this keeps intentional boundary copies visible during review and prevents the suppression from propagating inward.

Tests (`*Test/`) may use `ByteArray` freely.

## Core Components

### DefaultWebSocketClient
The primary WebSocket client implementation using composable components:
- `FrameReader` - Zero-copy frame parsing using StreamProcessor
- `FrameWriter` - Frame serialization with SIMD-optimized masking
- `MessageAssembler` - Fragmented message reassembly
- Proper cancellation support via `suspendCancellableCoroutine`

### Frame Package (`frame/`)
- `FrameReader.kt` - RFC 6455 compliant frame parser with value classes for zero-allocation header parsing
- `FrameWriter.kt` - Frame serializer with direct string writing and compression support
- `MessageAssembler.kt` - Handles message fragmentation per RFC 6455 Section 5.4
- `ParsedFrame` - Sealed interface hierarchy for type-safe frame handling

### Handshake Package (`handshake/`)
- `HandshakeRequest.kt` - Builder for WebSocket upgrade requests
- `HandshakeResponseParser.kt` - HTTP response parser for handshake validation
- `HandshakeValidator.kt` - Validates server response against RFC 6455 requirements

### Compression (`Compression.kt`)
- permessage-deflate extension support (RFC 7692)
- Sync-only compression/decompression (no suspend overhead)
- Streaming decompression for memory efficiency
- UTF-8 boundary handling for chunked string decoding

## Key Design Decisions

### Zero-Copy Buffer Pipeline
The library minimizes allocations by:
1. Using `writeString()` for direct UTF-8 encoding (no intermediate ByteArray)
2. In-place SIMD XOR masking via `xorMask()`
3. StreamProcessor peek operations for header inspection
4. Value classes for frame headers (no object allocation)

### Cancellation
- Socket operations use `suspendCancellableCoroutine` with `invokeOnCancellation`
- io_uring operations on Linux can be cancelled via `io_uring_prep_cancel64`
- No polling timeouts - uses actual socket timeouts

## Dependencies

- `com.ditchoom:buffer` - Platform buffer abstraction with compression
- `com.ditchoom:socket` - Platform socket implementation

## Testing

- Unit tests: `./gradlew check`
- Integration tests with Autobahn: `./gradlew jvmTest -PintegrationTests`

## Common Tasks

### Build and Test
```bash
./gradlew check                           # Run all checks (ktlint + tests)
./gradlew jvmTest                         # Run JVM tests only
./gradlew publishToMavenLocal            # Publish to local Maven
```

### Running Autobahn Integration Tests
```bash
# Start Autobahn server
docker run -d --name fuzzingserver -p 9001:9001 \
  -v $(pwd)/.docker/config:/config \
  crossbario/autobahn-testsuite \
  wstest -m fuzzingserver -s /config/fuzzingserver.json

# Run integration tests
./gradlew jvmTest -PintegrationTests
./gradlew linuxX64Test -PintegrationTests
```
