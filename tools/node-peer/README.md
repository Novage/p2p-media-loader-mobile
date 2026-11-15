# node-peer

The P2P Media Loader core in Node, with libdatachannel for WebRTC. It is a
peer like a browser, without a browser and without a player.

It runs the first check of
[the JS core proposal](../../specs/proposals/js-core-host.md): does the
unchanged web core meet browser peers in one swarm when its WebRTC is
libdatachannel? Later it is a headless peer for tests with mixed swarms.

## Setup

Node 24 or later, and pnpm. The core comes from a checkout of
[P2P Media Loader](https://github.com/Novage/p2p-media-loader), by default
`../p2p-media-loader` next to this repository, on the branch to test.

```bash
pnpm pack-core
pnpm install --frozen-lockfile
pnpm start
```

`pnpm start` runs `src/main.ts` directly; Node strips the types. Check the
types with `pnpm type-check`.

`pnpm start [stream URL] [--minutes N] [--isolated | --swarm ID]` plays a
live stream through the core, the way HLS.js with the P2P Media Loader adapter
plays it in a browser, for N minutes (default 2). The default stream is
`https://live.novage.workers.dev/bbb/live.m3u8`. The swarm:

- by default, the core's: the master playlist's URL, which every viewer of
  the stream joins, strangers included;
- `--isolated`: a swarm of its own, which no web peer joins;
- `--swarm ID`: a test swarm. Web peers under test join it with the same ID
  in the demo's `?swarmId=`, so that no stranger's peer mixes with them. They
  meet only on the same stream URL and the same quality: the infohash holds
  the stream's place in the master playlist (`main0` is the first). Use an
  ID of letters, digits and `-`, which the demo's URL does not change.

The player plays one quality, the highest bitrate, where web viewers on a
good connection play. It starts at the core's live delay, buffers up to the
core's buffer cap, requests one segment at a time, and reports its playback
to the core every 250 ms and before each request. It reloads the playlist and
retries a failed segment on HLS.js's schedule. It prints:

- each stream the core registered, with the infohash it announces to
  trackers, to compare with a web peer's swarm on the same stream;
- the placement: the live delay and the buffer cap;
- a line per segment: its sequence number; `core` when it came through the
  core, `direct` when `isSegmentLoadable` was false and the player loaded it
  itself; its size and load time; the buffer ahead and the stalls so far; and
  the bytes the core downloaded so far over HTTP and from peers.

At the end it prints a summary, also after an error that ends a run which
has started:

- `played`: the run's length, and the time from the start to the first
  playback;
- `stalls`: how many times the playhead reached the end of the buffer, and
  for how long in all;
- `live edge`: how far the playhead was behind the newest segment at the end.
  It is one sample and grows by up to a target duration between playlist
  reloads, so a smaller difference between two runs is noise;
- `segments`: through the core, loaded directly, and failed attempts; the
  mean and the longest load time;
- `bytes`: what the core downloaded over HTTP and from peers, the P2P share
  of it, and what it uploaded to peers;
- `peers`: peer connections opened, the most open at once, and tracker
  errors. An `--isolated` run shows no peer and a P2P share of 0; it still
  announces to trackers, so tracker errors can count;
- `playlist`: playlist reloads that changed, did not change, and failed.
- `cpu`, over the playing window: the share of time the main thread was busy
  (JS, native callbacks and garbage collection pauses on it), and the CPU
  time of all the process's threads in percent of one core (also
  libdatachannel and V8's helper threads). Both are upper bounds for the
  core: they also count HTTP and TLS, which run on the main thread in Node,
  and this test player's own work;
- `memory`: the process's resident memory at the start of playback and its
  peak, Node's own baseline included.

To run the core's JS on V8's interpreter alone, as in an engine without a
JIT, turn V8's compilers off:
`node --no-opt --no-maglev --no-sparkplug --no-experimental-webstorage src/main.ts …`.
`--jitless` does not work: it removes WebAssembly, which Node needs for
TypeScript type stripping and for `fetch`.

The run stops with exit code 1 on an error: among others, when the core does
not load the stream's segments at the start (no WebRTC, P2P off, or a stream
it cannot share), when a segment fails after 6 retries, and when the playhead
leaves the live window.

`--record-globals` lists, at the end, the host globals the core read: the
globals beyond ECMA-262 that a JS engine without browser APIs must give it,
`window` and `console` among them. For `navigator`, `performance`, `console`,
`process`, `crypto` and `localStorage` it also lists the members read. Each
entry says whether the core first read it while its bundle loaded (`load`) or
later (`run`). A browser global that Node lacks is recorded too, when the core
tests for it. Only reads the core makes itself count, so a run with peers
covers more of it than an `--isolated` one.

It is where a shim list starts, not all of it:

- the members of instances are not recorded: what a `Response` from `fetch`
  must have (`body.getReader()`, so a `ReadableStream`), or a `WebSocket`, an
  `RTCPeerConnection` and an `AbortSignal`;
- some entries are only probes: a `typeof` check of an API the core can do
  without, such as `document` and `process` for the logger;
- the core takes Node's branches: a check it skips in Node, such as for a
  prefixed `webkitRTCPeerConnection`, is not recorded.

`pnpm test` checks the playlist reader, the playback model, the record of
globals, and that the WebRTC the tool gives the core works the way the core
uses it.

## How the core runs

The tool loads the core's self-contained HLS bundle,
`dist/p2p-media-loader-core-hls.es.js`, not the package's entry points: a
mobile JS engine also loads one file, with no package resolution. The bundle
has the diagnostics ledger compiled out.

Before the core loads, `src/browser-globals.ts` gives it what Node lacks:
WebRTC from `node-datachannel`, and `window`, which is the global object as in
a browser, because the core reads its APIs from `window`. Node runs without
Web Storage (`--no-experimental-webstorage`), so the core's logger finds no
`localStorage`, as in a browser that blocks it. The logger reads
`process.env.DEBUG` then: `DEBUG='p2pml-core:*' pnpm start` shows the core's
logs.

## The core

`pnpm pack-core` installs the web checkout's dependencies from its lockfile,
builds the core, and packs it into `.core/`, as `pnpm publish` packs it. It
refuses a checkout with uncommitted changes, and it writes the branch and the
commit it packed to `.core/SOURCE`. Another checkout: `pnpm pack-core <path>`,
or set `P2PML_WEB`.

The same commit gives the same tarball, so the integrity hash in
`pnpm-lock.yaml` pins the core: `pnpm install --frozen-lockfile` stops when
the packed core is not the one the lockfile names. To use another core: check
out the branch or commit in the web repo, then run `pnpm pack-core` and
`pnpm install`, and commit the changed lockfile.
