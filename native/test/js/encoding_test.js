// TextEncoder and TextDecoder (src/polyfills/encoding.js, src/encoding.c).

const bytesOf = (...values) => new Uint8Array(values);
const decode = (...values) => new TextDecoder().decode(bytesOf(...values));
const decodeFatal = (...values) =>
  new TextDecoder("utf-8", { fatal: true }).decode(bytesOf(...values));

test("encode gives UTF-8 bytes", () => {
  const encoder = new TextEncoder();
  assertEquals(encoder.encoding, "utf-8", "encoding");
  assertEquals(hex(encoder.encode()), "", "no input");
  assertEquals(hex(encoder.encode("")), "", "empty");
  assertEquals(hex(encoder.encode("a\0")), "61 00", "ASCII and NUL");
  assertEquals(
    hex(encoder.encode("\u00e9\u20ac")),
    "c3 a9 e2 82 ac",
    "2 and 3 bytes",
  );
  assertEquals(hex(encoder.encode("\u{1F600}")), "f0 9f 98 80", "4 bytes");
  const result = encoder.encode("abc");
  assertEquals(result instanceof Uint8Array, true, "a Uint8Array");
  assertEquals(result.buffer.byteLength, 3, "its own buffer");
});

test("encode gives U+FFFD for a lone surrogate", () => {
  const encoder = new TextEncoder();
  assertEquals(hex(encoder.encode("\uD800")), "ef bf bd", "high");
  assertEquals(hex(encoder.encode("\uDFFF")), "ef bf bd", "low");
  assertEquals(hex(encoder.encode("a\uD83Db")), "61 ef bf bd 62", "between");
  assertEquals(
    hex(encoder.encode("\uDE00\uD83D")),
    "ef bf bd ef bf bd",
    "reversed pair",
  );
  assertEquals(hex(encoder.encode("\uD83D")), "ef bf bd", "at the end");
});

test("encode converts its input to a string", () => {
  const encoder = new TextEncoder();
  assertEquals(hex(encoder.encode(12)), "31 32", "number");
  assertEquals(hex(encoder.encode(null)), "6e 75 6c 6c", "null");
  assertEquals(hex(encoder.encode({ toString: () => "x" })), "78", "object");
  assertThrows(TypeError, () => encoder.encode(Symbol("s")), "Symbol");
});

test("decode gives the string of valid UTF-8", () => {
  assertEquals(decode(0x61, 0x00, 0x62), "a\0b", "NUL inside");
  assertEquals(decode(0x7f), "\x7f", "U+007F");
  assertEquals(decode(0xc2, 0x80), "\x80", "U+0080");
  assertEquals(decode(0xdf, 0xbf), "\u07ff", "U+07FF");
  assertEquals(decode(0xe0, 0xa0, 0x80), "\u0800", "U+0800");
  assertEquals(decode(0xed, 0x9f, 0xbf), "\ud7ff", "U+D7FF");
  assertEquals(decode(0xee, 0x80, 0x80), "\ue000", "U+E000");
  assertEquals(decode(0xef, 0xbf, 0xbf), "\uffff", "U+FFFF");
  assertEquals(decode(0xf0, 0x90, 0x80, 0x80), "\u{10000}", "U+10000");
  assertEquals(decode(0xf4, 0x8f, 0xbf, 0xbf), "\u{10FFFF}", "U+10FFFF");
});

test("decode gives U+FFFD for each maximal invalid subpart", () => {
  const cases = [
    [[0x80], "\ufffd", "lone continuation"],
    [[0xbf, 0x80], "\ufffd\ufffd", "two continuations"],
    [[0xc0, 0x80], "\ufffd\ufffd", "overlong 2 bytes"],
    [[0xc1, 0xbf], "\ufffd\ufffd", "overlong C1"],
    [[0xe0, 0x80, 0x80], "\ufffd\ufffd\ufffd", "overlong 3 bytes"],
    [[0xf0, 0x80, 0x80, 0x80], "\ufffd\ufffd\ufffd\ufffd", "overlong 4 bytes"],
    [[0xed, 0xa0, 0x80], "\ufffd\ufffd\ufffd", "surrogate"],
    [[0xf4, 0x90, 0x80, 0x80], "\ufffd\ufffd\ufffd\ufffd", "above U+10FFFF"],
    [[0xf5], "\ufffd", "F5"],
    [[0xff], "\ufffd", "FF"],
    [[0xe2, 0x82], "\ufffd", "truncated 3 bytes"],
    [[0xf0, 0x9f, 0x98], "\ufffd", "truncated 4 bytes"],
    [[0x61, 0x80, 0x62], "a\ufffdb", "between"],
    [[0xc2, 0x61], "\ufffda", "lead then ASCII"],
    // The Unicode Standard's example of maximal subparts.
    [
      [
        0x61, 0xf1, 0x80, 0x80, 0xe1, 0x80, 0xc2, 0x62, 0x80, 0x63, 0x80, 0xbf,
        0x64,
      ],
      "a\ufffd\ufffd\ufffdb\ufffdc\ufffd\ufffdd",
      "Unicode example",
    ],
  ];
  for (const [bytes, expected, what] of cases) {
    assertEquals(decode(...bytes), expected, what);
    assertThrows(TypeError, () => decodeFatal(...bytes), `${what}, fatal`);
  }
});

