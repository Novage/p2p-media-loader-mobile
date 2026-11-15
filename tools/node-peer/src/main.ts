import { readFileSync } from "node:fs";
import { fetchPlaylist, parseMediaPlaylist } from "./hls.ts";
import { loadCore } from "./load-core.ts";

const streamUrl =
  process.argv[2] ?? "https://live.novage.workers.dev/bbb/live.m3u8";

const source = readFileSync(
  new URL("../.core/SOURCE", import.meta.url),
  "utf8",
);
console.log(source.trimEnd());

const { Core, PEER_PROTOCOL_VERSION, hlsManifestParser } = await loadCore();
console.log(`core loaded, peer protocol: ${PEER_PROTOCOL_VERSION}`);

const core = new Core({ manifestParsers: [hlsManifestParser] });
try {
  const master = await fetchPlaylist(streamUrl);
  core.processManifest(master);

  // The highest bitrate: where web viewers on a good connection play.
  const [main] = core
    .getStreams()
    .filter((stream) => stream.type === "main")
    .sort((a, b) => (b.properties.bitrate ?? 0) - (a.properties.bitrate ?? 0));
  if (!main) throw new Error(`${streamUrl}: no main stream`);

  const media = await fetchPlaylist(main.runtimeId);
  core.processManifest(media);

  // Listed after the media playlist: one the core did not match to the
  // master's stream shows as a stream of its own.
  for (const stream of core.getStreams()) {
    const { bitrate, height } = stream.properties;
    console.log(
      `${stream.streamId}: ${bitrate} bit/s, ${height}p, infohash ${stream.infoHash}`,
    );
  }

  const newest = parseMediaPlaylist(media.data, media.url).segments.at(-1)?.url;
  if (!newest) throw new Error(`${media.url}: no segments`);
  const loadable = core.isSegmentLoadable(newest);
  console.log(`${main.streamId}, newest segment ${newest}`);
  console.log(`loadable: ${loadable}`);
  if (!loadable) process.exitCode = 1;
} finally {
  core.destroy();
}
