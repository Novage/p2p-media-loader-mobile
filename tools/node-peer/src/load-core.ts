import type * as CoreApi from "p2p-media-loader-core";
import type * as HlsApi from "p2p-media-loader-core/hls";
import "./browser-globals.ts";

// The core and its HLS parser in one file with no imports: what a mobile JS
// engine loads, with no package resolution. The package's exports do not name
// it, so it is loaded by its path.
export const BUNDLE = new URL(
  "../node_modules/p2p-media-loader-core/dist/p2p-media-loader-core-hls.es.js",
  import.meta.url,
);

/**
 * Loads the core. The browser globals are set before, because the core reads
 * WebRTC once, when it loads.
 */
export const loadCore = () =>
  import(BUNDLE.href) as Promise<typeof CoreApi & typeof HlsApi>;
