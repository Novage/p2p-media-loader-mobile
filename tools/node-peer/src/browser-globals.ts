// The browser APIs that Node lacks and the core needs. Load this module before
// the core: the core reads WebRTC once, when its modules load.
import {
  RTCError,
  RTCIceCandidate,
  RTCPeerConnection,
  RTCSessionDescription,
} from "node-datachannel/polyfill";

Object.assign(globalThis, {
  // The core reads its browser APIs from `window` (WebRTC, `fetch`, timers),
  // which is the global object in a browser.
  window: globalThis,
  RTCPeerConnection,
  RTCSessionDescription,
  RTCIceCandidate,
  // The polyfill's data channel creates an RTCError on a channel error
  // without importing it; without the global, the error stops the process.
  RTCError,
});
