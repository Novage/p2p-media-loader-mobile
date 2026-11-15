import { readFileSync } from "node:fs";
// The server entry point holds the identity code only: no DOM and no WebRTC.
import { PEER_PROTOCOL_VERSION } from "p2p-media-loader-core/server";

const source = readFileSync(
  new URL("../.core/SOURCE", import.meta.url),
  "utf8",
);

console.log(source.trimEnd());
console.log(`peer protocol: ${PEER_PROTOCOL_VERSION}`);
