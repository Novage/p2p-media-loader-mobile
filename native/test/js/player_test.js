// The player bridge (src/polyfills/player.js), with the real core and the
// fake clients of engine_test.c: fakePlayer.send posts the player's events as
// tasks, as the platform does, and fakePlayer.answers records each load's
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

// The answers to load `id`, after the tasks before it have run.
const answersOf = (id) => fakePlayer.answers.filter((a) => a.id === id);

// Waits for the first answer to load `id`.
const answerTo = async (id) => {
  for (let i = 0; i < 200; i++) {
    const [answer] = answersOf(id);
    if (answer !== undefined) return answer;
    await tasksRun();
  }
  throw new Error(`no answer to load ${id}`);
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

const startCore = () => {
  fakePlayer.send("start", 0, CONFIG);
  fakePlayer.send("manifest", 0, BASE + "master.m3u8", undefined, MASTER);
  fakePlayer.send("manifest", 0, BASE + "media.m3u8", undefined, MEDIA);
};

test("a load before start is a miss", () =>
  inOrder(async () => {
    fakePlayer.send("load", 1, BASE + "seg0.m4s");
    assertEquals((await answerTo(1)).kind, "miss", "the answer");
  }));

test("the event after start finds the core", () =>
  inOrder(async () => {
    // Each event is a task, as from the platform.
    startCore();
    fakePlayer.send("load", 2, BASE + "seg0.m4s");
    const request = await requestOf(BASE + "seg0.m4s");
    fakeHttp.respond(request.id, 200, "OK", request.url, []);
    fakeHttp.data(request.id, new Uint8Array([1, 2, 3]));
    fakeHttp.end(request.id);
    const answer = await answerTo(2);
    assertEquals(answer.kind, "segment", "the answer");
    assertEquals(hex(answer.data), "01 02 03", "the bytes");
    assertEquals(answersOf(2).length, 1, "one answer");
  }));

test("an unknown URL and an init segment are misses", () =>
  inOrder(async () => {
    fakePlayer.send("load", 3, BASE + "other.m4s");
    fakePlayer.send("load", 4, BASE + "init.mp4");
    assertEquals((await answerTo(3)).kind, "miss", "an unknown URL");
    assertEquals((await answerTo(4)).kind, "miss", "an init segment");
  }));

test("a byte-range segment is found by its range", () =>
  inOrder(async () => {
    fakePlayer.send("load", 5, BASE + "range.m4s", "0-500");
    assertEquals((await answerTo(5)).kind, "miss", "another range");
    fakePlayer.send("load", 6, BASE + "range.m4s", "0-999");
    const request = await requestOf(BASE + "range.m4s");
    assertEquals(request.headers.range, "bytes=0-999", "the request's range");
    fakeHttp.respond(request.id, 206, "Partial Content", request.url, []);
    fakeHttp.data(request.id, new Uint8Array(1000));
    fakeHttp.end(request.id);
    const answer = await answerTo(6);
    assertEquals(answer.kind, "segment", "the answer");
    assertEquals(answer.data.length, 1000, "the length");
  }));

test("a load that fails gets the core's error", () =>
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
    assertEquals(answer.kind, "fail", "the answer");
    assertEquals(answersOf(7).length, 1, "one answer");
  }));

test("abort gives one failure, at once", () =>
  inOrder(async () => {
    fakePlayer.send("load", 8, BASE + "seg3.m4s");
    await requestOf(BASE + "seg3.m4s");
    fakePlayer.send("abort", 8);
    const answer = await answerTo(8);
    assertEquals(`${answer.kind} ${answer.message}`, "fail aborted", "abort");
    await tasksRun();
    assertEquals(answersOf(8).length, 1, "one answer");
    fakePlayer.send("abort", 8);
    fakePlayer.send("abort", 99);
    await tasksRun();
    assertEquals(answersOf(8).length, 1, "an abort after the answer");
    assertEquals(answersOf(99).length, 0, "an abort of no load");
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

test("a failure gives the type of the core's error", () =>
  inOrder(async () => {
    const { CoreRequestError } = await import("p2p-media-loader-core");
    await withCoreMethod(
      "loadSegment",
      () => Promise.reject(new CoreRequestError("failed", "a detail")),
      async () => {
        fakePlayer.send("load", 20, BASE + "seg0.m4s");
        const answer = await answerTo(20);
        assertEquals(`${answer.kind} ${answer.message}`, "fail failed", "type");
      },
    );
  }));

test("an answer that cannot be sent becomes a failure", () =>
  inOrder(async () => {
    // A Uint8Array of a negative length throws.
    await withCoreMethod(
      "loadSegment",
      () => Promise.resolve({ data: -1 }),
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

test("stop fails the open loads, and a later load is a miss", () =>
  inOrder(async () => {
    // Not seg0, which the core keeps in its storage. The core may wait for
    // the request that the abort left: no new one starts.
    fakePlayer.send("load", 10, BASE + "seg3.m4s");
    await tasksRun();
    assertEquals(answersOf(10).length, 0, "an open load");
    fakePlayer.send("stop", 0);
    const answer = await answerTo(10);
    assertEquals(`${answer.kind} ${answer.message}`, "fail stopped", "stop");
    fakePlayer.send("load", 11, BASE + "seg0.m4s");
    assertEquals((await answerTo(11)).kind, "miss", "after stop");
    // The core can start again.
    startCore();
    fakePlayer.send("load", 12, BASE + "init.mp4");
    assertEquals((await answerTo(12)).kind, "miss", "an init segment again");
  }));
