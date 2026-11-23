// The player bridge (src/polyfills/player.js), with the real core and the
// fake clients of engine_test.c: fakePlayer.send posts the platform's events
// as tasks, as the platform does, and fakePlayer.answers records each call's
// answer; the core's requests go to the fake HTTP client (fakeHttp).

const BASE = "https://example.com/hls/";
const MASTER = [
  "#EXTM3U",
  '#EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS="avc1.4d401e,mp4a.40.2"',
  "media.m3u8",
  "",
].join("\n");
const MEDIA = [
  "#EXTM3U",
  "#EXT-X-VERSION:7",
  "#EXT-X-TARGETDURATION:4",
  "#EXT-X-MEDIA-SEQUENCE:0",
  "#EXT-X-PLAYLIST-TYPE:VOD",
  '#EXT-X-MAP:URI="init.mp4"',
  "#EXTINF:4.0,",
  "seg0.m4s",
  "#EXTINF:4.0,",
  "#EXT-X-BYTERANGE:1000@0",
  "range.m4s",
  "#EXTINF:4.0,",
  "seg2.m4s",
  "#EXTINF:4.0,",
  "seg3.m4s",
  "#EXT-X-ENDLIST",
  "",
].join("\n");

// A live stream: 10 segments of 4 s, with no end.
const LIVE = BASE + "live/";
const LIVE_MEDIA = [
  "#EXTM3U",
  "#EXT-X-VERSION:3",
  "#EXT-X-TARGETDURATION:4",
  "#EXT-X-MEDIA-SEQUENCE:100",
  ...Array.from({ length: 10 }, (_, i) => `#EXTINF:4.0,\nseg${100 + i}.ts`),
  "",
].join("\n");

// The bridge has one core: the tests run one after another.
let previous = Promise.resolve();
const inOrder = (body) => {
  const run = previous.then(body);
  previous = run.catch(() => {});
  return run;
};

// The answers to call `id`, after the tasks before it have run.
const answersOf = (id) => fakePlayer.answers.filter((a) => a.id === id);

// Waits for the first answer to call `id`.
const answerTo = async (id) => {
  for (let i = 0; i < 200; i++) {
    const [answer] = answersOf(id);
    if (answer !== undefined) return answer;
    await tasksRun();
  }
  throw new Error(`no answer to call ${id}`);
};

// Waits for a request of `url` by the core that no earlier step used, and
// returns it.
const requestOf = async (url) => {
  for (let i = 0; i < 200; i++) {
    const request = fakeHttp.started.find((r) => r.url === url && !r.used);
    if (request !== undefined) {
      request.used = true;
      return request;
    }
    await tasksRun();
  }
  throw new Error(`no request of ${url}`);
};

// No prefetch: the core loads only the segments that the tests ask for.
// The platform's functions (fakePlayer.onCall): 1 names the swarm, 2 builds
// the stream IDs, 3 filters the segments. Main and secondary have markers
// of their own for 1, which give one function.
const CONFIG = JSON.stringify({
  httpDownloadTimeWindow: 0,
  p2pDownloadTimeWindow: 0,
  streamIdBuilder: { function: 2 },
  segmentFilter: { function: 3 },
  mainStream: { swarmId: { function: 1 } },
  secondaryStream: { swarmId: { function: 1 } },
});

// The answers of the platform's functions as if there were none: the
// manifest's URL names the swarm, the default stream IDs, every segment
// shared.
const asWithout = (n, json) =>
  JSON.stringify({
    value: n === 1 ? JSON.parse(json).manifestUrl : n === 3 ? true : null,
  });
fakePlayer.onCall = asWithout;

const giveManifests = () => {
  fakePlayer.send("manifest", 0, BASE + "master.m3u8", undefined, MASTER);
  fakePlayer.send("manifest", 0, BASE + "media.m3u8", undefined, MEDIA);
};

