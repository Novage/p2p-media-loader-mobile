# The JS engine

The library runs the web core in QuickJS (see the proposal
[js-core-host.md](proposals/js-core-host.md) for why). This spec describes the
engine that hosts it: its thread, its loop of tasks, the globals it adds, and
the rules for the code that calls it.

## Where the code is

| Part                                     | Code                                                                                    |
| ---------------------------------------- | --------------------------------------------------------------------------------------- |
| QuickJS, Bellard's release as released   | `native/cmake/deps.cmake` (the downloaded sources)                                      |
| The engine: the loop, globals, eval      | `native/src/engine.h`, `native/src/engine.c`                                            |
| The polyfills: JS, and their C helpers   | `native/src/polyfills/*.js`, `native/src/encoding.c`, `native/src/engine.c`             |
| URL parsing: Ada (C++), and its C glue   | `native/cmake/deps.cmake` (the downloaded sources), `native/src/url.c`                  |
| The web core's bundle, as a module       | `native/package.json` (the npm package), `native/src/modules.c`                         |
| The channels of the network's items      | `native/src/channel.h`, `native/src/channel.c`                                          |
| fetch: the requests, and their events    | `native/src/http.h`, `native/src/http.c`, `native/src/polyfills/fetch.js`               |
| WebSocket: the sockets, and their events | `native/src/websocket.h`, `native/src/websocket.c`, `native/src/polyfills/websocket.js` |
| The network clients, over Ktor           | `core/src/commonMain/…/http/HttpBridge.kt`, `WsBridge.kt`                               |
| WebRTC's library, libdatachannel         | `native/cmake/deps.cmake` (the downloaded sources), `native/test/rtc_test.c`            |
| WebRTC: the peers, over libdatachannel   | `native/src/rtc.h`, `native/src/rtc.c`, `native/src/polyfills/webrtc.js`                |
| The player bridge: the core of a player  | `native/src/player.h`, `native/src/player.c`, `native/src/polyfills/player.js`          |
| The player bridge for Kotlin             | `core/src/androidMain/…/player/CoreSession.kt`                                          |
| The public Core and its settings         | `core/src/androidMain/…/core/Core.kt`, `CoreConfig.kt` (see [api.md](api.md))           |
| The JNI glue, nothing else               | `native/src/jni.c`                                                                      |
| The engine for Kotlin                    | `core/src/androidMain/…/engine/JsEngine.kt`                                             |

The engine is plain C, so that iOS calls the same code through cinterop, and
native libraries such as libdatachannel call it with no JNI. The C code,
QuickJS, Ada and libdatachannel make one shared library, `libp2pml.so`. Ada
and libdatachannel are C++: the library links libc++ statically on Android,
and exports only the JNI functions.

