# The public API

The library's API is the one of the web library, P2P Media Loader, with the
same names, shapes and meanings, so that its documentation and its users'
knowledge carry over. Where Kotlin must differ from JS, this spec says so.

## Modules and packages

As the web library has a package for its core and one for each player
(`p2p-media-loader-core`, `p2p-media-loader-hlsjs`, ...), the library has a
module for its core and one for each player integration. The table gives each
module's public package; its internal code can be in other packages, such as
`:core`'s engine in `com.novage.p2pml.engine`.

| Gradle module | Maven artifact            | Kotlin package            | What it is                       |
| ------------- | ------------------------- | ------------------------- | -------------------------------- |
| `:core`       | `p2p-media-loader-core`   | `com.novage.p2pml.core`   | `Core`, its settings, the engine |
| `:media3`     | `p2p-media-loader-media3` | `com.novage.p2pml.media3` | The Media3 integration (Android) |
| `:proxy`      | `p2p-media-loader-proxy`  | `com.novage.p2pml.proxy`  | A local server for URL players   |

On iOS, the modules for iOS are exported into one framework. Kotlin packages
do not appear in Swift, so Swift sees the class names as they are while no
two public classes of the modules have the same name; Kotlin/Native renames a
class whose name clashes.

## Two levels

As in the web library, an app uses an engine for its player, and the engine
uses the core:

- **The player's engine,** as the web library's `HlsJsP2PEngine`: it is made
  with `{ core: CoreConfig }`, gives `getConfig()` and `destroy()`, and plugs
  into the player. For Media3 it is `Media3P2PEngine`, in a module of its
  own (see "Media3").
- **The core,** as the web library's `Core`: `Core(config)`, `getConfig()`,
  `processManifest(url, requestedUrl, data)`,
  `isSegmentLoadable(url, byteRange)`, `loadSegment(url, byteRange)`,
  `abortSegmentLoading(url, byteRange)`, `updatePlayback(state)` and
  `destroy()`. A player integration calls it as a web adapter calls the JS
  core: it gives the core every manifest, asks `isSegmentLoadable` for each
  segment, loads it with `loadSegment` when it is true, loads every other
  request itself, and reports the playback state (`PlaybackState`: the
  seconds buffered ahead of the playhead, and the rate, 0 when the player
  does not play). With no report, the core takes an empty buffer, and every
  request is urgent.

The JS core's calls that `Core` does not have, such as the events
(`addEventListener`) and `applyDynamicConfig`, are in KNOWN-ISSUES.md.

## The settings

`CoreConfig` and `StreamConfig` have the JS settings, with the same names and
meanings; `null` is "unset", the core's default, as an absent JS field.
`mainStream` and `secondaryStream` override the stream settings for each
stream type. As in JS, `getConfig()` gives the common settings, and the
stream settings of each stream type in `mainStream` and `secondaryStream`.

The settings that are functions in JS (`httpRequestSetup`,
`validateP2PSegment`, `segmentFilter`, `streamIdBuilder`,
`customSegmentStorageFactory`) are not in the API: a Kotlin function cannot
run in the engine. As in JS, `httpErrorRetries` counts all the HTTP tries,
not the retries after the first, so `0` makes the core load nothing over
HTTP.

## Where Kotlin differs from JS

- **Blocking calls:** where JS returns a Promise, the Kotlin call blocks its
  thread for the answer: a player's loading thread, never the engine's.
  `loadSegment` throws `CoreRequestError` (an `IOException`) with the JS
  error's `type`, `"failed"` or `"aborted"`, where JS rejects. An interrupt
  of the waiting thread aborts the load and throws `InterruptedIOException`;
  the interrupt stays set.
- **`close()`:** the core runs in a JS engine with a thread of its own (see
  [engine.md](engine.md)), which JS's garbage collector would end. `Core` is
  `AutoCloseable`: `close()` ends the engine, and every open call fails.
  `destroy()` keeps its JS meaning: it readies the core for the next source.
