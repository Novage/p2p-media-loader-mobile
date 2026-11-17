// btoa and atob of the HTML Standard (specs/engine.md, "Globals"), over
// QuickJS's Uint8Array base64 functions.
(natives) => {
  // Kept now, so that a script that replaces these globals changes nothing
  // here.
  const { DOMException, SyntaxError } = globalThis;
  // Fewer arguments than the engine's limit for one call.
  const CHUNK = 8192;

  const invalidCharacter = (message) =>
    new DOMException(message, "InvalidCharacterError");

  // Methods, as WebIDL operations: not constructors, with no prototype. Not
  // arrows: they read `arguments`.
  const { btoa, atob } = {
    btoa(data) {
      natives.requireArguments(arguments.length, 1, "btoa");
      const text = `${data}`;
      const bytes = new Uint8Array(text.length);
      for (let i = 0; i < text.length; i++) {
        const code = text.charCodeAt(i);
        if (code > 0xff) {
          throw invalidCharacter(
            "The string to be encoded contains characters outside of the Latin1 range.",
          );
        }
        bytes[i] = code;
      }
      return bytes.toBase64();
    },

    // The HTML Standard's forgiving-base64 decode: QuickJS's "loose" mode
    // skips ASCII whitespace, takes a final group without its padding, and
    // ignores the extra bits of that group.
    atob(data) {
      natives.requireArguments(arguments.length, 1, "atob");
      // Before the try: an error of the conversion is not a decode error.
      const text = `${data}`;
      let bytes;
      try {
        bytes = Uint8Array.fromBase64(text, { lastChunkHandling: "loose" });
      } catch (error) {
        if (!(error instanceof SyntaxError)) throw error;
        throw invalidCharacter(
          "The string to be decoded is not correctly encoded.",
        );
      }
      let decoded = "";
      for (let i = 0; i < bytes.length; i += CHUNK) {
        // apply, not a spread: QuickJS reads a typed array's elements directly.
        decoded += String.fromCharCode.apply(
          null,
          bytes.subarray(i, i + CHUNK),
        );
      }
      return decoded;
    },
  };

  // As WebIDL defines operations of the global object.
  for (const operation of [btoa, atob]) {
    Object.defineProperty(globalThis, operation.name, {
      value: operation,
      writable: true,
      enumerable: true,
      configurable: true,
    });
  }
};
