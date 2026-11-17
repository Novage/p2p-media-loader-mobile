// URL and URLSearchParams (src/polyfills/url.js over Ada, src/url.c), against
// the WPT URL data (test/third_party/wpt-url) and the core's own calls.

const COMPONENTS = [
  "href",
  "origin",
  "protocol",
  "username",
  "password",
  "host",
  "hostname",
  "port",
  "pathname",
  "search",
  "hash",
];

// Runs `check` on each case and fails once, with the count and the first
// failures, so that one run shows how far the data is from passing.
const checkAll = (cases, describe, check) => {
  const failures = [];
  for (const item of cases) {
    try {
      check(item);
    } catch (error) {
      failures.push(`${describe(item)}: ${error.message}`);
    }
  }
  if (failures.length > 0) {
    throw new Error(
      `${failures.length} of ${cases.length} failed:\n${failures.slice(0, 10).join("\n")}`,
    );
  }
};

const checkComponents = (url, expected) => {
  for (const name of COMPONENTS) {
    if (name in expected) assertEquals(url[name], expected[name], name);
  }
  if ("searchParams" in expected) {
    assertEquals(
      url.searchParams.toString(),
      expected.searchParams,
      "searchParams",
    );
  }
};

test("WPT urltestdata: parsing and serialization", () => {
  const cases = [...wptUrlTestData, ...wptUrlTestDataJavaScriptOnly].filter(
    (item) => typeof item !== "string",
  );
  assertEquals(cases.length > 800, true, "cases");
  checkAll(
    cases,
    (item) => `${show(item.input)} on ${show(item.base)}`,
    (item) => {
      const base = item.base === null ? undefined : item.base;
      if (item.failure) {
        assertThrows(TypeError, () => new URL(item.input, base), "new URL");
        assertEquals(URL.canParse(item.input, base), false, "canParse");
        assertEquals(URL.parse(item.input, base), null, "parse");
      } else {
        checkComponents(new URL(item.input, base), item);
        checkComponents(URL.parse(item.input, base), item);
        assertEquals(URL.canParse(item.input, base), true, "canParse");
      }
    },
  );
});

test("WPT setters_tests", () => {
  const cases = [];
  for (const [setter, items] of Object.entries(wptSettersTests)) {
    if (setter === "comment") continue;
    for (const item of items) cases.push({ setter, ...item });
  }
  assertEquals(cases.length > 200, true, "cases");
  checkAll(
    cases,
    (item) => `${item.setter} = ${show(item.new_value)} on ${show(item.href)}`,
    (item) => {
      const url = new URL(item.href);
      url[item.setter] = item.new_value;
      checkComponents(url, item.expected);
    },
  );
});

test("WPT toascii: IDNA hosts", () => {
  const cases = wptToAsciiTests.filter((item) => typeof item !== "string");
  assertEquals(cases.length > 50, true, "cases");
  checkAll(
    cases,
    (item) => show(item.input),
    (item) => {
      if (item.output === null) {
        assertThrows(
          TypeError,
          () => new URL(`https://${item.input}/x`),
          "new URL",
        );
      } else {
        const url = new URL(`https://${item.input}/x`);
        assertEquals(url.host, item.output, "host");
        assertEquals(url.hostname, item.output, "hostname");
        assertEquals(url.pathname, "/x", "pathname");
        assertEquals(url.href, `https://${item.output}/x`, "href");
      }
      // A host that fails leaves the URL as it was.
      const set = new URL("https://x/x");
      set.hostname = item.input;
      assertEquals(set.hostname, item.output ?? "x", "hostname setter");
    },
  );
});

// The results of Node's URL parser, which follows the standard as browsers do.
test("the core's resolution of manifest URLs", () => {
  const cases = [
    [
      "seg-1.ts?x=1",
      "https://cdn.example.com/live/master.m3u8?token=a~b",
      "https://cdn.example.com/live/seg-1.ts?x=1",
    ],
    [
      "../720p/index.m3u8",
      "https://cdn.example.com/live/hls/master.m3u8",
      "https://cdn.example.com/live/720p/index.m3u8",
    ],
    [
      "//other.example.com/a.ts",
      "https://cdn.example.com/x/y.m3u8",
      "https://other.example.com/a.ts",
    ],
    [
      "https://cdn.example.com/a b/seg.ts?exp=2026-11-17T00:00:00Z&sig=a~b=c",
      undefined,
      "https://cdn.example.com/a%20b/seg.ts?exp=2026-11-17T00:00:00Z&sig=a~b=c",
    ],
    [
      "chunk-$Number$.m4s?hdnts=st=1~exp=2~acl=/*~hmac=ab",
      "https://example.com/dash/manifest.mpd",
      "https://example.com/dash/chunk-$Number$.m4s?hdnts=st=1~exp=2~acl=/*~hmac=ab",
    ],
    [
      "HTTPS://CDN.Example.COM:443/./a/../b.ts#frag",
      undefined,
      "https://cdn.example.com/b.ts#frag",
    ],
    ["seg.ts", "http://[::1]:8080/live/", "http://[::1]:8080/live/seg.ts"],
    [
      "seg.ts",
      "https://b\u00fccher.example/live/",
      "https://xn--bcher-kva.example/live/seg.ts",
    ],
  ];
  for (const [input, base, expected] of cases) {
    // As resolveUrl in the core.
    assertEquals(new URL(input, base).toString(), expected, show(input));
  }
  // As @videojs/vhs-utils: a URL object as the base, then href and protocol.
  const resolved = new URL("chunk.m4s", new URL("https://example.com/dash/"));
  assertEquals(resolved.href, "https://example.com/dash/chunk.m4s", "URL base");
  assertEquals(resolved.protocol, "https:", "protocol");
  // As m3u8-parser: a parameter of the playlist's URL.
  const params = new URL(
    "https://a.com/x.m3u8?v=1&name=a%20b+c",
    "https://a.com",
  ).searchParams;
  assertEquals(params.get("name"), "a b c", "searchParams.get");
  assertEquals(params.get("missing"), null, "missing parameter");
});

