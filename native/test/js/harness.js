// The checks of the JS tests that engine_test.c runs: a failed test writes an
// error line, which fails the C test. An async test (one that returns a
// Promise) can wait for later tasks, such as the events of the fake HTTP
// client; the engine closes when the file has run and no async test is
// pending (closeEngine, from engine_test.c).
let testsRun = 0;
let testsPending = 0;
let fileRan = false;
// How long the async tests of a file may take: setAsyncLimit changes it.
let asyncLimitMs = 5000;

const closeWhenDone = () => {
  if (fileRan && testsPending === 0) closeEngine();
};

globalThis.test = (name, body) => {
  testsRun++;
  const fail = (error) =>
    console.error(
      `${name}: ${error instanceof Error ? error.message : String(error)}`,
    );
  try {
    const result = body();
    if (result instanceof Promise) {
      testsPending++;
      result.catch(fail).finally(() => {
        testsPending--;
        closeWhenDone();
      });
    }
  } catch (error) {
    fail(error);
  }
};

// Sets how long the async tests of the file may take, for a file whose tests
// need more time than the others.
globalThis.setAsyncLimit = (ms) => {
  asyncLimitMs = ms;
};

// The count of tests, in the task after the file's. The engine closes after
// this task (not in it, which would lose the count), or when the last async
// test finishes. One that has not finished within the limit fails.
globalThis.testsFinished = () => {
  fileRan = true;
  setTimeout(() => {
    console.error(`${testsPending} async test(s) did not finish`);
  }, asyncLimitMs);
  Promise.resolve().then(closeWhenDone);
  return testsRun;
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

// Checks that `body` throws a DOMException with name `name`.
globalThis.assertDomException = (body, name, what) => {
  const error = assertThrows(DOMException, body, what);
  assertEquals(error.name, name, `${what}: name`);
};

// The error that `promise` rejects with; fails if it resolves.
globalThis.rejection = async (promise) => {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  throw new Error("expected a rejection");
};

// The next event `type` of `target`.
globalThis.nextEvent = (target, type) =>
  new Promise((resolve) =>
    target.addEventListener(type, resolve, { once: true }),
  );

// Waits until the tasks posted before it have run, such as the events of a
// fake client.
globalThis.tasksRun = () => new Promise((resolve) => setTimeout(resolve, 10));

// Bytes as hex, such as "61 c3 a9", for assertEquals.
globalThis.hex = (bytes) =>
  Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(" ");
