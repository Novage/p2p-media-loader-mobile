// URL and URLSearchParams of the WHATWG URL Standard (specs/engine.md,
// "Globals"). Ada parses and serializes (natives.urlParse and the other url*
// and search* helpers, native/src/url.c); this file gives the WebIDL surface,
// as Node's URL does over Ada. A URL keeps the parts that Ada gives; a setter
// asks Ada for the parts after it.
(natives) => {
  // WebIDL's USVString: a string, with U+FFFD for each lone surrogate. A
  // template literal converts as WebIDL does: a Symbol throws.
  const usv = (value) => `${value}`.toWellFormed();
  const optionalUsv = (value) => (value === undefined ? undefined : usv(value));
  const requireArguments = (count, needed, name) => {
    if (count < needed) {
      throw new TypeError(
        `${name}: ${needed} argument${needed > 1 ? "s" : ""} required`,
      );
    }
  };

  // A URL made from parts that Ada already gave (URL.parse).
  const FROM_PARTS = Symbol("URL parts");

  // The URLSearchParams internals that URL uses.
  let setParamsQuery;

  class URLSearchParams {
    // [name, value] pairs, in order.
    #list = [];
    // The URL whose query this is, or null.
    #url = null;

    static {
      // Links `params` to `url`, with the list of `query`.
      setParamsQuery = (params, url, query) => {
        params.#url = url;
        params.#list = natives.searchParse(query);
      };
    }

    // A sequence of pairs, a record, or a string (with or without "?").
    constructor(init = "") {
      // A function is an object to WebIDL too.
      if (
        (typeof init === "object" && init !== null) ||
        typeof init === "function"
      ) {
        if (typeof init[Symbol.iterator] === "function") {
          for (const pair of init) {
            if (
              (typeof pair !== "object" || pair === null) &&
              typeof pair !== "function"
            ) {
              throw new TypeError("URLSearchParams: a pair is not a sequence");
            }
            const items = [...pair];
            if (items.length !== 2) {
              throw new TypeError("URLSearchParams: a pair must have 2 items");
            }
            this.#list.push([usv(items[0]), usv(items[1])]);
          }
        } else {
          // WebIDL's record: a later key that converts to the same string
          // takes the place of the earlier one's value.
          const record = new Map();
          for (const key of Reflect.ownKeys(init)) {
            const property = Object.getOwnPropertyDescriptor(init, key);
            if (property?.enumerable) record.set(usv(key), usv(init[key]));
          }
          this.#list = [...record];
        }
      } else {
        // searchParse drops a leading "?", as the standard's constructor.
        this.#list = natives.searchParse(usv(init));
      }
    }

    get size() {
      return this.#list.length;
    }

    append(name, value) {
      requireArguments(arguments.length, 2, "append");
      // Converted first: a conversion can change the list.
      const pair = [usv(name), usv(value)];
      this.#list.push(pair);
      this.#update();
    }

    delete(name, value = undefined) {
      requireArguments(arguments.length, 1, "delete");
      const key = usv(name);
      const wanted = optionalUsv(value);
      this.#list = this.#list.filter(
        ([n, v]) => n !== key || (wanted !== undefined && v !== wanted),
      );
      this.#update();
    }

    get(name) {
      requireArguments(arguments.length, 1, "get");
      const key = usv(name);
      return this.#list.find(([n]) => n === key)?.[1] ?? null;
    }

    getAll(name) {
      requireArguments(arguments.length, 1, "getAll");
      const key = usv(name);
      return this.#list.filter(([n]) => n === key).map(([, v]) => v);
    }

    has(name, value = undefined) {
      requireArguments(arguments.length, 1, "has");
      const key = usv(name);
      const wanted = optionalUsv(value);
      return this.#list.some(
        ([n, v]) => n === key && (wanted === undefined || v === wanted),
      );
    }

    set(name, value) {
      requireArguments(arguments.length, 2, "set");
      const key = usv(name);
      const text = usv(value);
      const first = this.#list.findIndex(([n]) => n === key);
      if (first === -1) {
        this.#list.push([key, text]);
      } else {
        this.#list[first] = [key, text];
        this.#list = this.#list.filter(([n], i) => i <= first || n !== key);
      }
      this.#update();
    }

    // Stable, by the names' code units.
    sort() {
      this.#list.sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
      this.#update();
    }

    toString() {
      return natives.searchSerialize(this.#list);
    }

    forEach(callback, thisArg = undefined) {
      requireArguments(arguments.length, 1, "forEach");
      if (typeof callback !== "function") {
        throw new TypeError("forEach: the callback is not a function");
      }
      // As WebIDL: the list is read again at each step.
      for (let i = 0; i < this.#list.length; i++) {
        const [name, value] = this.#list[i];
        callback.call(thisArg, value, name, this);
      }
    }

    *entries() {
      for (let i = 0; i < this.#list.length; i++) yield [...this.#list[i]];
    }

    *keys() {
      for (let i = 0; i < this.#list.length; i++) yield this.#list[i][0];
    }

    *values() {
      for (let i = 0; i < this.#list.length; i++) yield this.#list[i][1];
    }

    // The standard's update steps: the URL's query follows the list; an
    // empty list leaves no query.
    #update() {
      if (this.#url !== null) {
        setUrlQuery(this.#url, natives.searchSerialize(this.#list));
      }
    }
  }

  // The URL internals that URLSearchParams uses.
  let setUrlQuery;

  class URL {
    #parts;
    // Made on first use: [SameObject].
    #searchParams = null;

    // No requireArguments: `arguments` costs an object on each call, and the
    // core makes a URL for each URI of a manifest. With no argument, the
    // input is "undefined", which throws the same TypeError.
    constructor(url, base = undefined) {
      if (url === FROM_PARTS) {
        this.#parts = base;
        return;
      }
      const input = usv(url);
      const parts = natives.urlParse(input, optionalUsv(base));
      // Without the input, which can hold a signed URL's token (as Node).
      if (parts === null) throw new TypeError("Invalid URL");
      this.#parts = parts;
    }

    static canParse(url, base = undefined) {
      requireArguments(arguments.length, 1, "canParse");
      return natives.urlParse(usv(url), optionalUsv(base)) !== null;
    }

    static parse(url, base = undefined) {
      requireArguments(arguments.length, 1, "parse");
      const parts = natives.urlParse(usv(url), optionalUsv(base));
      return parts === null ? null : new URL(FROM_PARTS, parts);
    }

    get href() {
      return this.#parts.href;
    }

    set href(value) {
      const input = usv(value);
      // As the standard: the new value parsed alone.
      const parts = natives.urlParse(input);
      if (parts === null) throw new TypeError("Invalid URL");
      this.#replace(parts);
    }

    get origin() {
      return this.#parts.origin;
    }

    get searchParams() {
      if (this.#searchParams === null) {
        this.#searchParams = new URLSearchParams();
        setParamsQuery(this.#searchParams, this, this.#parts.search);
      }
      return this.#searchParams;
    }

    toString() {
      return this.#parts.href;
    }

    toJSON() {
      return this.#parts.href;
    }

    // A failed setter leaves the URL as it was.
    #set(part, value) {
      // Converted first: a conversion can change this URL.
      const text = usv(value);
      const parts = natives.urlSet(this.#parts.href, part, text);
      if (parts !== null) this.#replace(parts);
    }

    // The searchParams list follows a changed query. An unchanged query has
    // the list already: the list and the query change together.
    #replace(parts) {
      const queryChanged = parts.search !== this.#parts.search;
      this.#parts = parts;
      if (queryChanged && this.#searchParams !== null) {
        setParamsQuery(this.#searchParams, this, parts.search);
      }
    }

    static {
      // From the list, so the list is not parsed again. The search setter
      // gives the same result as the standard's update steps: a serialized
      // query has nothing that it encodes again, and "" leaves no query.
      setUrlQuery = (url, query) => {
        url.#parts = natives.urlSet(url.#parts.href, "search", query);
      };

      // The parts that have a getter and a setter of the same name.
      for (const part of [
        "protocol",
        "username",
        "password",
        "host",
        "hostname",
        "port",
        "pathname",
        "search",
        "hash",
      ]) {
        Object.defineProperty(URL.prototype, part, {
          get() {
            return this.#parts[part];
          },
          set(value) {
            this.#set(part, value);
          },
          configurable: true,
        });
      }
    }
  }

  // As WebIDL: the iterator of a pair iterable is its entries.
  Object.defineProperty(URLSearchParams.prototype, Symbol.iterator, {
    value: URLSearchParams.prototype.entries,
    writable: true,
    configurable: true,
  });
  natives.expose(URL, URLSearchParams);
};
