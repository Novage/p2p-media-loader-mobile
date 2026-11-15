// Records which host globals the core reads, and which members of the host
// objects among them: what a JS engine without browser APIs must give it.
// ECMAScript's own globals are left alone; every engine has them.
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { getCallSites } from "node:util";
import { runInNewContext } from "node:vm";

type Phase = "load" | "run";

export type GlobalRead = {
  name: string;
  /** Whether the core first read it while its modules loaded, or later. */
  phase: Phase;
  /** The members it read, for the host objects whose members matter. */
  members: { name: string; phase: Phase }[];
};

// Host objects whose members a shim must provide one by one.
const OBJECTS = new Set([
  "navigator",
  "performance",
  "console",
  "process",
  "crypto",
  "localStorage",
]);
// What V8 puts in every context beyond ECMA-262: hosts such as QuickJS do not.
const V8_EXTRAS = ["console", "Intl", "WebAssembly", "Temporal"];

/** The globals and global functions a browser has, as TypeScript's DOM lib declares them. */
const browserGlobals = () => {
  const path = createRequire(import.meta.url).resolve(
    "typescript/lib/lib.dom.d.ts",
  );
  return Array.from(
    readFileSync(path, "utf8").matchAll(/^declare (?:var|function) (\w+)/gm),
    (match) => match[1],
  );
};

/**
 * Replaces each host global with a getter that records a read whose caller's
 * script `isReader` accepts. The host globals are those Node has beyond
 * ECMAScript, and those a browser has that Node lacks, which read as
 * `undefined`, so that a check for an absent API is recorded too.
 */
export function recordGlobals(isReader: (scriptName: string) => boolean) {
  let phase: Phase = "load";
  const reads = new Map<string, GlobalRead>();
  const seen = new Set<string>();
  const restores: (() => void)[] = [];

  // Call sites: this function, `note`, the getter or the proxy trap, and then
  // the code that read the global. Raw, not mapped to sources (which
  // --enable-source-maps turns on), so that the script is the bundle.
  const byReader = () =>
    isReader(getCallSites(4, { sourceMap: false })[3]?.scriptName ?? "");
  const readOf = (name: string) => {
    let read = reads.get(name);
    if (!read) reads.set(name, (read = { name, phase, members: [] }));
    return read;
  };
  // Once the core has read a name, the stack is not captured for it again.
  const note = (name: string, member?: string) => {
    const id = member === undefined ? name : `${name}.${member}`;
    if (seen.has(id) || !byReader()) return;
    seen.add(id);
    const read = readOf(name);
    if (member !== undefined) read.members.push({ name: member, phase });
  };

  const proxies = new WeakMap<object, object>();
  const proxyOf = (name: string, target: object) => {
    let proxy = proxies.get(target);
    if (!proxy) {
      proxy = new Proxy(target, {
        get(object, key) {
          if (typeof key === "string") note(name, key);
          const value: unknown = Reflect.get(object, key, object);
          // Bound to the real object, so that a method's brand check passes.
          return typeof value === "function" ? value.bind(object) : value;
        },
      });
      proxies.set(target, proxy);
    }
    return proxy;
  };

  const ecmaScript = new Set(
    Object.getOwnPropertyNames(runInNewContext("globalThis")),
  );
  for (const name of V8_EXTRAS) ecmaScript.delete(name);
  const names = new Set([
    ...Object.getOwnPropertyNames(globalThis).filter((n) => !ecmaScript.has(n)),
    ...browserGlobals().filter((n) => !(n in globalThis)),
  ]);
  for (const name of names) {
    const original = Object.getOwnPropertyDescriptor(globalThis, name);
    if (original && !original.configurable) continue;
    let value: unknown = original?.value;
    // A Node global defined by a getter is read only when the core reads it,
    // as some warn when read.
    const get = () => (original?.get ? original.get.call(globalThis) : value);
    Object.defineProperty(globalThis, name, {
      configurable: true,
      enumerable: original?.enumerable ?? false,
      get() {
        note(name);
        const current = get();
        return OBJECTS.has(name) && typeof current === "object" && current
          ? proxyOf(name, current)
          : current;
      },
      set(next: unknown) {
        if (original?.set) original.set.call(globalThis, next);
        else value = next;
      },
    });
    restores.push(() => {
      if (original) Object.defineProperty(globalThis, name, original);
      else delete (globalThis as Record<string, unknown>)[name];
    });
  }

  return {
    /** Marks the end of the core's loading: later reads are at run time. */
    loaded() {
      phase = "run";
    },
    /** Restores the globals, and returns the reads by name. */
    report(): GlobalRead[] {
      for (const restore of restores) restore();
      restores.length = 0;
      return [...reads.values()].sort((a, b) => a.name.localeCompare(b.name));
    },
  };
}
