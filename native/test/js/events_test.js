// Event, EventTarget, AbortController and AbortSignal
// (src/polyfills/events.js). A listener's exception is tested in C
// (engine_test.c), which can read the log.

test("an Event has its type and options", () => {
  const before = performance.now();
  const event = new Event("ping", { bubbles: 1, cancelable: "yes" });
  assertEquals(event.type, "ping", "type");
  assertEquals(event.bubbles, true, "bubbles");
  assertEquals(event.cancelable, true, "cancelable");
  assertEquals(event.composed, false, "composed");
  assertEquals(event.isTrusted, false, "isTrusted");
  assertEquals(event.target, null, "target");
  assertEquals(event.currentTarget, null, "currentTarget");
  assertEquals(event.eventPhase, Event.NONE, "eventPhase");
  assertEquals(event.defaultPrevented, false, "defaultPrevented");
  assertEquals(event.timeStamp >= before, true, "timeStamp");
  assertEquals(event.composedPath().length, 0, "composedPath");
  assertEquals(new Event(1).type, "1", "converted type");
  assertThrows(TypeError, () => new Event(), "no type");
  assertThrows(TypeError, () => new Event("x", 1), "options not an object");
  assertEquals(
    [
      Event.NONE,
      Event.CAPTURING_PHASE,
      Event.AT_TARGET,
      Event.BUBBLING_PHASE,
    ].join(),
    "0,1,2,3",
    "phases",
  );
  assertEquals(event.AT_TARGET, 2, "phase on the prototype");
});

test("preventDefault works only on a cancelable event", () => {
  const plain = new Event("x");
  plain.preventDefault();
  assertEquals(plain.defaultPrevented, false, "not cancelable");
  const cancelable = new Event("x", { cancelable: true });
  cancelable.preventDefault();
  assertEquals(cancelable.defaultPrevented, true, "cancelable");
});

test("dispatch sets the target and runs the listeners in order", () => {
  const target = new EventTarget();
  const calls = [];
  const event = new Event("x");
  target.addEventListener("x", function (e) {
    calls.push(
      `a ${this === target} ${e === event} ${e.target === target} ${e.currentTarget === target}`,
    );
    calls.push(`phase ${e.eventPhase} path ${e.composedPath()[0] === target}`);
  });
  target.addEventListener("x", () => calls.push("b"));
  target.addEventListener("y", () => calls.push("other type"));
  assertEquals(target.dispatchEvent(event), true, "result");
  assertEquals(
    calls.join("|"),
    "a true true true true|phase 2 path true|b",
    "calls",
  );
  assertEquals(event.eventPhase, Event.NONE, "phase after");
  assertEquals(event.currentTarget, null, "currentTarget after");
  assertEquals(event.target, target, "target after");
  assertEquals(event.isTrusted, false, "isTrusted");
});

test("capture listeners run first", () => {
  const target = new EventTarget();
  const calls = [];
  target.addEventListener("x", () => calls.push("bubble"));
  target.addEventListener("x", () => calls.push("capture"), true);
  target.addEventListener("x", () => calls.push("capture option"), {
    capture: true,
  });
  target.dispatchEvent(new Event("x"));
  assertEquals(calls.join(), "capture,capture option,bubble", "order");
});

test("a listener is added once for its type, callback and capture", () => {
  const target = new EventTarget();
  let count = 0;
  const listener = () => count++;
  target.addEventListener("x", listener);
  target.addEventListener("x", listener);
  target.addEventListener("x", listener, { once: true });
  target.addEventListener("x", listener, true);
  target.dispatchEvent(new Event("x"));
  assertEquals(count, 2, "bubble and capture");
  target.removeEventListener("x", listener, { capture: true });
  target.dispatchEvent(new Event("x"));
  assertEquals(count, 3, "after removing the capture listener");
  target.removeEventListener("x", listener);
  target.dispatchEvent(new Event("x"));
  assertEquals(count, 3, "after removing both");
  target.addEventListener("x", null);
  target.addEventListener("x", undefined);
  target.removeEventListener("x", null);
  assertThrows(TypeError, () => target.addEventListener("x", 1), "a number");
});