// The value of query `name` (such as loadable or config), parsed.
let lastQuery = 1000;
const query = async (name, ...texts) => {
  const id = ++lastQuery;
  fakePlayer.send(name, id, ...texts);
  const answer = await answerTo(id);
  assertEquals(answer.kind, "value", `${name}: the answer`);
  return JSON.parse(answer.value);
};

// Answers the core's next request of `url` with `bytes`: 206 for a range.
const serve = async (url, bytes) => {
  const request = await requestOf(url);
  const status = request.headers.range === undefined ? 200 : 206;
  fakeHttp.respond(request.id, status, "", request.url, []);
  fakeHttp.data(request.id, bytes);
  fakeHttp.end(request.id);
  return request;
};

// Fails each request of `url` until load `id` has its answer.
const failEach = async (url, id) => {
  for (let i = 0; answersOf(id).length === 0 && i < 200; i++) {
    for (const request of fakeHttp.started) {
      if (request.url === url && !request.used) {
        request.used = true;
        fakeHttp.fail(request.id, "no network");
      }
    }
    await tasksRun();
  }
};

test("before start, nothing is loadable and a load fails", () =>
  inOrder(async () => {
    // Takes effect when the core is made: see the events test.
    fakePlayer.send("listen", 0, "onStreamAdded");
    assertEquals(await query("loadable", BASE + "seg0.m4s"), false, "loadable");
    assertEquals(await query("config"), null, "config");
    assertEquals(await query("streams"), null, "streams");
    assertEquals(await query("stream", BASE + "media.m3u8"), null, "stream");
    assertEquals(
      await query("hasSegment", BASE + "seg0.m4s"),
      false,
      "hasSegment",
    );
    const manifest = await query(
      "manifest",
      BASE + "media.m3u8",
      undefined,
      MEDIA,
    );
    assertEquals(manifest, null, "manifest");
    fakePlayer.send("load", 1, BASE + "seg0.m4s");
    assertEquals((await answerTo(1)).kind, "fail", "load");
  }));

test("the event after start finds the core", () =>
  inOrder(async () => {
    // Each event is a task, as from the platform.
    fakePlayer.send("start", 0, CONFIG);
    giveManifests();
    assertEquals(await query("loadable", BASE + "seg0.m4s"), true, "loadable");
    fakePlayer.send("load", 2, BASE + "seg0.m4s");
    await serve(BASE + "seg0.m4s", new Uint8Array([1, 2, 3]));
    const answer = await answerTo(2);
    assertEquals(answer.kind, "segment", "the answer");
    assertEquals(hex(answer.data), "01 02 03", "the bytes");
    assertEquals(typeof answer.bandwidth, "number", "the bandwidth");
    assertEquals(answer.main, true, "the stream type");
    assertEquals(answersOf(2).length, 1, "one answer");
  }));

test("getConfig gives the given settings and the core's defaults", () =>
  inOrder(async () => {
    // As the JS core's: the stream settings of each stream type.
    const { mainStream, secondaryStream } = await query("config");
    assertEquals(mainStream.httpDownloadTimeWindow, 0, "a given setting");
    assertEquals(secondaryStream.httpDownloadTimeWindow, 0, "for both");
    assertEquals(mainStream.httpErrorRetries, 3, "a default");
  }));

test("an unknown URL and an init segment are not loadable", () =>
  inOrder(async () => {
    const other = BASE + "other.m4s";
    assertEquals(await query("loadable", other), false, "an unknown URL");
    assertEquals(await query("loadable", BASE + "init.mp4"), false, "init");
    // As in JS, a load of an unknown URL fails.
    fakePlayer.send("load", 3, other);
    assertEquals((await answerTo(3)).kind, "fail", "a load of it");
  }));

test("a byte-range segment is found by its range", () =>
  inOrder(async () => {
    const url = BASE + "range.m4s";
    assertEquals(await query("loadable", url, "0-500"), false, "another range");
    assertEquals(await query("loadable", url, "0-999"), true, "its range");
    fakePlayer.send("load", 6, url, "0-999");
    const request = await serve(url, new Uint8Array(1000));
    assertEquals(request.headers.range, "bytes=0-999", "the request's range");
    const answer = await answerTo(6);
    assertEquals(answer.kind, "segment", "the answer");
    assertEquals(answer.data.length, 1000, "the length");
  }));

