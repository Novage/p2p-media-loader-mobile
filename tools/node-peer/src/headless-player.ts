// Plays a live HLS stream through the core with real time, the way HLS.js
// with the P2P Media Loader adapter plays it in a browser: one quality,
// placed and buffered by the core's live rules, one segment request at a
// time, and playback reported every 250 ms and before each request.
import type * as CoreApi from "p2p-media-loader-core";
import { fetchPlaylist, parseMediaPlaylist } from "./hls.ts";
import { PlaybackModel, Timeline } from "./player.ts";

export type PlayOptions = {
  core: InstanceType<typeof CoreApi.Core>;
  liveDelayFor: typeof CoreApi.liveDelayFor;
  playerBufferFor: typeof CoreApi.playerBufferFor;
  streamUrl: string;
  seconds: number;
  log: (line: string) => void;
};

// How often a media element fires `timeupdate`, which the adapter reports on.
const REPORT_INTERVAL_MS = 250;
// HLS.js's default number of retries for a fragment error.
const MAX_RETRIES = 6;

const megabytes = (bytes: number) => (bytes / 1024 / 1024).toFixed(2);

/** Plays for `seconds`. Throws on an error that ends the run. */
export async function play(options: PlayOptions): Promise<void> {
  const { core, liveDelayFor, playerBufferFor, streamUrl, seconds, log } =
    options;
  const startedAt = performance.now();
  const say = (line: string) => {
    const elapsed = ((performance.now() - startedAt) / 1000).toFixed(1);
    log(`[${elapsed.padStart(6)}] ${line}`);
  };

  core.processManifest(await fetchPlaylist(streamUrl));
  // The highest bitrate: where web viewers on a good connection play.
  const [main] = core
    .getStreams()
    .filter((stream) => stream.type === "main")
    .sort((a, b) => (b.properties.bitrate ?? 0) - (a.properties.bitrate ?? 0));
  if (!main) throw new Error(`${streamUrl}: no main stream`);

  // HLS.js schedules each reload from when the previous one was due, starting
  // with the first request.
  let reloadDue = performance.now();
  const media = await fetchPlaylist(main.runtimeId);
  const processed = core.processManifest(media);
  const target = processed && liveDelayFor(processed);
  if (!target) throw new Error(`${media.url}: not a live stream`);

  // Listed after the media playlist: one the core did not match to the
  // master's stream shows as a stream of its own.
  for (const stream of core.getStreams()) {
    const { bitrate, height } = stream.properties;
    say(
      `${stream.streamId}: ${bitrate} bit/s, ${height}p, infohash ${stream.infoHash}`,
    );
  }

  const timeline = new Timeline();
  let playlist = parseMediaPlaylist(media.data, media.url);
  let segments = timeline.update(playlist);
  // False without WebRTC, with P2P off, or for a stream the core cannot
  // share: the run would measure nothing.
  if (!core.isSegmentLoadable(segments.at(-1)!.url)) {
    throw new Error(`${media.url}: the core does not load its segments`);
  }
  const maxBuffer = playerBufferFor(target);
  const model = new PlaybackModel({
    position: segments.at(-1)!.end - target.delay,
    maxBuffer,
  });
  say(
    `${main.streamId}: live delay ${target.delay} s, buffer up to ${maxBuffer} s`,
  );

  const bytes = { http: 0, p2p: 0 };
  const onChunkDownloaded = (length: number, source: "http" | "p2p") => {
    bytes[source] += length;
  };
  core.addEventListener("onChunkDownloaded", onChunkDownloaded);

  // Every handler moves the model to now first, so that the time before a
  // segment arrived is played, or stalled, before the segment is buffered.
  let lastAdvance = performance.now();
  const advance = () => {
    const now = performance.now();
    model.advance((now - lastAdvance) / 1000);
    lastAdvance = now;
  };

  let stopped = false;
  let fatal: unknown;
  let stop!: () => void;
  const done = new Promise<void>((resolve) => {
    stop = () => {
      stopped = true;
      resolve();
    };
  });
  const fail = (error: unknown) => {
    fatal ??= error;
    stop();
  };

  const abort = new AbortController();
  const fetchBytes = async (url: string) => {
    const response = await fetch(url, { signal: abort.signal });
    if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
    return response.arrayBuffer();
  };

  let loading = false;
  let initUrl: string | undefined;
  let lastLoaded: number | undefined;
  let retries = 0;
  let retryAt = 0;
  const loadNext = async (): Promise<void> => {
    if (loading || stopped || performance.now() < retryAt) return;
    advance();
    const segment = model.nextSegment(segments);
    if (!segment) return;
    if (segment.sequence === lastLoaded) {
      fail(new Error(`segment ${segment.sequence} did not join the buffer`));
      return;
    }

    loading = true;
    const requestedAt = performance.now();
    try {
      // The core passes init segments through: the player loads them.
      if (segment.initUrl !== undefined && segment.initUrl !== initUrl) {
        await fetchBytes(segment.initUrl);
        initUrl = segment.initUrl;
      }
      advance();
      core.updatePlayback(model.state());
      const viaCore = core.isSegmentLoadable(segment.url);
      const data = viaCore
        ? (await core.loadSegment(segment.url, { signal: abort.signal })).data
        : await fetchBytes(segment.url);
      advance();
      model.append(segment.start, segment.end);
      lastLoaded = segment.sequence;
      retries = 0;
      const ms = Math.round(performance.now() - requestedAt);
      say(
        `#${segment.sequence} ${viaCore ? "core" : "direct"} ` +
          `${megabytes(data.byteLength)} MB in ${ms} ms | ` +
          `buffer ${model.state().bufferAhead.toFixed(1)} s, ` +
          `stalls ${model.stalls} | ` +
          `http ${megabytes(bytes.http)} MB, p2p ${megabytes(bytes.p2p)} MB`,
      );
    } catch (error) {
      if (stopped) return;
      say(`#${segment.sequence} failed: ${String(error)}`);
      // HLS.js's default for a fragment error: 6 retries, 1 s apart at
      // first, the wait doubled each time up to 8 s.
      if (retries === MAX_RETRIES) {
        fail(new Error(`#${segment.sequence}: ${MAX_RETRIES} retries failed`));
      } else {
        retryAt = performance.now() + Math.min(1000 * 2 ** retries, 8000);
        retries++;
      }
      return;
    } finally {
      loading = false;
    }
    // HLS.js looks for the next fragment as soon as one is buffered.
    void loadNext();
  };

  const reportTimer = setInterval(() => {
    advance();
    core.updatePlayback(model.state());
    void loadNext();
  }, REPORT_INTERVAL_MS);

  // HLS.js's live reload (computeReloadInterval): a target duration after a
  // playlist that changed, or the last segment's duration where it is shorter
  // and the playhead is near the edge; half a target duration after one that
  // did not change.
  let refreshTimer: NodeJS.Timeout | undefined;
  const scheduleRefresh = (updated: boolean) => {
    let interval = playlist.targetDuration * 1000;
    if (!updated) {
      interval /= 2;
    } else {
      const last = playlist.segments.at(-1)!.duration * 1000;
      const toEdge = (segments.at(-1)!.end - model.playhead) * 1000;
      if (interval * 4 > toEdge && last < interval) interval = last;
    }
    const now = performance.now();
    if (reloadDue + interval < now) reloadDue = now;
    else if (reloadDue <= now) reloadDue += interval;
    refreshTimer = setTimeout(refresh, reloadDue - now);
  };
  const refresh = () => {
    fetchPlaylist(main.runtimeId)
      .then(
        (refreshed) => {
          if (stopped) return;
          core.processManifest(refreshed);
          const previous = playlist.segments.at(-1)?.sequence;
          playlist = parseMediaPlaylist(refreshed.data, refreshed.url);
          segments = timeline.update(playlist);
          advance();
          if (model.playhead < segments[0].start) {
            throw new Error("the playhead left the live window");
          }
          void loadNext();
          scheduleRefresh(playlist.segments.at(-1)?.sequence !== previous);
        },
        // Tried again as after a playlist that did not change.
        (error: unknown) => {
          if (stopped) return;
          say(`playlist refresh failed: ${String(error)}`);
          scheduleRefresh(false);
        },
      )
      .catch(fail);
  };

  const endTimer = setTimeout(stop, seconds * 1000);
  scheduleRefresh(true);
  void loadNext();
  await done;

  clearInterval(reportTimer);
  clearTimeout(refreshTimer);
  clearTimeout(endTimer);
  abort.abort();
  core.removeEventListener("onChunkDownloaded", onChunkDownloaded);
  if (fatal !== undefined) throw fatal;
}
