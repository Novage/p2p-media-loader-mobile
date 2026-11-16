#include "engine.h"

#include "encoding.h"
#include "polyfills.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define NS_PER_MS INT64_C(1000000)
#define NS_PER_S INT64_C(1000000000)
#define NO_DEADLINE INT64_MAX
// HTML: a timer set by a timer nested deeper than this waits at least
// MIN_DELAY_MS.
#define MIN_DELAY_NESTING 5
#define MIN_DELAY_MS 4
#define COUNT(array) ((int)(sizeof(array) / sizeof((array)[0])))

typedef struct task {
  struct task *next;
  p2pml_task_run *run;
  p2pml_task_discard *discard;
  void *data;
} task;

// A setTimeout call. The list is sorted by deadline, then by call.
typedef struct timer {
  struct timer *next;
  int64_t deadline;
  int32_t id;
  // HTML's timer nesting level, up to MIN_DELAY_NESTING + 1.
  int nesting;
  JSValue callback;
  int argc;
  JSValue argv[];
} timer;

// A rejected Promise that had no handler when it was rejected.
typedef struct rejection {
  struct rejection *next;
  JSValue promise;
  JSValue reason;
} rejection;

struct p2pml_engine {
  // Guards the queue, and `closed` for the wait.
  pthread_mutex_t mutex;
  pthread_cond_t wake;
  task *head;
  task *tail;
  // Atomic too: the engine thread reads it without the mutex, between Promise
  // jobs and while JS runs.
  atomic_bool closed;
  // Engine thread only.
  JSRuntime *runtime;
  JSContext *context;
  p2pml_log_write *log;
  void *log_data;
  // When the engine started: performance.now() is 0 there.
  int64_t started;
  timer *timers;
  int32_t last_timer_id;
  // The nesting of the timer that runs, or 0.
  int timer_nesting;
  rejection *rejections;
};

// The monotonic clock: it does not follow changes of the wall clock, and it
// stops while the system sleeps, as Chrome's clock and the wait (wait_until)
// do. Apple's CLOCK_MONOTONIC counts the sleep; its CLOCK_UPTIME_RAW does not.
static int64_t now(void) {
  struct timespec time;
#ifdef __APPLE__
  clock_gettime(CLOCK_UPTIME_RAW, &time);
#else
  clock_gettime(CLOCK_MONOTONIC, &time);
#endif
  return (int64_t)time.tv_sec * NS_PER_S + time.tv_nsec;
}

// Apple's pthreads cannot wait on the monotonic clock: the wait takes a
// relative time instead (see wait_until).
static int init_wake(pthread_cond_t *wake) {
#ifdef __APPLE__
  return pthread_cond_init(wake, NULL);
#else
  pthread_condattr_t attributes;
  if (pthread_condattr_init(&attributes) != 0) {
    return -1;
  }
  int result = pthread_condattr_setclock(&attributes, CLOCK_MONOTONIC);
  if (result == 0) {
    result = pthread_cond_init(wake, &attributes);
  }
  pthread_condattr_destroy(&attributes);
  return result;
#endif
}

p2pml_engine *p2pml_engine_new(void) {
  p2pml_engine *engine = calloc(1, sizeof *engine);
  if (engine == NULL) {
    return NULL;
  }
  if (pthread_mutex_init(&engine->mutex, NULL) != 0) {
    free(engine);
    return NULL;
  }
  if (init_wake(&engine->wake) != 0) {
    pthread_mutex_destroy(&engine->mutex);
    free(engine);
    return NULL;
  }
  atomic_init(&engine->closed, false);
  return engine;
}

static void discard_tasks(p2pml_engine *engine) {
  pthread_mutex_lock(&engine->mutex);
  task *next = engine->head;
  engine->head = NULL;
  engine->tail = NULL;
  pthread_mutex_unlock(&engine->mutex);
  while (next != NULL) {
    task *current = next;
    next = current->next;
    current->discard(current->data);
    free(current);
  }
}

