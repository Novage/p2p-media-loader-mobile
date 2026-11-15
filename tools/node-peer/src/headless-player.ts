// Plays a live HLS stream through the core with real time, the way HLS.js
// with the P2P Media Loader adapter plays it in a browser: one quality,
// placed and buffered by the core's live rules, one segment request at a
// time, and playback reported every 250 ms and before each request.
// Node's own: the DOM lib's Performance type has no eventLoopUtilization.
import { performance as nodePerformance } from "node:perf_hooks";
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

/** What a run measured. */
export type Summary = {
  /** How long the run played, in seconds. */
  seconds: number;
  /** From the start of the run to the first playback, in seconds. */
  startupSeconds?: number;
  stalls: number;
  stalledSeconds: number;
  /** How far the playhead was behind the live edge at the end, in seconds. */
  liveDistance: number;
  segments: { core: number; direct: number; failed: number };
  /** Segment load times, in milliseconds. */
  loadMs: { mean: number; max: number };
  reloads: { changed: number; unchanged: number; failed: number };
  /** What the core downloaded over HTTP and from peers, and uploaded. */
  bytes: { http: number; p2p: number; uploaded: number };
  peers: { connected: number; mostAtOnce: number };
  trackerErrors: number;
  /**
   * Over the playing window, from the placement to the end: the share of the
   * time the event loop was busy (JS, native callbacks, garbage collection
   * pauses), and the CPU time of all the process's threads in percent of one
   * core. Upper bounds for the core: they include HTTP, TLS and this player.
   */
  cpu: { mainThreadBusy: number; processPercent: number };
  /** Resident memory in MiB: at the start of playback, and the process's peak. */
  memory: { atStartMiB: number; peakMiB: number };
  /** The error that ended the run, if one did. */
  error?: unknown;
};

const megabytes = (bytes: number) => (bytes / 1024 / 1024).toFixed(2);

/**
 * Plays for `seconds`. Throws when the run cannot start; an error after the
 * start ends the run and is returned in the summary.
 */
