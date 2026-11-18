// fetch, Headers, Request and Response (src/polyfills/fetch.js), with the
// fake HTTP client of engine_test.c: fakeHttp.started and fakeHttp.cancelled
// record the requests; fakeHttp.respond, data, end and fail send their events
// as tasks, as a platform's thread does.

const URL_A = "https://example.com/a";

const lastStarted = () => fakeHttp.started[fakeHttp.started.length - 1];

// Reads a body to its end: its chunks as hex strings, such as "01 02 03".
const readAll = async (body) => {
  const reader = body.getReader();
  const chunks = [];
  for (;;) {
    const { value, done } = await reader.read();
    if (done) return chunks;
    chunks.push(hex(value));
  }
};

test("Headers: names are case-insensitive, values are joined", () => {
  const headers = new Headers({ "Content-Type": "text/plain" });
  headers.append("X-A", " one ");
  headers.append("x-a", "two");
  assertEquals(headers.get("content-type"), "text/plain", "get");
  assertEquals(headers.get("X-A"), "one, two", "joined");
  assertEquals(headers.has("x-A"), true, "has");
  headers.set("X-A", "three");
  assertEquals(headers.get("x-a"), "three", "set");
  headers.delete("X-a");
  assertEquals(headers.get("x-a"), null, "deleted");
  headers.append("B", "1");
  assertEquals(
    [...headers].map(([name, value]) => `${name}=${value}`).join(" "),
    "b=1 content-type=text/plain",
    "sorted entries",
  );
  const copy = new Headers(headers);
  assertEquals(copy.get("b"), "1", "from a Headers");
  assertEquals(new Headers([["C", "2"]]).get("c"), "2", "from pairs");
  assertThrows(TypeError, () => new Headers([["C"]]), "a short pair");
  assertThrows(
    TypeError,
    () => new Headers({ [Symbol("s")]: "x" }),
    "a Symbol key",
  );
  assertEquals(
    Headers.prototype[Symbol.iterator],
    Headers.prototype.entries,
    "the iterator is entries",
  );
  assertThrows(TypeError, () => headers.set("bad name", "x"), "a bad name");
  assertThrows(TypeError, () => headers.set("ok", "a\nb"), "a bad value");
  assertThrows(TypeError, () => headers.set("ok", "\u0100"), "above U+00FF");
});

test("Request: an absolute URL, a normalized method, no body", () => {
  const request = new Request(URL_A, {
    method: "get",
    headers: { Range: "bytes=0-" },
  });
  assertEquals(request.url, URL_A, "url");
  assertEquals(request.method, "GET", "method");
  assertEquals(request.headers.get("range"), "bytes=0-", "headers");
  assertEquals(request.signal instanceof AbortSignal, true, "a signal");
  assertEquals(
    new Request(URL_A, { method: "patch" }).method,
    "patch",
    "patch",
  );
  const copy = new Request(request);
  assertEquals(copy.url + copy.method, URL_A + "GET", "from a Request");
  assertEquals(copy.headers.get("range"), "bytes=0-", "its headers");
  assertThrows(TypeError, () => new Request("/relative"), "a relative URL");
  assertThrows(
    TypeError,
    () => new Request(URL_A, { method: "CONNECT" }),
    "CONNECT",
  );
  assertThrows(TypeError, () => new Request(URL_A, { body: "x" }), "a body");
  assertThrows(TypeError, () => new Request(URL_A, { signal: {} }), "a signal");
  assertThrows(TypeError, () => new Response(), "new Response()");
});

test("a response streams its chunks", async () => {
  const pending = fetch(URL_A, { headers: { Range: "bytes=0-4" } });
  const started = lastStarted();
  assertEquals(`${started.method} ${started.url}`, `GET ${URL_A}`, "started");
  assertEquals(started.headers.range, "bytes=0-4", "Range");
  assertEquals(started.headers["user-agent"], "test-agent", "User-Agent");
  fakeHttp.respond(started.id, 206, "Partial Content", URL_A, [
    "Content-Length",
    "5",
    "Content-Range",
    "bytes 0-4/10",
  ]);
  fakeHttp.data(started.id, new Uint8Array([1, 2, 3]));
  fakeHttp.data(started.id, new Uint8Array([4, 5]));
  fakeHttp.end(started.id);
  const response = await pending;
  assertEquals(response.status, 206, "status");
  assertEquals(response.ok, true, "ok");
  assertEquals(response.statusText, "Partial Content", "statusText");
  assertEquals(response.url, URL_A, "url");
  assertEquals(response.redirected, false, "redirected");
  assertEquals(
    response.headers.get("content-range"),
    "bytes 0-4/10",
    "headers",
  );
  assertThrows(
    TypeError,
    () => response.headers.set("a", "b"),
    "immutable headers",
  );
  assertEquals(response.bodyUsed, false, "bodyUsed before");
  assertEquals(
    (await readAll(response.body)).join("|"),
    "01 02 03|04 05",
    "chunks",
  );
  assertEquals(response.bodyUsed, true, "bodyUsed after");
});

test("chunks that arrive before a read wait for it", async () => {
  const pending = fetch(URL_A);
  const { id } = lastStarted();
  fakeHttp.respond(id, 200, "OK", URL_A, []);
  fakeHttp.data(id, new Uint8Array([1]));
  fakeHttp.data(id, new Uint8Array([2, 3]));
  fakeHttp.end(id);
  const response = await pending;
  await tasksRun();
  assertEquals((await readAll(response.body)).join("|"), "01|02 03", "chunks");
});