// Waits for a post or a close, until `deadline` if it is not NO_DEADLINE.
// The mutex is held. It can return early.
static void wait_until(p2pml_engine *engine, int64_t deadline) {
  if (deadline == NO_DEADLINE) {
    pthread_cond_wait(&engine->wake, &engine->mutex);
    return;
  }
#ifdef __APPLE__
  int64_t left = deadline - now();
  if (left > 0) {
    struct timespec relative = {.tv_sec = (time_t)(left / NS_PER_S), .tv_nsec = left % NS_PER_S};
    pthread_cond_timedwait_relative_np(&engine->wake, &engine->mutex, &relative);
  }
#else
  struct timespec absolute = {.tv_sec = (time_t)(deadline / NS_PER_S),
                              .tv_nsec = deadline % NS_PER_S};
  pthread_cond_timedwait(&engine->wake, &engine->mutex, &absolute);
#endif
}

// Takes the next task. Returns NULL once the engine is closed, or when no
// task is queued and a timer is due.
static task *take_task(p2pml_engine *engine) {
  int64_t deadline = engine->timers != NULL ? engine->timers->deadline : NO_DEADLINE;
  pthread_mutex_lock(&engine->mutex);
  while (!atomic_load(&engine->closed) && engine->head == NULL &&
         (deadline == NO_DEADLINE || now() < deadline)) {
    wait_until(engine, deadline);
  }
  task *next = NULL;
  if (!atomic_load(&engine->closed) && engine->head != NULL) {
    next = engine->head;
    engine->head = next->next;
    if (engine->head == NULL) {
      engine->tail = NULL;
    }
  }
  pthread_mutex_unlock(&engine->mutex);
  return next;
}

// The value converted to a string, as `${value}` does, as CESU-8 in memory of
// our own; `value` stays the caller's. Returns NULL only when out of memory.
static char *to_string(JSContext *context, JSValueConst value) {
  const char *text = JS_ToCStringLen2(context, NULL, value, true);
  if (text == NULL) {
    // The conversion threw, as for a Symbol, or an object whose toString
    // throws.
    JS_FreeValue(context, JS_GetException(context));
    return strdup("<a value that cannot be converted to a string>");
  }
  char *copy = strdup(text);
  JS_FreeCString(context, text);
  return copy;
}

// The `stack` of an exception, converted as to_string does, or NULL when it
// has none (or when out of memory); `exception` stays the caller's.
static char *stack_of(JSContext *context, JSValueConst exception) {
  if (!JS_IsObject(exception)) {
    return NULL;
  }
  JSValue stack = JS_GetPropertyStr(context, exception, "stack");
  char *text = NULL;
  if (JS_IsException(stack)) {
    JS_FreeValue(context, JS_GetException(context));
  } else if (!JS_IsUndefined(stack)) {
    text = to_string(context, stack);
  }
  JS_FreeValue(context, stack);
  return text;
}

// Adds `addition` to the end of `text`, which is in malloc's memory. On false
// (out of memory), `text` is unchanged.
static bool append(char **text, const char *addition) {
  size_t length = strlen(*text);
  size_t added = strlen(addition);
  char *grown = realloc(*text, length + added + 1);
  if (grown == NULL) {
    return false;
  }
  memcpy(grown + length, addition, added + 1);
  *text = grown;
  return true;
}

// The value as the console writes it: an Error as its string, then its stack
// on the next lines; any other value converted as to_string does. NULL when
// out of memory; `value` stays the caller's.
static char *describe(JSContext *context, JSValueConst value) {
  char *text = to_string(context, value);
  if (text == NULL || !JS_IsError(context, value)) {
    return text;
  }
  char *stack = stack_of(context, value);
  if (stack != NULL && stack[0] != '\0') {
    // QuickJS ends each line of the stack with a line break.
    size_t length = strlen(stack);
    if (stack[length - 1] == '\n') {
      stack[length - 1] = '\0';
    }
    if (!append(&text, "\n") || !append(&text, stack)) {
      free(text);
      text = NULL;
    }
  }
  free(stack);
  return text;
}

// Writes an uncaught error as `prefix` and the value, as the console writes
// it; `value` stays the caller's. Not once the engine is closed: then the
// error can be the close's.
static void report(p2pml_engine *engine, const char *prefix, JSValueConst value) {
  if (atomic_load(&engine->closed)) {
    return;
  }
  char *description = describe(engine->context, value);
  char *line = description != NULL ? strdup(prefix) : NULL;
  // Again: describe() can run JS, during which the engine can close.
  if (line != NULL && append(&line, description) && !atomic_load(&engine->closed)) {
    engine->log(engine->log_data, P2PML_LOG_ERROR, line);
  }
  free(line);
  free(description);
}

