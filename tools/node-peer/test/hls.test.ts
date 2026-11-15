import assert from "node:assert/strict";
import { test } from "node:test";
import { segmentUrls } from "../src/hls.ts";

test("segmentUrls resolves each segment line against the playlist URL", () => {
  const playlist = [
    "#EXTM3U",
    "#EXT-X-TARGETDURATION:6",
    '#EXT-X-MAP:URI="init_0.mp4"',
    "",
    "#EXTINF:6.000,",
    "seg_1.m4s?loop=7",
    "#EXTINF:6.000,",
    "/root/seg_2.m4s",
    "#EXTINF:6.000,",
    "https://cdn.example/seg_3.m4s",
    "",
  ].join("\r\n");

  assert.deepEqual(
    segmentUrls(playlist, "https://origin.example/bbb/1080p/live.m3u8"),
    [
      "https://origin.example/bbb/1080p/seg_1.m4s?loop=7",
      "https://origin.example/root/seg_2.m4s",
      "https://cdn.example/seg_3.m4s",
    ],
  );
});
