# Known issues

Issues found and not fixed, and gaps between the code and a spec. A fix
removes its entry. A decision to keep a behaviour by design goes in the spec
that describes the behaviour, not here.

## Open

- **The library's Kotlin metadata is for Kotlin 2.4.** The build sets no
  `apiVersion` or `languageVersion`, so a published build could not be used
  by an app that compiles with Kotlin 2.2 or older (a Kotlin compiler reads
  metadata one version ahead). The oldest Kotlin the library supports is
  decided when publishing is set up, and these two options follow from it.
- **The licence notices an app must ship are not set up.** The library
  includes QuickJS (MIT), whose notice must reach the app's users. How the
  library gives its notices to apps (a file in the AAR, the documentation) is
  decided when publishing is set up.
- **Uncaught JS errors are not reported.** An exception from a Promise job
  (a FinalizationRegistry callback, a failure of QuickJS) and a rejected
  Promise without a handler are dropped with no log (`run_jobs` in
  `native/src/engine.c`). The report of uncaught errors comes with `console`.
- **The NDK is a pre-release.** `gradle/libs.versions.toml` pins NDK
  28.0.12674087, which is r28 beta 2, and the clang-format version follows
  it. A stable NDK must replace it before a release; the SDK Manager lists a
  beta NDK only on its beta channel.
- **No native library for 32-bit x86.** `native/` builds arm64-v8a,
  armeabi-v7a and x86_64 only. On an x86 device or emulator, an app with no
  other x86 code runs the ARM library through translation where the system
  has it, and else does not install. An app that has other x86 code installs
  as x86: its first `JsEngine` throws `UnsatisfiedLinkError`, and each later
  one `NoClassDefFoundError`.
  The public API, still to be designed, must then play without P2P.
- **A Gradle deprecation does not fail the build.** Two plugins call
  `Configuration.setVisible`, which Gradle 9.9 deprecates: detekt
  2.0.0-alpha.6 (`DetektBasePlugin`, `DetektPlugin`) and Spotless 8.9.0
  (`GradleProvisioner`, when it fetches ktfmt). So
  `org.gradle.warning.mode=fail` would fail the build, and Gradle prints a
  summary of deprecations instead. Set the mode to `fail` in
  `gradle.properties` once releases of both stop the call.
