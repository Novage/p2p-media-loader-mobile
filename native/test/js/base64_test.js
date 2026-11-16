// btoa and atob (src/polyfills/base64.js).

// All 256 code units, from 0 to 255.
const ALL_BYTES = String.fromCharCode(
  ...Array.from({ length: 256 }, (_, i) => i),
);
const ALL_BYTES_BASE64 =
  "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4" +
  "OTo7PD0+P0BBQkNERUZHSElKS0xNTk9QUVJTVFVWV1hZWltcXV5fYGFiY2RlZmdoaWprbG1ub3Bx" +
  "cnN0dXZ3eHl6e3x9fn+AgYKDhIWGh4iJiouMjY6PkJGSk5SVlpeYmZqbnJ2en6ChoqOkpaanqKmq" +
  "q6ytrq+wsbKztLW2t7i5uru8vb6/wMHCw8TFxsfIycrLzM3Oz9DR0tPU1dbX2Nna29zd3t/g4eLj" +
  "5OXm5+jp6uvs7e7v8PHy8/T19vf4+fr7/P3+/w==";

const assertInvalidCharacter = (body, what) => {
  const error = assertThrows(DOMException, body, what);
  assertEquals(error.name, "InvalidCharacterError", `${what}: name`);
};

test("btoa encodes each code unit as a byte", () => {
  assertEquals(btoa(""), "", "empty");
  assertEquals(btoa("a"), "YQ==", "1 byte");
  assertEquals(btoa("ab"), "YWI=", "2 bytes");
  assertEquals(btoa("abc"), "YWJj", "3 bytes");
  assertEquals(btoa(ALL_BYTES), ALL_BYTES_BASE64, "all bytes");
  assertEquals(btoa(12), "MTI=", "number");
  assertEquals(btoa(null), "bnVsbA==", "null");
});

test("btoa takes only code units up to U+00FF", () => {
  assertInvalidCharacter(() => btoa("\u0100"), "U+0100");
  assertInvalidCharacter(() => btoa("a\u20acb"), "euro sign");
  assertInvalidCharacter(() => btoa("\ud83d"), "lone surrogate");
  assertThrows(TypeError, () => btoa(Symbol("s")), "Symbol");
  assertThrows(TypeError, () => btoa(), "no argument");
});

test("atob decodes valid base64", () => {
  assertEquals(atob(""), "", "empty");
  assertEquals(atob("YWJj"), "abc", "a full group");
  assertEquals(atob("YWI="), "ab", "2 bytes with padding");
  assertEquals(atob("YWI"), "ab", "2 bytes without padding");
  assertEquals(atob("YQ=="), "a", "1 byte with padding");
  assertEquals(atob("YQ"), "a", "1 byte without padding");
  assertEquals(atob(ALL_BYTES_BASE64), ALL_BYTES, "all bytes");
  assertEquals(atob(null), "\x9e\xe9e", "null is the text null");
});

test("atob skips ASCII whitespace anywhere", () => {
  assertEquals(atob(" \tY\nW\fJ\rj "), "abc", "around and inside");
  assertEquals(atob("YQ= ="), "a", "between the padding");
  assertEquals(atob("YQ==\n"), "a", "after the padding");
});

test("atob ignores the extra bits of a final partial group", () => {
  assertEquals(atob("YR=="), "a", "with padding");
  assertEquals(atob("YR"), "a", "without padding");
  assertEquals(atob("YWK="), "ab", "2 bytes");
});

test("atob rejects what the forgiving decode rejects", () => {
  const invalid = [
    ["YWJjZ", "length 1 mod 4"],
    ["Y", "1 character"],
    ["YQ=", "one = for a 1-byte group"],
    ["YQ===", "three ="],
    ["Y===", "a group of 1 with padding"],
    ["YQ==YQ==", "data after the padding"],
    ["=", "only padding"],
    ["YW-j", "- of base64url"],
    ["YW_j", "_ of base64url"],
    ["YW*j", "*"],
    ["YW\u00ffj", "U+00FF"],
    ["YW\vJj", "vertical tab"],
    ["YW\u00a0Jj", "NBSP"],
    [undefined, "undefined has 9 characters"],
  ];
  for (const [input, what] of invalid) {
    assertInvalidCharacter(() => atob(input), what);
  }
  assertThrows(TypeError, () => atob(Symbol("s")), "Symbol");
  assertThrows(TypeError, () => atob(), "no argument");
});

test("replaced DOMException and SyntaxError globals change nothing", () => {
  const { DOMException: SavedDOMException, SyntaxError: SavedSyntaxError } =
    globalThis;
  try {
    globalThis.DOMException = function Fake() {};
    globalThis.SyntaxError = function Other() {};
    const error = assertThrows(SavedDOMException, () => atob("Y"), "atob");
    assertEquals(error.name, "InvalidCharacterError", "name");
  } finally {
    globalThis.DOMException = SavedDOMException;
    globalThis.SyntaxError = SavedSyntaxError;
  }
});

test("an error of the argument's conversion is thrown as it is", () => {
  const argument = {
    toString() {
      throw new SyntaxError("own");
    },
  };
  const error = assertThrows(SyntaxError, () => atob(argument), "atob");
  assertEquals(error.message, "own", "message");
});

test("1 MB goes there and back", () => {
  const text = ALL_BYTES.repeat(4096);
  const encoded = btoa(text);
  assertEquals(encoded.length, 1398104, "encoded length");
  assertEquals(atob(encoded) === text, true, "decoded");
});

test("the globals look as in a browser", () => {
  for (const name of ["btoa", "atob"]) {
    const { value, writable, enumerable, configurable } =
      Object.getOwnPropertyDescriptor(globalThis, name);
    assertEquals(writable && enumerable && configurable, true, name);
    assertEquals(value.name, name, `${name}.name`);
    assertEquals(value.length, 1, `${name}.length`);
    assertEquals(Object.hasOwn(value, "prototype"), false, `${name}.prototype`);
    assertThrows(TypeError, () => new value("a"), `new ${name}`);
  }
});
