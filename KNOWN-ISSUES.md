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
  includes QuickJS (MIT), Ada (MIT) and the web core's bundle:
  p2p-media-loader-core (Apache-2.0) with the packages bundled into it,
  m3u8-parser (Apache-2.0), @videojs/vhs-utils, global, debug and ms (MIT).
  Their notices must reach the app's users. How the library gives its
  notices to apps (a file in the AAR, the documentation) is decided when
  publishing is set up.
- **The close cannot stop every endless loop.** The engine stops JS with an
  error that JS cannot catch (specs/engine.md, "Closing"), but QuickJS
  changes that error on some paths. An async function, a Promise executor,
  `Promise.try` and the other Promise functions that call JS turn it into a
  rejected Promise. The close of an iterator after an error (in `Array.from`
  with a map function, iterator helpers, the `Map` and `Set` constructors,
  spread and `Object.fromEntries`) makes it an error that `catch` catches. A
  loop around such a path goes on after the close and keeps the engine
  thread busy, such as `for (;;) spin()`, where `spin` is an async function
  with an endless loop. The core has no endless loop.
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
- **Ktor can change a request's URL.** `fetch` and `WebSocket` give Ktor the
  URL as the WHATWG parser writes it, and Ktor parses it again before OkHttp
  sends it: a repeated query name is regrouped (`?a=1&b=2&a=3` goes out as
  `?a=1&a=3&b=2`), an empty pair is dropped (`?x=1&&y=2`, `?=x&k`), and OkHttp
  encodes `|` and `^` in a path. A URL that a CDN signed over its exact text,
  or a cache key that uses it, can then fail where a browser succeeds. A fix
  that sent the exact URL through an OkHttp interceptor needed OkHttp to
  follow the redirects, which blocked those from http to https; it was
  removed. See specs/engine.md, "The platform's network clients".
- **Some request headers make a fetch fail.** OkHttp refuses a header value
  with a character above U+007F, which `Headers` accepts up to U+00FF, and
  Ktor refuses the headers that it sets itself: `Content-Length`,
  `Content-Type`, `Transfer-Encoding` and `Upgrade`. The request then fails as
  a network error, where a browser sends the Latin-1 value and drops or keeps
  the header. The core sends only `Range`; only an app's `httpRequestSetup`
  can add such a header.
