package com.novage.p2pml.engine

// The script of the WebRTC tests: two peer connections in one engine connect as the core
// connects its peers (a whole SDP once ICE gathering is complete), then move BYTES in messages
// of 64 KiB - 1, paced by bufferedamountlow, and log "webrtc: <bytes> bytes at <MiB/s> MiB/s",
// or an error.
internal object WebRtcScripts {
    const val BYTES = 4 * 1024 * 1024

    val TRANSFER =
        """
        (async () => {
          const nextEvent = (target, type) =>
            new Promise((resolve) => target.addEventListener(type, resolve, { once: true }));
          const gathered = async (peer) => {
            while (peer.iceGatheringState !== "complete") {
              await nextEvent(peer, "icegatheringstatechange");
            }
            const { type, sdp } = peer.localDescription;
            return { type, sdp };
          };
          const offerer = new RTCPeerConnection();
          const answerer = new RTCPeerConnection();
          const local = offerer.createDataChannel("webtorrent");
          const incoming = nextEvent(answerer, "datachannel");
          await offerer.setLocalDescription(await offerer.createOffer());
          await answerer.setRemoteDescription(await gathered(offerer));
          await answerer.setLocalDescription(await answerer.createAnswer());
          await offerer.setRemoteDescription(await gathered(answerer));
          const remote = (await incoming).channel;
          if (local.readyState !== "open") await nextEvent(local, "open");
          if (remote.readyState !== "open") await nextEvent(remote, "open");
          const data = new Uint8Array($BYTES);
          for (let i = 0; i < data.length; i++) data[i] = i % 251;
          let received = 0;
          // The script compares the chunks after the timed part, because the JS loop is slow.
          const chunks = [];
          const done = new Promise((resolve) => {
            remote.addEventListener("message", ({ data: chunk }) => {
              chunks.push(new Uint8Array(chunk));
              received += chunk.byteLength;
              if (received >= data.length) resolve();
            });
          });
          const start = performance.now();
          local.bufferedAmountLowThreshold = 65536;
          for (let sent = 0; sent < data.length; sent += 65535) {
            if (local.bufferedAmount > 65536) await nextEvent(local, "bufferedamountlow");
            local.send(data.subarray(sent, sent + 65535));
          }
          await done;
          const seconds = (performance.now() - start) / 1000;
          offerer.close();
          answerer.close();
          let offset = 0;
          for (const bytes of chunks) {
            for (let i = 0; i < bytes.length; i++) {
              if (bytes[i] !== data[offset + i]) throw new Error("the bytes differ");
            }
            offset += bytes.length;
          }
          console.log("webrtc: " + received + " bytes at " +
            (received / 1048576 / seconds).toFixed(1) + " MiB/s");
        })().catch((error) => console.error("failed: " + error));
        """
            .trimIndent()
}
