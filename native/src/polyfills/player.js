// The player bridge (specs/engine.md, "The player bridge"): not a browser API,
// but the library's glue around the core, which gives the Kotlin Core the
// JS core's calls (specs/api.md). The platform sends its events
// (natives.playerListen, native/src/player.c); `range` is "start-end",
// inclusive, or undefined:
// - start(0, config): makes the core, with `config` (JSON) as its settings;
// - manifest(id, url, requestedUrl, data), loadable(id, url, range) and
//   config(id): processManifest, isSegmentLoadable and getConfig, answered at
//   once with natives.playerValue(id, json). The answer of manifest is null,
//   or the processed streams with liveDelay: where the core's liveDelayFor
//   places a live player, with playerBufferFor and maxLiveLatencyFor of that
//   placement, or null;
// - streams(id), stream(id, runtimeId) and hasSegment(id, url, range):
//   getStreams, getStream and hasSegment, answered at once in the same way;
//   with no core, null, null and false;
// - dynamicConfig(0, config): applyDynamicConfig, with `config` (JSON);
// - manifestResponseUrl(0, url): setManifestResponseUrl;
// - load(id, url, range): loadSegment, answered once, later, with
//   playerData(id, bytes, bandwidth, main) or playerFail(id, type, message);
// - abort(0, url, range): abortSegmentLoading;
// - playback(0, bufferAhead, rate): updatePlayback, with numbers as text;
// - destroy(0): destroy, which readies the core for the next source;
// - listen(0, event) and unlisten(0, event): from then on, the core's event
//   `event` (an onXxx name of its CoreEventMap) goes, or no longer goes, to
//   the platform with natives.playerCoreEvent(event, json): its details object,
//   or, for the two chunk events, their arguments by name. An error or a
//   warning is {name, type, message, timestamp, cause}: JSON would drop its
//   message, which is not enumerable.
(natives) => {
  const {
    playerListen,
    playerValue,
    playerData,
    playerFail,
    playerCoreEvent,
    reportError,
  } = natives;
  // Kept now, so that a script that replaces these globals changes nothing
  // here.
  const { JSON, Number, String, Uint8Array } = globalThis;
  const { toString } = Object.prototype;

  // The core, and its placement rules: undefined before start.
  let core;
  let rules;
  // The loads that have no answer yet, by id.
  const loads = new Set();
  // The core's events that the platform listens to, with their listeners.
  const forwarded = new Map();

  // An error as data; undefined stays undefined.
  const errorOf = (error) => {
    if (error === undefined) {
      return undefined;
    }
    if (error === null || typeof error !== "object") {
      return { name: "Error", message: String(error) };
    }
    return {
      name: String(error.name ?? "Error"),
      type: error.type,
      message: String(error.message ?? ""),
      timestamp: error.timestamp,
      cause: errorOf(error.cause),
    };
  };

  // JSON.stringify drops a key whose value is undefined.
  const dataOf = (details) => ({
    ...details,
    error: errorOf(details.error),
    warning: errorOf(details.warning),
  });

  // The two chunk events have their details as arguments, to spare the core
  // an object for each chunk.
  const positional = {
    __proto__: null,
    onChunkDownloaded: (
      bytesLength,
      downloadSource,
      peerId,
      streamType,
      infoHash,
    ) => ({ bytesLength, downloadSource, peerId, streamType, infoHash }),
    onChunkUploaded: (bytesLength, peerId, streamType, infoHash) => ({
      bytesLength,
      peerId,
      streamType,
      infoHash,
    }),
  };

  // A listener of event `name` that gives it to the platform. An exception
  // is reported, never thrown into the core.
  const forwarderOf = (name) => {
    const toData = positional[name] ?? dataOf;
    return (...args) => {
      try {
        playerCoreEvent(name, JSON.stringify(toData(...args)));
      } catch (error) {
        reportError(error);
      }
    };
  };

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
        .then(
          ({
            Core,
            hlsManifestParser,
            debug,
            liveDelayFor,
            playerBufferFor,
            maxLiveLatencyFor,
          }) => {
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
            rules = { liveDelayFor, playerBufferFor, maxLiveLatencyFor };
            // The events that the platform listened to before the core.
            for (const [name, listener] of forwarded) {
              core.addEventListener(name, listener);
            }
          },
        )
        .catch(reportError);
    },

    manifest(id, url, requestedUrl, data) {
      let answer = null;
      try {
        const processed = core?.processManifest({ url, requestedUrl, data });
        if (processed !== undefined) {
          const target = rules.liveDelayFor(processed);
          answer = {
            streams: processed.streams,
            liveDelay:
              target === undefined
                ? null
                : {
                    delay: target.delay,
                    segment: target.segment,
                    playerBuffer: rules.playerBufferFor(target),
                    maxLiveLatency: rules.maxLiveLatencyFor(target),
                  },
          };
        }
      } catch (error) {
        // A manifest that the core cannot read costs P2P, not playback.
        reportError(error);
      }
      playerValue(id, JSON.stringify(answer));
    },

    loadable(id, url, range) {
      const loadable =
        core !== undefined && core.isSegmentLoadable(url, rangeOf(range));
      playerValue(id, JSON.stringify(loadable));
    },

    config(id) {
      playerValue(id, JSON.stringify(core?.getConfig() ?? null));
    },

    streams(id) {
      playerValue(id, JSON.stringify(core?.getStreams() ?? null));
    },

    stream(id, runtimeId) {
      playerValue(id, JSON.stringify(core?.getStream(runtimeId) ?? null));
    },

    hasSegment(id, url, range) {
      const has = core !== undefined && core.hasSegment(url, rangeOf(range));
      playerValue(id, JSON.stringify(has));
    },

    dynamicConfig(id, config) {
      core?.applyDynamicConfig(JSON.parse(config));
    },

    manifestResponseUrl(id, url) {
      core?.setManifestResponseUrl(url);
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

    // The core keeps its listeners across destroy(), as in JS.
    listen(id, name) {
      if (!forwarded.has(name)) {
        const listener = forwarderOf(name);
        forwarded.set(name, listener);
        core?.addEventListener(name, listener);
      }
    },

    unlisten(id, name) {
      const listener = forwarded.get(name);
      if (listener !== undefined) {
        forwarded.delete(name);
        core?.removeEventListener(name, listener);
      }
    },
  };

  // An exception goes back to the platform as "not taken": the call fails at
  // once there.
  playerListen((id, name, ...texts) => events[name](id, ...texts));
};
