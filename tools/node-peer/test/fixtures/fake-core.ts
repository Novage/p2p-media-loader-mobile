// Stands in for the core's bundle in test/record-globals.test.ts.
export const absent = typeof indexedDB;
const win = window;
const log = console.debug;

export function run() {
  performance.now();
  return [navigator.userAgent, typeof win.setTimeout, log];
}