- **`processManifest`** takes the manifest as a `String` only (JS also
  takes bytes, and an optional `protocol`). It returns the JS
  `ProcessedManifest` (`streams`, each a `ProcessedStream`), or null, with
  one more value, `liveDelay`: where to place a live player, as the JS
  core's `liveDelayFor` gives it for that manifest, with `playerBuffer` and
  `maxLiveLatency`, the JS core's `playerBufferFor` and `maxLiveLatencyFor`
  of that placement. JS exports these rules as functions for the adapters;
  here the core computes them, so that its rules have one source.
- **`manifestParsers`** is not a setting: the core has the HLS parser.
- **Java:** `getConfig`, `processManifest`, `isSegmentLoadable` and
  `loadSegment` declare their exceptions (`InterruptedIOException`, and `IOException` for `loadSegment`),
  and the calls with defaults at the end have overloads (`@JvmOverloads`).
  `processManifest` has none: its overload would be `(url, data)`, which
  reads as `(url, requestedUrl)`; from Java, `requestedUrl` is null when there
  was no redirect.
- **Threads:** every call can come from any thread; the core's work is on
  the engine thread.

## Media3

`Media3P2PEngine` (`:media3`) is the player's engine for Media3, as
`HlsJsP2PEngine` is for HLS.js: `Media3P2PEngine(Media3P2PEngineConfig(core))`,
`getConfig()` and `destroy()` with their JS meanings, and `close()`, which
ends its core. `buildPlayer(context, builder, upstream)` is its counterpart
of `getConfigForHlsJs()` and `bindHls()` together: one call, so that an app
cannot set up a part of it only. On the app's `ExoPlayer.Builder` it sets the
media source factory, of HLS sources that play through the core, the
bandwidth meter and the load control; then it builds the player and watches
it for the core.
`upstream` (default: `DefaultDataSource`) loads what the core does not. An
engine has one player: a second call throws.

```kotlin
val engine = Media3P2PEngine(Media3P2PEngineConfig(CoreConfig().apply { swarmId = "..." }))
val player = engine.buildPlayer(context, ExoPlayer.Builder(context).setHandleAudioBecomingNoisy(true))
// Later: release the player first, which loads through the engine until then.
player.release()
engine.close()
```

- **Playlists:** they load through `upstream`, and the core gets each one,
  with its URL after redirects, which its URIs resolve against, and the URL
  that the player asked for, as the HLS.js engine gives them. The engine
  asks for each playlist whole: it removes `_HLS_skip` from the request,
  because the core reads a delta update as a whole playlist and would number
  its segments wrongly. The playlist of a load that Media3 cancelled, such as
  one of a source that it released, does not reach the core.
