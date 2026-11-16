// DOMException (src/polyfills/dom_exception.js).

test("a DOMException has a name, a message and a code", () => {
  const error = new DOMException("Request aborted", "AbortError");
  assertEquals(error.name, "AbortError", "name");
  assertEquals(error.message, "Request aborted", "message");
  assertEquals(error.code, 20, "code");
  assertEquals(String(error), "AbortError: Request aborted", "toString");
  assertEquals(error instanceof DOMException, true, "instanceof DOMException");
  assertEquals(error instanceof Error, true, "instanceof Error");
  assertEquals(typeof error.stack, "string", "stack");
  assertEquals(Object.hasOwn(error, "message"), false, "no own message");
  assertEquals(
    Object.prototype.toString.call(error),
    "[object DOMException]",
    "tag",
  );
  const global = Object.getOwnPropertyDescriptor(globalThis, "DOMException");
  assertEquals(
    global.writable && global.configurable && !global.enumerable,
    true,
    "the global",
  );
});

test("the stack starts at the caller", () => {
  function makeError() {
    return new DOMException("m", "AbortError");
  }
  const first = makeError().stack.split("\n")[0];
  assertEquals(first.includes("makeError"), true, `first frame ${show(first)}`);
});

test("the defaults and the conversion of the arguments", () => {
  const plain = new DOMException();
  assertEquals(plain.name, "Error", "default name");
  assertEquals(plain.message, "", "default message");
  assertEquals(plain.code, 0, "code of Error");
  assertEquals(
    new DOMException(undefined, undefined).name,
    "Error",
    "undefined name",
  );
  const converted = new DOMException(12, null);
  assertEquals(converted.message, "12", "number message");
  assertEquals(converted.name, "null", "null name");
  assertThrows(
    TypeError,
    () => new DOMException(Symbol("m")),
    "Symbol message",
  );
  assertThrows(
    TypeError,
    () => new DOMException("m", Symbol("n")),
    "Symbol name",
  );
});

test("the legacy codes", () => {
  const codes = [
    ["IndexSizeError", 1],
    ["InvalidCharacterError", 5],
    ["NotFoundError", 8],
    ["NotSupportedError", 9],
    ["InvalidStateError", 11],
    ["SyntaxError", 12],
    ["NetworkError", 19],
    ["AbortError", 20],
    ["QuotaExceededError", 22],
    ["TimeoutError", 23],
    ["DataCloneError", 25],
    ["NotAllowedError", 0],
    ["toString", 0],
  ];
  for (const [name, code] of codes) {
    assertEquals(new DOMException("", name).code, code, name);
  }
});

test("the legacy constants", () => {
  assertEquals(DOMException.INDEX_SIZE_ERR, 1, "first");
  assertEquals(DOMException.ABORT_ERR, 20, "ABORT_ERR");
  assertEquals(DOMException.TIMEOUT_ERR, 23, "TIMEOUT_ERR");
  assertEquals(DOMException.DATA_CLONE_ERR, 25, "last");
  assertEquals(new DOMException().INVALID_CHARACTER_ERR, 5, "on the prototype");
  for (const owner of [DOMException, DOMException.prototype]) {
    const property = Object.getOwnPropertyDescriptor(owner, "ABORT_ERR");
    assertEquals(
      property.enumerable && !property.writable && !property.configurable,
      true,
      "attributes",
    );
  }
});

test("the getters check their object", () => {
  const getter = Object.getOwnPropertyDescriptor(
    DOMException.prototype,
    "name",
  ).get;
  assertThrows(TypeError, () => getter.call({}), "name of another object");
});
