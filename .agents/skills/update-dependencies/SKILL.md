---
name: update-dependencies
description: Use when updating any dependency of this repository — Gradle and its wrapper, the plugins and libraries of the version catalog (Ktor, kotlinx-coroutines and OkHttp among them), the Android SDK levels, the NDK, clang-format and CMake, the JDK, Node and the CI actions, the native sources that the build downloads (QuickJS, Ada, and the WebRTC library: libdatachannel with libjuice, usrsctp and plog, and Mbed TLS), the web core that the native build embeds (npm p2p-media-loader-core), the WPT URL test data, and tools/node-peer. Says where each one is pinned, where to find its releases, what to check, and how to verify and commit the update.
---

# Updating dependencies

Every dependency of this repository is pinned to an exact version, and some
to a hash. This skill says where each pin is, how to find a newer release,
what can break, and how to prove that the update works. The rules in
[AGENTS.md](../../../AGENTS.md) apply; where this skill and AGENTS.md
disagree, AGENTS.md is right.

## Rules for every update

- **Use the primary source.** Find versions and hashes on the project's own
  release page, registry or repository (the sources in the table below), not
  in a search result or a blog. Read the release notes of each version that
  you skip over, not only of the newest.
- **Stable releases only.** No beta, release candidate, alpha or preview,
  unless no stable release works and the reason is written next to the pin
  (detekt; and the web core, whose 5.0.0 is not released, see "The web core
  of the library"). Check the release _name_, not only the
  channel: the SDK repository has listed a beta NDK in its stable channel
  (r28 beta 2, `28.0.12674087`), and CI could not install it without the
  preview licence.
- **Check every hash that the repository records,** against a second source
  (a second download, or the digest that the release page lists): the Gradle
  distribution, QuickJS, Ada, the npm integrity of the lockfile.
- **A download is untrusted data.** Put it in its own new, empty folder,
  outside the repository. Read it; never run a script from it.
- **Ask the user before a large download,** such as an NDK or a system image
  (about 1 GB), and before any change to `minSdk`: that is a product
  decision.
- **One group per commit.** Update the Gradle wrapper, the Kotlin toolchain,
  the NDK, QuickJS, Ada and node-peer in separate commits, so that a failure
  points at one of them and `git bisect` works. Versions that must move
  together (AGP and its Gradle minimum; Kotlin and detekt) are one group.
- **Formatting changes go in their own commit.** A new ktfmt, clang-format or
  Prettier can format the code in a different way. Commit the version change
  first, then the output of `./gradlew spotlessApply` (or Prettier) with a
  message that says so.
- **Update the text that names the version:** the comments in
  `gradle/libs.versions.toml`, the comments of `native/cmake/deps.cmake`, the entries
  of `KNOWN-ISSUES.md` that name a version (remove an entry that the update
  fixes), and `README.md` when a tool that a developer installs changes.
- **Commit only when the user says so,** as for any change.

## Where each dependency is pinned

| Dependency                                            | Pinned in                                                                          | Releases                                                                                                                                                   |
| ----------------------------------------------------- | ---------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Gradle                                                | `gradle/wrapper/gradle-wrapper.properties` (version, sha256)                       | <https://gradle.org/releases/>, <https://services.gradle.org/versions/current>                                                                             |
| AGP (all `com.android.*` plugins)                     | `gradle/libs.versions.toml` `agp`                                                  | `https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml`; release notes on developer.android.com                        |
| Kotlin (KMP plugin)                                   | catalog `kotlin`                                                                   | `gh api repos/JetBrains/kotlin/releases`                                                                                                                   |
| detekt                                                | catalog `detekt`                                                                   | `gh api repos/detekt/detekt/releases`                                                                                                                      |
| Spotless, ktfmt                                       | catalog `spotless`, `ktfmt`                                                        | `gh api repos/diffplug/spotless/releases`, `gh api repos/facebook/ktfmt/releases`                                                                          |
| Media3 (`:media3`, sample app)                        | catalog `media3`                                                                   | `gh api repos/androidx/media/releases`                                                                                                                     |
| androidx.test runner                                  | catalog `androidx-test-runner`                                                     | Google Maven `androidx/test/runner/maven-metadata.xml`                                                                                                     |
| Android SDK levels                                    | catalog `android-*`                                                                | the platforms that the SDK Manager offers                                                                                                                  |
| NDK, its clang-format, CMake                          | catalog `ndk`, `clang-format`, `cmake`                                             | `https://dl.google.com/android/repository/repository2-3.xml`; the NDK's `source.properties` (`Pkg.ReleaseName`); <https://github.com/android/ndk/releases> |
| JDK of CI                                             | `.github/workflows/build.yml` (`java-version`)                                     | the lowest JDK that `README.md` names                                                                                                                      |
| GitHub Actions                                        | `.github/workflows/build.yml` (`uses:`)                                            | `gh api repos/<owner>/<action>/releases/latest`                                                                                                            |
| QuickJS                                               | `native/cmake/deps.cmake` (URL and sha256)                                         | <https://bellard.org/quickjs/>                                                                                                                             |
| Ada                                                   | `native/cmake/deps.cmake` (URL and sha256)                                         | `gh api repos/ada-url/ada/releases/latest` (each asset has a `digest`)                                                                                     |
| libdatachannel, libjuice, usrsctp, plog, Mbed TLS     | `native/cmake/deps.cmake` (URL and sha256 of each)                                 | `gh api repos/paullouisageneau/libdatachannel/releases`; `gh api repos/Mbed-TLS/mbedtls/releases` (each asset has a `digest`)                              |
| WPT URL test data                                     | `native/test/third_party/wpt-url/update.mjs` (`COMMIT`)                            | <https://github.com/web-platform-tests/wpt/tree/master/url>                                                                                                |
| The web core of the library (`p2p-media-loader-core`) | `native/package.json` (exact version), `native/package-lock.json` (sha512)         | `npm view p2p-media-loader-core versions`; the web repository's tags and CHANGELOG.md                                                                      |
| Node of CI                                            | `.github/workflows/build.yml` (`node-version`)                                     | the lowest Node that `README.md` names                                                                                                                     |
| Ktor, kotlinx-coroutines, OkHttp (fetch, WebSocket)   | catalog `ktor`, `kotlinx-coroutines`, `okhttp`                                     | `gh api repos/ktorio/ktor/releases/latest`; Maven Central for kotlinx-coroutines and OkHttp                                                                |
| node-peer packages, Prettier                          | `tools/node-peer/package.json`, `package-lock.json`                                | `npm view <package> version`                                                                                                                               |
| The web core of node-peer (`p2p-media-loader-core`)   | `tools/node-peer/package.json`; the local `.core/SOURCE` names the packed checkout | the web repository; see `tools/node-peer/README.md`, "pack-core"                                                                                           |

## Gradle wrapper

1. Read the release notes from the current version to the new one: removed
   APIs, and new deprecations.
2. Run `./gradlew wrapper --gradle-version <version>
--gradle-distribution-sha256-sum <sha256>`, with the sha256 of the `-bin`
   distribution from gradle.org's checksum page. Run it twice: the second run
   updates `gradle-wrapper.jar` with the new version's own wrapper.
3. Check that the AGP of the catalog supports this Gradle (AGP's release
   notes list the minimum Gradle), and that the configuration cache is still
   reused on a second `./gradlew build`.
4. `KNOWN-ISSUES.md`, "A Gradle deprecation does not fail the build": run
   `./gradlew build --warning-mode all` and update the entry (the plugins that
   call a deprecated API, the Gradle version that removes it), or remove it.

## The version catalog

These move together; check each pair before you change one:

- **AGP and Gradle:** AGP has a minimum Gradle version.
- **AGP and the SDK levels:** a new AGP can need a newer `compileSdk`, and a
  new `compileSdk` can need a newer AGP.
- **Kotlin and detekt:** detekt runs with type resolution, so it must be
  built on the same Kotlin compiler as the build (today the 2.4 compiler,
  which only detekt 2.0 alphas have; the reason is in the catalog). Update
  them together, and run `./gradlew check`.
- **Kotlin and the library's users:** see `KNOWN-ISSUES.md`, "The library's
  Kotlin metadata": a new Kotlin minor version changes which apps can use a
  published build.
- **Spotless and ktfmt:** a new ktfmt formats in a different way; commit its
  output on its own.

Then:

1. Change the versions in `gradle/libs.versions.toml`, and the comments next
   to them.
2. `./gradlew spotlessApply`, then `./gradlew build --console=plain`. Fix each
   new warning: the build treats warnings as errors (Kotlin, lint, detekt).
   A new lint or detekt rule is fixed in the code, not suppressed, unless the
   code is deliberate (AGENTS.md).
3. For a new `compileSdk` or `targetSdk`, install the platform with the SDK
   Manager, and read the behaviour changes of that Android version. Keep
   `android-minCompileSdk` equal to `compileSdk` unless the user decides
   otherwise. From API 37 on, a platform has a minor version
   (`platforms;android-37.0`); CI installs `android-<compileSdk>.0`.

## Ktor, kotlinx-coroutines and OkHttp

`fetch` and `WebSocket` run over Ktor in common code, with Ktor's OkHttp engine on Android
(specs/engine.md, "The platform's network clients"). Update them as one group.

1. Ktor: read the release notes of each version skipped, for the client API
   that `HttpBridge.kt` uses (`prepareRequest`, `execute`, `bodyAsChannel`,
   `readAvailable`), the WebSocket API that `WsBridge.kt` uses (`webSocket`,
   `incoming`, `outgoing`, `closeReason`; Ktor's `OkHttpWebsocketSession`
   turns the frames into OkHttp's calls), the OkHttp engine's configuration (`SharedHttpClient.kt`),
   and the Kotlin version it needs.
2. OkHttp: the catalog's `okhttp` is the version that `ktor-client-okhttp`
   depends on, so that `mockwebserver3` tests the same OkHttp. Check it with
   `./gradlew :core:dependencies --configuration androidRuntimeClasspath`.
3. A new OkHttp can raise the `compileSdk` that apps need (OkHttp 5.5.1 needs
   API 37): the build stops at `checkAndroidMainAarMetadata`. Raising
   `compileSdk` and `minCompileSdk` changes what apps compile against: ask
   the user.
4. Run the build (`HttpBridgeTest`, `WsBridgeTest`, `JsFetchTest` and
   `JsWebSocketTest` use a real local server) and the device test
   (`JsFetchDeviceTest` logs the throughput; `JsWebSocketDeviceTest` logs a
   connect to a public tracker).
5. Measure the sample app's release APK with R8: enable `isMinifyEnabled` with
   a keep rule for `com.novage.p2pml.**` in a temporary edit of
   `samples/android/build.gradle.kts`, build `:samples:android:assembleRelease`,
   compare with the size before, and remove the edit. 2.3a measured 0.42 MB
   for Ktor and its dependencies.

## NDK, clang-format and CMake

The NDK builds `native/` for Android, and its clang-tidy and clang-format
check our C. CI installs the NDK and CMake that the catalog names.

1. Find the newest stable NDK: its `source.properties` says `Pkg.ReleaseName
= rNN` (with a letter for an update, such as `r29b`), with no `beta` or
   `rc`. Ask the user before the download (about 1 GB); they install it with
   Android Studio's SDK Manager ("SDK Tools", "Show Package Details").
2. Set `ndk` in the catalog. Set `clang-format` to the version that the new
   NDK's `toolchains/llvm/prebuilt/<host>/bin/clang-format --version` prints
   (Spotless stops when they differ). The CI log also prints it.
3. Read the NDK's changelog: the minimum API level (it must not be above
   `minSdk`), changes to the default flags, the libc++ version, and the
   16 KB page size.
4. Build. Fix each new clang-tidy finding and each new compiler warning in
   our code; the downloaded sources build with warnings off.
5. Check the release library of each ABI, in
   `native/build/intermediates/stripped_native_libs/release/.../lib/<abi>/libp2pml.so`:
   - `llvm-readelf -lW`: each `LOAD` segment has alignment `0x4000`;
   - `llvm-nm -D --defined-only`: only `JNI_OnLoad` and the
     `Java_com_novage_p2pml_...` functions;
   - `llvm-readelf -d`: `NEEDED` has only system libraries (libc, libm,
     libdl);
   - the size, compared with the previous NDK.
6. Run the device test (below).

For CMake: set `cmake` to a version that the SDK Manager offers, not above
what `native/CMakeLists.txt` needs. Check that `cmake_minimum_required`
still matches.

## The JDK and the CI actions

- CI builds with the lowest JDK that `README.md` names; change both together.
  A newer JDK on a developer's computer must still work: the library's
  bytecode target is pinned (`jvmTarget` in `core/build.gradle.kts`), not
  taken from the JDK.
- Update each `uses:` of `.github/workflows/build.yml` to its newest major
  version, and read its release notes for changed inputs (such as
  `cache-read-only` of `gradle/actions/setup-gradle`).

## Downloaded native sources

QuickJS, Ada and the WebRTC library are not in the repository: the build
downloads them (specs/engine.md, "Where the code is"), and
`native/cmake/deps.cmake` pins each archive by URL and sha256. For each
update:

1. Download the new archive into an empty folder outside the repository, and
   run none of its scripts.
2. Check its sha256 against a second source: a `digest` that GitHub gives a
   release asset, a second download (bellard.org has no other source), or,
   for a GitHub archive of a tag or a commit, a `git clone` of that commit
   compared file by file with the extracted archive
   (`diff -r --exclude=.git`). GitHub makes the archives of tags and commits
   on request: if a hash fails with no pin change, compare the archive with a
   clone in the same way before you change the hash.
3. Review the change of the code: extract the old and the new archive and
   read the `diff -r` of the files that the build compiles. The repository
   shows only the change of the pin.
4. Change the URL and the sha256 in `native/cmake/deps.cmake`. The build
   downloads it again (`native/.deps/<name>/.sha256` names the archive that
   it extracted).

## QuickJS

Its archive is Bellard's release tarball. `add_quickjs` in
`native/CMakeLists.txt` lists the files it compiles: when the new release
adds a source file that they need, add it there, with any define of the
release's Makefile. Then check what the engine depends on, because QuickJS has no stable API promise. Each item has tests,
and the C test program runs them under AddressSanitizer:

- `JS_SetInterruptHandler` and the uncatchable error of the close
  (`native/test/engine_test.c`, the close tests), and the paths on which
  QuickJS changes that error (`KNOWN-ISSUES.md`, "The close cannot stop every
  endless loop"): check whether the entry is still true.
- `JS_NewTypedArray` reads three arguments (`native/src/encoding.c`).
- `JS_ToCStringLen2` with and without CESU-8, `JS_NewStringLen`, and what they
  do with lone surrogates and invalid UTF-8 (the encoding tests).
- `Uint8Array.fromBase64` in its "loose" mode is the HTML forgiving decode
  (`native/test/js/base64_test.js`), and `String.prototype.toWellFormed`
  exists.
- The Promise rejection tracker, `JS_ExecutePendingJob`, and FinalizationRegistry
  jobs (the C tests of uncaught errors).
- The stack: QuickJS stops JS at 1 MiB; the engine thread has 2 MiB; the
  sanitized test build of QuickJS is `-O1` so that a JS call does not take
  too much C stack (`native/CMakeLists.txt`).
- `CONFIG_VERSION` and the defines of its Makefile, which
  `native/CMakeLists.txt` copies; compare them with the new Makefile.

Measure the size of `libp2pml.so` and the engine start on the phone, as for
the NDK.

## Ada

Its archive is `singleheader.zip` of the release (`ada.cpp`, `ada.h`,
`ada_c.h`); its licence is MIT or Apache-2.0, and the library uses it under
MIT. Then:

- Read the release notes for changes to `ada_c.h`, which `native/src/url.c`
  calls, and to the defines that `native/CMakeLists.txt` sets
  (`ADA_INCLUDE_URL_PATTERN`). A new major version can change the C API.
- Ada is C++20, with exceptions and RTTI off; a new release can need a newer
  compiler (CI uses gcc 13 on Ubuntu 24.04).
- Check the exported symbols of `libp2pml.so` again (only JNI): Ada and
  libc++ are hidden by visibility and `--exclude-libs`.
- Update the WPT URL data with it (next section). All WPT cases must pass.

## libdatachannel and Mbed TLS

The WebRTC library is downloaded at build time (specs/engine.md, "The WebRTC
library"); `native/cmake/deps.cmake` pins each archive by URL and sha256.
Update them as one group:

1. libdatachannel: the newest release tag. Read its release notes for changes
   to `rtc.h` and to the CMake options that `native/CMakeLists.txt` sets
   (`add_webrtc`).
2. libjuice, usrsctp and plog: the commits that the new tag pins as
   submodules (`gh api 'repos/paullouisageneau/libdatachannel/contents/deps?ref=<tag>'`),
   as GitHub archives of those commits. usrsctp comes from the maintainer's
   fork, as the tag pins it.
3. Mbed TLS: the newest release of the 3.6 LTS line, which libdatachannel
   needs (`find_package(MbedTLS 3)`); its release asset, which has the
   generated files. Check again whether `MBEDTLS_SSL_DTLS_SRTP` is still the
   only option beyond its defaults that libdatachannel needs, and that
   libdatachannel still calls no debug function of Mbed TLS:
   `native/src/mbedtls_user_config.h` turns `MBEDTLS_DEBUG_C` off.
4. Each archive as "Downloaded native sources" says; for Mbed TLS, the
   second source is the `digest` that GitHub gives the asset.
5. Run the build: the fetch, the three Android ABIs (`libp2pml.so` links
   the library), the `rtc` C test and `webrtc_test.js` on the host, and
   `JsWebRtcTest`. Read the release notes for changes to the callbacks'
   threads and to deletion (specs/engine.md, "The WebRTC library": a
   callback after a delete must still find no engine). Check the release
   `libp2pml.so` of each ABI as the NDK section says, and compare the arm64
   size with the one before (3.63 MB with libdatachannel 0.24.6 and Mbed TLS
   3.6.8). Then the device test (`JsWebRtcDeviceTest` logs the throughput),
   and the phone with the Node peer in one swarm (`tools/node-peer/README.md`,
   "A phone in the swarm").
   `rtc_test` with `-DP2PML_RTC_TEST=ON` can still run alone on a device
   with `adb`, to tell a defect of the library from one of the binding.

## WPT URL test data

`native/test/third_party/wpt-url/update.mjs` downloads the URL test data of
the web-platform-tests at one commit and writes the `*_data.js` files.

1. Choose the WPT commit of the URL Standard that the new Ada release
   follows: Ada updates its own copy of the WPT data in pull requests named
   "Update web platform tests (url)"; take the commit of the last one before
   the release.
2. Set `COMMIT`, run `node update.mjs` in that folder, and check the diff of
   the data.
3. Run the build. When a case fails, compare the result with Node's
   `new URL(...)` (Node uses Ada too) and with a browser. A case that the URL
   Standard changed and Ada does not follow yet is a known issue of Ada:
   report it; never edit the data.

## The web core of the library

`libp2pml.so` embeds the bundle of the npm package `p2p-media-loader-core`
(specs/engine.md, "Modules"). Update it when the library moves to a new core,
not as a routine update: the core decides the swarms that a mobile peer
joins.

1. Read the web repository's CHANGELOG.md and MIGRATION.md from the old
   version to the new one, for changes to the stream identity, the peer
   protocol (`PEER_PROTOCOL_VERSION`), the globals the core reads, and the
   bundle's file name and exports. Read the user-agent rule of the default
   segment memory limit in the new bundle (`isAndroidWebview` and the
   limits in its segment memory storage): specs/engine.md, "Globals", says
   which limit the library's user agent gets.
2. In `native/`: `npm install --save-exact --ignore-scripts
p2p-media-loader-core@<version>`. Check that the lockfile's `integrity`
   is the one that `npm view p2p-media-loader-core@<version> dist.integrity`
   gives.
3. Update the version that `specs/engine.md` names, and the same version in
   `tools/node-peer`, so that the measurements run the core that the library
   embeds.
4. Run the build. The tests check the stream identity against the web
   repository's golden vectors
   (`packages/p2p-media-loader-core/test/stream-identity.test.ts`):
   `native/test/js/core_test.js`, `JsCoreTest.kt` and
   `JsEngineDeviceTest.kt`. When the web repository changes those vectors,
   change the tests with them, never to make a test pass.
5. `./gradlew :core:connectedAndroidDeviceTest` on the phone: it logs the
   bundle's load time. Measure the size of `libp2pml.so` as for QuickJS.
   Then the phone with the Node peer in one swarm
   (`tools/node-peer/README.md`, "A phone in the swarm").
6. The web peers of a mixed-swarm test must run a core with the same stream
   identity.

## tools/node-peer

A Node tool for measurements (see its README). Its packages are pinned by
`package-lock.json`. Prettier is pinned here too, and AGENTS.md uses this
Prettier to check the Markdown and JS of the whole repository.

1. In `tools/node-peer`: `npm outdated`, then update the versions in
   `package.json` and run `npm install`; the lockfile records the integrity
   of each package.
2. `npm test` and `npm run type-check`. After an update of
   node-datachannel, also run the phone with the Node peer in one swarm
   (the README, "A phone in the swarm").
3. A new Prettier can format in a different way: run it on the files that
   AGENTS.md lists, and commit its output on its own.
4. `p2p-media-loader-core` comes from the web repository with `npm run
pack-core` (its README); update it when the core that the library targets
   changes, not as a routine update.

## Verification

For every update, before you report it done:

1. `./gradlew spotlessApply`, then `./gradlew build --console=plain`: no
   warning; the C and JS tests, with all WPT cases, and the JVM tests pass.
2. `./gradlew build` a second time: the configuration cache is reused.
3. For a change to the NDK, CMake, QuickJS, Ada, the web core or the C or
   C++ flags:
   `./gradlew :core:connectedAndroidDeviceTest` on the phone. Ask the user
   to connect it.
4. For a change to Media3 or the web core:
   `./gradlew :media3:connectedAndroidTest` on the phone (it needs the
   internet).
5. After the push that the user asks for: the CI run of the commit passes
   (`gh run list --branch <branch>`, then `gh run watch <id>`).
6. The report names each version change (old and new), the release notes
   that matter, each fix the update needed, and the measured sizes and times
   where the native code changed.

## Commit messages

One commit per group, as AGENTS.md says for messages: what changed and why,
in simple English. Name the old and the new version, the reason for the
update (security fix, needed feature, routine), and each code change that the
update needed. For example: "Update the NDK from r30 to r31: …".
