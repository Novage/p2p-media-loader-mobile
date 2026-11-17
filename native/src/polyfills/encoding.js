// TextEncoder and TextDecoder of the WHATWG Encoding Standard, for UTF-8 only
// (specs/engine.md, "Globals"). The bytes are converted in C: encoding.c.
(natives) => {
  // The labels of UTF-8. A browser also knows other encodings; here they
  // throw, as an unknown label does.
  const LABELS = new Set([
    "unicode-1-1-utf-8",
    "unicode11utf8",
    "unicode20utf8",
    "utf-8",
    "utf8",
    "x-unicode20utf8",
  ]);
  const OUTER_ASCII_WHITESPACE = /^[\t\n\f\r ]+|[\t\n\f\r ]+$/g;

  class TextEncoder {
    get encoding() {
      return "utf-8";
    }

    encode(input = "") {
      return natives.utf8Encode(natives.usvString(input));
    }

    encodeInto() {
      throw new TypeError("TextEncoder.prototype.encodeInto is not supported");
    }
  }

  class TextDecoder {
    #fatal;
    #ignoreBOM;

    constructor(label = "utf-8", options = undefined) {
      const name = `${label}`
        .replace(OUTER_ASCII_WHITESPACE, "")
        .replace(/[A-Z]/g, (letter) => letter.toLowerCase());
      if (!LABELS.has(name)) {
        throw new RangeError(
          `The encoding "${label}" is not supported: only UTF-8 is`,
        );
      }
      // WebIDL reads the members in this order.
      const { fatal, ignoreBOM } = natives.dictionary(options);
      this.#fatal = Boolean(fatal);
      this.#ignoreBOM = Boolean(ignoreBOM);
    }

    get encoding() {
      return "utf-8";
    }

    get fatal() {
      return this.#fatal;
    }

    get ignoreBOM() {
      return this.#ignoreBOM;
    }

    decode(input = undefined, options = undefined) {
      const fatal = this.#fatal;
      const ignoreBOM = this.#ignoreBOM;
      if (natives.dictionary(options).stream) {
        throw new TypeError(
          "TextDecoder.prototype.decode with {stream: true} is not supported",
        );
      }
      if (input === undefined) return "";
      const isBuffer =
        input instanceof ArrayBuffer || input instanceof SharedArrayBuffer;
      if (!isBuffer && !ArrayBuffer.isView(input)) {
        throw new TypeError("The input is not an ArrayBuffer or a view of one");
      }
      // Also a detached buffer, whose length is 0.
      if (input.byteLength === 0) return "";
      const bytes = isBuffer
        ? new Uint8Array(input)
        : new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
      return natives.utf8Decode(bytes, fatal, ignoreBOM);
    }
  }

  natives.expose(TextEncoder, TextDecoder);
};