test("a load that fails gets the core's error type", () =>
  inOrder(async () => {
    fakePlayer.send("load", 7, BASE + "seg2.m4s");
    // The core tries again after a failure: each request fails.
    await failEach(BASE + "seg2.m4s", 7);
    const answer = await answerTo(7);
    assertEquals(`${answer.kind} ${answer.type}`, "fail failed", "the answer");
    assertEquals(answersOf(7).length, 1, "one answer");
  }));

test("abortSegmentLoading fails the load as aborted, once", () =>
  inOrder(async () => {
    fakePlayer.send("load", 8, BASE + "seg3.m4s");
    await requestOf(BASE + "seg3.m4s");
    fakePlayer.send("abort", 0, BASE + "seg3.m4s");
    const answer = await answerTo(8);
    // The core's error has no message: the message is its type.
    assertEquals(
      `${answer.kind} ${answer.type} ${answer.message}`,
      "fail aborted aborted",
      "abort",
    );
    await tasksRun();
    assertEquals(answersOf(8).length, 1, "one answer");
  }));

// Runs `body` with Core.prototype[name] replaced by `replacement`.
const withCoreMethod = async (name, replacement, body) => {
  const { Core } = await import("p2p-media-loader-core");
  const original = Core.prototype[name];
  Core.prototype[name] = replacement;
  try {
    await body();
  } finally {
    Core.prototype[name] = original;
  }
};

test("a failure gives the type and the message of the core's error", () =>
  inOrder(async () => {
    const { CoreRequestError } = await import("p2p-media-loader-core");
    await withCoreMethod(
      "loadSegment",
      () => Promise.reject(new CoreRequestError("failed", "a detail")),
      async () => {
        fakePlayer.send("load", 20, BASE + "seg0.m4s");
        const answer = await answerTo(20);
        assertEquals(
          `${answer.kind} ${answer.type} ${answer.message}`,
          "fail failed a detail",
          "the error",
        );
      },
    );
  }));

test("playback gives the core its playback state, as numbers", () =>
  inOrder(async () => {
    const states = [];
    await withCoreMethod(
      "updatePlayback",
      (state) => states.push(state),
      async () => {
        fakePlayer.send("playback", 0, "12.5", "0");
        await tasksRun();
      },
    );
    assertEquals(JSON.stringify(states), '[{"bufferAhead":12.5,"rate":0}]');
  }));

test("a segment of a secondary stream says so", () =>
  inOrder(async () => {
    await withCoreMethod(
      "loadSegment",
      () =>
        Promise.resolve({
          data: new ArrayBuffer(1),
          bandwidth: 5,
          streamType: "secondary",
        }),
      async () => {
        fakePlayer.send("load", 22, BASE + "seg0.m4s");
        const answer = await answerTo(22);
        assertEquals(answer.kind, "segment", "the answer");
        assertEquals(answer.bandwidth, 5, "the bandwidth");
        assertEquals(answer.main, false, "the stream type");
      },
    );
  }));

test("an answer that cannot be sent becomes a failure", () =>
  inOrder(async () => {
    // A Uint8Array of a negative length throws.
    await withCoreMethod(
      "loadSegment",
      () => Promise.resolve({ data: -1, bandwidth: 0 }),
      async () => {
        fakePlayer.send("load", 21, BASE + "seg0.m4s");
        assertEquals((await answerTo(21)).kind, "fail", "the answer");
        await tasksRun();
        assertEquals(answersOf(21).length, 1, "one answer");
      },
    );
  }));

