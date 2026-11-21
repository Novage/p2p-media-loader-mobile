// The player bridge (specs/engine.md, "The player bridge"): not a browser API,
// but the library's glue around the core, which gives the Kotlin Core the
// JS core's calls (specs/api.md). The platform sends its events
// (natives.playerListen, native/src/player.c); `range` is "start-end",
// inclusive, or undefined:
// - start(0, config): makes the core, with `config` (JSON) as its settings;
// - manifest(0, url, requestedUrl, data): processManifest;
// - loadable(id, url, range) and config(id): isSegmentLoadable and
//   getConfig, answered at once with natives.playerValue(id, json);
// - load(id, url, range): loadSegment, answered once, later, with
//   playerData(id, bytes, bandwidth, main) or playerFail(id, type, message);
// - abort(0, url, range): abortSegmentLoading;
// - playback(0, bufferAhead, rate): updatePlayback, with numbers as text;
// - destroy(0): destroy, which readies the core for the next source.
(natives) => {
  const { playerListen, playerValue, playerData, playerFail, reportError } =
    natives;
  // Kept now, so that a script that replaces these globals changes nothing
  // here.
  const { JSON, Number, String, Uint8Array } = globalThis;
  const { toString } = Object.prototype;

  // The core: undefined before start.
  let core;
  // The loads that have no answer yet, by id.
  const loads = new Set();

  // A failure as the Kotlin Core's CoreRequestError: the type of the core's
  // errors, such as "failed" or "aborted", and a message.
  const fail = (id, error) =>
    playerFail(
      id,
      String(error?.type || "failed"),
      // The core's errors have a type and often no message.
      String(error?.message || error?.type || error),
    );

  // Sends the answer to load `id`, once: a failure when sending it throws in
  // JS. (When the JVM cannot take the bytes, the engine closes, and the load
  // fails.)
  const settle = (id, send) => {
    if (loads.delete(id)) {
      try {
        send();
      } catch {
        // A constant: the error itself may not convert.
        playerFail(id, "failed", "the answer could not be sent");
      }
    }
  };

  const rangeOf = (range) => {
    if (range === undefined) {
      return undefined;
    }
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
        throw new Error("the core is already made");
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

    manifest(id, url, requestedUrl, data) {
      try {
        core?.processManifest({ url, requestedUrl, data });
      } catch (error) {
        // A manifest that the core cannot read costs P2P, not playback.
        reportError(error);
      }
    },

    loadable(id, url, range) {
      const loadable =
        core !== undefined && core.isSegmentLoadable(url, rangeOf(range));
      playerValue(id, JSON.stringify(loadable));
    },

    config(id) {
      playerValue(id, JSON.stringify(core?.getConfig() ?? null));
    },

    load(id, url, range) {
      if (core === undefined) {
        playerFail(id, "failed", "no core");
        return;
      }
      loads.add(id);
      core.loadSegment(url, { byteRange: rangeOf(range) }).then(
        (response) =>
          settle(id, () =>
            playerData(
              id,
              new Uint8Array(response.data),
              response.bandwidth,
              response.streamType === "main",
            ),
          ),
        (error) => settle(id, () => fail(id, error)),
      );
    },

    abort(id, url, range) {
      core?.abortSegmentLoading(url, rangeOf(range));
    },

    playback(id, bufferAhead, rate) {
      core?.updatePlayback({
        bufferAhead: Number(bufferAhead),
        rate: Number(rate),
      });
    },

    // As in JS, the core stays: destroy() readies it for the next source.
    destroy() {
      // The loads first: destroy() can throw.
      for (const id of loads) {
        loads.delete(id);
        playerFail(id, "aborted", "the core is destroyed");
      }
      core?.destroy();
    },
  };

  // An exception goes back to the platform as "not taken": the call fails at
  // once there.
  playerListen((id, name, ...texts) => events[name](id, ...texts));
};
