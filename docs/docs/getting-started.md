---
id: getting-started
title: Getting Started
sidebar_position: 2
---

# Getting Started

## Quick Start

Connect to a WebSocket server and exchange messages:

```kotlin
import com.ditchoom.websocket.WebSocketClient

val client = WebSocketClient.open("wss://echo.websocket.org")

// Send a text message
client.send("Hello, WebSocket!")

// Receive messages
val message = client.receive()
println("Received: $message")

// Close the connection
client.close()
```

## Dependencies

This library depends on:

- [`com.ditchoom:buffer`](https://github.com/DitchOoM/buffer) - Platform buffer abstraction
- [`com.ditchoom:socket`](https://github.com/DitchOoM/socket) - Platform socket implementation

These are pulled in transitively - you only need to add the websocket dependency.
