# Proposal: the web core in an embedded JS engine

Not a spec. This proposal describes how the library could run the P2P core,
and the measurements that decide whether it is built this way. See
"`specs/proposals/` is the one exception" in [`AGENTS.md`](../../AGENTS.md).

## The gap

The library needs a P2P core that meets web peers in the same swarm: the same
swarm IDs, the same segment identity, the same peer messages, and the same
scheduling and prefetch rules, so that a mobile peer helps a swarm as much as
a browser does. The web core is about 16,500 lines of TypeScript, tested
against real streams. A core written again in Kotlin must match it in every
detail, and stay matched with every web release.

## The design

The library runs the web core, unchanged, in a JS engine embedded in the app:

| Platform | Engine  |
| -------- | ------- |
| Android  | QuickJS |
| iOS      | QuickJS |

One engine on both platforms shares the binding and the native shims. The
engine's thread, loop and closing are in the spec [engine.md](../engine.md). The
system's JavaScriptCore on iOS would save the engine's code size there, and
possibly memory; it is the proposal
[javascriptcore-on-ios.md](javascriptcore-on-ios.md).

The engine runs the bundle the web core ships, the core and its HLS parser in
one file with no imports (`dist/p2p-media-loader-core-hls.es.js`). The library
does not bundle the core itself, so a phone runs the same published build of
the core as the web. The bundle is an ES module, which QuickJS loads as it is.
The package's `lib/` entry is for bundlers, not for engines.

### What the engine must give the core

The engine has only ECMAScript. A run of the core in Node recorded each host
global the bundle's code reads (the core and the packages bundled with it),
during a run with a peer, so the list below covers the tracker, the peer
connection, and downloads and uploads between peers. `window` and the WebRTC
classes must exist before the bundle loads: the core reads WebRTC once, at
load.

| Group             | Globals the core reads                                         | Given by                                              |
| ----------------- | -------------------------------------------------------------- | ----------------------------------------------------- |
| WebRTC            | `RTCPeerConnection`, `RTCSessionDescription`                   | libdatachannel                                        |
| Network           | `fetch`, `Request`, `Headers`, `WebSocket`                     | Ktor client                                           |
| Time              | `setTimeout`, `clearTimeout`, `performance.now`                | One event loop thread, the platform's monotonic clock |
| Logs              | `console`                                                      | The platform log                                      |
| Plain JS          | `AbortController`, `URL`, `TextEncoder`, `TextDecoder`, `btoa` | JS polyfills                                          |
| The global object | `window`                                                       | `window = globalThis`                                 |
| The user agent    | `navigator.userAgent`                                          | A value the library chooses                           |
| Probes only       | `document`, `localStorage`, `process`                          | Absent: the bundled logger checks for them            |

A record of globals cannot see everything a shim must give:

- **The members of instances.** The core reads a `Response` body in parts
  where it has `body.getReader()`, and as a whole at the end of the download
  where it does not. A streamed body is wanted, so that a download's progress
  reaches the core. The `WebSocket`, `RTCPeerConnection`, `RTCDataChannel`
  and `AbortSignal` instances need the members and events the core uses, and
  an aborted `AbortSignal` must cancel the native request.
- **Paths the run did not take.** `DOMException` is thrown when a request is
  aborted, and `atob` decodes a `data:` URI in the HLS parser. Both belong in
  the polyfills. Step 2.3a found one more: when `new WebSocket` throws (no
  `WebSocket`, or an invalid tracker URL), the core makes an `ErrorEvent`,
  which Node has; it is a polyfill too.

The network was decided in step 2.3a: `fetch` over Ktor, in common Kotlin
code, so that iOS uses the same code with Ktor's Darwin engine; on Android,
Ktor's OkHttp engine, which has HTTP/2 and the WebSocket of step 2.3b. The
`User-Agent` header is `navigator.userAgent`. OkHttp 5 needs apps to compile
against Android API 37. With R8, Ktor and its dependencies add 0.42 MB to the
sample app (5.56 MB to 5.98 MB). On the OnePlus 7, a 20 MB body went through
`fetch` at 34 to 40 MiB/s in the device test's debug build (three runs),
about 50 times the bitrate of 1080p.