test("the list is copied at the start of a dispatch", () => {
  const target = new EventTarget();
  const calls = [];
  const second = () => calls.push("second");
  target.addEventListener("x", () => {
    calls.push("first");
    target.removeEventListener("x", second);
    target.addEventListener("x", () => calls.push("added"));
  });
  target.addEventListener("x", second);
  target.dispatchEvent(new Event("x"));
  assertEquals(calls.join(), "first", "removed and added");
  calls.length = 0;
  target.dispatchEvent(new Event("x"));
  assertEquals(calls.join(), "first,added", "next dispatch");
});

test("once removes the listener before it runs", () => {
  const target = new EventTarget();
  let count = 0;
  target.addEventListener(
    "x",
    () => {
      count++;
      target.dispatchEvent(new Event("x"));
    },
    { once: true },
  );
  target.dispatchEvent(new Event("x"));
  assertEquals(count, 1, "runs once, also when dispatched again from itself");
});

test("a passive listener cannot cancel", () => {
  const target = new EventTarget();
  target.addEventListener("x", (event) => event.preventDefault(), {
    passive: true,
  });
  assertEquals(
    target.dispatchEvent(new Event("x", { cancelable: true })),
    true,
    "passive",
  );
  target.addEventListener("x", (event) => event.preventDefault());
  const event = new Event("x", { cancelable: true });
  assertEquals(target.dispatchEvent(event), false, "not passive");
  assertEquals(event.defaultPrevented, true, "defaultPrevented");
});

test("an object listener's handleEvent is looked up at each call", () => {
  const target = new EventTarget();
  const calls = [];
  const listener = {
    handleEvent(event) {
      calls.push(`first ${this === listener} ${event.type}`);
    },
  };
  target.addEventListener("x", listener);
  target.dispatchEvent(new Event("x"));
  listener.handleEvent = () => calls.push("second");
  target.dispatchEvent(new Event("x"));
  assertEquals(calls.join(), "first true x,second", "calls");
});

test("stopImmediatePropagation ends the dispatch", () => {
  const target = new EventTarget();
  const calls = [];
  target.addEventListener("x", (event) => {
    calls.push("first");
    event.stopImmediatePropagation();
  });
  target.addEventListener("x", () => calls.push("second"));
  target.dispatchEvent(new Event("x"));
  target.dispatchEvent(new Event("x"));
  assertEquals(calls.join(), "first,first", "calls");
});

test("an event cannot be dispatched while it is dispatched", () => {
  const target = new EventTarget();
  const event = new Event("x");
  let name;
  target.addEventListener("x", () => {
    name = assertThrows(DOMException, () => target.dispatchEvent(event)).name;
  });
  target.dispatchEvent(event);
  assertEquals(name, "InvalidStateError", "error");
  assertEquals(target.dispatchEvent(event), true, "again after the dispatch");
  assertThrows(
    TypeError,
    () => target.dispatchEvent({ type: "x" }),
    "not an Event",
  );
});

test("stopPropagation in a capture listener ends the dispatch at its pass", () => {
  const target = new EventTarget();
  const calls = [];
  target.addEventListener(
    "x",
    (event) => {
      calls.push("capture 1");
      event.stopPropagation();
    },
    true,
  );
  target.addEventListener("x", () => calls.push("capture 2"), true);
  target.addEventListener("x", () => calls.push("bubble"));
  target.dispatchEvent(new Event("x"));
  assertEquals(calls.join(), "capture 1,capture 2", "calls");
  calls.length = 0;
  target.dispatchEvent(new Event("x"));
  assertEquals(calls.join(), "capture 1,capture 2", "a new event");
});

