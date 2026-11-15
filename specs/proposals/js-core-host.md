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

The engine has no browser APIs, so the library gives the core what it uses,
as native code behind the same JS interfaces:

| JS API                                          | Native implementation            |
| ----------------------------------------------- | -------------------------------- |
| `RTCPeerConnection`, `RTCDataChannel`           | libdatachannel                   |
| `fetch` with a streamed body, `AbortController` | Ktor client                      |
| `WebSocket`                                     | Ktor client                      |
| Timers, `queueMicrotask`                        | Kotlin coroutines on one thread  |
| `performance.now`                               | The platform's monotonic clock   |
| `TextEncoder`, `TextDecoder`                    | A JS polyfill in the core bundle |

The exact list comes from a run of the core with a record of every global it
reads, not from a search of its source.

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
   has the other browser APIs, so this tests interop alone. The CPU of a run
   with `node --jitless` gives a first estimate of the cost in an engine
   without a JIT. JavaScriptCore runs without a JIT in an iOS app.
2. **Android:** QuickJS, the native APIs above, and Media3. All checks.
3. **iOS:** JavaScriptCore, the native APIs above, and AVPlayer. All checks.

If interop fails in step 1, both designs have a problem, and it is solved
before anything else. If the CPU or thermal checks fail in step 2 or 3, the
Kotlin core is the alternative to measure.
