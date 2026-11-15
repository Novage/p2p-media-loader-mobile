import assert from "node:assert/strict";
import { test } from "node:test";
import { parseMediaPlaylist } from "../src/hls.ts";
import { PlaybackModel, Timeline } from "../src/player.ts";

/** A live playlist of segments with the given durations, from `sequence`. */
const live = (sequence: number, durations: number[]) =>
  parseMediaPlaylist(
    [
      "#EXTM3U",
      "#EXT-X-TARGETDURATION:6",
      `#EXT-X-MEDIA-SEQUENCE:${sequence}`,
      ...durations.flatMap((d, i) => [`#EXTINF:${d},`, `s${sequence + i}.m4s`]),
    ].join("\n"),
    "https://origin.example/live.m3u8",
  );

const times = (segments: { sequence: number; start: number; end: number }[]) =>
  segments.map(({ sequence, start, end }) => [sequence, start, end]);

test("the timeline keeps each segment's start across refreshes", () => {
  const timeline = new Timeline();
  assert.deepEqual(times(timeline.update(live(100, [6, 4, 6]))), [
    [100, 0, 6],
    [101, 6, 10],
    [102, 10, 16],
  ]);

  // Two segments left the window, three were added.
  assert.deepEqual(times(timeline.update(live(102, [6, 6, 5, 6]))), [
    [102, 10, 16],
    [103, 16, 22],
    [104, 22, 27],
    [105, 27, 33],
  ]);

  // A stale copy from a CDN lists a segment before the first one seen.
  assert.deepEqual(times(timeline.update(live(99, [5, 6]))), [
    [99, -5, 0],
    [100, 0, 6],
  ]);

  assert.throws(() => timeline.update(live(200, [6])), /no segment seen/);
});

test("playback starts at the first buffered playhead, not before", () => {
  const model = new PlaybackModel({ position: 8, maxBuffer: 18 });
  model.advance(5);
  assert.equal(model.started, false);
  assert.equal(model.playhead, 8);
  assert.equal(model.stalls, 0);

  // A segment after the playhead does not start playback.
  model.append(12, 18);
  assert.equal(model.started, false);

  model.append(6, 12);
  assert.equal(model.started, true);
  assert.deepEqual(model.state(), { bufferAhead: 10, rate: 1 });

  model.advance(1.5);
  assert.equal(model.playhead, 9.5);
  assert.equal(model.state().bufferAhead, 8.5);
  assert.equal(model.stalls, 0);
});

test("a buffer exactly at maxBuffer requests nothing", () => {
  const segments = new Timeline().update(live(0, [6, 6, 6]));
  const model = new PlaybackModel({ position: 0, maxBuffer: 12 });
  model.append(0, 6);
  model.append(6, 12);
  assert.equal(model.nextSegment(segments), undefined);
});

test("a playhead at the end of the buffer stalls until more is buffered", () => {
  const model = new PlaybackModel({ position: 0, maxBuffer: 18 });
  model.append(0, 6);

  model.advance(8);
  assert.equal(model.playhead, 6);
  assert.equal(model.stalls, 1);
  assert.equal(model.stalledSeconds, 2);
  assert.deepEqual(model.state(), { bufferAhead: 0, rate: 1 });

  model.advance(1);
  assert.equal(model.stalls, 1, "one stall, however long");
  assert.equal(model.stalledSeconds, 3);

  model.append(6, 12);
  model.advance(7);
  assert.equal(model.playhead, 12);
  assert.equal(model.stalls, 2);
  assert.equal(model.stalledSeconds, 4);
});

test("the player requests the segment after its buffer, up to maxBuffer", () => {
  const segments = new Timeline().update(live(0, [6, 6, 6, 6, 6]));
  const model = new PlaybackModel({ position: 3, maxBuffer: 18 });

  // The segment that holds the start position.
  assert.equal(model.nextSegment(segments)?.sequence, 0);

  for (const sequence of [0, 1, 2]) {
    const { start, end } = segments[sequence];
    model.append(start, end);
  }
  // 15 s ahead of the playhead at 3: not full yet.
  assert.equal(model.nextSegment(segments)?.sequence, 3);
  model.append(segments[3].start, segments[3].end);
  assert.equal(model.nextSegment(segments), undefined, "21 s ahead: full");

  model.advance(6);
  assert.equal(model.nextSegment(segments)?.sequence, 4);
  model.append(segments[4].start, segments[4].end);

  model.advance(12);
  assert.equal(model.nextSegment(segments), undefined, "the live edge");
});
