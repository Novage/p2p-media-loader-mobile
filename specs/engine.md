# The JS engine

The library runs the web core in QuickJS (see the proposal
[js-core-host.md](proposals/js-core-host.md) for why). This spec describes the
engine that hosts it: its thread, its loop of tasks, and the rules for the
code that calls it.

## Where the code is

| Part                                    | Code                                                     |
| --------------------------------------- | -------------------------------------------------------- |
| QuickJS, Bellard's release as released  | `native/third_party/quickjs/` (version in its README.md) |
| The engine: the loop, tasks, evaluation | `native/src/engine.h`, `native/src/engine.c`             |
| The JNI glue, nothing else              | `native/src/jni.c`                                       |
| The engine for Kotlin                   | `library/src/androidMain/…/engine/JsEngine.kt`           |

The engine is plain C, so that iOS calls the same code through cinterop, and
native libraries such as libdatachannel call it with no JNI. The C code and
QuickJS make one shared library, `libp2pml.so`.

## One engine, one thread

An engine has one QuickJS runtime and one thread. The platform creates the
thread (on Android, a `Thread` with a 2 MiB stack) and calls the engine's
blocking `run` on it. `run` creates the runtime on that thread, so that
QuickJS measures its stack from there (it stops JS at 1 MiB of stack, its
default), runs the loop until the engine is closed, then frees the runtime
and returns.

Only the engine thread touches the runtime. A call that needs the runtime
from another thread posts a task. In Kotlin, a call that needs the runtime
throws `IllegalStateException` on any other thread.

## The loop

The engine runs posted tasks one at a time, in the order they were posted.
Any thread posts a task; posting returns false once the engine is closed, and
the task then stays with the caller.

After each task, the engine runs QuickJS's Promise jobs until none is left,
before the next task: as a browser runs microtasks after a task. So a Promise
that a task resolves has run its reactions before the next task starts.

## Closing

Any thread closes the engine, also a task of the engine. Closing does not
wait, so the app's main thread never waits for JS:

1. The engine takes no more tasks.
2. JS that still runs, such as an endless loop, stops with an error that JS
   cannot catch. A script that Kotlin evaluates during the close, or after
   it, throws `JsEngineClosedException`, also when it completed or failed.
   The current task finishes. After it, no JS runs, Promise jobs included.

   QuickJS checks for the close only from time to time, so JS that runs, or
   starts while another thread closes the engine, can run for a short time
   after `close` returns. So a function that the engine gives to JS checks
   the close itself before it uses a resource that the close releases.

3. The runtime is freed.
4. The tasks still queued are dropped; each frees what it holds, which is
   never a JS value. Then the thread ends.

Closing again does nothing.

## Errors

- A JS exception from a script that Kotlin evaluates throws `JsException`,
  with the exception converted to a string as its message, and its `stack`.
- An exception from a Kotlin task is a defect of the library. The engine logs
  it as an error and runs the next task, as a browser does with an exception
  from an event handler. A `JsEngineClosedException` is not logged: the
  close caused it. So a task lets it through, not wrapped in another
  exception. An `Error`, such as `OutOfMemoryError`, ends the engine thread,
  as in any thread of the app.

## Strings between JS and Kotlin

QuickJS gives strings to Kotlin as CESU-8, which is the JVM's modified UTF-8:
a character outside the Basic Multilingual Plane is a surrogate pair, each
half in three bytes. So JNI reads it as it is, and Android's CheckJNI does not
stop the app. One difference remains: U+0000 ends the string in Kotlin.

Kotlin gives code to QuickJS as UTF-8 bytes, followed by the NUL that QuickJS
needs.
