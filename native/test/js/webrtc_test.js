// RTCPeerConnection, RTCDataChannel and RTCSessionDescription
// (src/polyfills/webrtc.js) over the real libdatachannel: two peer
// connections in one engine connect over the loopback, as the core's peers
// do, with a whole SDP once ICE gathering is complete.

// The tests connect over the network stack, under ASan, on CI's few cores
// too.
setAsyncLimit(15000);

// The local description once gathering is complete, as the core sends it.
const gathered = async (peer) => {
  while (peer.iceGatheringState !== "complete") {
    await nextEvent(peer, "icegatheringstatechange");
  }
  const { type, sdp } = peer.localDescription;
  return { type, sdp };
};

const opened = (channel) =>
  channel.readyState === "open" ? undefined : nextEvent(channel, "open");

// Two connected peers and their data channel, as the core connects them.
const connect = async () => {
  const offerer = new RTCPeerConnection();
  const answerer = new RTCPeerConnection();
  const local = offerer.createDataChannel("webtorrent");
  const remote = nextEvent(answerer, "datachannel").then(
    (event) => event.channel,
  );
  await offerer.setLocalDescription(await offerer.createOffer());
  await answerer.setRemoteDescription(
    new RTCSessionDescription(await gathered(offerer)),
  );
  await answerer.setLocalDescription(await answerer.createAnswer());
  await offerer.setRemoteDescription(await gathered(answerer));
  const channel = await remote;
  await opened(local);
  await opened(channel);
  return { offerer, answerer, local, remote: channel };
};

const closeAll = (...peers) => {
  for (const peer of peers) peer.close();
};

test("the core's load probe: createOffer with no channel, then close", async () => {
  const peer = new RTCPeerConnection();
  const offer = peer.createOffer();
  assertEquals(offer instanceof Promise, true, "a promise");
  peer.close();
  peer.close();
  await offer.then(
    () => {},
    () => {},
  );
  assertEquals(peer.signalingState, "closed", "closed");
  assertEquals(
    (await rejection(peer.createOffer())).name,
    "InvalidStateError",
    "after close",
  );
});

test("RTCSessionDescription", () => {
  const description = new RTCSessionDescription({ type: "offer", sdp: "v=0" });
  assertEquals(
    `${description.type} ${description.sdp}`,
    "offer v=0",
    "members",
  );
  assertEquals(
    JSON.stringify(description),
    '{"type":"offer","sdp":"v=0"}',
    "toJSON",
  );
  assertThrows(TypeError, () => new RTCSessionDescription({}), "no type");
  assertThrows(
    TypeError,
    () => new RTCSessionDescription({ type: "other" }),
    "another type",
  );
});

