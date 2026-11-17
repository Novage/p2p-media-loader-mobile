# The JS engine

The library runs the web core in QuickJS (see the proposal
[js-core-host.md](proposals/js-core-host.md) for why). This spec describes the
engine that hosts it: its thread, its loop of tasks, the globals it adds, and
the rules for the code that calls it.

## Where the code is

| Part                                   | Code                                                                        |
| -------------------------------------- | --------------------------------------------------------------------------- |
| QuickJS, Bellard's release as released | `native/third_party/quickjs/` (version in its README.md)                    |
| The engine: the loop, globals, eval    | `native/src/engine.h`, `native/src/engine.c`                                |
| The polyfills: JS, and their C helpers | `native/src/polyfills/*.js`, `native/src/encoding.c`, `native/src/engine.c` |
| URL parsing: Ada (C++), and its C glue | `native/third_party/ada/` (version in its README.md), `native/src/url.c`    |
| The web core's bundle, as a module     | `native/package.json` (the npm package), `native/src/modules.c`             |
| fetch: the requests, and their events  | `native/src/http.h`, `native/src/http.c`, `native/src/polyfills/fetch.js`   |
| fetch's HTTP client, over Ktor         | `library/src/commonMain/…/http/HttpBridge.kt`                               |
| The JNI glue, nothing else             | `native/src/jni.c`                                                          |
| The engine for Kotlin                  | `library/src/androidMain/…/engine/JsEngine.kt`                              |

The engine is plain C, so that iOS calls the same code through cinterop, and
native libraries such as libdatachannel call it with no JNI. The C code,
QuickJS and Ada make one shared library, `libp2pml.so`. Ada is C++: the
library links libc++ statically on Android, and exports only the JNI
functions.

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
  platform's HTTP client").
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

## The platform's HTTP client

`fetch`'s network work is the platform's. The engine calls the platform's
client (`p2pml_http_client` in `native/src/http.h`) on the engine thread, to
start a request (method, URL and headers) and to cancel one. The platform
then gives the request's events from any thread: a response (status, status
text, URL after redirects, headers), any data, then an end; or a failure at
any point. Each event is copied into a task, so JS gets it on the engine
thread, after the tasks before it.

- **The bytes** are copied once, from the platform's buffer into memory that
  the JS `ArrayBuffer` of the chunk then owns.
- **No backpressure:** the platform reads as fast as the network gives, and
  the chunks wait in the queue of tasks while JS is busy. A segment bounds
  them: a few MB.
- **Cancel:** after the engine cancels a request (an abort, a cancel of the
  body, or the close), no event of it reaches JS; a late one is dropped. At
  the close, the engine cancels each request that is still open.
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
- **OkHttp 5 needs API 37:** apps that use the library compile against
  Android API 37 or later (`minCompileSdk`).

## Modules

JS can import one module, `p2p-media-loader-core`: the web core's bundle with
its HLS parser (`dist/p2p-media-loader-core-hls.es.js`), as the npm package
publishes it. QuickJS resolves a name as its default does (a name that
starts with `.` is relative to the file of the importer); any name that does
not resolve to `p2p-media-loader-core` fails with a `ReferenceError`.

- **The version** is the one that `native/package.json` pins, exactly;
  `native/package-lock.json` pins the tarball's sha512. It is the
  pre-release `5.0.0-alpha.7`, of the peer protocol `v3` of 5.0.0. The web
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
  does not substitute a `%` code such as `%s`, `%d` or `%O`, so the code
  stays in the line, and its value follows at the end of the line.

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

3. The runtime is freed, with the timers that did not run.
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