Step 2.3b added `WebSocket`, over Ktor's `WebSockets` plugin on the same
client, so the network row is complete. The core opens no tracker socket
while the page has no `RTCPeerConnection` (it turns P2P off), so the core
connects to its trackers only with WebRTC (step 2.4b); the tests give it a
stub `RTCPeerConnection` to see its first announce, which needs no peer
connection.

Not needed: `queueMicrotask`, because the core has its own function of that
name, and `RTCIceCandidate`, because the core sends the whole SDP through the
tracker and does not trickle candidates.

Without `localStorage` and `process`, nothing turns on the core's debug logs.
The bundle exports `debug`, so the library turns them on with
`debug.enable()`.

Two points needed a decision in the Android step:

- **The user agent sets the core's memory limit.** The core's segment storage
  holds up to 1024 MiB when `navigator.userAgent` names an iPhone, an iPad or
  an Android WebView, 2048 MiB on other Android, and 4096 MiB otherwise. The
  core's `segmentMemoryStorageLimit` setting overrides that rule, so the
  library can set the limit for a phone itself, whatever the user agent says.
  The user agent also decides whether the logger adds browser colour codes
  (`%c`) to its lines.

  Decided in step 2.2c: the user agent is the library's own,
  `P2PMediaLoaderMobile/<version> (Android <release>)`, with no colour codes
  (see [engine.md](../engine.md), "Globals"). The library does not set the
  limit: the core's rule gives 1024 MiB for this user agent, and the app can
  set another value through the library's config. The limit is a cap, not
  what the core uses: on live, the segments kept behind and ahead of the
  playhead take tens of MB; on VOD with no peer, the core holds about what
  the player requests. On VOD with a peer, the core prefetches over HTTP up
  to 3000 s ahead and over P2P up to 6000 s ahead, until 90% to 95% of the
  limit is used, so its segments can take 0.9 to 1 GiB of native memory. The
  memory check below includes a VOD with a peer.

- **`bufferedAmount` counts differently.** libdatachannel counts only the
  bytes that SCTP has not accepted yet; a browser counts every byte from
  `send()` on. So the core's pacing of uploads starts later than in a browser.
  The binding gives the value as libdatachannel reports it. Chrome with the
  Node peer shared as well as two Chrome peers (see the results of step 1);
  two Node peers, with libdatachannel at both ends, were not compared over a
  matched window.

The Kotlin part is the same in this design and in a Kotlin core: the public
API, the request router, the local HTTP server, the player integrations, and
the policies for background, temperature and upload. Only the core host and
the API implementations above would change.

### Alternatives

- **A WebView that runs the core.** A WebView needs a visible view hierarchy
  on iOS to keep running, can be suspended in the background while audio
  plays, and has no API to keep its WebRTC connections alive. Each of these
  stops P2P while the player continues.
- **A core written in Kotlin with libdatachannel.** It removes the JS engine
  and its CPU cost, but it must match the web core in every rule above, and
  any difference splits the swarm without an error.

### Choices before the Android step

Two building blocks were chosen before the Android step: the binding between
Kotlin and QuickJS, for both platforms, and the WebRTC library. The facts come from each project's
repository, releases and sources on 2026-11-15, and from two spikes on a Mac.

**The QuickJS binding: our own, a thin C layer over Bellard's QuickJS, pinned
to its 2026-06-04 release, with JNI on Android and Kotlin/Native cinterop on
iOS.** The core is a long-lived library driven by native events: network
callbacks, data channel messages and timers arrive on other threads and must
call into JS on the engine's one thread, often with segment bytes.