test("the configuration's ICE servers and policy", () => {
  closeAll(
    new RTCPeerConnection({
      iceServers: [
        { urls: "stun:stun.example.com:3478" },
        { urls: "STUN:stun.example.com:3478" },
        { urls: "Stun:stun.example.com:3478" },
        {
          urls: ["turn:turn.example.com:3478?transport=udp"],
          username: "a@b",
          credential: "p:w",
        },
      ],
      iceTransportPolicy: "relay",
    }),
  );
  assertDomException(
    () =>
      new RTCPeerConnection({ iceServers: [{ urls: "http://example.com" }] }),
    "SyntaxError",
    "another scheme",
  );
  assertDomException(
    () => new RTCPeerConnection({ iceServers: [{ urls: "stunX" }] }),
    "SyntaxError",
    "no scheme",
  );
  assertDomException(
    () =>
      new RTCPeerConnection({
        iceServers: [{ urls: "turnX", username: "u", credential: "c" }],
      }),
    "SyntaxError",
    "no TURN scheme",
  );
  // A provider's usual list: libjuice skips TLS and TCP, and uses the rest.
  closeAll(
    new RTCPeerConnection({
      iceServers: [
        { urls: "stuns:example.com" },
        {
          urls: [
            "turn:turn.example.com?transport=udp",
            "turn:turn.example.com?transport=tcp",
            "turns:turn.example.com:443?transport=tcp",
          ],
          username: "u",
          credential: "c",
        },
      ],
    }),
  );
  assertDomException(
    () => new RTCPeerConnection({ iceServers: [{ urls: [] }] }),
    "SyntaxError",
    "an empty urls list",
  );
  assertDomException(
    () =>
      new RTCPeerConnection({
        iceServers: [{ urls: "turn:turn.example.com" }],
      }),
    "InvalidAccessError",
    "TURN with no credential",
  );
  assertDomException(
    () =>
      new RTCPeerConnection({
        iceServers: [{ urls: "turns:turn.example.com:443" }],
      }),
    "InvalidAccessError",
    "a skipped TURN server with no credential",
  );
  assertDomException(
    () =>
      new RTCPeerConnection({
        iceServers: [{ urls: "turn:turn.example.com?transport=tcp" }],
      }),
    "InvalidAccessError",
    "a skipped TCP TURN server with no credential",
  );
  assertThrows(
    TypeError,
    () => new RTCPeerConnection({ iceServers: [{}] }),
    "no urls",
  );
  assertThrows(
    TypeError,
    () => new RTCPeerConnection({ iceTransportPolicy: "none" }),
    "a policy",
  );
});

test("createDataChannel takes the default options only", () => {
  const peer = new RTCPeerConnection();
  peer.createDataChannel("a", {
    ordered: true,
    negotiated: false,
    protocol: "",
    id: 3,
  });
  const closed = new RTCPeerConnection();
  closed.close();
  assertDomException(
    () => closed.createDataChannel("c", { ordered: false }),
    "InvalidStateError",
    "closed before the options",
  );
  for (const options of [
    { negotiated: true, id: 0 },
    { ordered: false },
    { maxRetransmits: 0 },
    { maxPacketLifeTime: null },
    { protocol: "chat" },
  ]) {
    assertDomException(
      () => peer.createDataChannel("b", options),
      "NotSupportedError",
      JSON.stringify(options),
    );
  }
  peer.close();
});

test("a listener that closes the connection stops its events", async () => {
  const peer = new RTCPeerConnection();
  peer.createDataChannel("webtorrent");
  const seen = [];
  peer.addEventListener("icegatheringstatechange", () => {
    if (peer.iceGatheringState === "complete") peer.close();
  });
  peer.addEventListener("icecandidate", () => seen.push("icecandidate"));
  await peer.setLocalDescription(await peer.createOffer());
  while (peer.signalingState !== "closed") {
    await nextEvent(peer, "icegatheringstatechange");
  }
  await tasksRun();
  assertEquals(seen.join(), "", "no icecandidate after the close");
});

test("setRemoteDescription needs a type", async () => {
  const peer = new RTCPeerConnection();
  assertEquals(
    (await rejection(peer.setRemoteDescription({ sdp: "" }))).name,
    "TypeError",
    "no type",
  );
  peer.close();
});

test("the negotiation's states and events", async () => {
  const offerer = new RTCPeerConnection();
  const seen = [];
  offerer.addEventListener("signalingstatechange", () =>
    seen.push(offerer.signalingState),
  );
  offerer.addEventListener("icecandidate", (event) =>
    seen.push(`candidate ${event.candidate}`),
  );
  assertEquals(
    (await rejection(offerer.createAnswer())).name,
    "InvalidStateError",
    "no offer",
  );
  offerer.createDataChannel("webtorrent");
  const offer = await offerer.createOffer();
  assertEquals(offer.type, "offer", "the offer's type");
  assertEquals(offer.sdp.includes("webrtc-datachannel"), true, "its SDP");
  await offerer.setLocalDescription(offer);
  await gathered(offerer);
  assertEquals(
    offerer.localDescription.sdp.includes("a=candidate:"),
    true,
    "the candidates",
  );
  assertEquals(
    (await rejection(offerer.setLocalDescription({ type: "answer" }))).name,
    "InvalidStateError",
    "no answer",
  );
  assertEquals(seen.join(), "have-local-offer,candidate null", "events");
  offerer.close();
  assertEquals(offerer.iceConnectionState, "closed", "closed");
});

