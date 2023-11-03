[![Contributors][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![MIT License][license-shield]][license-url]
[![LinkedIn][linkedin-shield]][linkedin-url]

<!-- PROJECT LOGO -->

<br />
<p align="center">
<h3 align="center">WebSocket</h3>

<p align="center">
A kotlin multiplatform library that allows you to connect a client websocket to websocket server.
<br />
Fully tested on all platforms using the extensive Autobahn test suite.
<!-- <a href="https://github.com/DitchOoM/websocket"><strong>Explore the docs »</strong></a> -->
<br />
<br />
<!-- <a href="https://github.com/DitchOoM/websocket">View Demo</a>
· -->
<a href="https://github.com/DitchOoM/websocket/issues">Report Bug</a>
·
<a href="https://github.com/DitchOoM/websocket/issues">Request Feature</a>
</p>


<details open="open">
  <summary>Table of Contents</summary>
  <ol>
    <li>
      <a href="#about-the-project">About The Project</a>
      <ul>
        <li><a href="#runtime-dependencies">Runtime Dependencies</a></li>
      </ul>
      <ul>
        <li><a href="#supported-platforms">Supported Platforms</a></li>
      </ul>
    </li>
    <li><a href="#installation">Installation</a></li>
    <li>
      <a href="#usage">Usage</a>
      <ul>
        <li><a href="#suspend-connect-read-write-and-close">Suspend connect, read, write and close</a></li>
        <li><a href="#Server-example">Server example</a></li>
        <li><a href="#TLS-support">TLS Support</a></li>
        <li><a href="#Client-echo-example">Client echo example</a></li>
        <li><a href="#Server-echo-example">Server echo example</a></li>
      </ul>
    </li>
    <li>
      <a href="#building-locally">Building Locally</a>
    </li>
    <li><a href="#getting-started">Getting Started</a></li>
    <li><a href="#roadmap">Roadmap</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
  </ol>
</details>

## About The Project

Managing a websocket client can be slightly different based on each platform. This project aims to make
it **easier to manage websockets in a cross platform way using kotlin multiplatform**. This was
originally created as a side project for a kotlin multiplatform mqtt data sync solution.

### Runtime Dependencies

* [Buffer](https://github.com/DitchOoM/buffer)

### [Supported Platforms](https://kotlinlang.org/docs/reference/mpp-supported-platforms.html)

|      Platform      | 🛠Builds🛠 + 🔬Tests🔬 |  
|:------------------:|:----------------------:|
|     `JVM` 1.8      |           🚀           |
|     `Node.js`      |           🚀           |
| `Browser` (Chrome) |           🚀           |
|     `Android`      |           🚀           |
|       `iOS`        |           🚀           |
|     `WatchOS`      |           🚀           |
|       `TvOS`       |           🚀           |
|      `MacOS`       |           🚀           |
|    `Linux X64`     |           🔮           |
|   `Windows X64`    |           🔮           |

## Installation

- Add `implementation("com.ditchoom:websocket:$version")` to your `build.gradle` dependencies
- Copy the contents of this [patch.js](https://github.com/DitchOoM/websocket/blob/main/webpack.config.d/patch.js) file
  into
  your own `webpack.config.d` directory if you are targeting `js`
- Add this to your `kotlin {` bracket in build.gradle.kts if you are targeting an apple platform

```
kotlin {
  ...
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
}
```

## Client WebSocket Usage

### Suspend connect read write and close

```kotlin
// Run in a coroutine scope
val connectionOptions = WebSocketConnectionOptions(name = "localhost", port = 8081, websocketEndpoint = "/echo")
val websocket = WebSocketClient.Companion.allocate(connectionOptions)
websocket.connect()
val string1 = "test"
websocket.write(string1)
val dataRead = websocket.read() as DataRead.StringDataRead
val stringReceived = dataRead.string
websocket.close()
```

### TLS support

```kotlin
// Simply add tls=true or change
val connectionOptions = WebSocketConnectionOptions(name = "localhost", port = 443, websocketEndpoint = "/echo", tls = true)

```

## Building Locally

- `git clone git@github.com:DitchOoM/websocket.git`
- Open cloned directory with [Intellij IDEA](https://www.jetbrains.com/idea/download).
    - Be sure
      to [open with gradle](https://www.jetbrains.com/help/idea/gradle.html#gradle_import_project_start)

## Roadmap

See the [open issues](https://github.com/DitchOoM/websocket/issues) for a list of proposed features (
and known issues).

## Contributing

Contributions are what make the open source community such an amazing place to be learn, inspire,
and create. Any contributions you make are **greatly appreciated**.

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

Distributed under the Apache 2.0 License. See `LICENSE` for more information.

[contributors-shield]: https://img.shields.io/github/contributors/DitchOoM/websocket.svg?style=for-the-badge

[contributors-url]: https://github.com/DitchOoM/websocket/graphs/contributors

[forks-shield]: https://img.shields.io/github/forks/DitchOoM/websocket.svg?style=for-the-badge

[forks-url]: https://github.com/DitchOoM/websocket/network/members

[stars-shield]: https://img.shields.io/github/stars/DitchOoM/websocket.svg?style=for-the-badge

[stars-url]: https://github.com/DitchOoM/websocket/stargazers

[issues-shield]: https://img.shields.io/github/issues/DitchOoM/websocket.svg?style=for-the-badge

[issues-url]: https://github.com/DitchOoM/websocket/issues

[license-shield]: https://img.shields.io/github/license/DitchOoM/websocket.svg?style=for-the-badge

[license-url]: https://github.com/DitchOoM/websocket/blob/master/LICENSE.md

[linkedin-shield]: https://img.shields.io/badge/-LinkedIn-black.svg?style=for-the-badge&logo=linkedin&colorB=555

[linkedin-url]: https://www.linkedin.com/in/thebehera

[maven-central]: https://search.maven.org/search?q=com.ditchoom

[npm]: https://www.npmjs.com/search?q=ditchoom-websocket

[cocoapods]: https://cocoapods.org/pods/DitchOoM-websocket

[apt]: https://packages.ubuntu.com/search?keywords=ditchoom&searchon=names&suite=groovy&section=all

[yum]: https://pkgs.org/search/?q=DitchOoM-websocket

[chocolately]: https://chocolatey.org/packages?q=DitchOoM-websocket