test("each pass copies the list", () => {
  const target = new EventTarget();
  const calls = [];
  target.addEventListener(
    "x",
    () => {
      calls.push("capture");
      target.addEventListener("x", () => calls.push("added"));
    },
    true,
  );
  target.dispatchEvent(new Event("x"));
  assertEquals(calls.join(), "capture,added", "added by the capture pass");
});

test("a failed dispatch leaves the event as it was", () => {
  const target = new EventTarget();
  const event = new Event("x");
  assertThrows(
    TypeError,
    () => EventTarget.prototype.dispatchEvent.call({}, event),
    "not a target",
  );
  assertEquals(event.eventPhase, Event.NONE, "phase");
  assertEquals(event.currentTarget, null, "currentTarget");
  assertEquals(target.dispatchEvent(event), true, "dispatched after");
});

test("dispatchEvent makes a trusted event untrusted", () => {
  const controller = new AbortController();
  let fired;
  controller.signal.addEventListener("abort", (event) => {
    fired = event;
  });
  controller.abort();
  assertEquals(fired.isTrusted, true, "fired by the signal");
  const target = new EventTarget();
  let seen;
  target.addEventListener("abort", (event) => {
    seen = event.isTrusted;
  });
  target.dispatchEvent(fired);
  assertEquals(seen, false, "dispatched by JS");
});

test("replaced globals do not change the polyfill", () => {
  const { performance: savedPerformance, DOMException: SavedDOMException } =
    globalThis;
  try {
    globalThis.performance = undefined;
    globalThis.DOMException = function Fake() {};
    assertEquals(typeof new Event("x").timeStamp, "number", "timeStamp");
    const controller = new AbortController();
    controller.abort();
    assertEquals(
      controller.signal.reason instanceof SavedDOMException,
      true,
      "reason",
    );
  } finally {
    globalThis.performance = savedPerformance;
    globalThis.DOMException = SavedDOMException;
  }
});

test("a signal removes its listener", () => {
  const target = new EventTarget();
  const controller = new AbortController();
  let count = 0;
  target.addEventListener("x", () => count++, { signal: controller.signal });
  target.dispatchEvent(new Event("x"));
  controller.abort();
  target.dispatchEvent(new Event("x"));
  assertEquals(count, 1, "removed by the abort");
  target.addEventListener("x", () => count++, { signal: AbortSignal.abort() });
  target.dispatchEvent(new Event("x"));
  assertEquals(count, 1, "not added with an aborted signal");
  assertThrows(
    TypeError,
    () => target.addEventListener("x", () => {}, { signal: {} }),
    "not a signal",
  );
  assertThrows(
    TypeError,
    () => target.addEventListener("x", () => {}, { signal: null }),
    "null signal",
  );
  // once, then the abort: the other listeners stay.
  const later = new AbortController();
  const calls = [];
  target.addEventListener("y", () => calls.push("once"), {
    once: true,
    signal: later.signal,
  });
  target.addEventListener("y", () => calls.push("stays"));
  target.dispatchEvent(new Event("y"));
  later.abort();
  target.dispatchEvent(new Event("y"));
  assertEquals(calls.join(), "once,stays,stays", "calls");
});

test("an on<type> handler keeps its place", () => {
  const signal = new AbortController().signal;
  assertEquals(signal.onabort, null, "default");
  const calls = [];
  signal.addEventListener("abort", () => calls.push("before"));
  signal.onabort = () => calls.push("handler 1");
  signal.addEventListener("abort", () => calls.push("after"));
  signal.onabort = function () {
    calls.push(`handler 2 ${this === signal}`);
  };
  signal.dispatchEvent(new Event("abort"));
  assertEquals(
    calls.join(),
    "before,handler 2 true,after",
    "replaced in its place",
  );
  calls.length = 0;
  signal.onabort = "not a function";
  assertEquals(signal.onabort, null, "not a function gives null");
  signal.onabort = () => calls.push("handler 3");
  signal.dispatchEvent(new Event("abort"));
  assertEquals(
    calls.join(),
    "before,after,handler 3",
    "null removed it; added at the end again",
  );
  assertEquals("onabort" in new EventTarget(), false, "only where defined");
});

