// The WebRTC that browser-globals.ts gives the core, used the way the core
// uses it: full SDP after ICE gathering (no trickle), listeners added with
// addEventListener, binary messages, and sends paced by bufferedAmount and
// the bufferedamountlow event. See webtorrent-client and data-channel-sender
// in the core.
import "../src/browser-globals.ts";
import assert from "node:assert/strict";
import { after, test } from "node:test";
import { cleanup } from "node-datachannel";

after(cleanup);

// The core's values: its buffer limit, and its default message size.
const MAX_BUFFERED_AMOUNT = 64 * 1024;
const MESSAGE_SIZE = 64 * 1024 - 1;
// libdatachannel counts in bufferedAmount only what SCTP has not accepted, not
// every byte sent as a browser does, so on loopback it rises only after about
// a megabyte, and on a busy machine it may not rise at all. Enough data for
// the sender to wait in most runs; when it waits, a missing bufferedamountlow
// fails the test by its timeout.
const DATA_SIZE = 4 * 1024 * 1024;

const waitFor = (target: EventTarget, type: string) =>
  new Promise<Event>((resolve) =>
    target.addEventListener(type, resolve, { once: true }),
  );

/** The description to send, once ICE gathering has completed. */
const gatheredDescription = async (pc: RTCPeerConnection) => {
  while (pc.iceGatheringState !== "complete") {
    await waitFor(pc, "icegatheringstatechange");
  }
  const { type, sdp } = pc.localDescription!;
  return { type, sdp };
};

const opened = async (channel: RTCDataChannel) => {
  if (channel.readyState !== "open") await waitFor(channel, "open");
};

test(
  "a data channel connects and carries binary data as the core uses it",
  {
    timeout: 10_000,
  },
  async () => {
    const offerer = new RTCPeerConnection();
    const answerer = new RTCPeerConnection();
    try {
      const channel = offerer.createDataChannel("webtorrent");
      const remoteChannel = waitFor(answerer, "datachannel").then(
        (event) => (event as RTCDataChannelEvent).channel,
      );

      await offerer.setLocalDescription(await offerer.createOffer());
      await answerer.setRemoteDescription(await gatheredDescription(offerer));
      await answerer.setLocalDescription(await answerer.createAnswer());
      await offerer.setRemoteDescription(await gatheredDescription(answerer));

      const remote = await remoteChannel;
      channel.binaryType = "arraybuffer";
      remote.binaryType = "arraybuffer";
      await Promise.all([opened(channel), opened(remote)]);

      const data = new Uint8Array(DATA_SIZE).map((_, i) => i % 251);
      const received = new Uint8Array(DATA_SIZE);
      let receivedBytes = 0;
      const allReceived = new Promise<void>((resolve) => {
        remote.addEventListener("message", (event) => {
          assert.ok(event.data instanceof ArrayBuffer);
          received.set(new Uint8Array(event.data), receivedBytes);
          receivedBytes += event.data.byteLength;
          if (receivedBytes === DATA_SIZE) resolve();
        });
      });

      channel.bufferedAmountLowThreshold = MAX_BUFFERED_AMOUNT;
      for (let offset = 0; offset < DATA_SIZE; offset += MESSAGE_SIZE) {
        if (channel.bufferedAmount > MAX_BUFFERED_AMOUNT) {
          await waitFor(channel, "bufferedamountlow");
        }
        // A view into the segment's buffer, as the core sends it.
        channel.send(data.subarray(offset, offset + MESSAGE_SIZE));
      }
      await allReceived;

      assert.deepEqual(received, data);

      const closed = Promise.all([
        waitFor(channel, "close"),
        waitFor(remote, "close"),
      ]);
      channel.close();
      await closed;
    } finally {
      offerer.close();
      answerer.close();
    }
  },
);