export async function play(options: PlayOptions): Promise<Summary> {
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
  const loopAtStart = nodePerformance.eventLoopUtilization();
  const cpuAtStart = process.cpuUsage();
  const playingFrom = performance.now();
  const memoryAtStart = process.memoryUsage().rss / 1024 / 1024;

  const segmentCounts = { core: 0, direct: 0, failed: 0 };
  const reloads = { changed: 0, unchanged: 0, failed: 0 };
  const bytes = { http: 0, p2p: 0, uploaded: 0 };
  const peers = { connected: 0, mostAtOnce: 0 };
  let openPeers = 0;
  let trackerErrors = 0;
  let startupSeconds: number | undefined;
  let loadMsTotal = 0;
  let loadMsMax = 0;

  const onChunkDownloaded = (length: number, source: "http" | "p2p") => {
    bytes[source] += length;
  };
  const onChunkUploaded = (length: number) => {
    bytes.uploaded += length;
  };
  const onPeerConnect = () => {
    peers.connected++;
    peers.mostAtOnce = Math.max(peers.mostAtOnce, ++openPeers);
  };
  const onPeerClose = () => {
    openPeers--;
  };
  const onTrackerError = () => {
    trackerErrors++;
  };
  core.addEventListener("onChunkDownloaded", onChunkDownloaded);
  core.addEventListener("onChunkUploaded", onChunkUploaded);
  core.addEventListener("onPeerConnect", onPeerConnect);
  core.addEventListener("onPeerClose", onPeerClose);
  core.addEventListener("onTrackerError", onTrackerError);

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
    try {
      // The core passes init segments through: the player loads them.
      if (segment.initUrl !== undefined && segment.initUrl !== initUrl) {
        await fetchBytes(segment.initUrl);
        initUrl = segment.initUrl;
      }
      const requestedAt = performance.now();
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
      segmentCounts[viaCore ? "core" : "direct"]++;
      loadMsTotal += ms;
      loadMsMax = Math.max(loadMsMax, ms);
      if (startupSeconds === undefined && model.started) {
        startupSeconds = (performance.now() - startedAt) / 1000;
      }
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
      segmentCounts.failed++;
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
          const changed = playlist.segments.at(-1)?.sequence !== previous;
          reloads[changed ? "changed" : "unchanged"]++;
          scheduleRefresh(changed);
        },
        // Tried again as after a playlist that did not change.
        (error: unknown) => {
          if (stopped) return;
          say(`playlist refresh failed: ${String(error)}`);
          reloads.failed++;
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
  core.removeEventListener("onChunkUploaded", onChunkUploaded);
  core.removeEventListener("onPeerConnect", onPeerConnect);
  core.removeEventListener("onPeerClose", onPeerClose);
  core.removeEventListener("onTrackerError", onTrackerError);

  advance();
  const playingMs = performance.now() - playingFrom;
  const cpuTime = process.cpuUsage(cpuAtStart);
  const cpu = {
    mainThreadBusy:
      nodePerformance.eventLoopUtilization(loopAtStart).utilization,
    // cpuUsage is in microseconds.
    processPercent: (cpuTime.user + cpuTime.system) / 10 / playingMs,
  };
  const memory = {
    atStartMiB: memoryAtStart,
    // maxRSS is in KiB.
    peakMiB: process.resourceUsage().maxRSS / 1024,
  };
  const loaded = segmentCounts.core + segmentCounts.direct;
  return {
    seconds: (performance.now() - startedAt) / 1000,
    startupSeconds,
    stalls: model.stalls,
    stalledSeconds: model.stalledSeconds,
    liveDistance: segments.at(-1)!.end - model.playhead,
    segments: segmentCounts,
    loadMs: { mean: loaded ? loadMsTotal / loaded : 0, max: loadMsMax },
    reloads,
    bytes,
    peers,
    trackerErrors,
    cpu,
    memory,
    error: fatal,
  };
}

/** The summary as lines to print. */
export const formatSummary = (summary: Summary): string[] => {
  const { segments, loadMs, reloads, bytes, peers } = summary;
  const downloaded = bytes.http + bytes.p2p;
  const share = downloaded ? (100 * bytes.p2p) / downloaded : 0;
  const startup =
    summary.startupSeconds === undefined
      ? "playback did not start"
      : `startup ${summary.startupSeconds.toFixed(1)} s`;
  return [
    `played     ${(summary.seconds / 60).toFixed(1)} min, ${startup}`,
    `stalls     ${summary.stalls} (${summary.stalledSeconds.toFixed(1)} s)`,
    `live edge  ${summary.liveDistance.toFixed(1)} s behind at the end`,
    `segments   ${segments.core} through the core, ${segments.direct} direct, ` +
      `${segments.failed} failed; load ${Math.round(loadMs.mean)} ms mean, ` +
      `${loadMs.max} ms max`,
    `bytes      http ${megabytes(bytes.http)} MB, p2p ${megabytes(bytes.p2p)} MB ` +
      `(P2P ${share.toFixed(1)} %), uploaded ${megabytes(bytes.uploaded)} MB`,
    `peers      ${peers.connected} connected, at most ${peers.mostAtOnce} ` +
      `at once; ${summary.trackerErrors} tracker errors`,
    `playlist   ${reloads.changed} reloads changed, ${reloads.unchanged} ` +
      `unchanged, ${reloads.failed} failed`,
    `cpu        main thread ${(100 * summary.cpu.mainThreadBusy).toFixed(1)} % ` +
      `busy, process ${summary.cpu.processPercent.toFixed(1)} % of one core`,
    `memory     ${Math.round(summary.memory.atStartMiB)} MiB at the start, ` +
      `${Math.round(summary.memory.peakMiB)} MiB peak`,
  ];
};
