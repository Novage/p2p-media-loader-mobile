// The player bridge (specs/engine.md, "The player bridge"): not a browser API,
// but the library's glue around the core, which gives the Kotlin Core the
// JS core's calls (specs/api.md). The platform sends its events
// (natives.playerListen, native/src/player.c); `range` is "start-end",
// inclusive, or undefined:
// - start(0, config): makes the core, with `config` (JSON) as its settings.
//   A function of the platform's in the settings (swarmId, streamIdBuilder,
//   segmentFilter, for both stream types or in mainStream or
//   secondaryStream) is {function: n}: the core gets a JS function that
//   calls natives.playerCall(n, json) with its argument, and gets
//   {value} or {error: message} as JSON. An async one (httpRequestSetup,
//   validateP2PSegment) returns a Promise: it calls
//   natives.playerCallAsync(n, id, json, bytes), with the segment's bytes for
//   the validator, and the platform answers later with answer(id, json);
// - manifest(id, url, requestedUrl, data), loadable(id, url, range) and
//   config(id): processManifest, isSegmentLoadable and getConfig, answered at
//   once with natives.playerValue(id, json). In the answer of config, a
//   function of the platform's is {function: n} again. The answer of manifest
//   is null, or the processed streams with liveDelay: where the core's
//   liveDelayFor places a live player, with playerBufferFor and
//   maxLiveLatencyFor of that placement, or null;
// - streams(id), stream(id, runtimeId) and hasSegment(id, url, range):
//   getStreams, getStream and hasSegment, answered at once in the same way;
//   with no core, null, null and false;
// - dynamicConfig(0, config): applyDynamicConfig, with `config` (JSON), whose
//   functions are markers as in start;
// - answer(id, json): the answer of the async call `id`, as playerCall's;
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
    playerCall,
    playerCallAsync,
    reportError,
  } = natives;
  // Kept now, so that a script that replaces these globals changes nothing
  // here.
  const { JSON, Number, String, Uint8Array, Headers, Request } = globalThis;
  const { toString } = Object.prototype;

  // The core, and its placement rules: undefined before start.
  let core;
  let rules;
  // The loads that have no answer yet, by id.
  const loads = new Set();
  // The core's events that the platform listens to, with their listeners.
  const forwarded = new Map();

  // The platform's functions, by number: one JS function for each, so that
  // one function of the platform's is one function for the core, which
  // keeps a swarmId function's answer by function. `numbers` maps them back.
  const functions = new Map();
  const numbers = new Map();

  // The value of a platform's answer, {value} or {error: message}, as JSON;
  // throws the error.
  const valueOf = (json) => {
    const answer = JSON.parse(json);
    if ("error" in answer) {
      throw new Error(answer.error);
    }
    return answer.value;
  };

  // The async calls that have no answer yet, by id. The answer comes in a
  // later task, so the call is kept after it is made: a throw of
  // playerCallAsync rejects the Promise and keeps nothing.
  const pending = new Map();
  let lastCall = 0;
  const callAsync = (n, argument, bytes) =>
    new Promise((resolve, reject) => {
      const id = ++lastCall;
      playerCallAsync(n, id, JSON.stringify(argument), bytes);
      pending.set(id, { resolve, reject });
    });

  // The async settings, by their keys: each gives the platform its
  // arguments, and the core the answer in its shape.
  const asyncSettings = {
    __proto__: null,
    // A request of the platform's: its URL and headers, with the core's
    // Range unless it has one; null keeps the core's request.
    httpRequestSetup:
      (n) => async (segmentUrl, segmentByteRange, signal, requestByteRange) => {
        const request = await callAsync(n, {
          segmentUrl,
          segmentByteRange,
          requestByteRange,
        });
        if (request === null) {
          return undefined;
        }
        const headers = new Headers(request.headers);
        if (requestByteRange !== undefined && !headers.has("Range")) {
          const { start, end } = requestByteRange;
          headers.set("Range", `bytes=${start}-${end ?? ""}`);
        }
        return new Request(request.url, { headers, signal });
      },
    validateP2PSegment: (n) => (url, byteRange, data) =>
      callAsync(n, { url, byteRange }, data),
  };

  // The function of the platform's n, of the setting `key`.
  const functionOf = (n, key) => {
    let call = functions.get(n);
    if (call === undefined) {
      call =
        asyncSettings[key]?.(n) ??
        // JSON has no undefined: a builder's undefined is null.
        ((argument) =>
          valueOf(playerCall(n, JSON.stringify(argument))) ?? undefined);
      functions.set(n, call);
      numbers.set(call, n);
    }
    return call;
  };

  // JSON.parse and JSON.stringify of settings: a {function: n} marker is the
  // platform's function n, and back.
  const withFunctions = (key, value) =>
    value !== null &&
    typeof value === "object" &&
    typeof value.function === "number"
      ? functionOf(value.function, key)
      : value;
  const withMarkers = (key, value) =>
    numbers.has(value) ? { function: numbers.get(value) } : value;

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
              ...JSON.parse(config, withFunctions),
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
      playerValue(id, JSON.stringify(core?.getConfig() ?? null, withMarkers));
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
      core?.applyDynamicConfig(JSON.parse(config, withFunctions));
    },

    answer(id, json) {
      const call = pending.get(id);
      if (call !== undefined) {
        pending.delete(id);
        try {
          call.resolve(valueOf(json));
        } catch (error) {
          call.reject(error);
        }
      }
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
