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
const CONFIG = JSON.stringify({
  httpDownloadTimeWindow: 0,
  p2pDownloadTimeWindow: 0,
});

const giveManifests = () => {
  fakePlayer.send("manifest", 0, BASE + "master.m3u8", undefined, MASTER);
  fakePlayer.send("manifest", 0, BASE + "media.m3u8", undefined, MEDIA);
};

// The value of query `name` (loadable, config), parsed.
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

test("before start, nothing is loadable and a load fails", () =>
  inOrder(async () => {
    assertEquals(await query("loadable", BASE + "seg0.m4s"), false, "loadable");
    assertEquals(await query("config"), null, "config");
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
    for (let i = 0; answersOf(7).length === 0 && i < 200; i++) {
      for (const request of fakeHttp.started) {
        if (request.url === BASE + "seg2.m4s" && !request.used) {
          request.used = true;
          fakeHttp.fail(request.id, "no network");
        }
      }
      await tasksRun();
    }
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