test("URLSearchParams", () => {
  const params = new URLSearchParams("b=2&a=1&a=3");
  assertEquals(params.getAll("a").join(), "1,3", "getAll");
  assertEquals(params.get("a"), "1", "get");
  assertEquals(params.get("z"), null, "get of a missing name");
  assertEquals(params.size, 3, "size");
  params.append("c", "x y");
  params.delete("b");
  assertEquals(params.toString(), "a=1&a=3&c=x+y", "append and delete");
  assertEquals([...params.keys()].join(), "a,a,c", "keys");
  assertEquals([...params.values()].join(), "1,3,x y", "values");
  assertEquals(
    JSON.stringify([...params]),
    '[["a","1"],["a","3"],["c","x y"]]',
    "entries",
  );
  params.set("a", "9");
  assertEquals(params.toString(), "a=9&c=x+y", "set keeps the first place");
});

test("URLSearchParams takes a string, pairs or a record", () => {
  assertEquals(new URLSearchParams("?a=1").toString(), "a=1", "one ? dropped");
  assertEquals(
    new URLSearchParams("??a=1").toString(),
    "%3Fa=1",
    "only one ? dropped",
  );
  assertEquals(
    new URLSearchParams([
      ["a", "1"],
      ["b", "2"],
    ]).toString(),
    "a=1&b=2",
    "pairs",
  );
  assertEquals(
    new URLSearchParams(new URLSearchParams("x=1")).toString(),
    "x=1",
    "params",
  );
  assertEquals(
    new URLSearchParams({ k: "v", n: 1 }).toString(),
    "k=v&n=1",
    "record",
  );
  assertEquals(new URLSearchParams().toString(), "", "nothing");
  // WebIDL's union: null is not an object, so it is the string "null"
  // (Node takes null as no argument).
  assertEquals(
    new URLSearchParams(null).toString(),
    "null=",
    "null is a string",
  );
  assertEquals(
    new URLSearchParams("a=%zz&b=%41+%2B").get("b"),
    "A +",
    "decoding",
  );
  assertEquals(
    new URLSearchParams("a=%zz").get("a"),
    "%zz",
    "invalid escape kept",
  );
  assertEquals(
    new URLSearchParams([["\ud800", "x"]]).toString(),
    "%EF%BF%BD=x",
    "lone surrogate",
  );
  assertThrows(TypeError, () => new URLSearchParams([["a"]]), "pair of 1");
  assertThrows(
    TypeError,
    () => new URLSearchParams([["a", "b", "c"]]),
    "pair of 3",
  );
  assertThrows(
    TypeError,
    () => new URLSearchParams(["ab"]),
    "a pair that is a string",
  );
  assertThrows(
    TypeError,
    () => new URLSearchParams({ [Symbol("s")]: "v" }),
    "Symbol key",
  );
});

// Node's results: the bytes decode as WHATWG UTF-8, U+FFFD for each invalid part.
test("URLSearchParams decodes invalid UTF-8 as the standard", () => {
  const cases = [
    ["x=%80a", "\ufffda", "x=%EF%BF%BDa"],
    ["x=%ED%A0%80", "\ufffd\ufffd\ufffd", "x=%EF%BF%BD%EF%BF%BD%EF%BF%BD"],
    ["x=%C0%80", "\ufffd\ufffd", "x=%EF%BF%BD%EF%BF%BD"],
    ["x=%F8%80%80%80%80", "\ufffd".repeat(5), `x=${"%EF%BF%BD".repeat(5)}`],
    ["x=%E2%82%AC", "\u20ac", "x=%E2%82%AC"],
    ["x=%F0%9F%98%80", "\u{1F600}", "x=%F0%9F%98%80"],
  ];
  for (const [query, value, serialized] of cases) {
    const params = new URLSearchParams(query);
    assertEquals(params.get("x"), value, query);
    assertEquals(params.toString(), serialized, `${query} serialized`);
  }
  const url = new URL("https://a/?x=%80a");
  url.searchParams.append("y", "1");
  assertEquals(url.href, "https://a/?x=%EF%BF%BDa&y=1", "through a URL");
});

