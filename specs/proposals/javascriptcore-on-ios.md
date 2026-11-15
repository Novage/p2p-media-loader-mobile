# Proposal: JavaScriptCore as the engine on iOS

Not a spec. This proposal describes a design the library does not have, and
the measurement that would justify building it. See "`specs/proposals/` is
the one exception" in [`AGENTS.md`](../../AGENTS.md).

## The gap

The library runs the web core in QuickJS on both platforms (see
[js-core-host.md](js-core-host.md)). On iOS, that adds the engine to every app
that ships the library: about 0.75 MB of code (Bellard's QuickJS, stripped, on
Android arm64; a similar size is expected on iOS), and the engine's memory in
the app's process. JavaScriptCore is part of iOS. Its code is in the system's
shared cache of libraries, whose code pages all processes share, so an app
that uses it adds no engine code. Whether it also uses less memory is not
known: each app has its own JavaScriptCore virtual machine and heap, which can
be larger than QuickJS's.

## The design

The iOS library runs the core in JavaScriptCore through its public C API, in
place of QuickJS.

- **Loading the core.** JavaScriptCore's public API cannot evaluate an ES
  module: `evaluateJSScript:` and the module loader are in
  `JSContextPrivate.h`, and apps may use only public APIs (App Review
  Guideline 2.5.1, and Apple's Developer Program License Agreement). The
  core's bundle (checked on alpha.7) has no `import`, no `import()` and no
  `import.meta`, and one `export { … }` statement, at its end. The host
  replaces that statement with code that returns the exported names, wraps the
  bundle in a function in strict mode, and evaluates it with
  `JSEvaluateScript`. The published bundle stays the one the web ships. A
  bundle of another shape stops the host with an error.
- **The binding.** JavaScriptCore's public C API (`JSBase.h`, `JSValueRef.h`,
  `JSObjectRef.h`, `JSTypedArray.h`) has what the host needs: functions kept
  as values (`JSValueProtect`), Promises (`JSObjectMakeDeferredPromise`), and
  array buffers that wrap native memory with no copy
  (`JSObjectMakeArrayBufferWithBytesNoCopy`, `JSObjectGetArrayBufferBytesPtr`).
  JavaScriptCore runs Promise jobs itself when a call into JS returns, so the
  host has no job queue to run (to confirm).
- **What is written a second time.** The binding, and the native shims that
  call it: timers, `fetch`, `WebSocket` and WebRTC. The JS polyfills work in
  both engines. If the shims are written against an engine interface of our
  own, in Kotlin, with one implementation for QuickJS and one for
  JavaScriptCore, only the binding is written twice.

## The measurement that decides

On the Mac, with each engine's interpreter (`jsc --useJIT=false`;
JavaScriptCore has no JIT in an iOS app), JavaScriptCore was faster than
Bellard's QuickJS at text parsing (110–125 ms against 182–206 ms) and regular
expressions (288–297 ms against 371–373 ms), and slower at arithmetic
(967–1107 ms against 543–585 ms). See "The speed of QuickJS, measured" in
[js-core-host.md](js-core-host.md).

The decision needs numbers from an iPhone, which do not need the second
binding: in the iOS step, a test app runs the core's bundle, loaded as above,
and the same benchmarks, once in JavaScriptCore with `JSEvaluateScript` and
once in QuickJS, and measures each engine's CPU time, the memory of the
process after the core has loaded, and the app's size.

JavaScriptCore is adopted on iOS when, on that iPhone, it saves at least
5 MB of memory or uses at least 25% less CPU time for the core's work than
QuickJS, and the cost of a second binding is accepted. These limits are
starting values. Until then, QuickJS runs on iOS as on Android.
