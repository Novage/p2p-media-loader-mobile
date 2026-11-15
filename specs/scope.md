# Scope

What P2P Media Loader Mobile does, for which platforms, players and streams,
and what it leaves out.

## Purpose

The library lets a native player on a phone or a tablet get media segments
from other viewers of the same stream, and give segments to them. A segment
that no peer can give in time comes from the origin over HTTP, as it would
without the library. The player plays the same bytes in both cases.

Mobile peers and web peers of
[P2P Media Loader](https://github.com/Novage/p2p-media-loader) meet in one
swarm. A stream gets the same swarm ID on a phone as in a browser, so a mobile
viewer adds to the swarm of the web viewers and takes from it. The swarm ID,
segment identity and peer messages are those of the web project; see
"The peer protocol belongs to the web project" in
[`AGENTS.md`](../AGENTS.md).

## Platforms

| Platform | Devices         | Distribution                               |
| -------- | --------------- | ------------------------------------------ |
| Android  | Phones, tablets | Maven Central                              |
| iOS      | iPhones, iPads  | Swift Package Manager, with an XCFramework |

Android TV, tvOS, desktop platforms and the web are outside the scope. The web
has P2P Media Loader itself.

## How requests reach the library

A player's requests reach the library in one of two ways.

**A data source.** The library gives the player its own data source, and the
player sends every request through it, with the real URL and byte range. All
URLs reach the library, relative and absolute, and the library sees each
request as the player makes it. This is the way for players that accept a
data source.

**A local HTTP server.** The library starts an HTTP server on the device, with
one port for each origin, and the app gives the player a local URL for the
manifest in place of the real one. A relative or root-relative URL in a
manifest resolves against the local URL, so the player requests it from the
local server, which passes it to the library. An absolute URL resolves to the
origin, so the player requests it from the origin directly, and the library
never sees that request. This is the way for any player that plays a URL.

## Players

| Platform | Player         | Way               |
| -------- | -------------- | ----------------- |
| Android  | Media3         | Data source       |
| Android  | Any URL player | Local HTTP server |
| iOS      | AVPlayer       | Local HTTP server |
| iOS      | Any URL player | Local HTTP server |

Each integration does the three things a web player adapter does, and no
others: it passes manifest bytes to the core, it sends segment requests
through the core, and it reports the playback state (the buffer ahead of the
playhead and the playback rate). The web project's `specs/player-adapters.md`
describes the duties.

With the local server, the server does the first two. The playback state
comes from the player, and every player reports it differently, so the
library has an API through which the app reports it. The AVPlayer integration
reports it for the app; so can the Media3 integration when it uses the local
server in place of its data source.

## Streams

| Platform | Protocols |
| -------- | --------- |
| Android  | HLS, DASH |
| iOS      | HLS       |

AVPlayer does not play DASH.

- **Live and VOD**, as the web core supports them for each protocol.
- **Media:** what the web core supports: MPEG-TS and fMP4 segments, alternate
  audio renditions, and DASH segments listed in the manifest or in a `sidx`
  index (`SegmentBase`).
- **Byte ranges:** a segment that is a range of a file (HLS
  `EXT-X-BYTERANGE`, DASH `SegmentBase` and indexed representations) reaches
  the core with its range.
- **Encryption:** the core handles ciphertext only, as in the web project. Key
  requests (AES-128 keys, FairPlay and Widevine licences) go to the network,
  never through peers.
- **Low latency:** LL-HLS parts and preload hints, and LL-DASH chunked
  segments, play without P2P. Nothing at the live edge is shareable.

### Which URLs get P2P

A request gets P2P only when it reaches the library and the core recognises
it:

- **Through a data source,** every URL in a manifest gets P2P, relative and
  absolute.
- **Through the local server,** only media playlists, MPD resources and
  segments that the manifest names with relative or root-relative URLs, on
  the origin of the manifest. A stream that names them with absolute URLs
  plays without P2P. The library does not rewrite manifests; a rewrite of the
  absolute URLs is the one way to give such streams P2P through the server,
  and it is outside the scope. A manifest that redirects to another origin
  plays without P2P through the server for the same reason: its relative URLs
  resolve to the new origin, which has no local port.

### What plays without P2P

These play as they would without the library:

- a request the core does not recognise;
- through the local server, a request to an absolute URL;
- AirPlay and other casting: the receiver cannot reach a local server on the
  phone, so the integration gives the receiver the original URL;
- key and licence requests.

## Outside the scope

- DASH on iOS.
- Through the local server: absolute URLs in manifests, and manifests that
  redirect to another origin.
- P2P while casting.
- Downloads for offline playback.
- Ready integrations for players other than Media3 and AVPlayer, ExoPlayer 2.x
  among them. Such players use the local server, and their app reports the
  playback state.
- Android TV, tvOS and desktop platforms.
