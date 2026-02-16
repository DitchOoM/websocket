---
id: autobahn-report
title: RFC 6455 Compliance
sidebar_position: 3
---

# Autobahn Compliance Reports

RFC 6455 compliance is verified via the [Autobahn TestSuite](https://github.com/crossbario/autobahn-testsuite) across all supported platforms: **JVM**, **Linux x64**, **Node.js**, **Browser JS**, **macOS**, and **iOS**.

The full test suite covers 517 test cases spanning:
- **Categories 1–5**: Framing (text, binary, fragmentation, reserved bits, opcodes)
- **Categories 6**: UTF-8 handling and validation
- **Category 7**: Close frame behavior
- **Categories 9**: Limits and performance
- **Categories 12–13**: WebSocket compression (permessage-deflate)

## Reports

Reports are generated automatically by CI on every release and are available as workflow artifacts on pull requests.

import AutobahnVersions from '@site/src/components/AutobahnVersions';

<AutobahnVersions />
