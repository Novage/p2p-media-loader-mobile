// RTCPeerConnection, RTCDataChannel and RTCSessionDescription of WebRTC, with
// their events (specs/engine.md, "Globals"): what the core uses for its
// peers. libdatachannel does the network work (natives.rtcPeer,
// native/src/rtc.c), with its own negotiation off: these classes take the
// W3C steps. Data channels only.
(natives) => {
  const {
    usvString: usv,
    requireArguments,
    dictionary,
    fireEvent,
    unsignedLong,
  } = natives;
  // Kept now, so that a script that replaces these globals changes nothing
  // here.
  const {
    Event,
    EventTarget,
    MessageEvent,
    DOMException,
    ArrayBuffer,
    Uint8Array,
    encodeURIComponent,
  } = globalThis;
  const { isView } = ArrayBuffer;

  // libdatachannel's codes (rtc.h).
  const ERR_INVALID = -1;

  const domError = (name, message) => new DOMException(message, name);
  // Each class has no other way to be made by script.
  const INTERNAL = Symbol("internal");

  const DESCRIPTION_TYPES = ["offer", "answer", "pranswer", "rollback"];

  class RTCSessionDescription {
    #type;
    #sdp;

    constructor(init) {
      requireArguments(arguments.length, 1, "RTCSessionDescription");
      // WebIDL converts the members in turn, by name.
      const options = dictionary(init);
      const { sdp } = options;
      const sdpText = sdp === undefined ? "" : `${sdp}`;
      const { type } = options;
      if (type === undefined) {
        throw new TypeError("RTCSessionDescription: type is required");
      }
      const typeText = `${type}`;
      if (!DESCRIPTION_TYPES.includes(typeText)) {
        throw new TypeError(
          `RTCSessionDescription: "${typeText}" is not a description type`,
        );
      }
      this.#type = typeText;
      this.#sdp = sdpText;
    }

    get type() {
      return this.#type;
    }

    get sdp() {
      return this.#sdp;
    }

    toJSON() {
      return { type: this.#type, sdp: this.#sdp };
    }
  }

  class RTCPeerConnectionIceEvent extends Event {
    #candidate;

    constructor(type, init = undefined) {
      requireArguments(arguments.length, 1, "RTCPeerConnectionIceEvent");
      super(type, init);
      const { candidate } = dictionary(init);
      // Only the end of the candidates: there is no RTCIceCandidate.
      this.#candidate = candidate === undefined ? null : candidate;
    }

    get candidate() {
      return this.#candidate;
    }
  }

  class RTCDataChannelEvent extends Event {
    #channel;

    constructor(type, init) {
      requireArguments(arguments.length, 2, "RTCDataChannelEvent");
      super(type, init);
      const { channel } = dictionary(init);
      if (!(channel instanceof RTCDataChannel)) {
        throw new TypeError("RTCDataChannelEvent: channel is required");
      }
      this.#channel = channel;
    }

    get channel() {
      return this.#channel;
    }
  }

  // The open data channels, by libdatachannel's number, and their state.
  const channels = new Map();
  let channelState;

  class RTCDataChannel extends EventTarget {
    #state;

    static {
      channelState = (channel) => channel.#state;
    }

    constructor(token, number, label, peer) {
      if (token !== INTERNAL) {
        throw new TypeError("RTCDataChannel: illegal constructor");
      }
      super();
      this.#state = {
        number,
        label,
        peer,
        readyState: "connecting",
        binaryType: "arraybuffer",
        threshold: 0,
      };
      channels.set(number, this);
    }

    get label() {
      return this.#state.label;
    }

    get ordered() {
      return true;
    }

    get readyState() {
      return this.#state.readyState;
    }

    // libdatachannel's queue, read now: the bytes that usrsctp took are not
    // counted (KNOWN-ISSUES.md).
    get bufferedAmount() {
      const state = this.#state;
      if (state.readyState === "closed") return 0;
      const amount = natives.rtcBufferedAmount(state.number);
      return amount < 0 ? 0 : amount;
    }

    get bufferedAmountLowThreshold() {
      return this.#state.threshold;
    }

    set bufferedAmountLowThreshold(value) {
      const state = this.#state;
      state.threshold = unsignedLong(value);
      if (state.readyState !== "closed") {
        natives.rtcSetLowThreshold(state.number, state.threshold);
      }
    }

    // "blob" or "arraybuffer"; binary messages are always an ArrayBuffer:
    // there is no Blob.
    get binaryType() {
      return this.#state.binaryType;
    }

    set binaryType(value) {
      const type = `${value}`;
      if (type === "blob" || type === "arraybuffer") {
        this.#state.binaryType = type;
      }
    }

    send(data) {
      requireArguments(arguments.length, 1, "RTCDataChannel.send");
      const state = this.#state;
      // WebIDL converts the argument before the steps; a BufferSource needs
      // no conversion.
      const buffer = data instanceof ArrayBuffer;
      const binary = buffer || isView(data);
      const text = binary ? undefined : usv(data);
      if (state.readyState !== "open") {
        throw domError(
          "InvalidStateError",
          "RTCDataChannel.send: the channel is not open",
        );
      }
      let message = text;
      if (buffer) message = new Uint8Array(data);
      else if (binary) {
        message = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
      }
      const code = natives.rtcSend(state.number, message);
      if (code === ERR_INVALID) {
        throw new TypeError(
          "RTCDataChannel.send: the message is larger than the maximum size",
        );
      }
      if (code < 0) {
        throw domError(
          "OperationError",
          "RTCDataChannel.send: the message could not be sent",
        );
      }
    }

    close() {
      const state = this.#state;
      if (state.readyState === "closing" || state.readyState === "closed") {
        return;
      }
      state.readyState = "closing";
      // libdatachannel closes it at once; its close comes as an event.
      natives.rtcChannelClose(state.number);
    }
  }

  // The channel is closed: by the peer connection's close, with no events
  // (as the spec), or by its own close, with them.
  const endChannel = (channel, events) => {
    const state = channelState(channel);
    if (state.readyState === "closed") return;
    channels.delete(state.number);
    const remote = state.readyState !== "closing";
    if (events && remote) {
      state.readyState = "closing";
      fireEvent(channel, new Event("closing"));
    }
    state.readyState = "closed";
    if (events) fireEvent(channel, new Event("close"));
  };

  // Ends the channels of `peer`, with or without their events.
  const endChannels = (peer, events) => {
    for (const channel of [...channels.values()]) {
      if (channelState(channel).peer === peer) endChannel(channel, events);
    }
  };

  // A W3C ICE server list as libdatachannel's URLs: TURN with the
  // credentials, percent-encoded, in the URL.
  const serverUrls = (servers) => {
    const urls = [];
    if (servers === undefined) return urls;
    for (const server of servers) {
      const options = dictionary(server);
      const { credential, urls: list } = options;
      if (list === undefined) {
        throw new TypeError("RTCPeerConnection: an ICE server has no urls");
      }
      const { username } = options;
      const all = typeof list === "string" ? [list] : [...list];
      if (all.length === 0) {
        throw domError(
          "SyntaxError",
          "RTCPeerConnection: an ICE server has an empty urls list",
        );
      }
      for (const url of all) {
        const text = usv(url);
        const colon = text.indexOf(":");
        // A URL without a colon has no scheme, so the last branch throws.
        const scheme = colon < 0 ? "" : text.slice(0, colon).toLowerCase();
        const turn = scheme === "turn" || scheme === "turns";
        // The W3C check, also for a server that is skipped below.
        if (turn && (username === undefined || credential === undefined)) {
          throw domError(
            "InvalidAccessError",
            "RTCPeerConnection: a TURN server needs a username and a credential",
          );
        }
        // libdatachannel takes the scheme in lower case only. libjuice has no
        // STUN or TURN over TLS or TCP: those servers are skipped, as
        // libjuice skips them, and the others of the list are used.
        const tls = scheme === "stuns" || scheme === "turns";
        const tcp = scheme === "turn" && /[?&]transport=(?!udp)/i.test(text);
        if (tls || tcp) continue;
        if (scheme === "stun") {
          urls.push(`${scheme}:${text.slice(colon + 1)}`);
        } else if (scheme === "turn") {
          const user = encodeURIComponent(usv(username));
          const secret = encodeURIComponent(usv(credential));
          urls.push(`${scheme}:${user}:${secret}@${text.slice(colon + 1)}`);
        } else {
          throw domError(
            "SyntaxError",
            `RTCPeerConnection: "${text}" is not a STUN or TURN URL`,
          );
        }
      }
    }
    return urls;
  };

  // The open peer connections, by the item id of natives.rtcPeer.
  const peers = new Map();
  let peerState;

  class RTCPeerConnection extends EventTarget {
    #state;

    static {
      peerState = (peer) => peer.#state;
    }

    constructor(configuration = undefined) {
      super();
      const options = dictionary(configuration);
      const urls = serverUrls(options.iceServers);
      const { iceTransportPolicy } = options;
      const policy =
        iceTransportPolicy === undefined ? "all" : `${iceTransportPolicy}`;
      if (policy !== "all" && policy !== "relay") {
        throw new TypeError(
          `RTCPeerConnection: "${policy}" is not an ICE transport policy`,
        );
      }
      const id = natives.rtcPeer(urls, policy === "relay");
      if (id < 0) {
        throw domError(
          "SyntaxError",
          "RTCPeerConnection: an ICE server URL is not valid",
        );
      }
      this.#state = {
        id,
        signalingState: "stable",
        iceGatheringState: "new",
        iceConnectionState: "new",
        // localDescription once closed.
        local: null,
      };
      peers.set(id, this);
    }

    get signalingState() {
      return this.#state.signalingState;
    }

    get iceGatheringState() {
      return this.#state.iceGatheringState;
    }

    get iceConnectionState() {
      return this.#state.iceConnectionState;
    }

    // Read now, so that it has the candidates gathered so far.
    get localDescription() {
      const state = this.#state;
      if (state.signalingState === "closed") return state.local;
      const pair = natives.rtcLocalDescription(state.id);
      if (pair === null) return null;
      return new RTCSessionDescription({ type: pair[0], sdp: pair[1] });
    }

    #check(name) {
      if (this.#state.signalingState === "closed") {
        throw domError(
          "InvalidStateError",
          `RTCPeerConnection.${name}: the connection is closed`,
        );
      }
    }

    async createOffer() {
      return this.#describe("offer", "createOffer");
    }

    async createAnswer() {
      if (this.#state.signalingState !== "have-remote-offer") {
        this.#check("createAnswer");
        throw domError(
          "InvalidStateError",
          "RTCPeerConnection.createAnswer: there is no remote offer",
        );
      }
      return this.#describe("answer", "createAnswer");
    }

    #describe(type, name) {
      this.#check(name);
      const { id } = this.#state;
      const sdp =
        type === "offer"
          ? natives.rtcCreateOffer(id)
          : natives.rtcCreateAnswer(id);
      if (typeof sdp !== "string") {
        throw domError(
          "OperationError",
          `RTCPeerConnection.${name}: libdatachannel failed (${sdp})`,
        );
      }
      return { type, sdp };
    }

    #setSignaling(value) {
      const state = this.#state;
      if (state.signalingState === value) return;
      state.signalingState = value;
      fireEvent(this, new Event("signalingstatechange"));
    }

    // The description's SDP is not used: libdatachannel makes a new one of
    // the type, equal to createOffer's or createAnswer's (the ICE credentials
    // and the fingerprint belong to the transport).
    async setLocalDescription(description = undefined) {
      this.#check("setLocalDescription");
      const state = this.#state;
      const { type } = dictionary(description);
      const kind =
        type !== undefined
          ? `${type}`
          : state.signalingState === "have-remote-offer"
            ? "answer"
            : "offer";
      const allowed =
        kind === "offer"
          ? state.signalingState === "stable" ||
            state.signalingState === "have-local-offer"
          : kind === "answer" && state.signalingState === "have-remote-offer";
      if (!allowed) {
        throw domError(
          "InvalidStateError",
          `RTCPeerConnection.setLocalDescription: no ${kind} in ${state.signalingState}`,
        );
      }
      const code = natives.rtcSetLocal(state.id, kind);
      if (code < 0) {
        throw domError(
          "OperationError",
          `RTCPeerConnection.setLocalDescription: libdatachannel failed (${code})`,
        );
      }
      this.#setSignaling(kind === "offer" ? "have-local-offer" : "stable");
    }

    async setRemoteDescription(description) {
      requireArguments(
        arguments.length,
        1,
        "RTCPeerConnection.setRemoteDescription",
      );
      this.#check("setRemoteDescription");
      const state = this.#state;
      const { sdp, type } = dictionary(description);
      if (type === undefined) {
        throw new TypeError(
          "RTCPeerConnection.setRemoteDescription: type is required",
        );
      }
      const kind = `${type}`;
      const allowed =
        kind === "offer"
          ? state.signalingState === "stable" ||
            state.signalingState === "have-remote-offer"
          : kind === "answer" && state.signalingState === "have-local-offer";
      if (!allowed) {
        throw domError(
          "InvalidStateError",
          `RTCPeerConnection.setRemoteDescription: no ${kind} in ${state.signalingState}`,
        );
      }
      const code = natives.rtcSetRemote(
        state.id,
        kind,
        sdp === undefined ? "" : `${sdp}`,
      );
      if (code < 0) {
        throw domError(
          code === ERR_INVALID ? "InvalidAccessError" : "OperationError",
          `RTCPeerConnection.setRemoteDescription: libdatachannel refused it (${code})`,
        );
      }
      this.#setSignaling(kind === "offer" ? "have-remote-offer" : "stable");
    }

    // Ordered and reliable: the core sets no options.
    createDataChannel(label, options = undefined) {
      requireArguments(
        arguments.length,
        1,
        "RTCPeerConnection.createDataChannel",
      );
      const text = usv(label);
      // The defaults are accepted: ordered, reliable, not negotiated, no
      // protocol (an id counts only for a negotiated channel).
      const {
        maxPacketLifeTime,
        maxRetransmits,
        negotiated,
        ordered,
        protocol,
      } = dictionary(options);
      this.#check("createDataChannel");
      const unsupported =
        (maxPacketLifeTime !== undefined && "maxPacketLifeTime") ||
        (maxRetransmits !== undefined && "maxRetransmits") ||
        (negotiated !== undefined && negotiated && "negotiated") ||
        (ordered !== undefined && !ordered && "an unordered channel") ||
        (protocol !== undefined && `${protocol}` !== "" && "protocol");
      if (unsupported) {
        throw domError(
          "NotSupportedError",
          `RTCPeerConnection.createDataChannel: ${unsupported} is not supported`,
        );
      }
      const number = natives.rtcChannel(this.#state.id, text);
      if (number < 0) {
        throw domError(
          "OperationError",
          `RTCPeerConnection.createDataChannel: libdatachannel failed (${number})`,
        );
      }
      return new RTCDataChannel(INTERNAL, number, text, this);
    }

    // As the spec: no event; the data channels close with no event either.
    // libdatachannel deletes the connection at once. Again does nothing.
    close() {
      const state = this.#state;
      if (state.signalingState === "closed") return;
      state.local = this.localDescription;
      state.signalingState = "closed";
      state.iceConnectionState = "closed";
      endChannels(this, false);
      peers.delete(state.id);
      natives.rtcClose(state.id);
    }
  }

  for (const type of [
    "icecandidate",
    "icegatheringstatechange",
    "iceconnectionstatechange",
    "signalingstatechange",
    "datachannel",
  ]) {
    natives.defineEventHandler(RTCPeerConnection.prototype, type);
  }
  for (const type of [
    "open",
    "message",
    "bufferedamountlow",
    "error",
    "closing",
    "close",
  ]) {
    natives.defineEventHandler(RTCDataChannel.prototype, type);
  }

  // The events of libdatachannel: the state changes, then the event fires.
  // `first` and `second` are the event's details (rtcListen, rtc.c): a state,
  // or a channel's number and its label or message.
  natives.rtcListen((id, event, first, second) => {
    const peer = peers.get(id);
    if (!peer) return;
    const state = peerState(peer);
    if (event === "gathering") {
      state.iceGatheringState = first;
      fireEvent(peer, new Event("icegatheringstatechange"));
      // A listener can close the connection: then no more events.
      if (
        state.iceGatheringState === "complete" &&
        state.signalingState !== "closed"
      ) {
        fireEvent(
          peer,
          new RTCPeerConnectionIceEvent("icecandidate", { candidate: null }),
        );
      }
    } else if (event === "ice") {
      state.iceConnectionState = first;
      fireEvent(peer, new Event("iceconnectionstatechange"));
    } else if (event === "datachannel") {
      const channel = new RTCDataChannel(INTERNAL, first, second, peer);
      fireEvent(peer, new RTCDataChannelEvent("datachannel", { channel }));
    } else if (event === "fail") {
      // The connection could not take an event, and libdatachannel deleted
      // it: as a failed ICE connection, its channels closed.
      state.iceConnectionState = "failed";
      endChannels(peer, true);
      if (state.signalingState !== "closed") {
        fireEvent(peer, new Event("iceconnectionstatechange"));
      }
    } else {
      const channel = channels.get(first);
      if (!channel) return;
      const channelData = channelState(channel);
      if (event === "open") {
        if (channelData.readyState !== "connecting") return;
        channelData.readyState = "open";
        fireEvent(channel, new Event("open"));
      } else if (event === "message") {
        if (channelData.readyState !== "open") return;
        // The platform's Uint8Array covers its whole buffer.
        const data = typeof second === "string" ? second : second.buffer;
        fireEvent(channel, new MessageEvent("message", { data }));
      } else if (event === "low") {
        fireEvent(channel, new Event("bufferedamountlow"));
      } else if (event === "channelclose") {
        endChannel(channel, true);
      }
    }
  });

  natives.expose(
    RTCPeerConnection,
    RTCDataChannel,
    RTCSessionDescription,
    RTCPeerConnectionIceEvent,
    RTCDataChannelEvent,
  );
};