| Candidate                                                                                                                           | Facts for the decision                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| ----------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [quickjs-kt](https://github.com/dokar3/quickjs-kt)                                                                                  | Active (v1.0.18, 2026-11-07), Apache-2.0, with Bellard's QuickJS. Kotlin cannot keep a JS function from it (a function property converts to the string `"[Function]"`, a function value to an empty object), and its async host functions work only inside one pending `evaluate()`, so every event would need a polling pump. Bytes are always copied, a JS view's `byteOffset` and `byteLength` are ignored, and a `ByteArray` passed to JS is not released (from its JNI sources). Its experimental `withNativeContext` gives the app's own C code the raw engine, but then the host would be our own C code anyway. |
| [Zipline](https://github.com/cashapp/zipline)                                                                                       | No host functions for plain JS (only its JSON-bridged Kotlin interfaces), no ES modules, no run of Promise jobs, and QuickJS from 2021.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| Others ([quickjs-wrapper](https://github.com/HarlonWang/quickjs-wrapper), [quickjs-kmp](https://github.com/HarlonWang/quickjs-kmp)) | quickjs-wrapper is Android and JVM only, with an engine from 2024; quickjs-kmp is from 2026-09 (release 0.1.4). Both copy bytes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| **Our own over a QuickJS C API**                                                                                                    | The C API has what the host needs: ES modules (`JS_EVAL_TYPE_MODULE`), Promise capabilities and a job loop the host runs (`JS_ExecutePendingJob`), JS functions kept as values, and typed arrays that wrap native memory with no copy (`JS_NewArrayBuffer`, `JS_NewUint8Array`, `JS_GetTypedArrayBuffer`). With no copy only on a native path, such as libdatachannel to QuickJS in C: bytes from Kotlin cross JNI with a copy, unless they come in a direct `ByteBuffer`. The cost: the binding owns reference counts and threading, where a mistake crashes the app; it needs tests under AddressSanitizer.           |

Two QuickJS projects have that API:

| Engine                                                            | Facts for the decision                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| ----------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **[Bellard's QuickJS](https://bellard.org/quickjs/)**, 2026-06-04 | About 25% faster than quickjs-ng at the text and regular expression work the core does (measured below). Tagged releases are rarer (2025-04-26, 2025-09-13, 2026-06-04), but the repository is active (at least 50 commits since 2026-08). MIT. About 0.75 MB on Android arm64 (a local stripped build of the 2026-06-04 release). It has no `btoa`, `atob` or `navigator`, which the library gives as for any engine; the core does not need `queueMicrotask`. |
| [quickjs-ng](https://github.com/quickjs-ng/quickjs) 0.18          | A release about every two months, test262 on each change, several maintainers. MIT. About 0.8 MB on Android arm64. More browser globals built in. Its no-copy constructors differ: `JS_NewArrayBuffer` and `JS_NewUint8Array` take a maximum length and a realloc callback (size 0 frees), where Bellard's take a free callback; it adds `JS_GetUint8Array`.                                                                                                    |

The rest of the API the binding uses is the same in both (module evaluation,
the job loop, Promise capabilities, `JS_NewCFunctionData`,
`JS_GetTypedArrayBuffer`). The binding builds its no-copy arrays in one
adapter function, so quickjs-ng stays a fallback at little cost. Both embed
the same libregexp, libunicode and dtoa. libregexp follows ES2023, which
covers the core: its regular expressions use only the `g` and `i` flags.

A JVM build of the JNI library runs the host's unit tests on a Mac.

**The WebRTC library: libdatachannel, built from source with the NDK,
data channels only, with Mbed TLS.**

| Candidate                                                                 | arm64 `.so`                                                                                | Notes                                                                                                                                                                                                                                                                                                                                |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **libdatachannel, own build** (`NO_MEDIA`, `NO_WEBSOCKET`, `USE_MBEDTLS`) | 3.8 MB stripped, as stored in the app (1.4 MB as a download), from a default Release build | Built with NDK 28 in about 20 s on a Mac, as the build script timed it; aligned to 16 KB pages, as Google Play requires for apps that target API 35; needs only Android system libraries. Its C API (`rtc.h`) is the same for a later cinterop binding on iOS. Android is a named target, but its CI does not test Android. MPL-2.0. |
| libdatachannel-java-android (`tel.schich`)                                | 8.7 MB stripped                                                                            | Includes media and OpenSSL, libdatachannel 0.24.1, and is aligned to 4 KB pages, which Google Play will not accept for updates from February 2027.                                                                                                                                                                                   |
| libwebrtc, `io.github.webrtc-sdk:android-prefixed-stripped`               | 7.7 MB                                                                                     | Chrome's own SCTP stack, but twice the size, and no `bufferedAmountLowThreshold` (only a change callback).                                                                                                                                                                                                                           |

Mbed TLS must be built with `MBEDTLS_SSL_DTLS_SRTP` for libdatachannel to
compile, even without media. What the binding must add to match a browser: a
`closing` state (libdatachannel has none) and the calls from library threads
posted to the engine thread. `bufferedAmount` leaves out up to the SCTP send
buffer (1 MiB by default, settable with `rtcSetSctpSettings`).

**Licences.** The obligations pass to every app that ships the library:
libdatachannel and libjuice are MPL-2.0, a copyleft for each file, so an app
must say where the source of those files is, our changes included; usrsctp is
BSD-3-Clause and Mbed TLS Apache-2.0 (or GPL-2.0-or-later; the library takes
Apache-2.0), and both need their notices; QuickJS is MIT; this repository
is Apache-2.0.

**Risks.** libdatachannel has one main maintainer, and its usrsctp comes from
that maintainer's fork. The build pins Mbed TLS 3.6, a long-term support line;
the move to Mbed TLS 4 must be checked, though libdatachannel already has code
for both. Shipping Mbed TLS for DTLS means an app answers the stores'
encryption export questions: the App Store asks about encryption not provided
by iOS, and US export rules (EAR 740.17) cover standard cryptography in mass
market software.

### The speed of QuickJS, measured

On the Mac, against V8 with its compilers and its regular expression compiler
off (`--no-opt --no-maglev --no-sparkplug --regexp-interpret-all`), and
against JavaScriptCore without its JIT (`jsc --useJIT=false`), in several runs
each. Bellard's QuickJS is its 2026-06-04 release tarball:

| Benchmark                        | Bellard's QuickJS | quickjs-ng 0.18 | V8, interpreted | JavaScriptCore, no JIT |
| -------------------------------- | ----------------- | --------------- | --------------- | ---------------------- |
| Arithmetic loop                  | 543–585 ms        | 598–621 ms      | 365–369 ms      | 967–1107 ms            |
| Playlist-like text parsing       | 182–206 ms        | 250–262 ms      | 117–121 ms      | 110–125 ms             |
| HLS attribute regular expression | 371–373 ms        | 475–487 ms      | 434–453 ms      | 288–297 ms             |
| Cut and join segment bytes       | 8–9 ms            | 9–11 ms         | 11–26 ms        | 11–17 ms               |

So Bellard's QuickJS is about 1.5 to 1.7 times slower than V8's interpreter
for the core's logic and a little faster for regular expressions; quickjs-ng
is 1.6 to 2.2 times slower and about the same. Byte copies are short native
calls in all of them, and their runs are too short for a stable factor. V8
with only its compilers off still compiles regular expressions, about 5 times
faster than this, so a CPU run without `--regexp-interpret-all` understates
the cost in an engine without a JIT. The core's bundle (alpha.7) loaded in
both QuickJS engines in 10 to 40 ms, processed the BBB playlists in 1 ms, and
gave the same infohashes as in Node and in browsers. In the library's engine
(step 2.2c), the first import of the bundle takes 16 to 33 ms in an `-O2`
build on the Mac, and 35 to 120 ms on the OnePlus 7 in the device test's
debug build, which has no optimization (three runs). The bundle adds 0.37 MB
to `libp2pml.so` (1.56 MB in all, arm64, stripped) and 1.8 s to a new CMake
configure.

## Measurements that decide

The design is adopted when all of these pass. The CPU and memory limits are
the starting values; they are set again if a measurement shows they are wrong.

| Check      | Pass                                                                                                           |
| ---------- | -------------------------------------------------------------------------------------------------------------- |
| Interop    | A mobile peer and browser peers exchange segments in one swarm, in both directions                             |
| P2P ratio  | Near the ratio of two browser peers on the same stream                                                         |
| CPU        | The library adds less than about 10% of one core at 1080p on a mid-range phone, compared with the player alone |
| Memory     | Stable over a 1-hour run, on live and on VOD with a peer                                                       |
| Background | Audio continues with the screen locked, and P2P follows the background policy                                  |
| Thermal    | No change of the thermal state that the library causes in 30 minutes                                           |

The checks run in this order, because each one costs more than the one before
it:

1. **The core in Node, with libdatachannel through `node-datachannel`.** Node
   has the other browser APIs, so this tests interop alone. A run with V8's
   compilers and regular expression compiler off gives a first estimate of the
   cost in an engine without a JIT, as QuickJS is. Done; the results are below.
2. **Android:** QuickJS, the native APIs above, and Media3. All checks.
3. **iOS:** QuickJS, the native APIs above, and AVPlayer. All checks.

If interop fails in step 1, both designs have a problem, and it is solved
before anything else. If the CPU or thermal checks fail in step 2 or 3, the
Kotlin core is the alternative to measure.

### Results of step 1

The tool is `tools/node-peer` (see its README): a headless HLS player that
plays a live stream through the core the way HLS.js with the P2P Media Loader
adapter does. The runs used the web core at commit `7b9c418a`, the BBB live
stream at 1080p, and test swarms that no other peer joined. All peers ran on
one Mac.

- **Interop: passed.** Chrome (the web demo with HLS.js) and the Node peer
  exchanged segments in both directions. The bytes each side counted match
  exactly: what one peer downloaded from the other is what the other
  uploaded. The peers connected about 1 s after the Node peer started.
- **P2P ratio: passed.** Over 10 minutes, two Chrome peers took 1.00 copies
  of each segment from the origin. Chrome with the Node peer took 1.001
  copies over a window of 534 s, which started a minute after the Node peer
  joined, as the baseline's window started after both peers played. No peer
  had a stall. Two Node peers took about 1.06 copies over their whole runs,
  the join included; that is not a matched window, so it is not compared
  with the numbers above.
- **CPU: an early estimate, not a result.** Two Node peers played 10 minutes
  in one swarm, three times. In the first pair both peers used V8's JIT; in
  the second, one peer ran with V8's compilers off; in the third, one peer
  ran with its regular expression compiler off as well
  (`--regexp-interpret-all`), as in an engine without a JIT. The main threads
  were busy 1.1% to 1.7% of the time in all six runs: 1.2%, 1.3%, 1.1% and
  1.3% with the JIT, 1.6% with the compilers off, and 1.7% with regular
  expressions interpreted too. So the core's JS work is small, and
  interpreting it adds little. The processes, with all their threads, used
  3.0% to 3.4% of one core. These are upper bounds for the core: in Node they
  include HTTP, TLS and the test player. As a rough guess, not a
  measurement: if a mid-range phone core is 2 to 3 times slower than the
  Mac's, and Bellard's QuickJS 1.5 to 1.7 times slower than V8's
  interpreter (see "The speed of QuickJS, measured"), 1.7% becomes about 5%
  to 9% of one core for the JS work alone; with quickjs-ng, up to 11%. The
  10% pass limit is for the whole library, native threads included, so this
  is not a pass. The check is made on a phone in step 2.

What step 1 does not show, and steps 2 and 3 must: peers on different
networks and behind NATs, more than two peers, the handling of a closed
connection by the real binding (node-datachannel has its own JS layer), long
runs, the cost of moving segment bytes between native code and the engine,
and a phone's CPU, memory, temperature and background behaviour.

Nothing in step 1 argues for the Kotlin core. Steps 2 and 3 decide on the
remaining checks.
