package com.novage.p2pml.engine

import org.json.JSONObject

// The script of NodePeerDeviceTest: the core plays a live HLS stream with no player. It is a
// reduced copy of tools/node-peer's headless player:
// - It plays the main stream with the highest bitrate, as the Node peer does. So both peers
//   announce the same infohash in the swarm of SWARM_ID.
// - It starts at the core's live delay and buffers up to the core's buffer cap.
// - It requests one segment at a time.
// - It reports its playback every 250 ms and before each request.
// It logs a line per segment. At the end it logs "summary http <B> p2p <B> uploaded <B> peers <N>
// trackerErrors <N> segments <N> failed <N>". When it cannot start or cannot follow the playlist,
// it logs "failed: <error>". Unlike the Node peer, it reloads the playlist a target duration after
// the last reload ends, not on HLS.js's schedule. It tries a failed segment again every second,
// with no limit. It does not check the live window.
internal object NodePeerScripts {
    fun driver(swarmId: String, streamUrl: String, seconds: Long) =
        "const SWARM_ID = ${JSONObject.quote(swarmId)};\n" +
            "const STREAM_URL = ${JSONObject.quote(streamUrl)};\n" +
            "const SECONDS = $seconds;\n" +
            body

    private val body =
        """
        (async () => {
          const api = await import("p2p-media-loader-core");
          const startedAt = performance.now();
          const say = (line) =>
            console.log("[" + ((performance.now() - startedAt) / 1000).toFixed(1) + "] " + line);
          const core = new api.Core({ manifestParsers: [api.hlsManifestParser], swarmId: SWARM_ID });
          const bytes = { http: 0, p2p: 0, uploaded: 0 };
          const counts = { peers: 0, trackerErrors: 0, segments: 0, failed: 0 };
          core.addEventListener("onChunkDownloaded", (length, source) => (bytes[source] += length));
          core.addEventListener("onChunkUploaded", (length) => (bytes.uploaded += length));
          core.addEventListener("onPeerConnect", () => counts.peers++);
          core.addEventListener("onTrackerError", () => counts.trackerErrors++);

          const fetchPlaylist = async (url) => {
            const response = await fetch(url);
            if (!response.ok) throw new Error(url + ": HTTP " + response.status);
            // Also the URL asked for, where a redirect changed it: the core matches a media
            // playlist to its stream by it.
            const requestedUrl = response.url === url ? undefined : url;
            return { url: response.url, requestedUrl, data: await response.text() };
          };
          // A segment keeps its start on the player's timeline across reloads, by its sequence
          // number; a new one is laid next to the segments seen before it.
          const starts = new Map();
          const readSegments = ({ url, data }) => {
            const found = [];
            let sequence = 0;
            let duration = 0;
            for (const raw of data.split("\n")) {
              const line = raw.trim();
              if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) sequence = Number(line.slice(22));
              else if (line.startsWith("#EXTINF:")) duration = parseFloat(line.slice(8));
              else if (line !== "" && !line.startsWith("#")) {
                found.push({ url: new URL(line, url).href, sequence: sequence + found.length, duration });
              }
            }
            const anchor = found.findIndex((segment) => starts.has(segment.sequence));
            if (starts.size > 0 && anchor === -1) throw new Error("the playlist lists no segment seen before");
            let cursor = anchor === -1 ? 0 : starts.get(found[anchor].sequence);
            for (const segment of found.slice(0, Math.max(anchor, 0))) cursor -= segment.duration;
            return found.map((segment) => {
              const start = starts.get(segment.sequence) ?? cursor;
              starts.set(segment.sequence, start);
              cursor = start + segment.duration;
              return { ...segment, start, end: cursor };
            });
          };

          core.processManifest(await fetchPlaylist(STREAM_URL));
          const [main] = core
            .getStreams()
            .filter((stream) => stream.type === "main")
            .sort((a, b) => (b.properties.bitrate ?? 0) - (a.properties.bitrate ?? 0));
          if (!main) throw new Error(STREAM_URL + ": no main stream");
          const media = await fetchPlaylist(main.runtimeId);
          const processed = core.processManifest(media);
          const target = processed && api.liveDelayFor(processed);
          if (!target) throw new Error(media.url + ": not a live stream");
          for (const stream of core.getStreams()) {
            say(stream.streamId + ": " + stream.properties.bitrate + " bit/s, infohash " + stream.infoHash);
          }
          let segments = readSegments(media);
          if (!core.isSegmentLoadable(segments.at(-1).url)) {
            throw new Error(media.url + ": the core does not load its segments");
          }
          const maxBuffer = api.playerBufferFor(target);
          const targetDuration = Number(/#EXT-X-TARGETDURATION:(\d+)/.exec(media.data)[1]);
          say("live delay " + target.delay + " s, buffer up to " + maxBuffer + " s");

          // The playhead moves only over buffered time; before the first segment it waits.
          let playhead = segments.at(-1).end - target.delay;
          let bufferEnd = playhead;
          let lastAdvance = performance.now();
          const advance = () => {
            const now = performance.now();
            playhead += Math.min((now - lastAdvance) / 1000, Math.max(bufferEnd - playhead, 0));
            lastAdvance = now;
          };
          const state = () => ({ bufferAhead: Math.max(bufferEnd - playhead, 0), rate: 1 });

          let stopped = false;
          let loading = false;
          let retryAt = 0;
          const loadNext = async () => {
            if (stopped || loading || performance.now() < retryAt) return;
            advance();
            if (state().bufferAhead >= maxBuffer) return;
            const segment = segments.find((candidate) => candidate.end > bufferEnd);
            if (!segment) return;
            loading = true;
            const requestedAt = performance.now();
            try {
              core.updatePlayback(state());
              const { data } = await core.loadSegment(segment.url);
              if (stopped) return;
              advance();
              bufferEnd = segment.end;
              counts.segments++;
              say(
                "#" + segment.sequence + " " + data.byteLength + " B in " +
                  Math.round(performance.now() - requestedAt) + " ms | buffer " +
                  state().bufferAhead.toFixed(1) + " s | http " + bytes.http + " B, p2p " +
                  bytes.p2p + " B, uploaded " + bytes.uploaded + " B",
              );
            } catch (error) {
              if (stopped) return;
              counts.failed++;
              retryAt = performance.now() + 1000;
              say("#" + segment.sequence + " failed: " + error);
              return;
            } finally {
              loading = false;
            }
            void loadNext();
          };

          let fail;
          const done = new Promise((resolve, reject) => {
            fail = reject;
            setTimeout(resolve, SECONDS * 1000);
          });
          // The engine has no setInterval (the core does not use it): each timer sets the next.
          const report = () => {
            if (stopped) return;
            advance();
            core.updatePlayback(state());
            void loadNext();
            setTimeout(report, 250);
          };
          // The next reload is set when the last one ends, so that two never overlap.
          const reload = () => {
            if (stopped) return;
            fetchPlaylist(main.runtimeId)
              .then((fresh) => {
                if (stopped) return;
                core.processManifest(fresh);
                segments = readSegments(fresh);
                void loadNext();
              }, (error) => {
                if (!stopped) say("playlist reload failed: " + error);
              })
              .then(() => setTimeout(reload, targetDuration * 1000))
              .catch(fail);
          };
          setTimeout(report, 250);
          setTimeout(reload, targetDuration * 1000);
          void loadNext();
          try {
            await done;
          } finally {
            stopped = true;
            core.destroy();
          }
          // The keys of bytes, then of counts: "summary http <B> p2p <B> uploaded <B> peers <N> ...".
          const totals = Object.entries({ ...bytes, ...counts });
          console.log("summary " + totals.map((entry) => entry.join(" ")).join(" "));
        })().catch((error) => console.error("failed: " + error));
        """
            .trimIndent()
}
