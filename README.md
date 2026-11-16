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
C compiler for the computer, for the C tests (on a Mac, Xcode's Command Line
Tools).
`./gradlew` uses the JDK that `JAVA_HOME` or `PATH` names; to use Android Studio's own, on a Mac:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
Set the SDK's path in `ANDROID_HOME` or in `local.properties` (`sdk.dir=…`), or
open the project in Android Studio, which writes `local.properties`.

| Gradle module     | What it is                                              |
| ----------------- | ------------------------------------------------------- |
| `library`         | The Kotlin Multiplatform library                        |
| `native`          | QuickJS and the JS engine in C, built with the NDK      |
| `samples/android` | A sample app that plays the BBB live stream with Media3 |

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

## License

[Apache License 2.0](LICENSE)
