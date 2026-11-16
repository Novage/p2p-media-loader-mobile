// WebIDL helpers that the polyfills after this one share through the natives.
(natives) => {
  // A dictionary argument: undefined and null give the defaults; anything
  // else that is not an object throws.
  natives.dictionary = (value) => {
    if (value === undefined || value === null) return {};
    if (typeof value !== "object" && typeof value !== "function") {
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