test("decode drops one BOM at the start, unless ignoreBOM", () => {
  assertEquals(decode(0xef, 0xbb, 0xbf, 0x61), "a", "BOM");
  assertEquals(
    decode(0xef, 0xbb, 0xbf, 0xef, 0xbb, 0xbf),
    "\ufeff",
    "two BOMs",
  );
  assertEquals(decode(0x61, 0xef, 0xbb, 0xbf), "a\ufeff", "BOM later");
  assertEquals(decode(0xef, 0xbb, 0xbf), "", "only a BOM");
  const keep = new TextDecoder("utf-8", { ignoreBOM: true });
  assertEquals(
    keep.decode(bytesOf(0xef, 0xbb, 0xbf, 0x61)),
    "\ufeffa",
    "ignoreBOM",
  );
  assertEquals(decodeFatal(0xef, 0xbb, 0xbf, 0x61), "a", "fatal");
});

test("decode takes each kind of buffer", () => {
  const decoder = new TextDecoder();
  const bytes = bytesOf(0x78, 0x61, 0x62, 0x63, 0x78);
  assertEquals(decoder.decode(), "", "no input");
  assertEquals(decoder.decode(undefined), "", "undefined");
  assertEquals(decoder.decode(bytes.buffer), "xabcx", "ArrayBuffer");
  assertEquals(decoder.decode(bytes.subarray(1, 4)), "abc", "subarray");
  assertEquals(
    decoder.decode(new DataView(bytes.buffer, 1, 3)),
    "abc",
    "DataView",
  );
  assertEquals(
    decoder.decode(new Uint16Array(bytes.buffer, 0, 2)),
    "xabc",
    "Uint16Array",
  );
  const shared = new Uint8Array(new SharedArrayBuffer(2));
  shared.set([0x6f, 0x6b]);
  assertEquals(decoder.decode(shared.buffer), "ok", "SharedArrayBuffer");
  assertEquals(decoder.decode(shared), "ok", "view of a SharedArrayBuffer");
  assertEquals(decoder.decode(new Uint8Array(0)), "", "empty");
  const detached = bytesOf(0x61).buffer;
  detached.transfer();
  assertEquals(decoder.decode(detached), "", "detached");
  for (const input of [null, "abc", [0x61], {}, 1]) {
    assertThrows(
      TypeError,
      () => decoder.decode(input),
      `input ${show(input)}`,
    );
  }
});

test("TextDecoder takes the labels of UTF-8 only", () => {
  const labels = [
    "unicode-1-1-utf-8",
    "unicode11utf8",
    "unicode20utf8",
    "utf-8",
    "utf8",
    "x-unicode20utf8",
    "UTF-8",
    " \t\n\f\rUtf8\r\n ",
  ];
  for (const label of labels) {
    assertEquals(
      new TextDecoder(label).encoding,
      "utf-8",
      `label ${show(label)}`,
    );
  }
  assertEquals(new TextDecoder().encoding, "utf-8", "default");
  assertEquals(new TextDecoder(undefined).encoding, "utf-8", "undefined");
  for (const label of [
    "",
    "utf-16le",
    "latin1",
    "replacement",
    "utf-8\v",
    "\u00a0utf-8",
    null,
  ]) {
    assertThrows(
      RangeError,
      () => new TextDecoder(label),
      `label ${show(label)}`,
    );
  }
  assertThrows(TypeError, () => new TextDecoder(Symbol("utf-8")), "Symbol");
});

test("TextDecoder converts its options", () => {
  const plain = new TextDecoder("utf-8", null);
  assertEquals(plain.fatal, false, "null: fatal");
  assertEquals(plain.ignoreBOM, false, "null: ignoreBOM");
  const set = new TextDecoder("utf-8", { fatal: 1, ignoreBOM: "yes" });
  assertEquals(set.fatal, true, "fatal");
  assertEquals(set.ignoreBOM, true, "ignoreBOM");
  assertEquals(new TextDecoder("utf-8", {}).fatal, false, "empty object");
  assertThrows(TypeError, () => new TextDecoder("utf-8", 1), "a number");
  assertThrows(TypeError, () => new TextDecoder("utf-8", "fatal"), "a string");
});

test("the parts that are not given throw", () => {
  assertThrows(
    TypeError,
    () => new TextEncoder().encodeInto("a", new Uint8Array(1)),
    "encodeInto",
  );
  assertThrows(
    TypeError,
    () => new TextDecoder().decode(bytesOf(0x61), { stream: true }),
    "stream",
  );
  assertEquals(
    new TextDecoder().decode(bytesOf(0x61), { stream: false }),
    "a",
    "stream false",
  );
  assertThrows(
    TypeError,
    () => new TextDecoder().decode(bytesOf(0x61), 1),
    "options",
  );
});

test("the globals look as in a browser", () => {
  for (const name of ["TextEncoder", "TextDecoder"]) {
    const property = Object.getOwnPropertyDescriptor(globalThis, name);
    assertEquals(
      property.writable && property.configurable && !property.enumerable,
      true,
      name,
    );
    assertEquals(
      Object.prototype.toString.call(new globalThis[name]()),
      `[object ${name}]`,
      `${name} tag`,
    );
  }
  assertThrows(
    TypeError,
    () => TextDecoder.prototype.decode.call({}, bytesOf(0x61)),
    "decode on another object",
  );
});

test("a large text goes there and back", () => {
  const text = "a\u00e9\u20ac\u{1F600}".repeat(100000);
  const bytes = new TextEncoder().encode(text);
  assertEquals(bytes.length, 1000000, "bytes");
  assertEquals(new TextDecoder().decode(bytes), text, "text");
});