- **Segments:** a segment that the core knows (`isSegmentLoadable`, with the
  request's byte range) loads through the core. Every other segment request,
  such as an init segment or an LL-HLS part, loads through `upstream`. A
  segment that the core loads does not go through `upstream`: a cache or a
  URL signer in `upstream` does not see it (KNOWN-ISSUES.md).
- **Other requests,** such as keys and steering manifests, go to `upstream`
  untouched: the engine handles playlists and segments only.
- **A new source:** each HLS source of the player readies the core for itself
  (`destroy()`) when the player prepares it, before it loads its first
  playlist, as the HLS.js engine does when a source loads. After an error,
  Media3 releases the source and prepares it again: the source keeps the
  core, as the HLS.js engine keeps its core when it recovers. The core
  recovers on its own, and falls back to HTTP where it must; a case that it
  cannot recover from is a defect of the core. An engine serves one player,
  and plays one item at a time (KNOWN-ISSUES.md).
- **The playback state:** the engine reports it to the core
  (`updatePlayback`) on each change of the player's state, play, pause,
  seek, speed and timeline, after each chunk that the player loads, and once
  a second: the buffer ahead is the player's buffered position less its
  position, in the current item, and the rate is the player's speed while
  it plays, else 0. Media3 has no event when its buffer grows, and gives its
  state on its application thread only.
- **A stop:** when the app stops the player (`STATE_IDLE` with no error) or
  clears its items, the engine readies the core (`destroy()`), so that it
  leaves its swarm, as the HLS.js engine's core when it is destroyed. The
  next source that the player prepares readies it again. An error also
  makes the player idle; the core stays for the recovery.
- **Live placement:** a live player plays where the core's `liveDelayFor`
  places it, as the web adapters place theirs: as deep in the live window as
  the window allows, so that the segments between the player and the live
  edge are left to peers. The engine takes the placement of the main
  stream's playlist (`ProcessedManifest.liveDelay`), and gives it to Media3
  in the source's timeline: the window's target live offset, and its default
  position, where the player starts, at the start of its segment. A media
  item with a target offset of the app's own, and a playlist with
  `EXT-X-START`, keep Media3's placement.
- **Forward buffer:** the load control (a `DefaultLoadControl`) stops the
  loads of a live player that the engine placed at the core's
  `playerBufferFor`, a segment short of the delay, as the web adapters hold
  their players' buffers. The limit is measured from the engine's delay, so
  where the app's target offset or `EXT-X-START` keeps Media3's placement,
  the buffer is Media3's too (the web library's
  `specs/player-adapters.md`, "Settings an adapter writes", rule 1). On VOD,
  and for a stream that ended, the buffer is Media3's: the core treats the
  requests past its urgent threshold as not urgent.
- **Re-sync:** a live player that falls more than the core's
  `maxLiveLatencyFor` behind the live edge, or more than the window where the
  window is wider (DVR), such as after a long pause, goes back to its
  placement (`seekToDefaultPosition`) while it is to play, as the HLS.js
  adapter re-syncs HLS.js. Media3 plays a plain HLS stream at speed 1, and
  would stay behind. Where the app's target offset or `EXT-X-START` owns the
  delay, the re-sync still applies: its threshold is never less than the
  window, so it moves only a player that has left the window, to Media3's
  default position, which keeps the app's offset. The check also runs at once
  when the app plays, before the player loads from where it stopped. A live
  player that falls out of the window, such as after a stall at its
  placement, fails with
  `ERROR_CODE_BEHIND_LIVE_WINDOW`; the engine then starts it again at its
  default position (`seekToDefaultPosition` and `prepare`), as Media3's
  documentation recommends and as HLS.js recovers from a stall, with no limit
  on the tries, as HLS.js. The app's listeners still get the error, when the
  player is already preparing again; the app needs no recovery of its own.
- **Cancellation:** Media3 interrupts the thread of a load that it cancels,
  and the interrupt aborts the core's load.
- **Errors:** a load that the core fails or aborts goes to `upstream`, which
  can have what the core does not, such as the app's request headers: a
  failure costs P2P for that request, not the playback. The core keeps one
  load for each stream type, and aborts the open one when Media3 loads two
  renditions of one type at once, such as two audio renditions.
- **Bandwidth:** the core is the source of the player's bandwidth estimate.
  The engine's bandwidth meter gives the player's adaptive bitrate the
  `bandwidth` that the core gave with its last segment of the main stream,
  as the web adapters do: the core measures its downloads, HTTP and P2P, and
  the clock says nothing about the network for a segment from a peer or from
  the core's store, which can come at once. The core measures each stream
  type in its own way, so the meter does not count a segment of a secondary
  stream (`SegmentResponse.streamType`), such as an audio rendition, as
  HLS.js counts only its main fragments. Until the core gives a bandwidth
  (no core, P2P off, or before its first main segment), the estimate is a
  `DefaultBandwidthMeter`'s, which times the transfers of `upstream`.

## Without the native code

On a device that cannot load the library's native code (32-bit x86, see
KNOWN-ISSUES.md), `Core` has no core: no segment is loadable, `loadSegment`
fails, and `getConfig()` gives the given settings, in the JS `getConfig()`
shape (the common settings, and each stream type's own over the ones for
both). Nothing says that P2P is off. The player loads every request itself,
and plays without P2P.
