import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { cleanup } from "node-datachannel";
import { formatSummary, play } from "./headless-player.ts";
import { loadCore } from "./load-core.ts";

const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    minutes: { type: "string", default: "2" },
    // A swarm of its own, which no web peer joins: the core over HTTP alone.
    isolated: { type: "boolean", default: false },
  },
});
const streamUrl =
  positionals[0] ?? "https://live.novage.workers.dev/bbb/live.m3u8";
const minutes = Number(values.minutes);
if (!(minutes > 0)) throw new Error("--minutes must be a positive number");

const source = readFileSync(
  new URL("../.core/SOURCE", import.meta.url),
  "utf8",
);
console.log(source.trimEnd());

const api = await loadCore();
console.log(`core loaded, peer protocol: ${api.PEER_PROTOCOL_VERSION}`);

const core = new api.Core({
  manifestParsers: [api.hlsManifestParser],
  swarmId: values.isolated ? `node-peer-${randomUUID()}` : undefined,
});
try {
  const summary = await play({
    core,
    liveDelayFor: api.liveDelayFor,
    playerBufferFor: api.playerBufferFor,
    streamUrl,
    seconds: minutes * 60,
    log: console.log,
  });
  console.log(["summary", ...formatSummary(summary)].join("\n  "));
  if (summary.error !== undefined) throw summary.error;
} catch (error) {
  console.error(error);
  process.exitCode = 1;
} finally {
  core.destroy();
  // Peer connections run on libdatachannel's threads, which keep Node
  // running until they are stopped.
  cleanup();
}