test("a debug line has its values in place", () =>
  inOrder(async () => {
    const { debug } = await import("p2p-media-loader-core");
    const logger = debug("p2pml-test");
    let line;
    logger.log = (...args) => {
      line = args.join("|");
    };
    logger.enabled = true;
    logger("a %s b %d c %O d %o", "x", 2, { toString: () => "y" }, 3);
    // debug adds the time since its last line at the end.
    assertEquals(
      line.startsWith("p2pml-test a x b 2 c y d 3 +"),
      true,
      `the line ${line}`,
    );
    // A value that String() cannot convert.
    logger("e %O", Object.create(null));
    assertEquals(
      line.startsWith("p2pml-test e [object Object] +"),
      true,
      `the line ${line}`,
    );
  }));

test("destroy aborts the open loads, and the core takes a new source", () =>
  inOrder(async () => {
    // Not seg0, which the core keeps in its storage. The core may wait for
    // the request that the abort left: no new one starts.
    fakePlayer.send("load", 10, BASE + "seg3.m4s");
    await tasksRun();
    assertEquals(answersOf(10).length, 0, "an open load");
    fakePlayer.send("destroy", 0);
    const answer = await answerTo(10);
    assertEquals(`${answer.kind} ${answer.type}`, "fail aborted", "destroy");
    const segment = BASE + "seg0.m4s";
    assertEquals(await query("loadable", segment), false, "no source");
    giveManifests();
    assertEquals(await query("loadable", segment), true, "a new source");
  }));

test("a manifest gives the core's streams, and a live one its placement", () =>
  inOrder(async () => {
    const vod = await query("manifest", BASE + "media.m3u8", undefined, MEDIA);
    assertEquals(vod.streams.length, 1, "a media playlist: one stream");
    assertEquals(vod.streams[0].type, "main", "its type");
    assertEquals(vod.streams[0].isLive, false, "VOD");
    assertEquals(vod.liveDelay, null, "no placement for VOD");
    assertEquals(
      await query("manifest", BASE + "x.m3u8", undefined, "not a playlist"),
      null,
    );
    fakePlayer.send("destroy", 0);
    const master = await query(
      "manifest",
      LIVE + "master.m3u8",
      undefined,
      MASTER,
    );
    assertEquals(JSON.stringify(master), '{"streams":[],"liveDelay":null}');
    const live = await query(
      "manifest",
      LIVE + "media.m3u8",
      undefined,
      LIVE_MEDIA,
    );
    assertEquals(live.streams[0].isLive, true, "live");
    assertEquals(live.streams[0].segmentCount, 10, "its segments");
    // The core's rules: as deep as the window allows, a segment inside its
    // tail; the buffer a segment short of that; two segments of slack.
    assertEquals(
      JSON.stringify(live.liveDelay),
      '{"delay":36,"segment":4,"playerBuffer":32,"maxLiveLatency":44}',
    );
    fakePlayer.send("destroy", 0);
  }));

// The core's events of a name from index `start`, with their details.
const eventsFrom = (start, name) =>
  fakePlayer.coreEvents
    .slice(start)
    .filter((e) => e.name === name)
    .map((e) => ({ name: e.name, details: JSON.parse(e.json) }));

