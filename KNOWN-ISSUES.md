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
  When `libp2pml.so` links the WebRTC library, it adds libdatachannel and
  libjuice (MPL-2.0, which also needs the app to say where their source is),
  usrsctp (BSD-3-Clause), plog (MIT) and Mbed TLS (Apache-2.0). Their notices
  must reach the app's users. How the library gives its notices to apps (a
  file in the AAR, the documentation) is decided when publishing is set up.
  The downloaded sources have their licence files, except Ada's archive
  (`singleheader.zip`): its MIT text is in its repository.
- **A fresh build needs bellard.org.** The build downloads QuickJS from
  bellard.org, its only source (`native/cmake/deps.cmake`), as it downloads
  the other native sources from GitHub. When that server is down, a new
  clone, a CI run and a pin change cannot build. A CI cache of
  `native/.deps`, keyed on the hash of `native/cmake/deps.cmake`, would keep
  CI building and save its download of about 60 MB.
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
  one `NoClassDefFoundError`. `Core` then has no core: the player loads every
  request itself and plays without P2P (specs/api.md).
- **The API is not complete.** `Core` does not have these calls of the JS
  core: the events (`addEventListener`, `removeEventListener`),
  `applyDynamicConfig`, `updatePlayback`, `setManifestResponseUrl`,
  `hasSegment`, `getStream`, `getStreams`, and the DASH segment index
  (`isSegmentIndex`, `processSegmentIndex`). There is no player integration
  yet: no `:media3` (`Media3P2PEngine`) and no `:proxy` module
  (specs/api.md). With no playback state, the core sees an empty buffer:
  every request is urgent and goes over HTTP, so a player gets no segment
  from peers.
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
- **A data channel's `bufferedAmount` counts less than a browser's.**
  libdatachannel counts only its own queue, not the bytes that usrsctp took
  into its send buffer (1 MiB, usrsctp's default, which `rtcSetSctpSettings`
  can change for the whole process before the first peer connection). So
  the core's pacing of uploads (`bufferedamountlow` at 64 KiB) starts later
  than in a browser, and more data waits in the buffer.
- **A peer connection that is "disconnected" does not recover.**
  libdatachannel closes it after a disconnection, where a browser can go back
  to "connected"; there is no ICE restart. The core then connects again
  through its tracker.
- **libjuice uses one STUN server.** Of the configuration's STUN servers,
  libdatachannel's ICE (libjuice) takes one, at random, and it has no STUN
  or TURN over TCP or TLS: the polyfill skips `stuns:`, `turns:` and a TCP
  or TLS transport, and uses the other servers of the list. A browser tries
  every server.
- **A data channel never fires `error`.** libdatachannel has no error for a
  data channel; a channel that fails closes (`closing`, then `close`).
- **A WebRTC event is lost when native memory is out.** When
  `p2pml_channel_post` cannot copy an event on libdatachannel's thread, the
  event is lost and the peer connection stays up, also for a `message`,
  `open` or `datachannel` event that JS must get. The core sees the missing
  bytes of a segment as a size mismatch or a timeout, and loads the segment
  again. Not fixed: a close of the peer connection from inside
  libdatachannel's callback can deadlock the library. A safe fix needs new
  state: a task that is allocated in advance for each peer connection, which
  then fails it on the engine thread.