test("the URL follows its params by their list", () => {
  const url = new URL("https://a/?x=1");
  const params = url.searchParams;
  const toString = URLSearchParams.prototype.toString;
  try {
    URLSearchParams.prototype.toString = () => "replaced";
    params.append("y", "2");
  } finally {
    URLSearchParams.prototype.toString = toString;
  }
  assertEquals(url.href, "https://a/?x=1&y=2", "not through toString");
  const error = assertThrows(
    TypeError,
    () => new URL("a", "nope"),
    "invalid base",
  );
  assertEquals(error.message, "Invalid URL", "no input in the message");
});

// As WebIDL: each argument is converted before the state is read (Node gives
// the same for append; its setters read href first, so it loses the hash).
test("a conversion that changes the object comes first", () => {
  const params = new URLSearchParams("x=1");
  params.append("a", {
    toString() {
      params.delete("x");
      return "1";
    },
  });
  assertEquals(params.toString(), "a=1", "append");
  const url = new URL("https://a/");
  url.pathname = {
    toString() {
      url.hash = "h";
      return "/p";
    },
  };
  assertEquals(url.href, "https://a/p#h", "a setter");
});

test("URLSearchParams is a pair iterable as WebIDL has it", () => {
  assertEquals(
    URLSearchParams.prototype[Symbol.iterator],
    URLSearchParams.prototype.entries,
    "the iterator is entries",
  );
  assertEquals(
    Object.getOwnPropertyDescriptor(URLSearchParams.prototype, Symbol.iterator)
      .enumerable,
    false,
    "the iterator is not enumerable",
  );
  assertEquals(
    new URLSearchParams(function () {}).toString(),
    "",
    "a function is a record",
  );
});

test("URLSearchParams delete and has with a value", () => {
  const params = new URLSearchParams("a=1&a=2&b=1");
  assertEquals(params.has("a", "2"), true, "has a value");
  assertEquals(params.has("a", "3"), false, "has another value");
  params.delete("a", "1");
  assertEquals(params.toString(), "a=2&b=1", "delete one value");
  params.delete("a", undefined);
  assertEquals(params.toString(), "b=1", "undefined is no value");
});

test("URLSearchParams sort is stable, by code units", () => {
  const params = new URLSearchParams("z=1&a=2&z=3&\uFB03=4&\u{1F600}=5&a=6");
  params.sort();
  assertEquals(
    [...params].map(([n, v]) => `${n}${v}`).join(),
    "a2,a6,z1,z3,\u{1F600}5,\uFB034",
    "order",
  );
});

test("URLSearchParams iterates over the list as it changes", () => {
  const params = new URLSearchParams("a=1&b=2");
  const seen = [];
  for (const [name] of params) {
    seen.push(name);
    if (name === "a") params.append("c", "3");
  }
  assertEquals(seen.join(), "a,b,c", "for of");
  const names = [];
  params.forEach((value, name, owner) => {
    names.push(`${name}${value}${owner === params}`);
    if (name === "a") params.delete("b");
  });
  assertEquals(names.join(), "a1true,c3true", "forEach");
});

test("a URL and its searchParams follow each other", () => {
  const url = new URL("https://a.com/p?x=1");
  const params = url.searchParams;
  assertEquals(url.searchParams === params, true, "the same object");
  params.append("y", "a b");
  assertEquals(url.href, "https://a.com/p?x=1&y=a+b", "params change the URL");
  url.search = "?q=2";
  assertEquals(params.toString(), "q=2", "search changes the params");
  url.href = "https://b.com/?r=3";
  assertEquals(params.get("r"), "3", "href changes the params");
  params.delete("r");
  assertEquals(url.href, "https://b.com/", "an empty list leaves no query");
  const opaque = new URL("data:space    ?query");
  opaque.searchParams.delete("query");
  // The standard now encodes the last space of an opaque path (Node's result).
  assertEquals(opaque.href, "data:space   %20", "the opaque path's last space");
});

test("the globals look as in a browser", () => {
  for (const name of ["URL", "URLSearchParams"]) {
    const { writable, enumerable, configurable } =
      Object.getOwnPropertyDescriptor(globalThis, name);
    assertEquals(writable && configurable && !enumerable, true, name);
  }
  assertEquals(
    Object.prototype.toString.call(new URL("https://a.com/")),
    "[object URL]",
    "tag",
  );
  assertEquals(String(new URL("https://a.com/")), "https://a.com/", "toString");
  assertEquals(
    JSON.stringify(new URL("https://a.com/")),
    '"https://a.com/"',
    "toJSON",
  );
  assertThrows(TypeError, () => URL.prototype.toString.call({}), "brand check");
  assertEquals(URL.createObjectURL, undefined, "createObjectURL");
});
