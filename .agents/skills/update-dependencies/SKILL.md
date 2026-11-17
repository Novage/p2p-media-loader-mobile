---
name: update-dependencies
description: Use when updating any dependency of this repository — Gradle and its wrapper, the plugins and libraries of the version catalog, the Android SDK levels, the NDK, clang-format and CMake, the JDK and the CI actions, the vendored native code (QuickJS, Ada), the WPT URL test data, and tools/node-peer. Says where each one is pinned, where to find its releases, what to check, and how to verify and commit the update.
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
  (detekt is the one case today). Check the release _name_, not only the
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
  `gradle/libs.versions.toml`, the README of a vendored library, the entries
  of `KNOWN-ISSUES.md` that name a version (remove an entry that the update
  fixes), and `README.md` when a tool that a developer installs changes.
- **Commit only when the user says so,** as for any change.

## Where each dependency is pinned

| Dependency                                          | Pinned in                                                                          | Releases                                                                                                                                                   |
| --------------------------------------------------- | ---------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Gradle                                              | `gradle/wrapper/gradle-wrapper.properties` (version, sha256)                       | <https://gradle.org/releases/>, <https://services.gradle.org/versions/current>                                                                             |
| AGP (all `com.android.*` plugins)                   | `gradle/libs.versions.toml` `agp`                                                  | `https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml`; release notes on developer.android.com                        |
| Kotlin (KMP plugin)                                 | catalog `kotlin`                                                                   | `gh api repos/JetBrains/kotlin/releases`                                                                                                                   |
| detekt                                              | catalog `detekt`                                                                   | `gh api repos/detekt/detekt/releases`                                                                                                                      |
| Spotless, ktfmt                                     | catalog `spotless`, `ktfmt`                                                        | `gh api repos/diffplug/spotless/releases`, `gh api repos/facebook/ktfmt/releases`                                                                          |
| Media3 (sample app)                                 | catalog `media3`                                                                   | `gh api repos/androidx/media/releases`                                                                                                                     |
| androidx.test runner                                | catalog `androidx-test-runner`                                                     | Google Maven `androidx/test/runner/maven-metadata.xml`                                                                                                     |
| Android SDK levels                                  | catalog `android-*`                                                                | the platforms that the SDK Manager offers                                                                                                                  |
| NDK, its clang-format, CMake                        | catalog `ndk`, `clang-format`, `cmake`                                             | `https://dl.google.com/android/repository/repository2-3.xml`; the NDK's `source.properties` (`Pkg.ReleaseName`); <https://github.com/android/ndk/releases> |
| JDK of CI                                           | `.github/workflows/build.yml` (`java-version`)                                     | the lowest JDK that `README.md` names                                                                                                                      |
| GitHub Actions                                      | `.github/workflows/build.yml` (`uses:`)                                            | `gh api repos/<owner>/<action>/releases/latest`                                                                                                            |
| QuickJS                                             | `native/third_party/quickjs/` (files, `VERSION`, README's sha256)                  | <https://bellard.org/quickjs/>                                                                                                                             |
| Ada                                                 | `native/third_party/ada/` (files, README's sha256)                                 | `gh api repos/ada-url/ada/releases/latest` (each asset has a `digest`)                                                                                     |
| WPT URL test data                                   | `native/test/third_party/wpt-url/update.mjs` (`COMMIT`)                            | <https://github.com/web-platform-tests/wpt/tree/master/url>                                                                                                |
| node-peer packages, Prettier                        | `tools/node-peer/package.json`, `package-lock.json`                                | `npm view <package> version`                                                                                                                               |
| The web core of node-peer (`p2p-media-loader-core`) | `tools/node-peer/package.json`; the local `.core/SOURCE` names the packed checkout | the web repository; see `tools/node-peer/README.md`, "pack-core"                                                                                           |

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
   otherwise.

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
   our code; `native/third_party` builds with warnings off.
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
  bytecode target is pinned (`jvmTarget` in `library/build.gradle.kts`), not
  taken from the JDK.
- Update each `uses:` of `.github/workflows/build.yml` to its newest major
  version, and read its release notes for changed inputs (such as
  `cache-read-only` of `gradle/actions/setup-gradle`).

## QuickJS

Follow `native/third_party/quickjs/README.md`. Then check what the engine
depends on, because QuickJS has no stable API promise. Each item has tests,
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

Follow `native/third_party/ada/README.md`. Then:

- Read the release notes for changes to `ada_c.h`, which `native/src/url.c`
  calls, and to the defines that `native/CMakeLists.txt` sets
  (`ADA_INCLUDE_URL_PATTERN`). A new major version can change the C API.
- Ada is C++20, with exceptions and RTTI off; a new release can need a newer
  compiler (CI uses gcc 13 on Ubuntu 24.04).
- Check the exported symbols of `libp2pml.so` again (only JNI): Ada and
  libc++ are hidden by visibility and `--exclude-libs`.
- Update the WPT URL data with it (next section). All WPT cases must pass.

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

## tools/node-peer

A Node tool for measurements (see its README). Its packages are pinned by
`package-lock.json`. Prettier is pinned here too, and AGENTS.md uses this
Prettier to check the Markdown and JS of the whole repository.

1. In `tools/node-peer`: `npm outdated`, then update the versions in
   `package.json` and run `npm install`; the lockfile records the integrity
   of each package.
2. `npm test` and `npm run type-check`.
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
3. For a change to the NDK, CMake, QuickJS, Ada or the C or C++ flags:
   `./gradlew :library:connectedAndroidDeviceTest` on the phone. Ask the user
   to connect it.
4. After the push that the user asks for: the CI run of the commit passes
   (`gh run list --branch <branch>`, then `gh run watch <id>`).
5. The report names each version change (old and new), the release notes
   that matter, each fix the update needed, and the measured sizes and times
   where the native code changed.

## Commit messages

One commit per group, as AGENTS.md says for messages: what changed and why,
in simple English. Name the old and the new version, the reason for the
update (security fix, needed feature, routine), and each code change that the
update needed. For example: "Update the NDK from r30 to r31: …".