// Writes and clears the current exception, as report() does.
static void report_exception(p2pml_engine *engine) {
  JSValue exception = JS_GetException(engine->context);
  report(engine, "Uncaught ", exception);
  JS_FreeValue(engine->context, exception);
}

static void free_rejection(JSContext *context, rejection *rejected) {
  JS_FreeValue(context, rejected->promise);
  JS_FreeValue(context, rejected->reason);
  free(rejected);
}

// QuickJS calls it when a Promise is rejected with no handler, and when a
// handler is added to such a Promise. `promise` and `reason` stay QuickJS's:
// the list keeps its own copies.
static void track_rejection(JSContext *context, JSValueConst promise, JSValueConst reason,
                            JS_BOOL is_handled, void *opaque) {
  p2pml_engine *engine = opaque;
  // Nothing is reported after the close, and JS that the close stops can
  // reject many Promises.
  if (atomic_load(&engine->closed)) {
    return;
  }
  rejection **link = &engine->rejections;
  if (is_handled) {
    while (*link != NULL && !JS_StrictEq(context, (*link)->promise, promise)) {
      link = &(*link)->next;
    }
    if (*link != NULL) {
      rejection *handled = *link;
      *link = handled->next;
      free_rejection(context, handled);
    }
    return;
  }
  rejection *added = malloc(sizeof *added);
  if (added == NULL) {
    // Out of memory: not reported.
    return;
  }
  *added = (rejection){.next = NULL,
                       .promise = JS_DupValue(context, promise),
                       .reason = JS_DupValue(context, reason)};
  while (*link != NULL) {
    link = &(*link)->next;
  }
  *link = added;
}

// Runs Promise jobs until none is left, as a browser runs microtasks after a
// task. Stops when the engine closes: then no JS runs.
static void run_jobs(p2pml_engine *engine) {
  while (!atomic_load(&engine->closed)) {
    int ran = JS_ExecutePendingJob(engine->runtime, NULL);
    if (ran == 0) {
      return;
    }
    if (ran < 0) {
      // A FinalizationRegistry callback threw, or QuickJS failed, such as out
      // of memory. A Promise reaction turns an exception into a rejection.
      report_exception(engine);
    }
  }
}

// After a task or a timer: the Promise jobs, then the rejections that still
// have no handler, as a browser reports them after its microtasks.
static void finish_js(p2pml_engine *engine) {
  run_jobs(engine);
  rejection *rejected;
  while ((rejected = engine->rejections) != NULL) {
    engine->rejections = rejected->next;
    report(engine, "Uncaught (in promise) ", rejected->reason);
    free_rejection(engine->context, rejected);
  }
}

static void free_timer(JSContext *context, timer *expired) {
  JS_FreeValue(context, expired->callback);
  for (int i = 0; i < expired->argc; i++) {
    JS_FreeValue(context, expired->argv[i]);
  }
  free(expired);
}

// Runs the first timer if it is due.
static void run_due_timer(p2pml_engine *engine) {
  timer *due = engine->timers;
  // Not started after the close, as a script.
  if (due == NULL || due->deadline > now() || atomic_load(&engine->closed)) {
    return;
  }
  engine->timers = due->next;
  engine->timer_nesting = due->nesting;
  // `this` is the global object, as in a browser.
  JSValue global = JS_GetGlobalObject(engine->context);
  JSValue result =
      JS_Call(engine->context, due->callback, global, due->argc, (JSValueConst *)due->argv);
  JS_FreeValue(engine->context, global);
  if (JS_IsException(result)) {
    report_exception(engine);
  }
  JS_FreeValue(engine->context, result);
  free_timer(engine->context, due);
  finish_js(engine);
  engine->timer_nesting = 0;
}

