# P2P Media Loader Mobile

P2P delivery of HLS and DASH for Media3 on Android and of HLS for AVPlayer on
iOS, and for other players through a local HTTP server. Mobile viewers share
segments with each other and with the web viewers of
[P2P Media Loader](https://github.com/Novage/p2p-media-loader), in one swarm.

This branch holds the next major version. It is in development and is not
published. The current version, an Android library, is on the
[`main`](https://github.com/Novage/p2p-media-loader-mobile/tree/main) branch.

- [Scope](specs/scope.md): platforms, players, streams, and which requests get
  P2P.
- [Specs](specs/README.md): the design.
- [AGENTS.md](AGENTS.md): how to work in this repository.

## Build

You need the Android SDK, with the NDK and CMake versions that
`gradle/libs.versions.toml` names (Android Studio's SDK Manager installs
them, under "SDK Tools", with "Show Package Details"), JDK 17 or later, and a
C and C++20 compiler for the computer, for the C tests (on a Mac, Xcode's
Command Line Tools; on Linux, gcc and g++ 13 or later). The build also needs
Node 24 or later, with npm: it installs the web core that `libp2pml.so`
embeds (`native/package.json`). Gradle runs `npm` from the `PATH` of its
daemon, so an IDE that starts Gradle must see the same `PATH` as the
shell; after a change to `PATH`, such as a new Node, run `./gradlew --stop`. Apps that use the library do not need Node.
Android Studio's sync runs CMake without Gradle's tasks: on a new clone, run
`./gradlew :native:npmCi :native:fetchNativeDeps` once before the first sync
(the second downloads the sources of QuickJS, Ada and the WebRTC library: a
download of about 60 MB, about 125 MB once extracted into `native/.deps`,
which `clean` keeps). CMake makes that download: it takes a
proxy only from the `https_proxy` variable, not from Gradle's settings.
`./gradlew` uses the JDK that `JAVA_HOME` or `PATH` names; to use Android Studio's own, on a Mac:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
Set the SDK's path in `ANDROID_HOME` or in `local.properties` (`sdk.dir=…`), or
open the project in Android Studio, which writes `local.properties`.

| Gradle module     | What it is                                                                                         |
| ----------------- | -------------------------------------------------------------------------------------------------- |
| `core`            | The public `Core`, and the Kotlin side of the engine that runs the web core (Kotlin Multiplatform) |
| `media3`          | `Media3P2PEngine`: Media3 plays HLS through the core (specs/api.md, "Media3")                      |
| `native`          | QuickJS, the JS engine in C, and the web core's bundle                                             |
| `samples/android` | A sample app that plays the BBB live stream with Media3, through `media3`                          |

`tools/node-peer` is a Node tool, not a Gradle module: it runs the web core as
a peer; see its README.

```bash
./gradlew spotlessApply
```

```bash
./gradlew build
```

```bash
./gradlew :samples:android:installDebug
```

`spotlessApply` formats the Kotlin and Gradle files with ktfmt and the C files
with clang-format, and fixes trailing whitespace and final newlines in the
other build files. `build`
compiles the modules, runs the library's tests and the C tests, and fails on a
warning from the compilers, Android lint, detekt (with type resolution),
clang-tidy or the format checks. The last command installs the sample on a
connected device or a running emulator.

### The sample in a test swarm

The sample takes optional intent extras: `swarm`, a swarm ID for a test
swarm that no stranger joins; `stream`, a stream URL in place of BBB live; and
`highest`, which keeps the player on the rendition with the highest bitrate
that its decoders support. A rendition is a swarm, so the other peers of a
test must play the same one. A blank extra is the same as no extra. For
example, with a new ID:

```bash
adb shell am start -S -n com.novage.p2pml.sample/.MainActivity --es swarm <id> --ez highest true
```

`adb shell` gives the command to the device's shell, so quote a URL that has
`&` or `?` twice: `--es stream "'<url>'"`.

Every 10 s the sample logs (tag `P2pmlSample`) its totals since its last
start: the bytes that the core loaded over HTTP and P2P, bytes uploaded, and
the stalls. At the time of the line, it also logs the connections that the
core holds in all its swarms, and the height of the last video frame.

- A segment that Media3 loads past the core, such as before the core has the
  item, is not in the totals.
- A playback error is not a stall: it has a line of its own,
  `playback error`. A seek is a stall, so do not seek during a window.
- The difference of two lines gives the totals of a time window. The totals
  start again when the sample comes back to the front, so a window must not
  include a stop.

## License

[Apache License 2.0](LICENSE)
