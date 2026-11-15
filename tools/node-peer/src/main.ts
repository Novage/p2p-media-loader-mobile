import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { parseArgs } from "node:util";
import { cleanup } from "node-datachannel";
import { formatSummary, play } from "./headless-player.ts";
import { BUNDLE, loadCore } from "./load-core.ts";
import { recordGlobals } from "./record-globals.ts";

const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    minutes: { type: "string", default: "2" },
    // A swarm of its own, which no web peer joins: the core over HTTP alone.
    isolated: { type: "boolean", default: false },
    // A test swarm the web peers under test join with the demo's ?swarmId=.
    swarm: { type: "string" },
    // Lists the host globals the core reads: what an engine must give it.
    "record-globals": { type: "boolean", default: false },
  },
});
const streamUrl =
  positionals[0] ?? "https://live.novage.workers.dev/bbb/live.m3u8";
const minutes = Number(values.minutes);
if (!(minutes > 0)) throw new Error("--minutes must be a positive number");
if (values.isolated && values.swarm !== undefined) {
  throw new Error("--isolated and --swarm cannot be used together");
}
// An empty ID is no ID, as the demo reads an empty ?swarmId=.
const swarmId = values.isolated
  ? `node-peer-${randomUUID()}`
  : values.swarm || undefined;

const source = readFileSync(
  new URL("../.core/SOURCE", import.meta.url),
  "utf8",
);
console.log(source.trimEnd());

// After the browser globals, which load-core.ts sets when it is imported, so
// that the core's reads of them are recorded too.
// The file name: a call site shows the path behind pnpm's symlink.
const bundleFile = basename(BUNDLE.pathname);
const recorder = values["record-globals"]
  ? recordGlobals((script) => script.endsWith(bundleFile))
  : undefined;
const api = await loadCore();
recorder?.loaded();
console.log(`core loaded, peer protocol: ${api.PEER_PROTOCOL_VERSION}`);

const core = new api.Core({
  manifestParsers: [api.hlsManifestParser],
  swarmId,
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
  if (recorder) {
    const lines = recorder.report().map((read) => {
      const members = read.members.map((m) => `${m.name} (${m.phase})`);
      return `${read.name} (${read.phase})${members.length ? `: ${members.join(", ")}` : ""}`;
    });
    console.log(["globals the core read", ...lines].join("\n  "));
  }
  // Peer connections run on libdatachannel's threads, which keep Node
  // running until they are stopped.
  cleanup();
}