// setTimeout(callback, delay, ...args), as in a browser, except that the
// callback must be a function. The delay is converted as WebIDL's `long`
// (ToInt32); a negative delay is 0, and a delay of a deeply nested timer is
// at least MIN_DELAY_MS. QuickJS's convention: the arguments stay QuickJS's,
// and the returned value is new, for the caller to free; the timer keeps its
// own copies.
static JSValue set_timeout(JSContext *context, JSValueConst this_value, int argc,
                           JSValueConst *argv) {
  (void)this_value;
  p2pml_engine *engine = JS_GetContextOpaque(context);
  if (argc < 1 || !JS_IsFunction(context, argv[0])) {
    return JS_ThrowTypeError(context, "setTimeout: the callback is not a function");
  }
  int32_t delay = 0;
  if (argc > 1 && JS_ToInt32(context, &delay, argv[1]) != 0) {
    return JS_EXCEPTION;
  }
  int extra = argc > 2 ? argc - 2 : 0;
  timer *added = malloc(sizeof *added + (size_t)extra * sizeof(JSValue));
  if (added == NULL) {
    return JS_ThrowOutOfMemory(context);
  }
  // Positive and growing; after the largest, from 1 again.
  engine->last_timer_id = engine->last_timer_id == INT32_MAX ? 1 : engine->last_timer_id + 1;
  added->id = engine->last_timer_id;
  added->nesting = engine->timer_nesting <= MIN_DELAY_NESTING ? engine->timer_nesting + 1
                                                              : engine->timer_nesting;
  if (delay < 0) {
    delay = 0;
  }
  if (engine->timer_nesting > MIN_DELAY_NESTING && delay < MIN_DELAY_MS) {
    delay = MIN_DELAY_MS;
  }
  added->deadline = now() + delay * NS_PER_MS;
  added->callback = JS_DupValue(context, argv[0]);
  added->argc = extra;
  for (int i = 0; i < extra; i++) {
    added->argv[i] = JS_DupValue(context, argv[i + 2]);
  }
  // After the timers with the same deadline: they were set before.
  timer **link = &engine->timers;
  while (*link != NULL && (*link)->deadline <= added->deadline) {
    link = &(*link)->next;
  }
  added->next = *link;
  *link = added;
  return JS_NewInt32(context, added->id);
}

// clearTimeout(id): an unknown id does nothing. Values as setTimeout's.
static JSValue clear_timeout(JSContext *context, JSValueConst this_value, int argc,
                             JSValueConst *argv) {
  (void)this_value;
  p2pml_engine *engine = JS_GetContextOpaque(context);
  int32_t id = 0;
  if (argc > 0 && JS_ToInt32(context, &id, argv[0]) != 0) {
    return JS_EXCEPTION;
  }
  for (timer **link = &engine->timers; *link != NULL; link = &(*link)->next) {
    if ((*link)->id == id) {
      timer *cleared = *link;
      *link = cleared->next;
      free_timer(context, cleared);
      break;
    }
  }
  return JS_UNDEFINED;
}

// performance.now(): milliseconds since the engine started, with a fraction.
// Values as setTimeout's.
static JSValue performance_now(JSContext *context, JSValueConst this_value, int argc,
                               JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  (void)argv;
  p2pml_engine *engine = JS_GetContextOpaque(context);
  return JS_NewFloat64(context, (double)(now() - engine->started) / (double)NS_PER_MS);
}

// console.debug/log/info/warn/error: the arguments as describe() gives them,
// joined by spaces, as one line. `level` is a p2pml_log_level. Values as
// setTimeout's.
static JSValue console_write(JSContext *context, JSValueConst this_value, int argc,
                             JSValueConst *argv, int level) {
  (void)this_value;
  p2pml_engine *engine = JS_GetContextOpaque(context);
  char *line = strdup("");
  for (int i = 0; line != NULL && i < argc; i++) {
    char *part = describe(context, argv[i]);
    if (part == NULL || (i > 0 && !append(&line, " ")) || !append(&line, part)) {
      free(line);
      line = NULL;
    }
    free(part);
  }
  if (line == NULL) {
    return JS_ThrowOutOfMemory(context);
  }
  // Closed before the call, or during a conversion, whose error to_string()
  // dropped: stop the JS that called, as the close's own error does.
  if (atomic_load(&engine->closed)) {
    free(line);
    JS_ThrowInternalError(context, "interrupted");
    JS_SetUncatchableException(context, true);
    return JS_EXCEPTION;
  }
  engine->log(engine->log_data, (p2pml_log_level)level, line);
  free(line);
  return JS_UNDEFINED;
}