test("the core's events reach the platform while it listens", () =>
  inOrder(async () => {
    // Listened to before start: the first manifests gave it.
    const [added] = eventsFrom(0, "onStreamAdded");
    assertEquals(added.details.stream.runtimeId, BASE + "media.m3u8", "added");
    assertEquals(added.details.stream.type, "main", "its type");

    const start = fakePlayer.coreEvents.length;
    for (const name of [
      "onSegmentLoaded",
      "onChunkDownloaded",
      "onSegmentError",
      "onSegmentRegistryMiss",
    ]) {
      fakePlayer.send("listen", 0, name);
    }
    giveManifests();
    fakePlayer.send("load", 30, BASE + "seg3.m4s");
    await serve(BASE + "seg3.m4s", new Uint8Array([1, 2, 3]));
    await answerTo(30);
    const [loaded] = eventsFrom(start, "onSegmentLoaded");
    assertEquals(loaded.details.segment.url, BASE + "seg3.m4s", "the segment");
    assertEquals(loaded.details.bytesLength, 3, "its bytes");
    assertEquals(loaded.details.downloadSource, "http", "its source");
    // A chunk event's arguments come by name.
    const chunk = eventsFrom(start, "onChunkDownloaded").at(-1).details;
    assertEquals(
      `${chunk.bytesLength} ${chunk.downloadSource} ${chunk.streamType}`,
      "3 http main",
      "a chunk",
    );

    fakePlayer.send("load", 31, BASE + "seg2.m4s");
    await failEach(BASE + "seg2.m4s", 31);
    const { error } = eventsFrom(start, "onSegmentError")[0].details;
    assertEquals(error.name, "RequestError", "the error's class");
    assertEquals(typeof error.type, "string", "its type");
    assertEquals(typeof error.message, "string", "its message");

    await query("loadable", BASE + "unknown.m4s");
    const [miss] = eventsFrom(start, "onSegmentRegistryMiss");
    assertEquals(miss.details.url, BASE + "unknown.m4s", "a registry miss");

    // No more after unlisten.
    fakePlayer.send("unlisten", 0, "onSegmentLoaded");
    const before = eventsFrom(start, "onSegmentLoaded").length;
    fakePlayer.send("load", 32, BASE + "seg0.m4s");
    await serve(BASE + "seg0.m4s", new Uint8Array([4]));
    await answerTo(32);
    assertEquals(
      eventsFrom(start, "onSegmentLoaded").length,
      before,
      "unlistened",
    );
    // The bridge forwards nothing more for a test after this one.
    for (const name of [
      "onStreamAdded",
      "onChunkDownloaded",
      "onSegmentError",
      "onSegmentRegistryMiss",
    ]) {
      fakePlayer.send("unlisten", 0, name);
    }
    fakePlayer.send("destroy", 0);
  }));

test("the streams, their segments, the swarm's name and dynamic settings", () =>
  inOrder(async () => {
    try {
      // Before the first manifest: the swarm's name, without the query.
      const named = "https://cdn.example.com/show/index.m3u8";
      fakePlayer.send("manifestResponseUrl", 0, named + "?token=1");
      giveManifests();
      const streams = await query("streams");
      assertEquals(streams.length, 1, "the streams");
      assertEquals(streams[0].runtimeId, BASE + "media.m3u8", "its runtimeId");
      assertEquals(streams[0].swarmId, named, "its swarm");
      const stream = await query("stream", BASE + "media.m3u8");
      assertEquals(stream.infoHash, streams[0].infoHash, "the stream");
      assertEquals(await query("stream", BASE + "x.m3u8"), null, "unknown");

      const has = (url, range) => query("hasSegment", BASE + url, range);
      assertEquals(await has("seg2.m4s"), true, "a segment");
      assertEquals(await has("other.m4s"), false, "an unknown URL");
      assertEquals(await has("range.m4s", "0-999"), true, "its range");
      assertEquals(await has("range.m4s", "0-500"), false, "another range");

      // As in JS: for both stream types, or for one; a static key is ignored.
      fakePlayer.send(
        "dynamicConfig",
        0,
        JSON.stringify({
          isP2PDisabled: true,
          swarmId: "changed",
          mainStream: { httpErrorRetries: 5 },
        }),
      );
      const { mainStream, secondaryStream } = await query("config");
      assertEquals(mainStream.isP2PDisabled, true, "for both");
      assertEquals(secondaryStream.isP2PDisabled, true, "for both, secondary");
      assertEquals(mainStream.httpErrorRetries, 5, "for main");
      assertEquals(secondaryStream.httpErrorRetries, 3, "not for secondary");
      // The platform's functions are their markers again.
      assertEquals(mainStream.swarmId?.function, 1, "a static key");
      assertEquals(secondaryStream.segmentFilter?.function, 3, "a function");
    } finally {
      // The settings of the tests after this one, which destroy keeps.
      fakePlayer.send(
        "dynamicConfig",
        0,
        JSON.stringify({ isP2PDisabled: false, httpErrorRetries: 3 }),
      );
      fakePlayer.send("destroy", 0);
    }
  }));

