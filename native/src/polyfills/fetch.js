// fetch, Headers, Request and Response of the Fetch Standard (specs/engine.md,
// "Globals"): the part that the core uses. The platform's HTTP client does
// the network work (natives.httpStart, native/src/http.c); this file gives
// the WebIDL surface and the streamed body.
(natives) => {
  const {
    usvString: usv,
    requireArguments,
    isObject,
    dictionary,
    isHttpToken,
  } = natives;
  // Kept now, so that a script that replaces these globals changes nothing
  // here.
  const { URL, TextDecoder, AbortController, AbortSignal } = globalThis;
  const { userAgent } = navigator;

  // Leading and trailing HTTP whitespace.
  const HTTP_WHITESPACE = /^[\t\n\r ]+|[\t\n\r ]+$/g;
  // A ByteString with no NUL, CR or LF.
  const INVALID_VALUE = /[\0\r\n\u0100-\uffff]/;

  const headerName = (name) => {
    const text = `${name}`;
    if (!isHttpToken(text)) {
      throw new TypeError(`Headers: "${text}" is not a valid header name`);
    }
    return text.toLowerCase();
  };

  const headerValue = (value) => {
    const text = `${value}`.replace(HTTP_WHITESPACE, "");
    if (INVALID_VALUE.test(text)) {
      throw new TypeError("Headers: the header value is not valid");
    }
    return text;
  };

  // Sets a response's headers from the platform's names and values, in turn.
  let fillFromPlatform;

  class Headers {
    // Lowercase name -> the values, joined by ", ".
    #map = new Map();
    // A response's headers cannot change.
    #immutable = false;

    static {
      // Without the checks of append: a server's header can be anything,
      // and a throw here would leave the fetch unsettled.
      fillFromPlatform = (headers, pairs) => {
        for (let i = 0; i + 1 < pairs.length; i += 2) {
          const name = pairs[i].toLowerCase();
          const old = headers.#map.get(name);
          headers.#map.set(
            name,
            old === undefined ? pairs[i + 1] : `${old}, ${pairs[i + 1]}`,
          );
        }
        headers.#immutable = true;
      };
    }

    // A Headers, a sequence of [name, value] pairs, or a record.
    constructor(init = undefined) {
      if (init === undefined) return;
      if (!isObject(init)) {
        throw new TypeError("Headers: the init is not an object");
      }
      // A ByteString: append checks the characters.
      const byteString = (value) => `${value}`;
      for (const [name, value] of natives.pairsOf(
        init,
        "Headers",
        byteString,
      )) {
        this.append(name, value);
      }
    }

    #change() {
      if (this.#immutable) {
        throw new TypeError("Headers: these headers cannot change");
      }
    }

    append(name, value) {
      requireArguments(arguments.length, 2, "Headers.append");
      const key = headerName(name);
      const text = headerValue(value);
      this.#change();
      const old = this.#map.get(key);
      this.#map.set(key, old === undefined ? text : `${old}, ${text}`);
    }

    set(name, value) {
      requireArguments(arguments.length, 2, "Headers.set");
      const key = headerName(name);
      const text = headerValue(value);
      this.#change();
      this.#map.set(key, text);
    }

    delete(name) {
      requireArguments(arguments.length, 1, "Headers.delete");
      const key = headerName(name);
      this.#change();
      this.#map.delete(key);
    }

    get(name) {
      requireArguments(arguments.length, 1, "Headers.get");
      return this.#map.get(headerName(name)) ?? null;
    }

    has(name) {
      requireArguments(arguments.length, 1, "Headers.has");
      return this.#map.has(headerName(name));
    }

    forEach(callback, thisArg = undefined) {
      requireArguments(arguments.length, 1, "Headers.forEach");
      if (typeof callback !== "function") {
        throw new TypeError("Headers.forEach: the callback is not a function");
      }
      for (const [name, value] of this) {
        callback.call(thisArg, value, name, this);
      }
    }

    // The pairs, sorted by name, as the Fetch Standard iterates them.
    *entries() {
      const names = [...this.#map.keys()].sort();
      for (const name of names) {
        if (this.#map.has(name)) yield [name, this.#map.get(name)];
      }
    }

    *keys() {
      for (const [name] of this.entries()) yield name;
    }

    *values() {
      for (const [, value] of this.entries()) yield value;
    }
  }

  // The methods whose name is normalized to uppercase; the forbidden ones.
  const NORMALIZED = ["DELETE", "GET", "HEAD", "OPTIONS", "POST", "PUT"];
  const FORBIDDEN = ["CONNECT", "TRACE", "TRACK"];

  const methodOf = (value) => {
    const text = `${value}`;
    if (!isHttpToken(text)) {
      throw new TypeError(`Request: "${text}" is not a valid method`);
    }
    const upper = text.toUpperCase();
    if (FORBIDDEN.includes(upper)) {
      throw new TypeError(`Request: the method ${text} is forbidden`);
    }
    return NORMALIZED.includes(upper) ? upper : text;
  };

  class Request {
    #url;
    #method;
    #headers;
    #signal;

    // `init` takes method, headers and signal. A body throws: the core sends
    // none. mode, credentials, cache and priority are taken and ignored: there
    // are no cookies and no HTTP cache.
    constructor(input, init = undefined) {
      requireArguments(arguments.length, 1, "Request");
      const options = dictionary(init);
      const from = input instanceof Request ? input : undefined;
      if (from) {
        this.#url = from.url;
      } else {
        // No document base: a relative URL throws a TypeError.
        this.#url = new URL(usv(input)).href;
      }
      if (options.body !== undefined && options.body !== null) {
        throw new TypeError("Request: a body is not supported");
      }
      this.#method =
        options.method !== undefined
          ? methodOf(options.method)
          : (from?.method ?? "GET");
      this.#headers = new Headers(options.headers ?? from?.headers);
      const signal = options.signal ?? from?.signal;
      if (signal !== undefined && !(signal instanceof AbortSignal)) {
        throw new TypeError("Request: the signal is not an AbortSignal");
      }
      this.#signal = signal ?? new AbortController().signal;
    }

    get url() {
      return this.#url;
    }

    get method() {
      return this.#method;
    }

    get headers() {
      return this.#headers;
    }

    get signal() {
      return this.#signal;
    }
  }

  // A response body: a readable byte stream with the reader that the core
  // uses. `cancel` stops the request.
  class Body {
    #state = "readable";
    #chunks = [];
    #reads = [];
    #error;
    #cancel;
    #locked = false;
    disturbed = false;

    constructor(cancel) {
      this.#cancel = cancel;
    }

    get locked() {
      return this.#locked;
    }

    // From the request.
    enqueue(bytes) {
      if (this.#state !== "readable") return;
      const read = this.#reads.shift();
      if (read) read.resolve({ value: bytes, done: false });
      else this.#chunks.push(bytes);
    }

    close() {
      if (this.#state !== "readable") return;
      this.#state = "closed";
      for (const read of this.#reads.splice(0)) {
        read.resolve({ value: undefined, done: true });
      }
    }

    error(reason) {
      if (this.#state !== "readable") return;
      this.#state = "errored";
      this.#error = reason;
      this.#chunks = [];
      for (const read of this.#reads.splice(0)) read.reject(reason);
    }

    // For the reader and Response.
    read() {
      this.disturbed = true;
      if (this.#chunks.length > 0) {
        return Promise.resolve({ value: this.#chunks.shift(), done: false });
      }
      if (this.#state === "closed") {
        return Promise.resolve({ value: undefined, done: true });
      }
      if (this.#state === "errored") return Promise.reject(this.#error);
      return new Promise((resolve, reject) => {
        this.#reads.push({ resolve, reject });
      });
    }

    cancelBody() {
      this.disturbed = true;
      if (this.#state === "errored") return Promise.reject(this.#error);
      if (this.#state === "readable") {
        this.#chunks = [];
        this.close();
        this.#cancel();
      }
      return Promise.resolve();
    }

    lock() {
      if (this.#locked) {
        throw new TypeError("ReadableStream: the stream is locked");
      }
      this.#locked = true;
      return () => {
        this.#locked = false;
      };
    }

    // The stream object that response.body gives.
    stream() {
      const body = this;
      return Object.freeze({
        get locked() {
          return body.locked;
        },
        cancel() {
          if (body.locked) {
            return Promise.reject(
              new TypeError("ReadableStream: the stream is locked"),
            );
          }
          return body.cancelBody();
        },
        getReader() {
          const unlock = body.lock();
          let released = false;
          const check = () =>
            released
              ? Promise.reject(
                  new TypeError("ReadableStreamReader: the lock is released"),
                )
              : undefined;
          return Object.freeze({
            read: () => check() ?? body.read(),
            cancel: () => check() ?? body.cancelBody(),
            releaseLock: () => {
              if (released) return;
              released = true;
              unlock();
            },
          });
        },
      });
    }
  }

  // The secret that only fetch has: Response is not constructed from JS.
  const MADE_BY_FETCH = Symbol("Response");
  // Status codes whose response has no body (the Fetch Standard).
  const NULL_BODY_STATUS = [101, 103, 204, 205, 304];

  class Response {
    #status;
    #statusText;
    #url;
    #redirected;
    #headers;
    #body;
    #stream;

    constructor(secret, state) {
      if (secret !== MADE_BY_FETCH) {
        throw new TypeError(
          "Response: constructing a Response is not supported",
        );
      }
      this.#status = state.status;
      this.#statusText = state.statusText;
      this.#url = state.url;
      this.#redirected = state.redirected;
      this.#headers = state.headers;
      this.#body = state.body;
      this.#stream = state.body ? state.body.stream() : null;
    }

    get ok() {
      return this.#status >= 200 && this.#status <= 299;
    }

    get status() {
      return this.#status;
    }

    get statusText() {
      return this.#statusText;
    }

    get url() {
      return this.#url;
    }

    get redirected() {
      return this.#redirected;
    }

    get headers() {
      return this.#headers;
    }

    get body() {
      return this.#stream;
    }

    get bodyUsed() {
      return this.#body?.disturbed ?? false;
    }

    async arrayBuffer() {
      const body = this.#body;
      if (!body) return new ArrayBuffer(0);
      if (body.disturbed || body.locked) {
        throw new TypeError("Response: the body is already used");
      }
      const unlock = body.lock();
      const chunks = [];
      let length = 0;
      try {
        for (;;) {
          const { value, done } = await body.read();
          if (done) break;
          chunks.push(value);
          length += value.byteLength;
        }
      } finally {
        unlock();
      }
      const bytes = new Uint8Array(length);
      let offset = 0;
      for (const chunk of chunks) {
        bytes.set(chunk, offset);
        offset += chunk.byteLength;
      }
      return bytes.buffer;
    }

    async text() {
      return new TextDecoder().decode(await this.arrayBuffer());
    }
  }

  // The requests in flight, by the platform's id.
  const requests = new Map();

  const networkError = (message) =>
    new TypeError(`Failed to fetch: ${message}`);

  // A request's end: it is no longer in flight, and an abort does nothing.
  const finish = (id, request) => {
    requests.delete(id);
    request.removeAbort();
  };

  natives.httpListen((id, event, ...details) => {
    const request = requests.get(id);
    if (!request) return;
    if (event === "response") {
      const [status, statusText, url, pairs] = details;
      const headers = new Headers();
      fillFromPlatform(headers, pairs);
      const hasBody =
        request.method !== "HEAD" && !NULL_BODY_STATUS.includes(status);
      request.body = hasBody
        ? new Body(() => {
            natives.httpCancel(id);
            finish(id, request);
          })
        : undefined;
      request.resolve(
        new Response(MADE_BY_FETCH, {
          status,
          statusText,
          url,
          redirected: url !== request.url,
          headers,
          body: request.body,
        }),
      );
    } else if (event === "data") {
      request.body?.enqueue(details[0]);
    } else if (event === "end") {
      finish(id, request);
      request.body?.close();
    } else if (event === "fail") {
      finish(id, request);
      // Before the response there is no body; after it, the reject does
      // nothing.
      const error = networkError(details[0]);
      request.body?.error(error);
      request.reject(error);
    }
  });

  // A method, as WebIDL operations: not a constructor. Not an arrow: it reads
  // `arguments`.
  const { fetch } = {
    fetch(input, init = undefined) {
      try {
        requireArguments(arguments.length, 1, "fetch");
        const request = new Request(input, init);
        const { signal } = request;
        if (signal.aborted) return Promise.reject(signal.reason);
        const pairs = [];
        for (const [name, value] of request.headers) pairs.push(name, value);
        if (!request.headers.has("user-agent")) {
          pairs.push("user-agent", userAgent);
        }
        return new Promise((resolve, reject) => {
          let id;
          try {
            id = natives.httpStart(request.method, request.url, pairs);
          } catch (error) {
            reject(networkError(error.message));
            return;
          }
          const state = {
            method: request.method,
            url: request.url,
            resolve,
            reject,
            body: undefined,
            removeAbort: () => {},
          };
          requests.set(id, state);
          state.removeAbort = natives.onAbort(signal, () => {
            natives.httpCancel(id);
            requests.delete(id);
            // As for a failure.
            state.body?.error(signal.reason);
            reject(signal.reason);
          });
        });
      } catch (error) {
        return Promise.reject(error);
      }
    },
  };

  natives.pairIterable(Headers);
  natives.expose(Headers, Request, Response);
  natives.exposeOperations(fetch);
};
