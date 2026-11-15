import assert from "node:assert/strict";
import { test } from "node:test";
import { recordGlobals } from "../src/record-globals.ts";

test("the recorder lists the host globals the reader reads, and no others", async () => {
  Object.assign(globalThis, { window: globalThis });
  const performanceDescriptor = Object.getOwnPropertyDescriptor(
    globalThis,
    "performance",
  );
  const recorder = recordGlobals((script) => script.endsWith("fake-core.ts"));
  try {
    // Read by another caller before the core reads it: not the core's.
    navigator.language;
    const core = await import("./fixtures/fake-core.ts");
    recorder.loaded();
    core.run();
    // Read by another caller after the core: still not the core's.
    queueMicrotask(() => undefined);
  } finally {
    const reads = recorder.report();
    delete (globalThis as Record<string, unknown>).window;

    assert.deepEqual(reads, [
      {
        name: "console",
        phase: "load",
        members: [{ name: "debug", phase: "load" }],
      },
      { name: "indexedDB", phase: "load", members: [] },
      {
        name: "navigator",
        phase: "run",
        members: [{ name: "userAgent", phase: "run" }],
      },
      {
        name: "performance",
        phase: "run",
        members: [{ name: "now", phase: "run" }],
      },
      { name: "setTimeout", phase: "run", members: [] },
      { name: "window", phase: "load", members: [] },
    ]);
    assert.equal("indexedDB" in globalThis, false, "the globals are restored");
    assert.deepEqual(
      Object.getOwnPropertyDescriptor(globalThis, "performance"),
      performanceDescriptor,
    );
  }
});
