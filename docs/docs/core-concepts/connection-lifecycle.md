---
id: connection-lifecycle
title: Connection Lifecycle
sidebar_position: 1
---

# Connection Lifecycle

## States

A WebSocket connection moves through these states, exposed via `connectionState: StateFlow<ConnectionState>`:

```
Initialized ──> Connecting ──> Connected ──> Disconnected
                    │                            ▲
                    └────────────────────────────┘
                         (connection failed)
```

- **Initialized** - Client created, no connection attempt yet
- **Connecting** - TCP/TLS handshake and HTTP upgrade in progress
- **Connected** - WebSocket connection established, ready for messages
- **Disconnected** - Connection ended (normal close, error, or server-initiated)

## Connection Flow

1. `WebSocketClient.allocate(options)` creates the client in `Initialized` state
2. `connect()` transitions to `Connecting`, performs TCP/TLS + HTTP upgrade
3. On success, transitions to `Connected` and starts the read loop
4. Messages flow through `incomingMessages` / `incomingTextMessages` / `incomingBinaryMessages`
5. `close()` sends a close frame, waits for the read loop to exit, then transitions to `Disconnected`

## Close Behavior

When `close()` is called:

1. Sends a WebSocket close frame (code 1000, "Client closing") if still connected
2. Clears the frame writer to prevent further writes
3. Closes compression resources
4. Closes the underlying socket (unblocks any pending read)
5. Waits for the read loop to finish cleanup (releasing buffers)

The close is deterministic - no timeouts. The socket close unblocks the read loop, which catches the I/O exception and exits naturally.

## Server-Initiated Close

When the server sends a close frame:

1. The read loop receives and processes the close frame
2. `connectionState` transitions to `Disconnected` with the server's close code and reason
3. The read loop exits, releasing resources
4. Subsequent `close()` calls are a no-op (already disconnected)

## Awaiting Connection

```kotlin
// Wait for the connection to be established
client.connect()

// Or reactively observe state changes
client.connectionState.first { it is ConnectionState.Connected }
```
