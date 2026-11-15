import { readFileSync } from "node:fs";
import { loadCore } from "./load-core.ts";

const source = readFileSync(
  new URL("../.core/SOURCE", import.meta.url),
  "utf8",
);
console.log(source.trimEnd());

const { PEER_PROTOCOL_VERSION } = await loadCore();
console.log(`core loaded, peer protocol: ${PEER_PROTOCOL_VERSION}`);