test("text() and arrayBuffer() read the whole body, once", async () => {
  const pending = fetch(URL_A);
  const { id } = lastStarted();
  fakeHttp.respond(id, 200, "OK", "https://example.com/moved", []);
  fakeHttp.data(id, new TextEncoder().encode("hel"));
  fakeHttp.data(id, new TextEncoder().encode("lo"));
  fakeHttp.end(id);
  const response = await pending;
  assertEquals(response.redirected, true, "redirected");
  assertEquals(await response.text(), "hello", "text");
  const error = await rejection(response.arrayBuffer());
  assertEquals(error instanceof TypeError, true, "a second read");
});

test("a server's header that a request could not send is kept", async () => {
  const pending = fetch(URL_A);
  const { id } = lastStarted();
  fakeHttp.respond(id, 200, "OK", URL_A, [
    "Bad Name",
    "1",
    "X-Text",
    "\u0432\u0438\u0434\u0435\u043e",
  ]);
  fakeHttp.end(id);
  const response = await pending;
  assertEquals(
    response.headers.get("x-text"),
    "\u0432\u0438\u0434\u0435\u043e",
    "value",
  );
  assertEquals(
    [...response.headers.keys()].join(" "),
    "bad name x-text",
    "names",
  );
});

test("HEAD and a 204 response have no body", async () => {
  const head = fetch(URL_A, { method: "HEAD" });
  const first = lastStarted();
  assertEquals(first.method, "HEAD", "method");
  fakeHttp.respond(first.id, 200, "OK", URL_A, [
    "Date",
    "Tue, 17 Nov 2026 10:00:00 GMT",
  ]);
  fakeHttp.end(first.id);
  const headResponse = await head;
  assertEquals(headResponse.body, null, "HEAD body");
  assertEquals(
    headResponse.headers.get("date"),
    "Tue, 17 Nov 2026 10:00:00 GMT",
    "Date",
  );
  const empty = fetch(URL_A);
  const second = lastStarted();
  fakeHttp.respond(second.id, 204, "No Content", URL_A, []);
  fakeHttp.end(second.id);
  const emptyResponse = await empty;
  assertEquals(emptyResponse.body, null, "204 body");
  assertEquals((await emptyResponse.arrayBuffer()).byteLength, 0, "204 bytes");
});

test("an aborted signal rejects at once, and starts nothing", async () => {
  // The tests run side by side at their awaits: count before the first one.
  const before = fakeHttp.started.length;
  const reason = new Error("stop");
  const pending = fetch(URL_A, { signal: AbortSignal.abort(reason) });
  assertEquals(fakeHttp.started.length, before, "nothing started");
  assertEquals(await rejection(pending), reason, "the reason");
});

test("an abort before the response cancels the request", async () => {
  const controller = new AbortController();
  const pending = fetch(URL_A, { signal: controller.signal });
  const { id } = lastStarted();
  controller.abort();
  const error = await rejection(pending);
  assertEquals(error.name, "AbortError", "an AbortError");
  assertEquals(fakeHttp.cancelled.includes(id), true, "cancelled");
  // Its late events are dropped.
  fakeHttp.respond(id, 200, "OK", URL_A, []);
  fakeHttp.fail(id, "late");
  await tasksRun();
});

test("an abort during the body rejects the read and cancels", async () => {
  const controller = new AbortController();
  const pending = fetch(URL_A, { signal: controller.signal });
  const { id } = lastStarted();
  fakeHttp.respond(id, 200, "OK", URL_A, []);
  const response = await pending;
  const reader = response.body.getReader();
  const read = reader.read();
  controller.abort();
  const error = await rejection(read);
  assertEquals(error.name, "AbortError", "an AbortError");
  assertEquals(fakeHttp.cancelled.includes(id), true, "cancelled");
});

test("a cancel of the body or of its reader cancels the request", async () => {
  for (const cancel of [
    (body) => body.cancel(),
    (body) => body.getReader().cancel(),
  ]) {
    const pending = fetch(URL_A);
    const { id } = lastStarted();
    fakeHttp.respond(id, 200, "OK", URL_A, []);
    const response = await pending;
    await cancel(response.body);
    assertEquals(fakeHttp.cancelled.includes(id), true, "cancelled");
    fakeHttp.data(id, new Uint8Array([9]));
    await tasksRun();
  }
});

test("a failure is a TypeError, before and during the body", async () => {
  const before = fetch(URL_A);
  const first = lastStarted();
  fakeHttp.fail(first.id, "connection refused");
  const error = await rejection(before);
  assertEquals(error instanceof TypeError, true, "a TypeError");
  assertEquals(error.message, "Failed to fetch: connection refused", "message");
  const during = fetch(URL_A);
  const second = lastStarted();
  fakeHttp.respond(second.id, 200, "OK", URL_A, []);
  fakeHttp.data(second.id, new Uint8Array([1]));
  fakeHttp.fail(second.id, "reset");
  const response = await during;
  const readError = await rejection(readAll(response.body));
  assertEquals(readError.message, "Failed to fetch: reset", "during the body");
});

test("a request's own User-Agent is kept; no argument rejects", async () => {
  const pending = fetch(URL_A, { headers: { "User-Agent": "mine" } });
  const { id, headers } = lastStarted();
  assertEquals(headers["user-agent"], "mine", "User-Agent");
  fakeHttp.fail(id, "done");
  await rejection(pending);
  const error = await rejection(fetch());
  assertEquals(error instanceof TypeError, true, "no argument");
  // Events of an id that was never started are dropped.
  fakeHttp.data(9999, new Uint8Array([1]));
  await tasksRun();
});
