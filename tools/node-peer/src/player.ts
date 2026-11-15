// The headless player's state, with no network and no timers: where the
// segments are on its timeline, what it has buffered, where the playhead is,
// and what it asks for next. It follows HLS.js with the P2P Media Loader
// adapter, so that the core sees the requests and the playback a browser peer
// gives it.
import type { PlaybackState } from "p2p-media-loader-core";
import type { MediaPlaylist, MediaSegment } from "./hls.ts";

export type TimedSegment = MediaSegment & {
  /** Seconds on the player's timeline. */
  start: number;
  end: number;
};

type Range = { start: number; end: number };

/**
 * Lays the segments of a live media playlist on the player's timeline. The
 * first playlist starts at 0. A segment keeps its start across refreshes, as
 * HLS.js keeps a fragment's, and a new one is laid next to the segments seen
 * before it.
 */
export class Timeline {
  readonly #starts = new Map<number, number>();

  update({ segments }: MediaPlaylist): TimedSegment[] {
    if (segments.length === 0) return [];
    let anchor = 0;
    let anchorStart = 0;
    if (this.#starts.size > 0) {
      anchor = segments.findIndex((s) => this.#starts.has(s.sequence));
      if (anchor === -1) {
        throw new Error("the playlist lists no segment seen before");
      }
      anchorStart = this.#starts.get(segments[anchor].sequence)!;
    }

    let cursor = segments
      .slice(0, anchor)
      .reduce((start, segment) => start - segment.duration, anchorStart);
    return segments.map((segment) => {
      const start = this.#starts.get(segment.sequence) ?? cursor;
      this.#starts.set(segment.sequence, start);
      cursor = start + segment.duration;
      return { ...segment, start, end: cursor };
    });
  }
}

/**
 * What a player with one quality has buffered and played. It starts at the
 * position the core's live placement gives, and buffers up to `maxBuffer`
 * ahead of the playhead, as HLS.js does with the adapter's `maxBufferLength`.
 *
 * Time is the caller's: call `advance` with the time since the last call
 * before each `append`, `nextSegment` and `state`, so that the time before a
 * segment arrived is played, or stalled, before the segment is buffered.
 */
export class PlaybackModel {
  readonly #maxBuffer: number;
  #playhead: number;
  #ranges: Range[] = [];
  #started = false;
  #stalled = false;
  #stalls = 0;
  #stalledSeconds = 0;

  constructor({
    position,
    maxBuffer,
  }: {
    position: number;
    maxBuffer: number;
  }) {
    this.#playhead = position;
    this.#maxBuffer = maxBuffer;
  }

  get playhead() {
    return this.#playhead;
  }

  /** Whether playback has started: it does at the first buffered playhead. */
  get started() {
    return this.#started;
  }

  /** How many times the playhead reached the end of the buffer. */
  get stalls() {
    return this.#stalls;
  }

  get stalledSeconds() {
    return this.#stalledSeconds;
  }

  /** Adds a loaded segment's time range to the buffer. */
  append(start: number, end: number) {
    const ranges = [...this.#ranges, { start, end }].sort(
      (a, b) => a.start - b.start,
    );
    this.#ranges = [];
    for (const range of ranges) {
      const last = this.#ranges.at(-1);
      if (last && range.start <= last.end) {
        last.end = Math.max(last.end, range.end);
      } else {
        this.#ranges.push({ ...range });
      }
    }
    if (!this.#started && this.#bufferAhead() > 0) this.#started = true;
  }

  /**
   * Plays for the elapsed time, up to the end of the buffer. Before playback
   * starts the time is startup; after it, time without media is a stall.
   */
  advance(seconds: number) {
    if (!this.#started) return;
    const played = Math.min(seconds, this.#bufferAhead());
    this.#playhead += played;
    if (played > 0) this.#stalled = false;
    if (played < seconds) {
      if (!this.#stalled) this.#stalls++;
      this.#stalled = true;
      this.#stalledSeconds += seconds - played;
    }
  }

  /**
   * What the adapter reports from a media element: the buffer ahead in the
   * range that contains the playhead, and the rate of an element that is not
   * paused. A stalled element is not paused, so a stall is rate 1 with no
   * buffer: the most urgent state.
   */
  state(): PlaybackState {
    return { bufferAhead: this.#bufferAhead(), rate: 1 };
  }

  /**
   * The segment to request now: the one after the end of the buffer, while
   * the buffer ahead is shorter than `maxBuffer`. `undefined` when the buffer
   * is full, or when the playlist does not list the next segment yet.
   */
  nextSegment(segments: readonly TimedSegment[]): TimedSegment | undefined {
    const bufferAhead = this.#bufferAhead();
    if (bufferAhead >= this.#maxBuffer) return undefined;
    const bufferEnd = this.#playhead + bufferAhead;
    return segments.find((segment) => segment.end > bufferEnd);
  }

  // The rule of the core's getBufferAhead.
  #bufferAhead() {
    const playhead = this.#playhead;
    const range = this.#ranges.find(
      ({ start, end }) => start <= playhead && playhead <= end,
    );
    return range ? range.end - playhead : 0;
  }
}
