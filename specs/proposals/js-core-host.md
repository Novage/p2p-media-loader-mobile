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

| Platform | Engine         |
| -------- | -------------- |
| iOS      | JavaScriptCore |
| Android  | QuickJS        |

The engine runs the bundle the web core ships, the core and its HLS parser in
one file with no imports (`dist/p2p-media-loader-core-hls.es.js`). The library
does not bundle the core itself, so a phone runs the same published build of
the core as the web. The bundle is an ES module. QuickJS loads ES modules.
Whether JavaScriptCore can load one through a public API in an iOS app is to
be confirmed in the iOS step; if it cannot, an IIFE build of the core, as the
web integration packages already have, is the alternative. The package's
`lib/` entry is for bundlers, not for engines.

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
  the polyfills.

Not needed: `queueMicrotask`, because the core has its own function of that
name, and `RTCIceCandidate`, because the core sends the whole SDP through the
tracker and does not trickle candidates.

Without `localStorage` and `process`, nothing turns on the core's debug logs.
The bundle exports `debug`, so the library turns them on with
`debug.enable()`.

Two points need a decision in the Android step:

- **The user agent sets the core's memory limit.** The core's segment storage
  holds up to 1024 MiB when `navigator.userAgent` names an iPhone, an iPad or
  an Android WebView, 2048 MiB on other Android, and 4096 MiB otherwise. The
  core's `segmentMemoryStorageLimit` setting overrides that rule, so the
  library can set the limit for a phone itself, whatever the user agent says.
  The user agent also decides whether the logger adds browser colour codes
  (`%c`) to its lines.
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

## Measurements that decide

The design is adopted when all of these pass. The CPU and memory limits are
the starting values; they are set again if a measurement shows they are wrong.

| Check      | Pass                                                                                                           |
| ---------- | -------------------------------------------------------------------------------------------------------------- |
| Interop    | A mobile peer and browser peers exchange segments in one swarm, in both directions                             |
| P2P ratio  | Near the ratio of two browser peers on the same stream                                                         |
| CPU        | The library adds less than about 10% of one core at 1080p on a mid-range phone, compared with the player alone |
| Memory     | Stable over a 1-hour run                                                                                       |
| Background | Audio continues with the screen locked, and P2P follows the background policy                                  |
| Thermal    | No change of the thermal state that the library causes in 30 minutes                                           |

The checks run in this order, because each one costs more than the one before
it:

1. **The core in Node, with libdatachannel through `node-datachannel`.** Node
   has the other browser APIs, so this tests interop alone. A run with V8's
   compilers off (`--no-opt --no-maglev --no-sparkplug`) gives a first
   estimate of the cost in an engine without a JIT. JavaScriptCore runs
   without a JIT in an iOS app. Done; the results are below.
2. **Android:** QuickJS, the native APIs above, and Media3. All checks.
3. **iOS:** JavaScriptCore, the native APIs above, and AVPlayer. All checks.

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
  in one swarm, twice. In the first pair both peers used V8's JIT; in the
  second, one peer ran on V8's interpreter alone. The main threads were busy
  1.1% to 1.6% of the time in all four runs, with no consistent difference
  between the JIT and the interpreter: 1.2% and 1.3% with the JIT, 1.6% on
  the interpreter, 1.1% for its JIT peer. The processes, with all their
  threads, used 3.1% to 3.4% of one core. These are upper bounds for the
  core: in Node they include HTTP, TLS and the test player. As a rough guess,
  not a measurement: if a mid-range phone core is 2 to 3 times slower than
  the Mac's, and QuickJS 2 to 3 times slower than V8's interpreter, 1.6%
  becomes 6% to 15% of one core in the worst case. How JavaScriptCore without
  a JIT compares is not known yet. The check is made on a phone in step 2.

What step 1 does not show, and steps 2 and 3 must: peers on different
networks and behind NATs, more than two peers, the handling of a closed
connection by the real binding (node-datachannel has its own JS layer), long
runs, the cost of moving segment bytes between native code and the engine,
and a phone's CPU, memory, temperature and background behaviour.

Nothing in step 1 argues for the Kotlin core. Steps 2 and 3 decide on the
remaining checks.
