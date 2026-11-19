// The player bridge (specs/engine.md, "The player bridge"): not a browser API,
// but the library's glue around the core. The platform's player integration
// sends its events (natives.playerListen, native/src/player.c):
// - start(config): makes the core, with `config` (JSON) as its settings;
// - manifest(url, requestedUrl, text): gives the core a manifest;
// - load(id, url, range): loads a segment through the core; `range` is
//   "start-end", inclusive, or undefined;
// - abort(id): stops a load;
// - stop(): destroys the core.
// Each load gets one answer: natives.playerMiss when the core does not load
// it, playerData with the whole segment, or playerFail.
(natives) => {
  const { playerListen, playerMiss, playerData, playerFail, reportError } =
    natives;
  // Kept now, so that a script that replaces these globals changes nothing
  // here.
  const { JSON, Map, Number, String, AbortController, Uint8Array } = globalThis;
  const { toString } = Object.prototype;

  // The core: undefined before start and after stop.
  let core;
  // The loads that have no answer yet: their AbortController, by id.
  const loads = new Map();

  // The type of the core's errors, such as "failed", else the message.
  const messageOf = (error) => String(error?.type || error?.message || error);

  // Sends the answer to load `id`, once: a failure when sending it throws in
  // JS. (When the JVM cannot take the bytes, the engine closes, and the load
  // becomes a miss.)
  const settle = (id, send) => {
    if (loads.delete(id)) {
      try {
        send();
      } catch (error) {
        playerFail(id, messageOf(error));
      }
    }
  };

  const parseRange = (range) => {
    const dash = range.indexOf("-");
    return {
      start: Number(range.slice(0, dash)),
      end: Number(range.slice(dash + 1)),
    };
  };

  const events = {
    __proto__: null,

    start(id, config) {
      if (core !== undefined) {
        throw new Error("the core is already started");
      }
      // QuickJS loads the module in this task's Promise jobs, which run
      // before the next task: the next event finds the core.
      import("p2p-media-loader-core")
        .then(({ Core, hlsManifestParser, debug }) => {
          // The console does no substitution: the value goes in place of
          // its directive, as a browser's console would show it.
          // A value that cannot be converted (no prototype) still logs.
          for (const directive of ["O", "o", "s", "d"]) {
            debug.formatters[directive] = (value) => {
              try {
                return String(value);
              } catch {
                return toString.call(value);
              }
            };
          }
          core = new Core({
            ...JSON.parse(config),
            manifestParsers: [hlsManifestParser],
          });
        })
        .catch(reportError);
    },

    manifest(id, url, requestedUrl, text) {
      try {
        core?.processManifest({ url, requestedUrl, data: text });
      } catch (error) {
        // A manifest that the core cannot read costs P2P, not playback.
        reportError(error);
      }
    },

    load(id, url, range) {
      const byteRange = range === undefined ? undefined : parseRange(range);
      if (core === undefined || !core.isSegmentLoadable(url, byteRange)) {
        playerMiss(id);
        return;
      }
      const controller = new AbortController();
      loads.set(id, controller);
      core.loadSegment(url, { byteRange, signal: controller.signal }).then(
        (result) =>
          settle(id, () => playerData(id, new Uint8Array(result.data))),
        (error) => settle(id, () => playerFail(id, messageOf(error))),
      );
    },

    abort(id) {
      const controller = loads.get(id);
      if (controller !== undefined) {
        loads.delete(id);
        controller.abort();
        playerFail(id, "aborted");
      }
    },

    stop() {
      // The loads first: destroy() can throw.
      for (const [id, controller] of loads) {
        loads.delete(id);
        controller.abort();
        playerFail(id, "stopped");
      }
      const destroyed = core;
      core = undefined;
      destroyed?.destroy();
    },
  };

  // An exception goes back to the platform as "not taken": for a load, the
  // player then loads the segment itself.
  playerListen((id, name, ...texts) => events[name](id, ...texts));
};
