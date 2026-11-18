// WebSocket, MessageEvent and CloseEvent (src/polyfills/websocket.js), with
// the fake WebSocket client of engine_test.c: fakeWs.opened, sent, closed and
// cancelled record the calls; fakeWs.open, message and close send its events
// as tasks, as a platform's thread does.

const WS_URL = "wss://example.com/tracker";

const lastOpened = () => fakeWs.opened[fakeWs.opened.length - 1];

// A socket that the fake opened, with its id.
const openSocket = async (protocol = "") => {
  const socket = new WebSocket(WS_URL);
  const { id } = lastOpened();
  const opened = nextEvent(socket, "open");
  fakeWs.open(id, protocol, "");
  await opened;
  return { socket, id };
};

test("the constructor checks the URL and the subprotocols", () => {
  const socket = new WebSocket("https://example.com/t");
  assertEquals(socket.url, "wss://example.com/t", "https becomes wss");
  assertEquals(lastOpened().url, "wss://example.com/t", "opened");
  assertEquals(lastOpened().protocols.length, 0, "no subprotocol");
  assertEquals(new WebSocket("http://a.example/").url, "ws://a.example/", "ws");
  assertEquals(socket.readyState, WebSocket.CONNECTING, "connecting");
  assertEquals(socket.CLOSED, 3, "a constant on the prototype");
  assertEquals(socket.binaryType, "blob", "binaryType");
  socket.binaryType = "arraybuffer";
  socket.binaryType = "other";
  assertEquals(socket.binaryType, "arraybuffer", "binaryType set");
  new WebSocket(WS_URL, "chat");
  assertEquals(lastOpened().protocols.join(), "chat", "one subprotocol");
  new WebSocket(WS_URL, ["a", "b"]);
  assertEquals(lastOpened().protocols.join(), "a,b", "two subprotocols");
  for (const [url, protocols, what] of [
    ["ftp://example.com/", [], "another scheme"],
    ["wss://example.com/#a", [], "a fragment"],
    ["wss://example.com/#", [], "an empty fragment"],
    ["not a URL", [], "not a URL"],
    [WS_URL, ["a", "A"], "a subprotocol twice"],
    [WS_URL, ["a b"], "not a token"],
  ]) {
    assertDomException(
      () => new WebSocket(url, protocols),
      "SyntaxError",
      what,
    );
  }
});

test("an exception of the URL's conversion goes to the caller", () => {
  const thrown = new Error("no string");
  let calls = 0;
  // It throws only once: a second conversion would hide the exception.
  const url = {
    toString() {
      calls += 1;
      if (calls === 1) throw thrown;
      return "wss://example.com/";
    },
  };
  const caught = assertThrows(Error, () => new WebSocket(url), "the URL");
  assertEquals(caught, thrown, "its own exception");
  assertEquals(calls, 1, "one conversion");
});

test("the arguments of close() and send() are converted first", async () => {
  const thrown = new Error("no string");
  const throwing = {
    toString() {
      throw thrown;
    },
  };
  const connecting = new WebSocket(WS_URL);
  const caught = assertThrows(
    Error,
    () => connecting.send(throwing),
    "send while connecting",
  );
  assertEquals(caught, thrown, "send: its own exception");
  // A BufferSource needs no conversion: a detached one is not read before the
  // state check.
  const detached = new ArrayBuffer(1);
  detached.transfer();
  assertEquals(
    assertThrows(DOMException, () => connecting.send(detached), "detached")
      .name,
    "InvalidStateError",
    "a detached buffer while connecting",
  );
  connecting.close();
  const { socket } = await openSocket();
  const closeCaught = assertThrows(
    Error,
    () => socket.close(1, throwing),
    "a wrong code",
  );
  assertEquals(closeCaught, thrown, "close: its own exception");
  // The code is converted before the reason.
  const first = new Error("code");
  const codeCaught = assertThrows(
    Error,
    () =>
      socket.close(
        {
          valueOf() {
            throw first;
          },
        },
        throwing,
      ),
    "both throw",
  );
  assertEquals(codeCaught, first, "close: the code first");
  socket.close();
});

test("open, text and binary messages, then a clean close", async () => {
  const socket = new WebSocket(WS_URL);
  socket.binaryType = "arraybuffer";
  const { id } = lastOpened();
  const seen = [];
  socket.onopen = () =>
    seen.push(`open ${socket.readyState} ${socket.protocol}`);
  socket.onmessage = (event) =>
    seen.push(
      typeof event.data === "string"
        ? `text ${event.data} ${event.origin}`
        : `binary ${hex(new Uint8Array(event.data))} ${event.data instanceof ArrayBuffer}`,
    );
  const closed = nextEvent(socket, "close");
  fakeWs.open(id, "chat", "");
  fakeWs.message(id, "h\u00e9llo");
  fakeWs.message(id, new Uint8Array([1, 2]));
  fakeWs.close(id, 1000, "bye", true, false);
  const event = await closed;
  assertEquals(
    seen.join(" | "),
    "open 1 chat | text h\u00e9llo wss://example.com | binary 01 02 true",
    "events",
  );
  assertEquals(event instanceof CloseEvent, true, "a CloseEvent");
  assertEquals(
    `${event.code} ${event.reason} ${event.wasClean} ${socket.readyState}`,
    "1000 bye true 3",
    "the close",
  );
});

