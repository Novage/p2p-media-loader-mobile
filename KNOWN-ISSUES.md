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
- **The API is not complete.** `Core` does not have the DASH segment index
  of the JS core (`isSegmentIndex`, `processSegmentIndex`), and its settings
  do not have the JS function settings `httpRequestSetup`,
  `validateP2PSegment` and `customSegmentStorageFactory` (specs/api.md,
  "Functions in the settings"). There is no `:proxy` module (specs/api.md).
- **A `segmentFilter` is slow on long live windows.** The core asks it for
  each listed segment at each playlist refresh, and each call crosses from
  JS to Kotlin and back with JSON: about 35 µs on a OnePlus 7. A two-hour
  window of 2 s segments, 3600 segments, then costs about 120 ms of the
  core's thread for each refresh of each playlist, and the core's own work
  on it is as much again (specs/api.md, "Functions in the settings"). A
  call for all the segments of a refresh at once would need a change in the
  web core, which asks for each segment.
- **A preloaded item starts without the core.** The core serves the item
  that plays (specs/api.md, "Media3"). An item that Media3 prepares ahead,
  as a preload or the next item of a playlist, loads over HTTP until it
  plays. A preloaded live item starts where Media3 places it, and the
  core's placement applies from the hand-over, at its next playlist. The
  first item gets the core on the player's thread, after Media3 prepares
  its source: when that thread is busy, its first playlists load before the
  hand-over, and a live item can start where Media3 places it.
- **The engine finds a source by what its `MediaItem` loads.** Of two items
  of one URL in one playlist, it can give the core to the other one's
  source, whose loads then go over HTTP. A source of the app's own
  (`setMediaSource`) for the URL of the engine's item that it replaces
  keeps the core in that item's swarm until the item changes again. A fix needs a way to tell such
  a source from a recovery, where Media3 prepares the engine's source again.
- **A playlist can reach the core of the next item.** The engine gives a
  playlist to the core when its source has the core. A playlist whose load
  ends just when the current item changes can pass that check and reach
  the next item's core, which then registers its streams; when it is the
  first, it also names the next item's swarm, which then plays without P2P.
  The hand-over (specs/api.md, "Media3") has the same window: the old
  item's playlist that it gives can land after the next change, and a
  playlist that the new item loads while its hand-over waits can reach the
  core before the master. A kept live playlist that the hand-over gives
  after a newer one that a loading thread gave makes the core drop the
  newest segment, which loads over HTTP, until the next refresh. After a stop, such a playlist brings the core
  back into the old item's swarm until the next item or `close()`. A fix
  runs the core's change and the hand-over in one order, and holds a
  source's playlists until its hand-over.
- **A stop after an error keeps the core in its swarm.** `stop()` after a
  player error leaves the player idle with the error, which the engine
  takes for a recovery: the core stays until the next item, a clear of the
  items, a release of the player, or `close()`.
- **The core does not use Media3's `upstream` for its segments.** A cache
  (`CacheDataSource`), a URL signer (`ResolvingDataSource`) or request
  headers in `upstream` apply to the playlists, keys and the segments that
  the core does not load, but the core loads its segments itself, as in a
  browser. A cached segment loads again, and a segment from the core does
  not go into the cache. A segment that needs the signer or the headers
  fails in the core, after its retries, and then loads through `upstream`.
- **`Media3P2PEngine` makes HLS sources only.** Its factory takes the place
  of `DefaultMediaSourceFactory`: a DASH, SmoothStreaming or progressive
  item fails, and the factory does not add what `DefaultMediaSourceFactory`
  adds to an item, such as side-loaded subtitles, clipping and ads. DASH is
  in the scope (specs/scope.md) and not done.
- **The bandwidth estimate can stay at an old value.** After the core gives
  one bandwidth, the meter keeps its last value: when every later load fails
  in the core and goes to `upstream`, the estimate does not follow the
  network.
- **A playback report can come after the next request.** The Media3 engine
  reports the buffer after each chunk on the player's application thread,
  and the player can ask for the next segment first, on its loading thread.
  Near the core's urgent threshold, the core then reads that request as
  urgent and loads it over HTTP.
- **A date that the engine cannot read breaks the core's timeline.** An
  `EXT-X-PROGRAM-DATE-TIME` with an offset such as `+01` or `+1:00`, or a
  lowercase `z`, is valid ISO 8601, and Media3 reads it. The `Date` of
  QuickJS (and of V8, in the web library) gives NaN for it, so the core lays
  out the stream's segments at NaN times: `processManifest` gives NaN bounds
  and no live placement, every segment is in the core's download windows, and
  its storage evicts what it holds. Playback goes on; P2P works badly. A fix
  is in the core's HLS parsing.
- **Media3's segment URLs and the core's keys can differ.** The core
  resolves a playlist's URIs with the WHATWG URL parser, which lowercases
  the host, drops a default port and percent-encodes spaces and non-ASCII
  characters; Media3 resolves them as written. For a stream with such a
  URL, `isSegmentLoadable` is false for every segment, and the stream plays
  without P2P. A browser resolves both with the same parser. A fix
  normalises the request URL in the core.
- **The Media3 engine asks for whole playlists.** The core reads a delta
  update (`EXT-X-SKIP`) as a whole playlist and would number its segments
  wrongly, so the engine removes `_HLS_skip` from each playlist request
  (specs/api.md, "Media3"). A live stream with a long window then sends its
  whole playlist at each reload. A fix reads delta updates in the core.
- **A resumed byte-range segment counts as a registry miss.** When a read
  from `upstream` fails, Media3 asks for the rest of the segment, and the
  Media3 engine asks the core with that partial range. The core does not
  know it, so it logs and counts a registry miss, though the playlists
  agree. The request loads through `upstream`, as it would anyway.
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
