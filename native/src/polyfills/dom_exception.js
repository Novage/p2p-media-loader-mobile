// DOMException of WebIDL (specs/engine.md, "Globals").
(natives) => {
  // The legacy constants in the order of their values (1, 2, ...), each with
  // the name that has it as its code; null where no name has it.
  const LEGACY = [
    ["INDEX_SIZE_ERR", "IndexSizeError"],
    ["DOMSTRING_SIZE_ERR", null],
    ["HIERARCHY_REQUEST_ERR", "HierarchyRequestError"],
    ["WRONG_DOCUMENT_ERR", "WrongDocumentError"],
    ["INVALID_CHARACTER_ERR", "InvalidCharacterError"],
    ["NO_DATA_ALLOWED_ERR", null],
    ["NO_MODIFICATION_ALLOWED_ERR", "NoModificationAllowedError"],
    ["NOT_FOUND_ERR", "NotFoundError"],
    ["NOT_SUPPORTED_ERR", "NotSupportedError"],
    ["INUSE_ATTRIBUTE_ERR", "InUseAttributeError"],
    ["INVALID_STATE_ERR", "InvalidStateError"],
    ["SYNTAX_ERR", "SyntaxError"],
    ["INVALID_MODIFICATION_ERR", "InvalidModificationError"],
    ["NAMESPACE_ERR", "NamespaceError"],
    ["INVALID_ACCESS_ERR", "InvalidAccessError"],
    ["VALIDATION_ERR", null],
    ["TYPE_MISMATCH_ERR", "TypeMismatchError"],
    ["SECURITY_ERR", "SecurityError"],
    ["NETWORK_ERR", "NetworkError"],
    ["ABORT_ERR", "AbortError"],
    ["URL_MISMATCH_ERR", "URLMismatchError"],
    ["QUOTA_EXCEEDED_ERR", "QuotaExceededError"],
    ["TIMEOUT_ERR", "TimeoutError"],
    ["INVALID_NODE_TYPE_ERR", "InvalidNodeTypeError"],
    ["DATA_CLONE_ERR", "DataCloneError"],
  ];
  const CONSTANTS = {};
  // The codes of the names that have one; any other name has 0.
  const CODES = new Map();
  LEGACY.forEach(([constant, name], index) => {
    CONSTANTS[constant] = index + 1;
    if (name) CODES.set(name, index + 1);
  });

  // An Error, so that it has a stack and reports as one. As WebIDL has them,
  // name and message are getters on the prototype: super() gets no message.
  class DOMException extends Error {
    #name;
    #message;

    // Template literals convert as WebIDL does: a Symbol throws.
    constructor(message = "", name = "Error") {
      super();
      // The stack starts at the caller, as in a browser.
      this.stack = this.stack.replace(/^[^\n]*\n/, "");
      this.#message = `${message}`;
      this.#name = `${name}`;
    }

    get name() {
      return this.#name;
    }

    get message() {
      return this.#message;
    }

    get code() {
      return CODES.get(this.#name) ?? 0;
    }
  }

  natives.defineConstants(DOMException, CONSTANTS);
  natives.expose(DOMException);
};
