import assert from "node:assert/strict";
import { test } from "node:test";
import { parseMediaPlaylist } from "../src/hls.ts";

const PLAYLIST_URL = "https://origin.example/bbb/1080p/live.m3u8";
const playlist = (...lines: string[]) => lines.join("\n");

test("a live playlist gives each segment its URL, duration, sequence and map", () => {
  const text = [
    "#EXTM3U",
    "#EXT-X-VERSION:7",
    "#EXT-X-TARGETDURATION:6",
    "#EXT-X-MEDIA-SEQUENCE:4587192",
    "#EXT-X-DISCONTINUITY-SEQUENCE:43687",
    '#EXT-X-MAP:URI="init_0.mp4"',
    "",
    "#EXT-X-PROGRAM-DATE-TIME:2026-11-15T13:19:12.000Z",
    "#EXTINF:6.000,",
    "seg_0057.m4s?loop=43687",
    "#EXTINF:5.5,title",
    "/root/seg_0058.m4s",
    '#EXT-X-PART:DURATION=1.0,URI="part.m4s"',
    '#EXT-X-PRELOAD-HINT:TYPE=PART,URI="hint.m4s"',
    "#EXTINF:6.000,",
    "https://cdn.example/seg_0059.m4s",
    "",
  ].join("\r\n");

  assert.deepEqual(parseMediaPlaylist(text, PLAYLIST_URL), {
    targetDuration: 6,
    mediaSequence: 4587192,
    endList: false,
    segments: [
      {
        url: "https://origin.example/bbb/1080p/seg_0057.m4s?loop=43687",
        duration: 6,
        sequence: 4587192,
        initUrl: "https://origin.example/bbb/1080p/init_0.mp4",
      },
      {
        url: "https://origin.example/root/seg_0058.m4s",
        duration: 5.5,
        sequence: 4587193,
        initUrl: "https://origin.example/bbb/1080p/init_0.mp4",
      },
      {
        url: "https://cdn.example/seg_0059.m4s",
        duration: 6,
        sequence: 4587194,
        initUrl: "https://origin.example/bbb/1080p/init_0.mp4",
      },
    ],
  });
});

test("a new map applies only to the segments after it", () => {
  const { segments } = parseMediaPlaylist(
    playlist(
      "#EXTM3U",
      "#EXT-X-TARGETDURATION:6",
      '#EXT-X-MAP:URI="init_0.mp4"',
      "#EXTINF:6,",
      "a.m4s",
      "#EXT-X-DISCONTINUITY",
      '#EXT-X-MAP:URI="init_1.mp4"',
      "#EXTINF:6,",
      "b.m4s",
    ),
    PLAYLIST_URL,
  );

  assert.deepEqual(
    segments.map((segment) => segment.initUrl),
    [
      "https://origin.example/bbb/1080p/init_0.mp4",
      "https://origin.example/bbb/1080p/init_1.mp4",
    ],
  );
});

test("a VOD playlist has ended, and sequences start at 0 without the tag", () => {
  const parsed = parseMediaPlaylist(
    playlist(
      "#EXTM3U",
      "#EXT-X-TARGETDURATION:6",
      "#EXT-X-PLAYLIST-TYPE:VOD",
      "#EXTINF:6,",
      "seg_0000.m4s",
      "#EXTINF:6,",
      "seg_0001.m4s",
      "#EXT-X-ENDLIST",
    ),
    PLAYLIST_URL,
  );

  assert.equal(parsed.endList, true);
  assert.equal(parsed.mediaSequence, 0);
  assert.deepEqual(
    parsed.segments.map((segment) => [segment.sequence, segment.initUrl]),
    [
      [0, undefined],
      [1, undefined],
    ],
  );
});