#define GLOBAL (JS_PROP_WRITABLE | JS_PROP_CONFIGURABLE)

static const JSCFunctionListEntry console_functions[] = {
    JS_CFUNC_MAGIC_DEF("debug", 0, console_write, P2PML_LOG_DEBUG),
    JS_CFUNC_MAGIC_DEF("log", 0, console_write, P2PML_LOG_INFO),
    JS_CFUNC_MAGIC_DEF("info", 0, console_write, P2PML_LOG_INFO),
    JS_CFUNC_MAGIC_DEF("warn", 0, console_write, P2PML_LOG_WARN),
    JS_CFUNC_MAGIC_DEF("error", 0, console_write, P2PML_LOG_ERROR),
};

static const JSCFunctionListEntry performance_functions[] = {
    JS_CFUNC_DEF("now", 0, performance_now),
};

static const JSCFunctionListEntry global_functions[] = {
    JS_CFUNC_DEF("setTimeout", 1, set_timeout),
    JS_CFUNC_DEF("clearTimeout", 0, clear_timeout),
    JS_OBJECT_DEF("console", console_functions, COUNT(console_functions), GLOBAL),
    JS_OBJECT_DEF("performance", performance_functions, COUNT(performance_functions), GLOBAL),
};

// natives.reportError(value): writes the value as an uncaught error, as the
// engine's own reports (report()). Values as setTimeout's.
static JSValue report_error(JSContext *context, JSValueConst this_value, int argc,
                            JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  report(JS_GetContextOpaque(context), "Uncaught ", argv[0]);
  return JS_UNDEFINED;
}

static const JSCFunctionListEntry native_functions[] = {
    JS_CFUNC_DEF("reportError", 1, report_error),
};

// Evaluates each polyfill (src/polyfills/*.js), a function expression, and
// calls it with the natives: the C helpers that the polyfills use, which do
// not become globals. False when one fails; its error goes to the log.
static bool add_polyfills(p2pml_engine *engine) {
  JSContext *context = engine->context;
  JSValue natives = JS_NewObject(context);
  bool added = !JS_IsException(natives) &&
               JS_SetPropertyFunctionList(context, natives, native_functions,
                                          COUNT(native_functions)) == 0 &&
               p2pml_add_encoding(context, natives);
  for (int i = 0; added && i < p2pml_polyfills_count; i++) {
    const p2pml_script *script = &p2pml_polyfills[i];
    const char *source = (const char *)script->source;
    // Strict: a stray assignment fails here, not as a new global.
    JSValue install = JS_Eval(context, source, strlen(source), script->name,
                              JS_EVAL_TYPE_GLOBAL | JS_EVAL_FLAG_STRICT);
    JSValue result = JS_IsException(install)
                         ? JS_EXCEPTION
                         : JS_Call(context, install, JS_UNDEFINED, 1, (JSValueConst *)&natives);
    added = !JS_IsException(result);
    JS_FreeValue(context, result);
    JS_FreeValue(context, install);
  }
  if (!added) {
    report_exception(engine);
  }
  JS_FreeValue(context, natives);
  return added;
}

// The globals that QuickJS does not have and the web core needs: those in C,
// then the polyfills. False when one cannot be added.
static bool add_globals(p2pml_engine *engine) {
  JSContext *context = engine->context;
  JSValue global = JS_GetGlobalObject(context);
  bool added =
      JS_SetPropertyFunctionList(context, global, global_functions, COUNT(global_functions)) == 0 &&
      JS_SetPropertyStr(context, global, "window", JS_DupValue(context, global)) >= 0;
  JS_FreeValue(context, global);
  return added && add_polyfills(engine);
}

// Stops JS that still runs when the engine closes, such as an endless loop.
// QuickJS calls it from time to time while JS runs; its error is uncatchable.
static int interrupt_if_closed(JSRuntime *runtime, void *opaque) {
  (void)runtime;
  p2pml_engine *engine = opaque;
  return atomic_load(&engine->closed);
}

