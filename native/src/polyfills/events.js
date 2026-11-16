// Event, EventTarget, AbortController and AbortSignal of the DOM Standard
// (specs/engine.md, "Globals"). An EventTarget here has no parent: an event
// is dispatched at its target only, with no capture or bubble path. For the
// polyfills after this one: natives.defineEventHandler, natives.fireEvent and
// natives.onAbort.
(natives) => {
  const PHASES = {
    NONE: 0,
    CAPTURING_PHASE: 1,
    AT_TARGET: 2,
    BUBBLING_PHASE: 3,
  };
  // AbortSignal's constructor is not public.
  const CONSTRUCT = Symbol("AbortSignal");
  // Kept now, so that a script that replaces these globals changes nothing.
  const now = performance.now.bind(performance);
  const { DOMException } = globalThis;

  // The dispatch state of an event; throws a TypeError for a non-Event.
  let stateOf;
  // Dispatches an event that the platform fires: its isTrusted is true.
  let dispatchTrusted;

  class Event {
    #state;

    constructor(type, init = undefined) {
      if (arguments.length === 0) {
        throw new TypeError("Event: the type is required");
      }
      // A template literal converts as WebIDL does: a Symbol throws.
      const name = `${type}`;
      const { bubbles, cancelable, composed } = natives.dictionary(init);
      this.#state = {
        type: name,
        bubbles: Boolean(bubbles),
        cancelable: Boolean(cancelable),
        composed: Boolean(composed),
        timeStamp: now(),
        // Fired by the polyfills (natives.fireEvent).
        trusted: false,
        target: null,
        // Not null only while the event is being dispatched.
        currentTarget: null,
        canceled: false,
        // stopPropagation: at the target, the capture listeners end it.
        propagationStopped: false,
        stopped: false,
        inPassiveListener: false,
      };
    }

    static {
      stateOf = (event) => event.#state;
    }

    get type() {
      return this.#state.type;
    }

    get target() {
      return this.#state.target;
    }

    get currentTarget() {
      return this.#state.currentTarget;
    }

    get eventPhase() {
      return this.#state.currentTarget === null
        ? PHASES.NONE
        : PHASES.AT_TARGET;
    }

    get bubbles() {
      return this.#state.bubbles;
    }

    get cancelable() {
      return this.#state.cancelable;
    }

    get composed() {
      return this.#state.composed;
    }

    get defaultPrevented() {
      return this.#state.canceled;
    }

    get isTrusted() {
      return this.#state.trusted;
    }

    get timeStamp() {
      return this.#state.timeStamp;
    }

    preventDefault() {
      if (this.#state.cancelable && !this.#state.inPassiveListener) {
        this.#state.canceled = true;
      }
    }

    // No other target follows: nothing to stop.
    stopPropagation() {
      this.#state.propagationStopped = true;
    }

    stopImmediatePropagation() {
      this.#state.propagationStopped = true;
      this.#state.stopped = true;
    }

    composedPath() {
      const target = this.#state.currentTarget;
      return target === null ? [] : [target];
    }
  }

  // addEventListener's options: a dictionary, or a boolean for capture.
  const listenerOptions = (options) =>
    options === undefined ||
    typeof options === "object" ||
    typeof options === "function"
      ? natives.dictionary(options)
      : { capture: options };

  // A callback: an object or a function (WebIDL's EventListener), or null.
  const checkCallback = (callback) => {
    if (
      typeof callback !== "object" &&
      typeof callback !== "function" &&
      callback !== undefined
    ) {
      throw new TypeError("The listener is not an object");
    }
    return callback ?? null;
  };

  class EventTarget {
    // The listeners of each type, in the order added.
    #listeners = new Map();

    addEventListener(type, callback, options = undefined) {
      const name = `${type}`;
      const listener = checkCallback(callback);
      const { capture, once, passive, signal } = listenerOptions(options);
      if (signal !== undefined && !(signal instanceof AbortSignal)) {
        throw new TypeError("The signal is not an AbortSignal");
      }
      if (
        listener === null ||
        signal?.aborted ||
        this.#find(name, listener, Boolean(capture))
      ) {
        return;
      }
      const entry = {
        callback: listener,
        capture: Boolean(capture),
        once: Boolean(once),
        passive: Boolean(passive),
        removed: false,
      };
      const list = this.#listeners.get(name);
      if (list) list.push(entry);
      else this.#listeners.set(name, [entry]);
      if (signal) {
        entry.forget = natives.onAbort(signal, () => this.#remove(name, entry));
      }
    }

    removeEventListener(type, callback, options = undefined) {
      const name = `${type}`;
      const listener = checkCallback(callback);
      const capture = Boolean(listenerOptions(options).capture);
      const entry = this.#find(name, listener, capture);
      if (entry) this.#remove(name, entry);
    }

    #find(type, callback, capture) {
      return this.#listeners
        .get(type)
        ?.find(
          (entry) => entry.callback === callback && entry.capture === capture,
        );
    }

    // Also for an entry already removed, as by once and then its signal.
    #remove(type, entry) {
      if (entry.removed) return;
      entry.removed = true;
      // The signal no longer keeps the target and the callback.
      entry.forget?.();
      const list = this.#listeners.get(type);
      list.splice(list.indexOf(entry), 1);
    }

    dispatchEvent(event) {
      return this.#dispatch(event, false);
    }

    static {
      dispatchTrusted = (target, event) => target.#dispatch(event, true);
    }

    // Checks the target and the event before it changes either.
    #dispatch(event, trusted) {
      const state = stateOf(event);
      const listeners = this.#listeners;
      if (state.currentTarget !== null) {
        throw new DOMException(
          "The event is already being dispatched",
          "InvalidStateError",
        );
      }
      state.trusted = trusted;
      state.target = this;
      state.currentTarget = this;
      // Capture listeners first, as at the target of a browser's dispatch.
      for (const capture of [true, false]) {
        if (state.propagationStopped) break;
        // A listener added during a pass does not run in that pass.
        const list = [...(listeners.get(state.type) ?? [])];
        for (const entry of list) {
          if (state.stopped) break;
          if (entry.capture !== capture || entry.removed) continue;
          if (entry.once) this.#remove(state.type, entry);
          state.inPassiveListener = entry.passive;
          try {
            const callback = entry.callback;
            if (typeof callback === "function") {
              callback.call(this, event);
            } else {
              const handleEvent = callback.handleEvent;
              if (typeof handleEvent !== "function") {
                throw new TypeError("The listener has no handleEvent function");
              }
              handleEvent.call(callback, event);
            }
          } catch (error) {
            // As a browser: reported, and the dispatch goes on.
            natives.reportError(error);
          }
          state.inPassiveListener = false;
        }
      }
      state.propagationStopped = false;
      state.stopped = false;
      state.currentTarget = null;
      return !state.canceled;
    }
  }

  // The private members of AbortSignal that the code outside it uses.
  let algorithmsOf;
  let signalAbort;

  class AbortSignal extends EventTarget {
    // Aborted when not undefined: an abort with undefined gives the default.
    #reason = undefined;
    // Run before the abort event: for the listeners' signal option, and for
    // the polyfills after this one (natives.onAbort).
    #algorithms = new Set();

    constructor(key) {
      if (key !== CONSTRUCT) throw new TypeError("Illegal constructor");
      super();
    }

    static {
      algorithmsOf = (signal) => signal.#algorithms;
      signalAbort = (signal, reason) => signal.#abort(reason);
    }

    // A new signal has no listeners: its abort event reaches nothing.
    static abort(reason = undefined) {
      const signal = new AbortSignal(CONSTRUCT);
      signal.#abort(reason);
      return signal;
    }

    get aborted() {
      return this.#reason !== undefined;
    }

    get reason() {
      return this.#reason;
    }

    throwIfAborted() {
      if (this.#reason !== undefined) throw this.#reason;
    }

    #abort(reason) {
      if (this.#reason !== undefined) return;
      // Chrome's text.
      this.#reason =
        reason === undefined
          ? new DOMException("signal is aborted without reason", "AbortError")
          : reason;
      const algorithms = [...this.#algorithms];
      this.#algorithms.clear();
      for (const algorithm of algorithms) algorithm();
      natives.fireEvent(this, new Event("abort"));
    }
  }

  class AbortController {
    #signal = new AbortSignal(CONSTRUCT);

    get signal() {
      return this.#signal;
    }

    abort(reason = undefined) {
      signalAbort(this.#signal, reason);
    }
  }

  // An on<type> property as a browser has it: a function is kept, anything
  // else gives null. The first function adds a listener, whose place stays
  // while the property changes; null removes it. A return of false cancels
  // the event.
  natives.defineEventHandler = (prototype, type) => {
    // For each target: the handler, and the listener that calls it.
    const handlers = new WeakMap();
    Object.defineProperty(prototype, `on${type}`, {
      get() {
        return handlers.get(this)?.handler ?? null;
      },
      set(value) {
        const handler = typeof value === "function" ? value : null;
        let entry = handlers.get(this);
        if (!entry) {
          const created = { handler: null };
          created.listener = function (event) {
            if (created.handler.call(this, event) === false) {
              event.preventDefault();
            }
          };
          handlers.set(this, (entry = created));
        }
        if (handler && !entry.handler) {
          EventTarget.prototype.addEventListener.call(
            this,
            type,
            entry.listener,
          );
        } else if (!handler && entry.handler) {
          EventTarget.prototype.removeEventListener.call(
            this,
            type,
            entry.listener,
          );
        }
        entry.handler = handler;
      },
      enumerable: true,
      configurable: true,
    });
  };

  natives.fireEvent = dispatchTrusted;

  // Runs `algorithm` when the signal aborts, before its abort event. Returns
  // a function that removes it.
  natives.onAbort = (signal, algorithm) => {
    const algorithms = algorithmsOf(signal);
    // As the DOM Standard: an aborted signal runs no more algorithms.
    if (signal.aborted) return () => false;
    algorithms.add(algorithm);
    return () => algorithms.delete(algorithm);
  };

  natives.defineEventHandler(AbortSignal.prototype, "abort");
  natives.defineConstants(Event, PHASES);
  natives.expose(Event, EventTarget, AbortSignal, AbortController);
};