test("1 MiB in messages of 64 KiB - 1, paced by bufferedamountlow", async () => {
  const { offerer, answerer, local, remote } = await connect();
  assertEquals(
    ["connected", "completed"].includes(offerer.iceConnectionState),
    true,
    "connected",
  );
  const MAX_BUFFERED = 64 * 1024;
  const MESSAGE = 64 * 1024 - 1;
  // 1 MiB, not node-peer's 4: under ASan, JS loops over the bytes are slow;
  // JsWebRtcTest moves 4 MiB. copyWithin fills it in native code.
  const data = new Uint8Array(1024 * 1024);
  for (let i = 0; i < 251; i++) data[i] = i;
  for (let filled = 251; filled < data.length; filled *= 2) {
    data.copyWithin(filled, 0, filled);
  }
  const received = new Uint8Array(data.length);
  let offset = 0;
  const done = new Promise((resolve, reject) => {
    remote.addEventListener("message", (event) => {
      if (!(event.data instanceof ArrayBuffer)) reject(new Error("not binary"));
      received.set(new Uint8Array(event.data), offset);
      offset += event.data.byteLength;
      if (offset === data.length) resolve();
    });
  });
  local.bufferedAmountLowThreshold = MAX_BUFFERED;
  for (let sent = 0; sent < data.length; sent += MESSAGE) {
    if (local.bufferedAmount > MAX_BUFFERED) {
      await nextEvent(local, "bufferedamountlow");
    }
    local.send(data.subarray(sent, sent + MESSAGE));
  }
  await done;
  assertEquals(
    received.every((byte, i) => byte === data[i]),
    true,
    "the bytes",
  );
  const closes = Promise.all([
    nextEvent(local, "close"),
    nextEvent(remote, "close"),
  ]);
  local.close();
  assertEquals(local.readyState, "closing", "closing");
  await closes;
  assertEquals(
    `${local.readyState} ${remote.readyState}`,
    "closed closed",
    "closed",
  );
  closeAll(offerer, answerer);
});

test("send: text, a view, the size limit, and not before open or after close", async () => {
  const { offerer, answerer, local, remote } = await connect();
  const seen = [];
  const both = new Promise((resolve) =>
    remote.addEventListener("message", (event) => {
      seen.push(
        typeof event.data === "string"
          ? event.data
          : hex(new Uint8Array(event.data)),
      );
      if (seen.length === 2) resolve();
    }),
  );
  local.send("h\u00e9");
  local.send(new Uint8Array([9, 8, 7, 6]).subarray(1, 3));
  await both;
  assertEquals(seen.join(" | "), "h\u00e9 | 08 07", "messages");
  assertThrows(
    TypeError,
    () => local.send(new Uint8Array(1024 * 1024)),
    "over the maximum",
  );
  const pending = offerer.createDataChannel("later");
  assertDomException(
    () => pending.send("x"),
    "InvalidStateError",
    "before open",
  );
  closeAll(offerer, answerer);
  assertEquals(local.readyState, "closed", "the close closes the channels");
  assertDomException(() => local.send("x"), "InvalidStateError", "after close");
  assertThrows(
    TypeError,
    () => new RTCDataChannel(),
    "no constructor for script",
  );
});

test("a closed peer's channels close on the other side", async () => {
  const { offerer, answerer, remote } = await connect();
  const seen = [];
  remote.addEventListener("closing", () => seen.push("closing"));
  const closed = nextEvent(remote, "close").then(() => seen.push("close"));
  offerer.close();
  await closed;
  assertEquals(seen.join(), "closing,close", "events");
  answerer.close();
});
