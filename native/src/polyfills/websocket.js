// WebSocket of the WebSockets Standard, with MessageEvent and CloseEvent of
// the HTML Standard (specs/engine.md, "Globals"): what the core uses for its
// trackers. The platform's WebSocket client does the network work
// (natives.wsOpen, native/src/websocket.c).
(natives) => {
  const {
    usvString: usv,
    requireArguments,
    dictionary,
    fireEvent,
    isHttpToken,
    unsignedShort,
    clampedUnsignedShort,
    utf8Encode,
  } = natives;
  // Kept now, so that a script that replaces these globals changes nothing
  // here.
  const {
    URL,
    Event,
    EventTarget,
    DOMException,
    setTimeout,
    ArrayBuffer,
    Uint8Array,
  } = globalThis;
  const { isView } = ArrayBuffer;

  const syntaxError = (message) => new DOMException(message, "SyntaxError");

  // MessageEvent: only the members that a WebSocket gives.
  class MessageEvent extends Event {
    #data;
    #origin;
    #lastEventId;

    constructor(type, init = undefined) {
      requireArguments(arguments.length, 1, "MessageEvent");
      super(type, init);
      // WebIDL gets and converts each member in turn, by name.
      const options = dictionary(init);
      this.#data = options.data === undefined ? null : options.data;
      const { lastEventId } = options;
      this.#lastEventId = lastEventId === undefined ? "" : `${lastEventId}`;
      const { origin } = options;
      this.#origin = origin === undefined ? "" : usv(origin);
    }

    get data() {
      return this.#data;
    }

    get origin() {
      return this.#origin;
    }

    get lastEventId() {
      return this.#lastEventId;
    }
  }

  class CloseEvent extends Event {
    #code;
    #reason;
    #wasClean;

    constructor(type, init = undefined) {
      requireArguments(arguments.length, 1, "CloseEvent");
      super(type, init);
      // WebIDL gets and converts each member in turn, by name.
      const options = dictionary(init);
      this.#code = unsignedShort(options.code);
      const { reason } = options;
      this.#reason = reason === undefined ? "" : usv(reason);
      this.#wasClean = Boolean(options.wasClean);
    }

    get code() {
      return this.#code;
    }

    get reason() {
      return this.#reason;
    }

    get wasClean() {
      return this.#wasClean;
    }
  }

  const CONNECTING = 0;
  const OPEN = 1;
  const CLOSING = 2;
  const CLOSED = 3;
  const STATES = { CONNECTING, OPEN, CLOSING, CLOSED };

  // The sockets that are open on the platform, by id.
  const sockets = new Map();

  // The socket's state, for the events that the platform gives.
  let stateOf;

  class WebSocket extends EventTarget {
    #state = {
      url: "",
      readyState: CONNECTING,
      protocol: "",
      extensions: "",
      binaryType: "blob",
      bufferedAmount: 0,
      id: 0,
      origin: "",
    };

    static {
      stateOf = (socket) => socket.#state;
    }

    constructor(url, protocols = []) {
      requireArguments(arguments.length, 1, "WebSocket");
      super();
      // Converted once, outside the try: its exception goes to the caller.
      const text = usv(url);
      let parsed;
      try {
        parsed = new URL(text);
      } catch {
        throw syntaxError(`WebSocket: "${text}" is not a valid URL`);
      }
      if (parsed.protocol === "http:") parsed.protocol = "ws:";
      else if (parsed.protocol === "https:") parsed.protocol = "wss:";
      if (parsed.protocol !== "ws:" && parsed.protocol !== "wss:") {
        throw syntaxError(
          `WebSocket: the scheme of "${text}" is not ws or wss`,
        );
      }
      if (parsed.hash !== "" || parsed.href.endsWith("#")) {
        throw syntaxError("WebSocket: the URL has a fragment");
      }
      const list =
        typeof protocols === "string" ||
        protocols === null ||
        typeof protocols[Symbol.iterator] !== "function"
          ? [usv(protocols)]
          : [...protocols].map(usv);
      for (const protocol of list) {
        if (!isHttpToken(protocol)) {
          throw syntaxError(`WebSocket: "${protocol}" is not a subprotocol`);
        }
      }
      if (new Set(list.map((p) => p.toLowerCase())).size !== list.length) {
        throw syntaxError("WebSocket: a subprotocol is there twice");
      }
      const state = this.#state;
      state.url = parsed.href;
      state.origin = parsed.origin;
      state.id = natives.wsOpen(parsed.href, list);
      sockets.set(state.id, this);
    }

    get url() {
      return this.#state.url;
    }

    get readyState() {
      return this.#state.readyState;
    }

    get protocol() {
      return this.#state.protocol;
    }

    get extensions() {
      return this.#state.extensions;
    }

    get bufferedAmount() {
      return this.#state.bufferedAmount;
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
      requireArguments(arguments.length, 1, "WebSocket.send");
      const state = this.#state;
      // WebIDL converts the argument before the steps; a BufferSource needs
      // no conversion.
      const buffer = data instanceof ArrayBuffer;
      const binary = buffer || isView(data);
      const text = binary ? undefined : usv(data);
      if (state.readyState === CONNECTING) {
        throw new DOMException(
          "WebSocket.send: the socket is still connecting",
          "InvalidStateError",
        );
      }
      let message = text;
      if (buffer) message = new Uint8Array(data);
      else if (binary) {
        message = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
      }
      // As a browser: after the close starts, the data counts as buffered and
      // is dropped.
      if (state.readyState !== OPEN) {
        state.bufferedAmount +=
          typeof message === "string"
            ? utf8Encode(message).byteLength
            : message.byteLength;
        return;
      }
      natives.wsSend(state.id, message);
    }

    close(code = undefined, reason = undefined) {
      const state = this.#state;
      // WebIDL converts both arguments before the steps.
      const number = code === undefined ? -1 : clampedUnsignedShort(code);
      const text = reason === undefined ? "" : usv(reason);
      if (
        number !== -1 &&
        number !== 1000 &&
        (number < 3000 || number > 4999)
      ) {
        throw new DOMException(
          "WebSocket.close: the code must be 1000 or 3000-4999",
          "InvalidAccessError",
        );
      }
      if (utf8Encode(text).byteLength > 123) {
        throw syntaxError(
          "WebSocket.close: the reason is longer than 123 bytes",
        );
      }
      if (state.readyState === CLOSING || state.readyState === CLOSED) return;
      const connecting = state.readyState === CONNECTING;
      state.readyState = CLOSING;
      if (connecting) {
        // Fail the connection, as a browser: no more events from the
        // platform; error, then close, in a later task.
        natives.wsCancel(state.id);
        sockets.delete(state.id);
        setTimeout(() => finish(this, 1006, "", false, true), 0);
      } else {
        natives.wsClose(state.id, number, text);
      }
    }
  }

  natives.defineConstants(WebSocket, STATES);
  for (const type of ["open", "message", "error", "close"]) {
    natives.defineEventHandler(WebSocket.prototype, type);
  }

  // The socket's end: closed, then error (if it failed) and close.
  const finish = (socket, code, reason, wasClean, failed) => {
    const state = stateOf(socket);
    state.readyState = CLOSED;
    if (failed) fireEvent(socket, new Event("error"));
    fireEvent(socket, new CloseEvent("close", { code, reason, wasClean }));
  };

  natives.wsListen((id, event, ...details) => {
    const socket = sockets.get(id);
    if (!socket) return;
    const state = stateOf(socket);
    if (event === "open") {
      [state.protocol, state.extensions] = details;
      // A close() before the open removed the socket: it is connecting.
      state.readyState = OPEN;
      fireEvent(socket, new Event("open"));
    } else if (event === "message") {
      if (state.readyState !== OPEN) return;
      const [payload] = details;
      // Text is a string; the platform's Uint8Array covers its whole buffer.
      const data = typeof payload === "string" ? payload : payload.buffer;
      fireEvent(
        socket,
        new MessageEvent("message", { data, origin: state.origin }),
      );
    } else if (event === "close") {
      sockets.delete(id);
      finish(socket, ...details);
    }
  });

  natives.expose(WebSocket, MessageEvent, CloseEvent);
};