// A source with a video stream and an audio rendition.
const SHOW = BASE + "show/";
const SHOW_MASTER = [
  "#EXTM3U",
  '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="en",URI="audio.m3u8"',
  '#EXT-X-STREAM-INF:BANDWIDTH=1280000,AUDIO="a"',
  "media.m3u8",
  "",
].join("\n");
const AUDIO = MEDIA.replaceAll("seg", "aud").replace("range", "arange");

const giveShow = () => {
  fakePlayer.send(
    "manifest",
    0,
    SHOW + "master.m3u8",
    SHOW + "master.m3u8?id=42",
    SHOW_MASTER,
  );
  fakePlayer.send("manifest", 0, SHOW + "media.m3u8", undefined, MEDIA);
  fakePlayer.send("manifest", 0, SHOW + "audio.m3u8", undefined, AUDIO);
};

test("the platform's functions name the swarm, the streams and the shared segments", () =>
  inOrder(async () => {
    const calls = [];
    const argumentsOf = (n) =>
      calls.filter((call) => call.n === n).map((call) => call.argument);
    try {
      fakePlayer.onCall = (n, json) => {
        const argument = JSON.parse(json);
        calls.push({ n, argument });
        const answer = (value) => JSON.stringify({ value });
        if (n === 1) return answer("show-42");
        if (n === 2) return answer(argument.index === 0 ? null : "x");
        if (argument.url.endsWith("/seg2.m4s")) return answer(false);
        if (argument.url.endsWith("/range.m4s")) {
          return JSON.stringify({ error: "the filter failed" });
        }
        return answer(true);
      };
      giveShow();
      const streams = await query("streams");
      assertEquals(streams.length, 2, "the streams");
      const [video, audio] = streams;
      assertEquals(video.swarmId, "show-42", "the swarm");
      assertEquals(audio.swarmId, "show-42", "the audio's swarm");
      // One function for main and secondary: asked once for the source.
      assertEquals(
        JSON.stringify(argumentsOf(1)),
        JSON.stringify([
          {
            manifestUrl: SHOW + "master.m3u8",
            requestedUrl: SHOW + "master.m3u8?id=42",
          },
        ]),
        "the swarm's function",
      );
      // A builder's null keeps the default.
      assertEquals(video.streamId, "main0", "the default stream ID");
      const built = argumentsOf(2).find((a) => a.streamType === "main");
      assertEquals(built.swarmId, "show-42", "the builder gets the swarm");
      assertEquals(
        built.manifest.url,
        SHOW + "master.m3u8?id=42",
        "its manifest, as requested",
      );

      const has = (url, range) => query("hasSegment", SHOW + url, range);
      assertEquals(await has("seg0.m4s"), true, "a shared segment");
      assertEquals(await has("seg2.m4s"), false, "a filtered segment");
      assertEquals(await has("range.m4s", "0-999"), false, "a failed filter");
      const filtered = argumentsOf(3).find((a) => a.url === SHOW + "seg2.m4s");
      assertEquals(filtered.runtimeId, SHOW + "media.m3u8", "its stream");
      assertEquals(
        `${filtered.startTime}-${filtered.endTime}`,
        "8-12",
        "times",
      );
      const ranged = argumentsOf(3).find((a) => a.url === SHOW + "range.m4s");
      assertEquals(JSON.stringify(ranged.byteRange), '{"start":0,"end":999}');

      // The next source asks again; no answer is a throw in the core, which
      // then registers no stream.
      fakePlayer.send("destroy", 0);
      fakePlayer.onCall = () => undefined;
      giveShow();
      assertEquals((await query("streams")).length, 0, "no answer");
    } finally {
      fakePlayer.onCall = asWithout;
      fakePlayer.send("destroy", 0);
    }
  }));
