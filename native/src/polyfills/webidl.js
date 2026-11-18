// WebIDL helpers that the polyfills after this one share through the natives,
// and the HTTP token test. A DOMString needs no helper: a template literal
// converts as WebIDL does (a Symbol throws).
(natives) => {
  // An HTTP token (RFC 9110): a header name, a method or a subprotocol.
  const TOKEN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/;
  natives.isHttpToken = (text) => TOKEN.test(text);

  // WebIDL's object test: a function is an object too.
  natives.isObject = (value) =>
    (typeof value === "object" && value !== null) ||
    typeof value === "function";

  // A USVString: a DOMString with U+FFFD for each lone surrogate.
  natives.usvString = (value) => `${value}`.toWellFormed();

  // An unsigned long: ToNumber, then modulo 2^32 (NaN and Infinity are 0).
  natives.unsignedLong = (value) => +value >>> 0;

  // An unsigned short: ToNumber, then modulo 2^16 (NaN and Infinity are 0).
  natives.unsignedShort = (value) => (+value >>> 0) & 0xffff;

  // A [Clamp] unsigned short: NaN is 0; then into 0-65535; then the nearest
  // integer, a half to the even one.
  natives.clampedUnsignedShort = (value) => {
    const number = +value;
    if (Number.isNaN(number)) return 0;
    const clamped = Math.min(Math.max(number, 0), 0xffff);
    const floor = Math.floor(clamped);
    const fraction = clamped - floor;
    if (fraction > 0.5 || (fraction === 0.5 && floor % 2 === 1))
      return floor + 1;
    return floor;
  };

  // WebIDL counts the arguments: a missing required one throws. The caller
  // passes its `arguments.length`.
  natives.requireArguments = (count, needed, name) => {
    if (count < needed) {
      throw new TypeError(
        `${name}: ${needed} argument${needed > 1 ? "s" : ""} required`,
      );
    }
  };

  // A dictionary argument: undefined and null give the defaults; anything
  // else that is not an object throws.
  natives.dictionary = (value) => {
    if (value === undefined || value === null) return {};
    if (!natives.isObject(value)) {
      throw new TypeError("The options are not an object");
    }
    return value;
  };

  // A sequence of [key, value] pairs or a record, as the init of
  // URLSearchParams and of Headers: a list of pairs, each key and value
  // converted with `convert`. `name` is the interface's, for the errors.
  natives.pairsOf = (init, name, convert) => {
    if (typeof init[Symbol.iterator] === "function") {
      const pairs = [];
      for (const pair of init) {
        if (!natives.isObject(pair)) {
          throw new TypeError(`${name}: a pair is not a sequence`);
        }
        const items = [...pair];
        if (items.length !== 2) {
          throw new TypeError(`${name}: a pair must have 2 items`);
        }
        pairs.push([convert(items[0]), convert(items[1])]);
      }
      return pairs;
    }
    // WebIDL's record: a later key that converts to the same string takes the
    // place of the earlier one's value.
    const record = new Map();
    for (const key of Reflect.ownKeys(init)) {
      const property = Object.getOwnPropertyDescriptor(init, key);
      if (property?.enumerable) record.set(convert(key), convert(init[key]));
    }
    return [...record];
  };

  // As WebIDL: the iterator of a pair iterable is its entries.
  natives.pairIterable = (type) => {
    Object.defineProperty(type.prototype, Symbol.iterator, {
      value: type.prototype.entries,
      writable: true,
      configurable: true,
    });
  };

  // Interface constants as WebIDL has them: on the class and on its
  // prototype, enumerable, not writable and not configurable.
  natives.defineConstants = (type, constants) => {
    for (const [name, value] of Object.entries(constants)) {
      const constant = { value, enumerable: true };
      Object.defineProperty(type, name, constant);
      Object.defineProperty(type.prototype, name, constant);
    }
  };

  // Makes classes globals as a browser has them: writable, configurable and
  // not enumerable, with the name as the toStringTag of the instances.
  natives.expose = (...types) => {
    for (const type of types) {
      Object.defineProperty(type.prototype, Symbol.toStringTag, {
        value: type.name,
        configurable: true,
      });
      Object.defineProperty(globalThis, type.name, {
        value: type,
        writable: true,
        configurable: true,
      });
    }
  };

  // Makes functions operations of the global object, as WebIDL defines them:
  // writable, enumerable and configurable.
  natives.exposeOperations = (...operations) => {
    for (const operation of operations) {
      Object.defineProperty(globalThis, operation.name, {
        value: operation,
        writable: true,
        enumerable: true,
        configurable: true,
      });
    }
  };
};
