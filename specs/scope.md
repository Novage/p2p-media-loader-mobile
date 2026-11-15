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

## Players

| Platform | Player   | How requests reach the library                          |
| -------- | -------- | ------------------------------------------------------- |
| Android  | Media3   | A `DataSource` that the integration gives to the player |
| iOS      | AVPlayer | A local HTTP server; the player plays a local URL       |

Each integration does the three things a web player adapter does, and no
others: it passes manifest bytes to the core, it sends segment requests
through the core, and it reports the playback state (the buffer ahead of the
playhead and the playback rate). The web project's `specs/player-adapters.md`
describes the duties.

A player without an integration is outside the scope.

## Streams

- **Protocol:** HLS, live and VOD. DASH is outside the scope.
- **Media:** what the web core supports for HLS: MPEG-TS and fMP4 segments,
  alternate audio renditions.
- **Encryption:** the core handles ciphertext only, as in the web project. Key
  requests (AES-128 keys, FairPlay and Widevine licences) go to the network,
  never through peers.
- **Low latency:** LL-HLS parts and preload hints play, without P2P. Nothing
  at the live edge is shareable.

### Which URLs get P2P

A request gets P2P only when it reaches the library and the core recognises
it. How a request reaches the library depends on the player:

- **Media3** gives every request to the library's `DataSource`, with the real
  URL. All URLs in a playlist reach the library, relative and absolute.
- **AVPlayer** has no hook for media segments. The integration starts a local
  HTTP server with one port for each origin, and gives the player a local URL
  for the master playlist. A relative or root-relative URL in a playlist
  resolves against the local URL, so the player requests it from the local
  server. An absolute URL resolves to the origin, so the player requests it
  from the origin directly, and the library never sees that request.

So on iOS, P2P applies to media playlists and segments that the playlists name
with relative or root-relative URLs, on the origin of the master playlist. A
stream that names them with absolute URLs plays without P2P. The library does
not rewrite playlists. A rewrite of the absolute URLs is the one way to give
such streams P2P on AVPlayer; it is outside the scope.

A master playlist that redirects to another origin plays without P2P on iOS
for the same reason: its relative URLs resolve to the new origin, which has no
local port.

### What plays without P2P

These play as they would without the library:

- a request the core does not recognise, on both platforms;
- on iOS, a request to an absolute URL;
- AirPlay and other casting: the receiver cannot reach a local server on the
  phone, so the integration gives the receiver the original URL;
- key and licence requests.

## Outside the scope

- DASH, on both platforms.
- On iOS: absolute URLs in playlists, and master playlists that redirect to
  another origin.
- P2P while casting.
- Downloads for offline playback.
- Players other than Media3 and AVPlayer.
- Android TV, tvOS and desktop platforms.