test("send: text as UTF-8, binary as bytes; not while connecting", async () => {
  const connecting = new WebSocket(WS_URL);
  assertDomException(
    () => connecting.send("x"),
    "InvalidStateError",
    "while connecting",
  );
  const { socket, id } = await openSocket();
  socket.send("h\u00e9");
  socket.send(new Uint8Array([1, 2, 3]).buffer);
  socket.send(new Uint8Array([9, 8, 7, 6]).subarray(1, 3));
  const sent = fakeWs.sent.filter((record) => record.id === id);
  assertEquals(sent[0].data, "h\u00e9", "text");
  assertEquals(hex(sent[1].data), "01 02 03", "an ArrayBuffer");
  assertEquals(hex(sent[2].data), "08 07", "a view");
});

test("close: the checks of its code and reason, then the handshake", async () => {
  const { socket, id } = await openSocket();
  for (const code of [999, 1001, 2999, 5000, 66536]) {
    assertDomException(
      () => socket.close(code),
      "InvalidAccessError",
      `code ${code}`,
    );
  }
  assertDomException(
    () => socket.close(1000, "a".repeat(124)),
    "SyntaxError",
    "a reason of 124 bytes",
  );
  const closed = nextEvent(socket, "close");
  socket.close(1000, "done");
  socket.close(3000);
  assertEquals(socket.readyState, WebSocket.CLOSING, "closing");
  const calls = fakeWs.closed.filter((record) => record.id === id);
  assertEquals(calls.length, 1, "one close");
  assertEquals(`${calls[0].code} ${calls[0].reason}`, "1000 done", "its code");
  socket.send("abc");
  assertEquals(socket.bufferedAmount, 3, "data after the close counts");
  fakeWs.close(id, 1000, "done", true, false);
  assertEquals((await closed).wasClean, true, "clean");
  const other = await openSocket();
  other.socket.close();
  const call = fakeWs.closed.find((record) => record.id === other.id);
  assertEquals(call.code, -1, "no code");
});

test("close() while connecting fails the connection", async () => {
  const socket = new WebSocket(WS_URL);
  const { id } = lastOpened();
  const seen = [];
  socket.onerror = () => seen.push("error");
  socket.onclose = (event) =>
    seen.push(`close ${event.code} ${event.wasClean}`);
  const closed = nextEvent(socket, "close");
  socket.close();
  assertEquals(socket.readyState, WebSocket.CLOSING, "closing");
  assertEquals(fakeWs.cancelled.includes(id), true, "cancelled");
  await closed;
  // A late open from the platform is dropped.
  fakeWs.open(id, "", "");
  await tasksRun();
  assertEquals(seen.join(" | "), "error | close 1006 false", "events");
  assertEquals(socket.readyState, WebSocket.CLOSED, "closed");
});

test("a failed connection gives error, then close 1006", async () => {
  const socket = new WebSocket(WS_URL);
  const { id } = lastOpened();
  const seen = [];
  socket.addEventListener("error", (event) => seen.push(event.type));
  const closed = nextEvent(socket, "close");
  fakeWs.close(id, 1006, "", false, true);
  const event = await closed;
  assertEquals(seen.join(), "error", "error first");
  assertEquals(`${event.code} ${event.wasClean}`, "1006 false", "the close");
});

test("MessageEvent and CloseEvent", () => {
  const empty = new MessageEvent("message");
  assertEquals(
    `${empty.data} ${empty.origin}|${empty.lastEventId}`,
    "null |",
    "defaults",
  );
  const message = new MessageEvent("message", {
    data: 1,
    origin: "o",
    lastEventId: "x",
  });
  assertEquals(
    `${message.data} ${message.origin} ${message.lastEventId}`,
    "1 o x",
    "members",
  );
  const close = new CloseEvent("close", {
    code: 70000,
    reason: "r",
    wasClean: 1,
  });
  assertEquals(
    `${close.code} ${close.reason} ${close.wasClean}`,
    "4464 r true",
    "an unsigned short",
  );
  assertEquals(close instanceof Event, true, "an Event");
  const none = new CloseEvent("close");
  assertEquals(
    `${none.code} ${none.reason}|${none.wasClean}`,
    "0 |false",
    "defaults",
  );
});