The external sources are downloaded at build time, not kept in the
repository: QuickJS, Ada, and the WebRTC library (see "The WebRTC
library"). `native/cmake/deps.cmake` gives the URL and the sha256 of each
archive, and the Gradle task `:native:fetchNativeDeps` downloads them once
into `native/.deps` (outside `build/`, as AGP's `.cxx`: `clean` keeps them),
before any CMake configure. A wrong hash fails the build. No source is
patched, and their code is compiled without warnings and outside clang-tidy;
their headers are system headers.

## One engine, one thread

An engine has one QuickJS runtime and one thread. The platform creates the
thread (on Android, a `Thread` with a 2 MiB stack) and calls the engine's
blocking `run` on it. `run` creates the runtime on that thread, so that
QuickJS measures its stack from there (it stops JS at 1 MiB of stack, its
default), runs the loop until the engine is closed, then frees the runtime
and returns.

Only the engine thread touches the runtime. A call that needs the runtime
from another thread posts a task. In Kotlin, a call that needs the runtime
throws `IllegalStateException` on any other thread.

## The loop

The engine runs posted tasks one at a time, in the order they were posted.
Any thread posts a task; posting returns false once the engine is closed, and
the task then stays with the caller.

After each task, the engine runs QuickJS's Promise jobs until none is left,
before the next task: as a browser runs microtasks after a task. So a Promise
that a task resolves has run its reactions before the next task starts.

A timer that is due (see "Globals") runs as a task does, with the Promise
jobs after it. Tasks and due timers take turns, so a long queue of one does
not stop the other.

## Globals

The engine adds the globals of a browser that the web core uses and QuickJS
does not have. They are in C or in the built-in JS polyfills (see
"Polyfills"), so iOS gets the same ones.

- **`setTimeout(callback, delay, ...args)` and `clearTimeout(id)`**, as in a
  browser, with two differences: the callback must be a function, else
  `setTimeout` throws a `TypeError` (a browser also takes a string of code);
  and there is no `setInterval`, which the core does not use. The delay is
  converted as WebIDL's `long` is: NaN and Infinity are 0, a number is taken
  modulo 2³² as a signed 32-bit integer (so 2³¹ is negative), and a negative
  delay is 0. As in a browser, a timer set by a timer that is nested more
  than 5 deep has a delay of at least 4 ms, so a chain of `setTimeout(f, 0)`
  does not keep the thread busy. The callback gets the global object as
  `this`. The id is a positive number that grows; `clearTimeout` with an
  unknown id does nothing. Due timers run in the order of their deadlines,
  then in the order of their `setTimeout` calls.
- **`performance.now()`**: milliseconds since the engine started, with a
  fraction.
- **The clock** of the timers and of `performance.now()` is monotonic
  (`CLOCK_MONOTONIC`, on Apple platforms `CLOCK_UPTIME_RAW`): it does not
  follow changes of the wall clock, and it stops while the device sleeps, as
  Chrome's does.
- **`console.debug`, `log`, `info`, `warn` and `error`** write one line to
  the log: the arguments, joined by spaces. A string is written as it is, an
  `Error` as its string and then its stack on the next lines, and any other
  value converted as `${value}` does (an object gives `[object Object]`; a
  Symbol, which cannot be converted, gives a placeholder). There is no `%s`
  or `%c` substitution. On Android the log is Logcat, with the tag `P2PML`:
  `debug` at DEBUG, `log` and `info` at INFO, `warn` at WARN, `error` at
  ERROR. A `console` call that the close stops, or that starts after it,
  writes nothing and throws the close's error.
- **`window`**, which is `globalThis`.
- **`navigator`**, with only `userAgent`: a string that the platform gives
  when it creates the engine. JS cannot assign either of them, as in a
  browser (in strict code, an assignment throws a `TypeError`). On Android it
  is `P2PMediaLoaderMobile/<library version> (Android <release>)`
  (`UserAgent.kt`). The core reads it for the default of its segment memory
  limit, `segmentMemoryStorageLimit`: "Android" without "Chrome" or "Firefox"
  is an Android WebView to the core, 1024 MiB. The core's logger, the `debug`
  package, adds `%c` colour codes to its lines for "AppleWebKit/" or
  "Firefox/", which `console` does not substitute; this value has neither.
- **`TextEncoder` and `TextDecoder`** of the WHATWG Encoding Standard, for
  UTF-8 only: `encode`, and `decode` of an `ArrayBuffer`, a
  `SharedArrayBuffer` or a view of one, with the options `fatal` and
  `ignoreBOM`. A label of another encoding throws a `RangeError`, as an
  unknown label does. `decode` with `{stream: true}` and `encodeInto` throw a
  `TypeError`: the core does not use them.
- **`URL` and `URLSearchParams`** of the WHATWG URL Standard: Ada, the URL
  parser of Node.js, parses and serializes, as the standard defines, IDNA
  hosts included. `URL.createObjectURL` and `revokeObjectURL` (File API)
  are absent: the core does not use them.
- **`btoa` and `atob`** of the HTML Standard: `atob` is the forgiving base64
  decode. A code unit above U+00FF (`btoa`) or input that is not base64
  (`atob`) throws a `DOMException` named `InvalidCharacterError`.
- **`DOMException`** of WebIDL: an `Error`, with `name`, `message`, the
  legacy `code` and the legacy constants.
- **`Event` and `EventTarget`** of the DOM Standard. An `EventTarget` has no
  parent, so an event is dispatched at its target only: capture listeners
  first, then the others, in the order added; there is no capture or bubble
  path. `addEventListener` takes the options `capture`, `once`, `passive` and
  `signal`, and a function or an object with `handleEvent`. The legacy
  members of `Event` (`srcElement`, `returnValue`, `cancelBubble`,
  `initEvent`) are absent.
- **`AbortController` and `AbortSignal`**: `abort(reason)`, `aborted`,
  `reason`, `throwIfAborted`, `onabort` and `AbortSignal.abort`. The default
  reason is a `DOMException` named `AbortError`. `AbortSignal.timeout` and
  `AbortSignal.any` are absent: the core does not use them.
- **`ErrorEvent`** of the HTML Standard: `message`, `filename`, `lineno`,
  `colno` and `error`. The core makes one when a `WebSocket` cannot be
  created.
- **`fetch`, `Headers`, `Request` and `Response`** of the Fetch Standard, for
  what the core does: a GET or HEAD with headers, a streamed body, and an
  abort. The platform's HTTP client does the network work (see "The
  platform's network clients").
  - `Headers`: names are case-insensitive, values are joined with `, `, and
    iteration is sorted by name. A name that is not an HTTP token, or a value
    with NUL, CR, LF or a code point above U+00FF, throws a `TypeError`. A
    response's headers cannot change.
  - `Request(input, init)`: `url`, `method`, `headers` and `signal`. There is
    no document base, so a relative URL throws a `TypeError`; so does a
    `body`, which the core never sends. `mode`, `credentials`, `cache` and
    `priority` are taken and ignored: there are no cookies and no HTTP cache.
  - `Response`, which only `fetch` makes: `ok`, `status`, `statusText`,
    `url`, `redirected`, `headers`, `body`, `bodyUsed`, `arrayBuffer()` and
    `text()`. A HEAD response and a status without a body (101, 103, 204,
    205, 304) have no `body`.
  - The `body` is a stream with `getReader()` (`read`, `cancel`,
    `releaseLock`), `locked` and `cancel()`; `ReadableStream` is not a
    global. A cancel cancels the request.
  - `fetch(input, init)` sends `navigator.userAgent` as `User-Agent`, unless
    the request has one. An aborted signal rejects at once with its reason.
    An abort before the response rejects the `fetch`; after it and before
    the body's end arrives, the pending and later reads; both cancel the
    request. A network failure rejects with
    `TypeError("Failed to fetch: <reason>")`.
  - Absent, as the core does not use them: a request body, `clone()`,
    `json()`, `blob()`, `formData()`, `Response`'s constructor and static
    methods, cookies and the HTTP cache.
- **`WebSocket`** of the WebSockets Standard, with **`MessageEvent`** and
  **`CloseEvent`** of the HTML Standard, for the core's trackers. The
  platform's WebSocket client does the network work (see "The platform's
  network clients").
  - `new WebSocket(url, protocols)`: `ws:` and `wss:`, and `http:` and
    `https:`, which become `ws:` and `wss:`. Another scheme, a fragment, a
    subprotocol that is not an HTTP token, or one that is there twice, throws
    a `SyntaxError` `DOMException`.
  - `url`, `readyState` and its constants, `protocol` and `extensions` (the
    server's), `bufferedAmount`, `binaryType`, `send`, `close`, and the
    `onopen`, `onmessage`, `onerror` and `onclose` handlers.
  - `binaryType` is `"blob"` by default, as in a browser, but a binary
    message is always an `ArrayBuffer`: there is no `Blob`. The core sets
    `"arraybuffer"`.
  - `send(data)`: a string goes as UTF-8 text, an `ArrayBuffer` or a view as
    binary. While connecting it throws an `InvalidStateError`. After the
    close starts, the data is dropped and counts in `bufferedAmount`, as in a
    browser; else `bufferedAmount` is 0, because the platform queues.
  - `close(code, reason)`: a code other than 1000 or 3000–4999 throws an
    `InvalidAccessError`, a reason over 123 bytes of UTF-8 a `SyntaxError`.
    While connecting, it fails the connection: `error`, then `close` with
    1006, in a later task. With no code, the close frame has 1000 (OkHttp
    always sends a code; a browser sends none).
  - A failed connection, or one the network cuts, fires `error` (an `Event`),
    then `close` with 1006, not clean. A message's `origin` is the URL's
    origin.
  - The handshake sends `navigator.userAgent` as `User-Agent`, and no
    `Origin`: there is no page. There are no pings, as in a browser.
  - Not checked, as the core asks for no subprotocol: that the server's
    subprotocol is one that the socket asked for.
- **`RTCPeerConnection`**, **`RTCDataChannel`** and
  **`RTCSessionDescription`** of WebRTC, with **`RTCPeerConnectionIceEvent`**
  and **`RTCDataChannelEvent`**, for the core's peers: data channels only.
  libdatachannel does the network work (see "The WebRTC library").
  - `new RTCPeerConnection(configuration)`: `iceServers` (`urls`, a string or
    a list; `username` and `credential` for TURN) and `iceTransportPolicy`.
    A URL that is not STUN or TURN, one that libdatachannel cannot parse, or
    an empty `urls` list throws a `SyntaxError`; TURN with no credential an
    `InvalidAccessError`. STUN or TURN over TLS or TCP is skipped, as
    libjuice skips it (KNOWN-ISSUES.md).
  - The steps of the spec: `createOffer()` and `createAnswer()` resolve with
    a description that is not set; `setLocalDescription(description)` sets a
    new one of its type, equal to it (libdatachannel's ICE credentials and
    fingerprint belong to the transport; the given SDP is not read);
    `setRemoteDescription(description)`. Each sets `signalingState` and fires
    `signalingstatechange` before its promise resolves. There is no trickle
    ICE: `localDescription`, read when gathering is complete, has every
    candidate, as the core sends it; the one `icecandidate` event has a null
    candidate, after `icegatheringstatechange` to `"complete"`.
  - `iceGatheringState`, `iceConnectionState` and their events;
    `datachannel`, whose channel then fires `open`.
  - `createDataChannel(label)`: ordered and reliable; another option throws a
    `NotSupportedError`.
  - `RTCDataChannel`: `label`, `ordered`, `readyState`, `bufferedAmount`
    (libdatachannel's queue, read when asked), `bufferedAmountLowThreshold`,
    `binaryType`, `send`, `close`, and the `onopen`, `onmessage`,
    `onbufferedamountlow`, `onerror`, `onclosing` and `onclose` handlers. A
    binary message is always an `ArrayBuffer` (there is no `Blob`), as the
    core asks.
  - `send(data)`: a string as text, an `ArrayBuffer` or a view (only its
    bytes) as binary. Not open: an `InvalidStateError`; above the maximum
    message size: a `TypeError`.
  - `close()` of a channel: `"closing"`, then `close`; a channel that the
    other side closes fires `closing`, then `close`. `close()` of the peer
    connection: every state `"closed"`, its channels closed with no event, as
    the spec; again does nothing.
  - Not there, as the core does not use them: `RTCIceCandidate`,
    `addIceCandidate`, `getStats`, `connectionState`, `negotiationneeded`,
    renegotiation and ICE restart.

## Polyfills

A global that is mostly API surface is written in JS, with its byte work in
C. The base64 byte work of `btoa` and `atob` is QuickJS's own: the
`Uint8Array` base64 functions, with `fromBase64` in its "loose" mode, which
is the forgiving decode. The parsing and serializing of `URL` and
`URLSearchParams` is Ada's, which `url.c` calls through Ada's C API. The JS
files are in `native/src/polyfills/`; the build turns each into a C array
(`embed_js` in `native/CMakeLists.txt`), so they are part of `libp2pml.so`.
When the engine starts, after the globals in C, it evaluates each file in the
order the build lists them. A file is one
function expression, `(natives) => { … }`, which the engine calls with an
object of C helpers; the helpers do not become globals. If a polyfill fails,
the engine does not start, as when the runtime cannot be created.

A polyfill can also put helpers on that object for the polyfills after it:
`webidl.js` gives WebIDL's object test, the conversions of a USVString, of
options and of a sequence of pairs or a record, the count of required
arguments, interface constants, the iterator of a pair iterable, and the way a
class or an operation becomes a global; `events.js` gives `on…` handler properties,
the dispatch of a trusted event, and abort steps that run before a signal's
`abort` event, for the shims of `fetch`, `WebSocket` and WebRTC.

## The platform's network clients

The network work is the platform's. The engine keeps the items that it runs
for JS in channels (`native/src/channel.c`): an id for each item, the open
ones, one JS listener, and the item's events, which the platform posts from
any thread and the engine gives to JS in tasks. When JS cannot take an event
that it needs (out of memory, or a defect of the polyfill), the channel stops
the item and gives JS a failure in its place, if JS can take that (if JS's
own memory is out too, the item waits until the core's own timeout). At the
close, it stops each open item.

For `fetch`, the engine calls the platform's client (`p2pml_http_client` in
`native/src/http.h`) on the engine thread, to start a request (method, URL and
headers) and to cancel one. The platform then gives the request's events from
any thread: a response (status, status text, URL after redirects, headers),
any data, then an end; or a failure at any point. Each event is copied into a
task, so JS gets it on the engine thread, after the tasks before it.

- **The bytes** are copied once, from the platform's buffer into memory that
  the JS `ArrayBuffer` of the chunk then owns.
- **No backpressure:** the platform reads as fast as the network gives, and
  the chunks wait in the queue of tasks while JS is busy. A segment bounds
  them: a few MB.
- **Cancel:** after the engine cancels a request (an abort, a cancel of the
  body, or the close), no event of it reaches JS; a late one is dropped.
- **Android and iOS:** the client is `HttpBridge`, over Ktor, in common
  Kotlin code: a coroutine per request, which reads the body in chunks of up
  to 64 KiB. On Android, Ktor's OkHttp engine; one Ktor client per process.
  It follows redirects, except one from `https` to `http` (as Media3's
  `DefaultHttpDataSource` by default): `fetch` then gives the redirect's own
  response. It has no cookies, no HTTP cache and no call timeout (the core
  has its own timeouts, and aborts). A read that waits 30 s for bytes (also
  for the response's headers) fails: far above the core's default timeouts (3
  s with no bytes, 10 s to the first byte), it ends a read that stalled after
  the core gave up, because a cancel does not stop a blocked read, and such a
  read would hold its thread and socket for good. A core timeout that is set
  above 30 s is cut to 30 s. It connects within 10 s, and starts up to 6
  requests at once to one host (OkHttp's per-host limit counts a request until
  its headers arrive, not while its body downloads). A response of
  any status is a response. The engine for Kotlin delivers the events only
  while its native engine exists, under the lock that the free takes.
- **Cleartext HTTP** follows the app's network security config: from
  `targetSdk` 28 on, Android refuses it unless the app allows it, and the
  request fails as a network error. The library's manifest asks only for
  `INTERNET`; only its device-test APK allows cleartext, for its local
  server.

For `WebSocket`, the engine calls the platform's client (`p2pml_ws_client` in
`native/src/websocket.h`) on the engine thread, to open a socket (URL and
subprotocols), send a message, start the closing handshake, and stop a
socket. The platform gives the socket's events from any thread: an open (the
server's subprotocol and extensions), any messages, then a close (code,
reason, whether it was clean, whether the connection failed). An open or a
message that JS cannot take fails the socket (JS gets `error`, then `close`
with 1006), so that JS never goes on as if it got every message. A `close()`
while connecting, such a failure, and the close of the engine stop a socket:
no event of the platform reaches JS after that, but the platform can still
send a close frame of any code.

- **Text with invalid UTF-8** from the server: on Android, OkHttp decodes it
  with U+FFFD. A browser fails the connection: JS gets `error`, then `close`
  with 1006, and the server gets 1007.
- **Text** crosses JNI as UTF-8 bytes, in both directions: JNI's strings
  are modified UTF-8, which differs above U+FFFF (a peer id in a tracker
  message can hold any code point).
- **Android and iOS:** the client is `WsBridge`, over Ktor's `WebSockets`
  plugin on the same client as `fetch`: a coroutine per socket. OkHttp's
  read timeout does not count the time between frames, so a tracker that is
  silent for minutes does not fail the socket; a frame that stalls for 30 s
  does. A stop cancels the socket's coroutine:
  Ktor then sends a close frame (code 1011, or 1000, as its cancel goes),
  also after a handshake that was still running, and on Android OkHttp waits
  up to 60 s for the server's answer.

Both clients run over OkHttp 5 on Android, which needs API 37: apps that use
the library compile against Android API 37 or later (`minCompileSdk`).

## The WebRTC library

WebRTC's data channels come from libdatachannel (C++, with a C API,
`rtc.h`), built from source with the rest of the native code and linked into
`libp2pml.so`. It needs libjuice (ICE), usrsctp (SCTP), plog (its log) and
Mbed TLS (DTLS). Its own log is off.

- **The sources** are downloaded as the other external sources (see "Where
  the code is"): libdatachannel at a release tag, its dependencies at the
  commits that the tag pins as submodules, and Mbed TLS as its 3.6 LTS
  release, which libdatachannel needs (Mbed TLS 3).
- **Data channels only:** `NO_MEDIA` and `NO_WEBSOCKET` (`WebSocket` is
  the platform's, see "The platform's network clients"), and libjuice has
  no TURN server code. Mbed TLS gets one option beyond its defaults,
  `MBEDTLS_SSL_DTLS_SRTP` (`native/src/mbedtls_user_config.h`), without
  which libdatachannel does not compile, also with no media.
- **The test**, `native/test/rtc_test.c` (ctest `rtc`): two peer
  connections in one process connect (on the host, also with no network:
  only the host build lets libjuice gather loopback candidates), each with the
  other's whole description once its ICE gathering ends (as the core sends
  its SDP through a tracker), and send text, binary, empty and 64 KiB − 1
  messages both ways. With `-DP2PML_RTC_TEST=ON`, the Android build makes
  the same test, to run on a device with `adb`: for each ABI, it is also the
  check that the libraries compile for Android.
- **The binding** (`native/src/rtc.c`) calls `rtc.h` from the engine's C,
  with libdatachannel's own negotiation off (the polyfill takes the steps).
  A peer connection is an item of the engine's channel, as a socket of
  WebSocket; its data channels are libdatachannel's numbers. An open data
  channel, its open or a message that JS cannot take fails the peer
  connection: libdatachannel deletes it, and JS gets a `"failed"` ICE
  connection and the channels' `close`.
- **Threads:** libdatachannel calls back from its own threads, and a callback
  can still run while or just after its peer connection is deleted. Each
  peer connection's user pointer is its own id, not memory; one table for the
  process maps that id to its engine. A callback posts only while it holds
  the table's lock and finds its id there, and the stop of a peer connection
  (its close, a failure, or the engine's close) takes it out of the table
  before it deletes the data channels and the peer connection. So no
  callback reaches an engine after the close. `rtcCleanup` is never called:
  it would delete the objects of every engine; libdatachannel's threads stay
  for the life of the process.
- **The cost:** libdatachannel adds 2.03 MB to the arm64 `libp2pml.so`
  (3.63 MB in all, stripped). Mbed TLS is built without its debug log
  (`MBEDTLS_DEBUG_C`): with it, each DTLS record goes through the debug
  functions, although libdatachannel sets no debug callback.

## The player bridge

The library's public `Core` (see [api.md](api.md)) has the JS core's calls;
the bridge is how they reach the core in the engine. It is not a browser API:
`native/src/polyfills/player.js` is the glue around the core, installed as
the polyfills are, and `native/src/player.c` gives it its natives. A player
integration does what a web player's adapter does (the web repository's
`specs/player-adapters.md`): it gives the core every manifest, loads the
segments through it, loads every other request itself, and reports the
playback state.

- **The events,** from Kotlin to JS: `CoreSession` (Kotlin, any thread)
  posts each one as a task, and on the engine thread `playerEvent` (JNI)
  gives it to the glue's listener as `(id, name, ...texts)`, as a channel
  does (`p2pml_listener_call`). A range is `"start-end"`, inclusive, or
  none.
  - `start(config)` imports the core and makes it, once, with the JSON
    object `config` as its settings and the HLS parser. QuickJS loads the
    module in the Promise jobs of this event's task, which run before the
    next task: the next event finds the core. A function of the platform's
    in the settings (`swarmId`, `streamIdBuilder` or `segmentFilter`, for
    both stream types or in `mainStream` or `secondaryStream`) is
    `{"function": n}` there: the glue gives the core one JS function for
    each `n` (see the functions below).
  - `manifest(id, url, requestedUrl, data)` is `processManifest`, with the
    URL after redirects and the one asked for, as the HLS.js adapter gives
    them: a query, answered at once with the core's result and the
    placement of `liveDelayFor`, `playerBufferFor` and `maxLiveLatencyFor`
    (specs/api.md), or `null`. The core ignores a manifest that it cannot
    parse (only its debug log shows it), which costs P2P only; an exception
    from `processManifest` is reported, and the answer is `null`.
  - `loadable(id, url, range)`, `config(id)`, `streams(id)`,
    `stream(id, runtimeId)` and `hasSegment(id, url, range)` are
    `isSegmentLoadable`, `getConfig`, `getStreams`, `getStream` and
    `hasSegment`: queries, answered at once. A stream is the JS core's
    snapshot, as the details of `onStreamAdded` give it. In the answer of
    `config`, a function of the platform's is `{"function": n}` again, so
    that `getConfig()` gives the platform's function object back.
  - `dynamicConfig(config)` is `applyDynamicConfig`, with the JSON object
    `config`; `manifestResponseUrl(url)` is `setManifestResponseUrl`.
  - `load(id, url, range)` is `loadSegment`: as in JS, a URL that the core
    does not know fails.
  - `abort(url, range)` is `abortSegmentLoading`; `destroy()` is `destroy`,
    which, as in JS, readies the core for the next source.
  - `playback(bufferAhead, rate)` is `updatePlayback`, with the two numbers
    as text.
  - `listen(event)` and `unlisten(event)`: from then on, the core's event
    `event` (an `onXxx` name of its `CoreEventMap`) goes, or no longer goes,
    to Kotlin. `Core` sends them at its first listener of an event and after
    its last, so that JS forwards only the events that an app listens to;
    the chunk events come for each chunk. The glue attaches the listeners
    that were asked for before the core was made when it makes the core.
- **The answers,** from JS to Kotlin, one for each query and each load,
  through the natives and the client of `p2pml_engine_run` (`PlayerSink` in
  Kotlin):
  - `playerValue(id, json)`: a query's value; with no core, `false` for
    `loadable` and `hasSegment`, and `null` for the others;
  - `playerData(id, bytes, bandwidth, main)`: the whole segment, copied once
    from its `ArrayBuffer` into a Kotlin `ByteArray` (`loadSegment` gives the
    segment at its end, not in parts), the bandwidth the core measured, and
    whether the segment's `streamType` is `"main"`;
  - `playerFail(id, type, message)`: the core's error type (`"failed"`,
    `"aborted"`) and its message. `destroy` fails the open loads as
    `"aborted"` before it calls the core's `destroy()`, which can throw. The
    core keeps one load for each stream type (main, secondary): a new load
    of the same type aborts the open one. So the integration loads one
    segment at a time for each type, as HLS.js does.
- **The core's events,** from JS to Kotlin, with no call:
  `playerCoreEvent(name, json)` gives the core's event with its details as
  JSON (`PlayerSink.coreEvent` in Kotlin). The two chunk events, whose JS details are arguments, give an
  object with the arguments' names. An error or a warning is
  `{name, type, message, timestamp, cause}`: JSON would drop its `message`,
  which is not enumerable. An exception while the glue serializes an event
  is reported, never thrown into the core. `Core` gives the details to its
  listeners on its event thread (specs/api.md, "Events").
- **The platform's functions,** from JS to Kotlin, with an answer: the JS
  function of `n` calls the native `playerCall(n, json)` with its argument
  as JSON, and the engine calls the client's `call` (`PlayerSink.call` in
  Kotlin) on the engine thread, which waits for it. The client gives its
  answer during the call: `{"value": v}`, where `null` is JS's
  `undefined`, or `{"error": message}`, which the JS function throws as an
  `Error`; a call with no answer throws too. `Core` numbers each function
  object once for each kind, so that one object is one JS function: the
  JS core keeps a `swarmId` function's answer by function. On the engine
  thread, a call that waits for an answer of the bridge throws
  `IllegalStateException` in Kotlin: it would wait for itself.
- **When JS does not take an event** (an exception, out of memory), the
  listener's error is reported, and the call fails at once: a query gives no
  value, a load fails.
- **The close:** an engine that closes answers nothing more. `JsEngine`
  fails every open call when its thread ends, and a call that comes after
  the close fails at once: no caller waits for an engine that has gone.
- **A wait** blocks the caller's thread, not the engine's; an interrupt of
  that thread ends the wait (`InterruptedIOException`).

## Modules

JS can import one module, `p2p-media-loader-core`: the web core's bundle with
its HLS parser (`dist/p2p-media-loader-core-hls.es.js`), as the npm package
publishes it. QuickJS resolves a name as its default does (a name that
starts with `.` is relative to the file of the importer); any name that does
not resolve to `p2p-media-loader-core` fails with a `ReferenceError`.

- **The version** is the one that `native/package.json` pins, exactly;
  `native/package-lock.json` pins the tarball's sha512. It is the
  pre-release `5.0.0-alpha.9`, of the peer protocol `v3` of 5.0.0. The web
  peers it meets must run a core with the same stream identity.
- **The build:** npm installs the package (`./gradlew :native:npmCi`, which
  the native build tasks depend on), and `native/CMakeLists.txt` builds the
  bundle into `libp2pml.so` (`embed_js`). It is the bundle as published, not
  minified, so that stack traces in the log are readable; the minified one is
  half the size (190 KB against 370 KB), if the size matters more.
- **The load:** the first import compiles and runs the bundle; QuickJS keeps
  the module, so a later import gives the same namespace. The engine does not
  load it at start: an engine that never imports it costs nothing, and the
  globals of the engine exist before the core reads them. The core reads
  WebRTC once, at load; without `RTCPeerConnection` it runs without P2P.
- **The logs:** the bundle exports `debug`; `debug.enable("p2pml-core:*")`
  turns on the core's debug lines, which `console.debug` writes. `console`
  does not substitute a `%` code such as `%s`, `%d` or `%O`, so the player
  bridge (below) makes `debug` do it: when it starts the core, it sets
  `debug.formatters` for `%O`, `%o`, `%s` and `%d` to `String(value)`, so
  each value is in its place in the line. Before the bridge starts a core,
  the code stays in the line, and its value follows at the end of the line.

## Closing

Any thread closes the engine, also a task of the engine. Closing does not
wait, so the app's main thread never waits for JS:

1. The engine takes no more tasks.
2. JS that still runs, such as an endless loop, stops with an error that JS
   cannot catch. A script that Kotlin evaluates during the close, or after
   it, throws `JsEngineClosedException`, also when it completed or failed.
   The current task finishes. After it, no JS runs, Promise jobs included.

   QuickJS checks for the close only from time to time, so JS that runs, or
   starts while another thread closes the engine, can run for a short time
   after `close` returns. So a function that the engine gives to JS checks
   the close itself before it uses a resource that the close releases.

3. The open requests, sockets and peer connections are stopped (no event of
   them reaches the engine after this), and the runtime is freed, with the
   timers that did not run.
4. The tasks still queued are dropped; each frees what it holds, which is
   never a JS value. Then the thread ends.

Closing again does nothing.

## Errors

- A JS exception from a script that Kotlin evaluates throws `JsException`,
  with the exception converted to a string as its message, and its `stack`.
- An exception that JS does not catch, from a timer's callback, an event
  listener or a Promise job (a FinalizationRegistry callback), goes to the
  log as an error: `Uncaught `, then the exception as `console` writes it.
  The loop continues.
- A Promise that is rejected with no handler goes to the log as an error:
  `Uncaught (in promise) `, then the reason. It is written after the Promise
  jobs of its task or timer have run, and only if no handler was added by
  then, as a browser does; it is written once.
- Nothing is reported once the engine is closed: the error is then likely
  the close's.
- An exception from a Kotlin task is a defect of the library. The engine logs
  it as an error and runs the next task, as a browser does with an exception
  from an event handler. A `JsEngineClosedException` is not logged: the
  close caused it. So a task lets it through, not wrapped in another
  exception. An `Error`, such as `OutOfMemoryError`, ends the engine thread,
  as in any thread of the app. So does an exception from the log, which
  closes the engine.

## Strings between JS and Kotlin

QuickJS gives strings to Kotlin, results and log lines, as CESU-8, which is
the JVM's modified UTF-8: a character outside the Basic Multilingual Plane is
a surrogate pair, each half in three bytes. So JNI reads it as it is, and
Android's CheckJNI does not stop the app. One difference remains: U+0000 ends
the string in Kotlin.

Kotlin gives code to QuickJS as UTF-8 bytes, followed by the NUL that QuickJS
needs.