bool p2pml_engine_run(p2pml_engine *engine, p2pml_log_write *log, void *log_data) {
  engine->log = log;
  engine->log_data = log_data;
  engine->started = now();
  // Created on this thread, which QuickJS takes as the top of its stack.
  engine->runtime = JS_NewRuntime();
  if (engine->runtime != NULL) {
    JS_SetInterruptHandler(engine->runtime, interrupt_if_closed, engine);
    JS_SetHostPromiseRejectionTracker(engine->runtime, track_rejection, engine);
    engine->context = JS_NewContext(engine->runtime);
  }
  if (engine->context != NULL) {
    JS_SetContextOpaque(engine->context, engine);
  }
  // A close can stop the polyfills' JS: that is not a failure to start.
  bool started = engine->context != NULL && (add_globals(engine) || atomic_load(&engine->closed));
  if (!started) {
    p2pml_engine_close(engine);
  }
  if (engine->context != NULL) {
    // The polyfills' Promise jobs and rejections, as after a task.
    finish_js(engine);
  }
  while (!atomic_load(&engine->closed)) {
    task *next = take_task(engine);
    if (next != NULL) {
      next->run(engine, next->data);
      free(next);
      finish_js(engine);
    }
    run_due_timer(engine);
  }
  while (engine->timers != NULL) {
    timer *pending = engine->timers;
    engine->timers = pending->next;
    free_timer(engine->context, pending);
  }
  if (engine->context != NULL) {
    JS_FreeContext(engine->context);
    engine->context = NULL;
  }
  if (engine->runtime != NULL) {
    JS_FreeRuntime(engine->runtime);
    engine->runtime = NULL;
  }
  return started;
}

bool p2pml_engine_post(p2pml_engine *engine, p2pml_task_run *run, p2pml_task_discard *discard,
                       void *data) {
  task *added = malloc(sizeof *added);
  if (added == NULL) {
    return false;
  }
  *added = (task){.next = NULL, .run = run, .discard = discard, .data = data};
  pthread_mutex_lock(&engine->mutex);
  if (atomic_load(&engine->closed)) {
    pthread_mutex_unlock(&engine->mutex);
    free(added);
    return false;
  }
  if (engine->tail == NULL) {
    engine->head = added;
  } else {
    engine->tail->next = added;
  }
  engine->tail = added;
  pthread_cond_signal(&engine->wake);
  pthread_mutex_unlock(&engine->mutex);
  return true;
}

void p2pml_engine_close(p2pml_engine *engine) {
  pthread_mutex_lock(&engine->mutex);
  atomic_store(&engine->closed, true);
  pthread_cond_signal(&engine->wake);
  pthread_mutex_unlock(&engine->mutex);
}

void p2pml_engine_free(p2pml_engine *engine) {
  discard_tasks(engine);
  pthread_cond_destroy(&engine->wake);
  pthread_mutex_destroy(&engine->mutex);
  free(engine);
}

JSContext *p2pml_engine_context(p2pml_engine *engine) { return engine->context; }

static bool eval_script(JSContext *context, const char *code, size_t length, const char *file_name,
                        p2pml_eval_result *result) {
  JSValue value = JS_Eval(context, code, length, file_name, JS_EVAL_TYPE_GLOBAL);
  if (!JS_IsException(value)) {
    result->value = to_string(context, value);
    JS_FreeValue(context, value);
    return true;
  }
  JSValue exception = JS_GetException(context);
  result->error = to_string(context, exception);
  result->stack = stack_of(context, exception);
  JS_FreeValue(context, exception);
  return false;
}

bool p2pml_engine_eval(p2pml_engine *engine, const char *code, size_t length, const char *file_name,
                       p2pml_eval_result *result) {
  *result = (p2pml_eval_result){0};
  // Not started after the close: the interrupt handler would let it run for a
  // while.
  bool ok = !atomic_load(&engine->closed) &&
            eval_script(engine->context, code, length, file_name, result);
  // After the script too: the close may have stopped it, or the conversion of
  // its result.
  if (atomic_load(&engine->closed)) {
    p2pml_eval_result_free(result);
    result->closed = true;
    return false;
  }
  return ok;
}

void p2pml_eval_result_free(p2pml_eval_result *result) {
  free(result->value);
  free(result->error);
  free(result->stack);
  *result = (p2pml_eval_result){0};
}
