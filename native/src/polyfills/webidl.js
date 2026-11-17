// WebIDL helpers that the polyfills after this one share through the natives.
// A DOMString needs no helper: a template literal converts as WebIDL does (a
// Symbol throws).
(natives) => {
  // WebIDL's object test: a function is an object too.
  natives.isObject = (value) =>
    (typeof value === "object" && value !== null) ||
    typeof value === "function";

  // A USVString: a DOMString with U+FFFD for each lone surrogate.
  natives.usvString = (value) => `${value}`.toWellFormed();

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
};