test("a handler that returns false cancels the event", () => {
  const signal = new AbortController().signal;
  signal.onabort = () => false;
  const event = new Event("abort", { cancelable: true });
  assertEquals(signal.dispatchEvent(event), false, "result");
  signal.onabort = () => true;
  assertEquals(
    signal.dispatchEvent(new Event("abort", { cancelable: true })),
    true,
    "true",
  );
});

test("abort sets the reason and fires one trusted abort event", () => {
  const controller = new AbortController();
  const signal = controller.signal;
  const calls = [];
  signal.addEventListener("abort", (event) => {
    calls.push(
      `listener ${signal.aborted} ${event.isTrusted} ${event.target === signal}`,
    );
  });
  signal.onabort = () => calls.push("handler");
  assertEquals(signal.aborted, false, "before");
  assertEquals(signal.reason, undefined, "reason before");
  signal.throwIfAborted();
  controller.abort();
  controller.abort("again");
  assertEquals(calls.join(), "listener true true true,handler", "calls");
  assertEquals(signal.reason instanceof DOMException, true, "default reason");
  assertEquals(signal.reason.name, "AbortError", "reason name");
  assertEquals(
    signal.reason.message,
    "signal is aborted without reason",
    "reason message",
  );
  assertEquals(
    assertThrows(DOMException, () => signal.throwIfAborted()),
    signal.reason,
    "throwIfAborted",
  );
});

test("the abort steps run before the abort listeners", () => {
  const controller = new AbortController();
  const target = new EventTarget();
  const calls = [];
  target.addEventListener("x", () => calls.push("removed listener"), {
    signal: controller.signal,
  });
  controller.signal.addEventListener("abort", () => {
    target.dispatchEvent(new Event("x"));
    calls.push("abort listener");
  });
  controller.abort();
  assertEquals(calls.join(), "abort listener", "calls");
});

test("abort keeps a given reason", () => {
  const reason = new Error("stop");
  const controller = new AbortController();
  controller.abort(reason);
  assertEquals(controller.signal.reason, reason, "error");
  const nullReason = new AbortController();
  nullReason.abort(null);
  assertEquals(nullReason.signal.reason, null, "null is a reason");
  const undefinedReason = new AbortController();
  undefinedReason.abort(undefined);
  assertEquals(
    undefinedReason.signal.reason.name,
    "AbortError",
    "undefined gives the default",
  );
});

test("AbortSignal.abort gives an aborted signal", () => {
  const signal = AbortSignal.abort();
  assertEquals(signal.aborted, true, "aborted");
  assertEquals(signal.reason.name, "AbortError", "default reason");
  assertEquals(AbortSignal.abort(7).reason, 7, "given reason");
  assertEquals(signal instanceof EventTarget, true, "an EventTarget");
});

test("what AbortSignal does not have", () => {
  assertThrows(TypeError, () => new AbortSignal(), "constructor");
  assertEquals(AbortSignal.timeout, undefined, "timeout");
  assertEquals(AbortSignal.any, undefined, "any");
});

test("the globals look as in a browser", () => {
  for (const name of [
    "Event",
    "EventTarget",
    "AbortController",
    "AbortSignal",
  ]) {
    const property = Object.getOwnPropertyDescriptor(globalThis, name);
    assertEquals(
      property.writable && property.configurable && !property.enumerable,
      true,
      name,
    );
  }
  assertEquals(
    Object.prototype.toString.call(new EventTarget()),
    "[object EventTarget]",
    "tag",
  );
  assertEquals(
    Object.prototype.toString.call(new AbortController().signal),
    "[object AbortSignal]",
    "signal tag",
  );
  assertThrows(
    TypeError,
    () => EventTarget.prototype.dispatchEvent.call({}, new Event("x")),
    "dispatch on another object",
  );
});
