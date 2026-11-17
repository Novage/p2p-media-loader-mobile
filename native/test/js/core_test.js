// The web core's bundle, the module p2p-media-loader-core (src/modules.c),
// with the polyfills: it loads, and gives the stream identity of the web
// repo's golden vectors (packages/p2p-media-loader-core/test/
// stream-identity.test.ts), which are what browser peers announce.

const CORE = "p2p-media-loader-core";

// Two variants and an audio rendition. With no swarmId, the swarm is the
// master playlist's URL.
const MASTER_URL = "https://example.com/hls/master.m3u8";
const MASTER = [
  "#EXTM3U",
  '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="audio/en.m3u8"',
  '#EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aac"',
  "video/360p.m3u8",
  '#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aac"',
  "video/1080p.m3u8",
  "",
].join("\n");

test("the core loads once, as a module", async () => {
  const core = await import(CORE);
  for (const name of [
    "Core",
    "computeInfoHash",
    "buildStreamSwarmId",
    "debug",
  ]) {
    assertEquals(typeof core[name], "function", name);
  }
  assertEquals(typeof core.hlsManifestParser, "object", "hlsManifestParser");
  assertEquals(core.PEER_PROTOCOL_VERSION, "v3", "PEER_PROTOCOL_VERSION");
  // A module namespace cannot be converted to a string for a failure message.
  assertEquals((await import(CORE)) === core, true, "the second import");
});

test("a master playlist gives the golden stream identity", async () => {
  const { Core, hlsManifestParser } = await import(CORE);
  const core = new Core({ manifestParsers: [hlsManifestParser] });
  try {
    core.processManifest({ url: MASTER_URL, data: MASTER });
    const streams = core
      .getStreams()
      .map((stream) => `${stream.streamId} ${stream.infoHash}`)
      .join(", ");
    assertEquals(
      streams,
      "main0 W1b9WgA8SSH2c5Dblnh5, main1 fvoqSv5iYPAR/cBJJimo, " +
        "secondary0 MssY0aR7vJXFmU9Ltes7",
      "streams",
    );
  } finally {
    core.destroy();
  }
});

test("a swarm ID that is not ASCII is hashed as UTF-8", async () => {
  const { buildStreamSwarmId, computeInfoHash } = await import(CORE);
  const id = buildStreamSwarmId(
    "https://example.com/vid\u00e9o/master.m3u8",
    "main0",
  );
  assertEquals(computeInfoHash(id), "2md7lrTf2S17B9sZBnnr", "infohash");
});

// "test-agent" is USER_AGENT of engine_test.c.
test("navigator.userAgent is the engine's, and cannot change", () => {
  assertEquals(navigator.userAgent, "test-agent", "userAgent");
  assertThrows(
    TypeError,
    () => {
      "use strict";
      navigator.userAgent = "other";
    },
    "an assignment of userAgent",
  );
  assertThrows(
    TypeError,
    () => {
      "use strict";
      globalThis.navigator = { userAgent: "other" };
    },
    "an assignment of navigator",
  );
  assertEquals(navigator.userAgent, "test-agent", "userAgent after");
});

test("another module name does not load", async () => {
  const error = await import("./core.js").catch((caught) => caught);
  assertEquals(error instanceof ReferenceError, true, "a ReferenceError");
  assertEquals(error.message, "could not load module 'core.js'", "message");
});
