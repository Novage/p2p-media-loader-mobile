// The JS engine: one QuickJS runtime, run by one thread, with a loop of tasks.
// Platform-neutral C: the JNI glue (jni.c) and, later, iOS call it.
// See specs/engine.md.

#ifndef P2PML_ENGINE_H
#define P2PML_ENGINE_H

#include <stdbool.h>
#include <stddef.h>

#include "quickjs.h"

typedef struct p2pml_engine p2pml_engine;

// Runs a posted task on the engine thread. The task owns `data` from here on.
typedef void p2pml_task_run(p2pml_engine *engine, void *data);

// Frees the data of a posted task that will not run, because the engine
// closed first. Called by p2pml_engine_free, on its thread, after the runtime
// is freed: so `data` holds no JS value.
typedef void p2pml_task_discard(void *data);

// The levels of the lines that the engine writes, in this order.
typedef enum {
  P2PML_LOG_DEBUG,
  P2PML_LOG_INFO,
  P2PML_LOG_WARN,
  P2PML_LOG_ERROR,
} p2pml_log_level;

// Writes a line: from console, or the report of an uncaught error. Called on
// the engine thread while p2pml_engine_run runs. `message` is CESU-8 (see
// p2pml_eval_result) and stays the engine's.
typedef void p2pml_log_write(void *data, p2pml_log_level level, const char *message);

// Returns NULL when out of memory. Any thread.
p2pml_engine *p2pml_engine_new(void);

// Runs the engine on the calling thread, which becomes the engine thread, and
// returns after p2pml_engine_close. Creates the QuickJS runtime and the
// globals (specs/engine.md, "Globals") on entry and frees them on return; the
// engine writes its lines through `log`. Returns false if the runtime or its
// globals cannot be created; the engine is then closed. Call it once.
bool p2pml_engine_run(p2pml_engine *engine, p2pml_log_write *log, void *log_data);

// Adds a task to the end of the queue. Any thread. Returns false, and keeps
// `data` with the caller, once the engine is closed or when out of memory.
bool p2pml_engine_post(p2pml_engine *engine, p2pml_task_run *run, p2pml_task_discard *discard,
                       void *data);

// Closes the engine. Any thread, also a task's; does not wait. JS that still
// runs stops with an uncatchable error, and the current task finishes; then no
// JS runs, and p2pml_engine_run frees the runtime and returns. QuickJS checks
// for the close only from time to time: JS can run for a short time after
// this returns (see specs/engine.md, "Closing").
// p2pml_engine_free discards the tasks still queued. Closing again does
// nothing.
void p2pml_engine_close(p2pml_engine *engine);

// Frees the engine: after p2pml_engine_run has returned, or when it never
// runs. Discards the tasks still queued. The caller makes sure that no post
// or close runs during or after it.
void p2pml_engine_free(p2pml_engine *engine);

// The engine's context. Engine thread only, while p2pml_engine_run runs. Its
// opaque (JS_SetContextOpaque) is the engine's own: do not change it.
JSContext *p2pml_engine_context(p2pml_engine *engine);

// The result of p2pml_engine_eval. The strings are CESU-8, which is the JVM's
// modified UTF-8 except that U+0000 ends the string; free them with
// p2pml_eval_result_free. A string that the result must have (`value` on
// success, `error` on a failure that is not `closed`) is NULL only when out
// of memory.
typedef struct {
  // On success: the completion value converted to a string, as `${value}`
  // does (a Symbol cannot be converted).
  char *value;
  // On failure: the exception converted in the same way, such as
  // "TypeError: x".
  char *error;
  // On failure: the exception's stack, or NULL when it has none.
  char *stack;
  // On failure: the engine is closed. The close stopped the script, the
  // script did not start, or it completed or failed before the close; the
  // strings are NULL.
  bool closed;
} p2pml_eval_result;

// Evaluates a script in the global scope. Engine thread only. `code` must
// end with a NUL at code[length], as QuickJS requires. Returns true on
// success; its exception goes to the result, not to the log. Promise jobs
// that the script queues run after the current task.
// Once the engine is closed, it fails with `closed`, also when the script
// completed.
bool p2pml_engine_eval(p2pml_engine *engine, const char *code, size_t length, const char *file_name,
                       p2pml_eval_result *result);

void p2pml_eval_result_free(p2pml_eval_result *result);

#endif
