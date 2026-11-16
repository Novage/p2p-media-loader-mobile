// The checks of the JS tests that engine_test.c runs: a failed test writes an
// error line, which fails the C test; testsRun counts the tests.
globalThis.testsRun = 0;

globalThis.test = (name, body) => {
  testsRun++;
  try {
    body();
  } catch (error) {
    console.error(
      `${name}: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
};

// Shows a value in a failure: a string in quotes, with each character
// outside printable ASCII (U+FFFD, a BOM, a lone surrogate) as \uXXXX.
const show = (value) =>
  typeof value === "string"
    ? `"${value.replace(/[^\x20-\x7e]/g, (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, "0")}`)}"`
    : String(value);

globalThis.assertEquals = (actual, expected, what = "value") => {
  if (!Object.is(actual, expected)) {
    throw new Error(`${what}: expected ${show(expected)}, got ${show(actual)}`);
  }
};

// Returns the error, for more checks.
globalThis.assertThrows = (type, body, what = "call") => {
  try {
    body();
  } catch (error) {
    if (!(error instanceof type)) {
      throw new Error(`${what}: expected a ${type.name}, got ${show(error)}`);
    }
    return error;
  }
  throw new Error(`${what}: expected a ${type.name}, nothing thrown`);
};

// Bytes as hex, such as "61 c3 a9", for assertEquals.
globalThis.hex = (bytes) =>
  Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(" ");
