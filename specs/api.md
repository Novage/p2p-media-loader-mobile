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
  `abortSegmentLoading(url, byteRange)` and `destroy()`. A player
  integration calls it as a web adapter calls the JS core: it gives the core
  every manifest, asks `isSegmentLoadable` for each segment, loads it with
  `loadSegment` when it is true, and loads every other request itself.

The JS core's calls that `Core` does not have, such as the events
(`addEventListener`), `applyDynamicConfig` and `updatePlayback` (the playback
state), are in KNOWN-ISSUES.md.

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
  takes bytes, and an optional `protocol`), and returns nothing: the JS
  return value places a player in a live stream, which comes with the
  playback state.
- **`manifestParsers`** is not a setting: the core has the HLS parser.
- **Java:** `getConfig`, `isSegmentLoadable` and `loadSegment` declare their
  exceptions (`InterruptedIOException`, and `IOException` for `loadSegment`),
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
ends its core. `mediaSourceFactory(upstream)` is its counterpart of
`getConfigForHlsJs()`: a factory of HLS sources for ExoPlayer, which plays
through the core. `upstream` loads what the core does not.

```kotlin
val engine = Media3P2PEngine(Media3P2PEngineConfig(CoreConfig().apply { swarmId = "..." }))
val player =
    ExoPlayer.Builder(context)
        .setMediaSourceFactory(engine.mediaSourceFactory(DefaultDataSource.Factory(context)))
        .build()
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
- **A new source:** each source of the factory readies the core for itself
  (`destroy()`) when the player prepares it, before it loads its first
  playlist, as the HLS.js engine does when a source loads. After an error,
  Media3 releases the source and prepares it again: the source keeps the
  core, as the HLS.js engine keeps its core when it recovers. The core
  recovers on its own, and falls back to HTTP where it must; a case that it
  cannot recover from is a defect of the core. An engine serves one player,
  and plays one item at a time (KNOWN-ISSUES.md).
- **Cancellation:** Media3 interrupts the thread of a load that it cancels,
  and the interrupt aborts the core's load.
- **Errors:** a load that the core fails or aborts goes to `upstream`, which
  can have what the core does not, such as the app's request headers: a
  failure costs P2P for that request, not the playback. The core keeps one
  load for each stream type, and aborts the open one when Media3 loads two
  renditions of one type at once, such as two audio renditions.
- **Bandwidth:** Media3's bandwidth meter times each transfer by the clock.
  The engine reports a segment from the core from the start of the core's
  wait, so the meter sees the wait (KNOWN-ISSUES.md).

## Without the native code

On a device that cannot load the library's native code (32-bit x86, see
KNOWN-ISSUES.md), `Core` has no core: no segment is loadable, `loadSegment`
fails, and `getConfig()` gives the given settings, in the JS `getConfig()`
shape (the common settings, and each stream type's own over the ones for
both). Nothing says that P2P is off. The player loads every request itself,
and plays without P2P.
