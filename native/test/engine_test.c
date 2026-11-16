// Tests of engine.c, run by ctest with AddressSanitizer and
// UndefinedBehaviorSanitizer. QuickJS's asserts are on, so freeing the runtime
// checks that no JS object leaked.

#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "engine.h"
#include "polyfills.h"

// test/js/*.js, which CMakeLists.txt builds in: harness.js first.
extern const p2pml_script p2pml_test_scripts[];
extern const int p2pml_test_scripts_count;

#define CHECK(condition)                                                                           \
  do {                                                                                             \
    if (!(condition)) {                                                                            \
      fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__, __LINE__, #condition);                \
      abort();                                                                                     \
    }                                                                                              \
  } while (0)

// The lines that an engine wrote. The engine thread writes them; the test
// reads them after the thread ended.
enum { MAX_LINES = 32 };

typedef struct {
  int count;
  p2pml_log_level levels[MAX_LINES];
  char *lines[MAX_LINES];
} captured_log;

static void capture(void *data, p2pml_log_level level, const char *message) {
  captured_log *log = data;
  CHECK(log->count < MAX_LINES);
  log->levels[log->count] = level;
  log->lines[log->count] = strdup(message);
  CHECK(log->lines[log->count] != NULL);
  log->count++;
}

// The log of a test that expects no line: no console call, and no uncaught
// error, also none caused by a close.
static void no_line_expected(void *data, p2pml_log_level level, const char *message) {
  (void)data;
  fprintf(stderr, "unexpected line (level %d): %s\n", (int)level, message);
  abort();
}

static void free_log(captured_log *log) {
  for (int i = 0; i < log->count; i++) {
    free(log->lines[i]);
  }
}

typedef struct {
  p2pml_engine *engine;
  captured_log *log;
} run_args;

static void *run_engine(void *data) {
  run_args *args = data;
  CHECK(p2pml_engine_run(args->engine, args->log != NULL ? capture : no_line_expected, args->log));
  free(args);
  return NULL;
}

// Runs the engine on a new thread; its lines go to `log`, which can be NULL
// when the test expects none.
static pthread_t start_with_log(p2pml_engine *engine, captured_log *log) {
  run_args *args = malloc(sizeof *args);
  CHECK(args != NULL);
  *args = (run_args){.engine = engine, .log = log};
  // As the Android engine thread (JsEngine.kt): QuickJS stops JS at 1 MiB of
  // stack, more than a default pthread has on macOS.
  pthread_attr_t attributes;
  CHECK(pthread_attr_init(&attributes) == 0);
  CHECK(pthread_attr_setstacksize(&attributes, (size_t)2 * 1024 * 1024) == 0);
  pthread_t thread;
  CHECK(pthread_create(&thread, &attributes, run_engine, args) == 0);
  pthread_attr_destroy(&attributes);
  return thread;
}

static pthread_t start(p2pml_engine *engine) { return start_with_log(engine, NULL); }

static void stop(p2pml_engine *engine, pthread_t thread) {
  CHECK(pthread_join(thread, NULL) == 0);
  p2pml_engine_free(engine);
}

static void close_engine(p2pml_engine *engine, void *data) {
  (void)data;
  p2pml_engine_close(engine);
}

static void no_discard(void *data) { (void)data; }

static void post_close(p2pml_engine *engine) {
  CHECK(p2pml_engine_post(engine, close_engine, no_discard, NULL));
}

// A task that records its number in a log.
typedef struct {
  int entries[64];
  int count;
  int discarded;
} task_log;

typedef struct {
  task_log *log;
  int number;
} log_task;

static void record(p2pml_engine *engine, void *data) {
  (void)engine;
  log_task *task = data;
  task->log->entries[task->log->count++] = task->number;
  free(task);
}

static void discard_record(void *data) {
  log_task *task = data;
  task->log->discarded++;
  free(task);
}

static bool post_record(p2pml_engine *engine, task_log *log, int number) {
  log_task *task = malloc(sizeof *task);
  CHECK(task != NULL);
  *task = (log_task){.log = log, .number = number};
  bool posted = p2pml_engine_post(engine, record, discard_record, task);
  if (!posted) {
    free(task);
  }
  return posted;
}

// A task that evaluates a script and keeps the result.
typedef struct {
  const char *code;
  bool ok;
  p2pml_eval_result result;
} eval_task;

static void evaluate(p2pml_engine *engine, void *data) {
  eval_task *task = data;
  task->ok = p2pml_engine_eval(engine, task->code, strlen(task->code), "test.js", &task->result);
}

static void post_eval(p2pml_engine *engine, eval_task *task) {
  CHECK(p2pml_engine_post(engine, evaluate, no_discard, task));
}

static void test_close_without_tasks(void) {
  p2pml_engine *engine = p2pml_engine_new();
  CHECK(engine != NULL);
  pthread_t thread = start(engine);
  p2pml_engine_close(engine);
  p2pml_engine_close(engine);
  stop(engine, thread);
}

static void test_tasks_run_in_order(void) {
  p2pml_engine *engine = p2pml_engine_new();
  task_log log = {0};
  for (int i = 0; i < 32; i++) {
    CHECK(post_record(engine, &log, i));
  }
  post_close(engine);
  stop(engine, start(engine));
  CHECK(log.count == 32);
  for (int i = 0; i < 32; i++) {
    CHECK(log.entries[i] == i);
  }
  CHECK(log.discarded == 0);
}

enum { POSTERS = 4, POSTS = 2000 };

typedef struct {
  p2pml_engine *engine;
  atomic_int *runs;
} poster_args;

static void count_run(p2pml_engine *engine, void *data) {
  (void)engine;
  atomic_fetch_add((atomic_int *)data, 1);
}

static void *post_many(void *data) {
  poster_args *args = data;
  for (int i = 0; i < POSTS; i++) {
    CHECK(p2pml_engine_post(args->engine, count_run, no_discard, &args->runs[i]));
  }
  return NULL;
}

static void test_tasks_from_many_threads_run_once(void) {
  p2pml_engine *engine = p2pml_engine_new();
  pthread_t thread = start(engine);
  static atomic_int runs[POSTERS][POSTS];
  pthread_t posters[POSTERS];
  poster_args arguments[POSTERS];
  for (int i = 0; i < POSTERS; i++) {
    arguments[i] = (poster_args){.engine = engine, .runs = runs[i]};
    CHECK(pthread_create(&posters[i], NULL, post_many, &arguments[i]) == 0);
  }
  for (int i = 0; i < POSTERS; i++) {
    CHECK(pthread_join(posters[i], NULL) == 0);
  }
  post_close(engine);
  stop(engine, thread);
  for (int i = 0; i < POSTERS; i++) {
    for (int j = 0; j < POSTS; j++) {
      CHECK(atomic_load(&runs[i][j]) == 1);
    }
  }
}

static void test_promise_jobs_run_before_the_next_task(void) {
  p2pml_engine *engine = p2pml_engine_new();
  eval_task first = {.code = "globalThis.steps = [];"
                             "Promise.resolve().then(() => steps.push('job'));"
                             "steps.push('task');"};
  eval_task second = {.code = "steps.push('next task'); steps.join()"};
  post_eval(engine, &first);
  post_eval(engine, &second);
  post_close(engine);
  stop(engine, start(engine));
  CHECK(first.ok && second.ok);
  CHECK(strcmp(second.result.value, "task,job,next task") == 0);
  p2pml_eval_result_free(&first.result);
  p2pml_eval_result_free(&second.result);
}

static void test_close_from_a_task_discards_the_rest(void) {
  p2pml_engine *engine = p2pml_engine_new();
  task_log log = {0};
  CHECK(post_record(engine, &log, 1));
  post_close(engine);
  CHECK(post_record(engine, &log, 2));
  CHECK(post_record(engine, &log, 3));
  stop(engine, start(engine));
  CHECK(log.count == 1 && log.entries[0] == 1);
  CHECK(log.discarded == 2);
}

static void test_close_before_run_discards_all(void) {
  p2pml_engine *engine = p2pml_engine_new();
  task_log log = {0};
  CHECK(post_record(engine, &log, 1));
  p2pml_engine_close(engine);
  CHECK(!post_record(engine, &log, 2));
  stop(engine, start(engine));
  CHECK(log.count == 0 && log.discarded == 1);
}

static void test_free_without_run_discards_all(void) {
  p2pml_engine *engine = p2pml_engine_new();
  task_log log = {0};
  CHECK(post_record(engine, &log, 1));
  p2pml_engine_free(engine);
  CHECK(log.count == 0 && log.discarded == 1);
}

static atomic_bool marked;

// Returns a new undefined value for the caller to free; the arguments stay
// QuickJS's.
static JSValue mark(JSContext *context, JSValueConst this_value, int argc, JSValueConst *argv) {
  (void)context;
  (void)this_value;
  (void)argc;
  (void)argv;
  atomic_store(&marked, true);
  return JS_UNDEFINED;
}

static p2pml_engine *engine_to_close;

// Closes `engine_to_close`. Returns a new undefined value, as mark().
static JSValue close_from_js(JSContext *context, JSValueConst this_value, int argc,
                             JSValueConst *argv) {
  (void)context;
  (void)this_value;
  (void)argc;
  (void)argv;
  p2pml_engine_close(engine_to_close);
  return JS_UNDEFINED;
}

// Runs QuickJS's garbage collector, as std.gc() of quickjs-libc does. Returns
// a new undefined value, as mark().
static JSValue run_gc(JSContext *context, JSValueConst this_value, int argc, JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  (void)argv;
  JS_RunGC(JS_GetRuntime(context));
  return JS_UNDEFINED;
}

// Gives the script a global mark() that sets `marked`, closeEngine() and
// runGc().
static void add_test_globals(p2pml_engine *engine, void *data) {
  (void)data;
  engine_to_close = engine;
  JSContext *context = p2pml_engine_context(engine);
  JSValue global = JS_GetGlobalObject(context);
  JS_SetPropertyStr(context, global, "mark", JS_NewCFunction(context, mark, "mark", 0));
  JS_SetPropertyStr(context, global, "closeEngine",
                    JS_NewCFunction(context, close_from_js, "closeEngine", 0));
  JS_SetPropertyStr(context, global, "runGc", JS_NewCFunction(context, run_gc, "runGc", 0));
  JS_FreeValue(context, global);
}

static void eval_and_close(p2pml_engine *engine, void *data) {
  evaluate(engine, data);
  p2pml_engine_close(engine);
}

static void test_no_promise_job_runs_after_close(void) {
  atomic_store(&marked, false);
  p2pml_engine *engine = p2pml_engine_new();
  CHECK(p2pml_engine_post(engine, add_test_globals, no_discard, NULL));
  // Freeing the runtime frees the job that does not run.
  eval_task task = {.code = "Promise.resolve().then(mark); 'queued'"};
  CHECK(p2pml_engine_post(engine, eval_and_close, no_discard, &task));
  stop(engine, start(engine));
  CHECK(task.ok && strcmp(task.result.value, "queued") == 0);
  CHECK(!atomic_load(&marked));
  p2pml_eval_result_free(&task.result);
}

// Evaluates `code` in a new engine with the test globals, and closes the
// engine once the script calls mark(). Any line in the log fails the test.
static eval_task close_when_marked(const char *code) {
  atomic_store(&marked, false);
  p2pml_engine *engine = p2pml_engine_new();
  CHECK(p2pml_engine_post(engine, add_test_globals, no_discard, NULL));
  eval_task task = {.code = code};
  post_eval(engine, &task);
  pthread_t thread = start(engine);
  while (!atomic_load(&marked)) {
    sched_yield();
  }
  p2pml_engine_close(engine);
  stop(engine, thread);
  return task;
}

static void test_close_stops_running_js(void) {
  // The script cannot catch the error that stops it.
  eval_task task = close_when_marked("mark(); for (;;) { try { for (;;) {} } catch (e) {} }");
  CHECK(!task.ok && task.result.closed && task.result.error == NULL);
  p2pml_eval_result_free(&task.result);
}

static void close_and_eval(p2pml_engine *engine, void *data) {
  p2pml_engine_close(engine);
  evaluate(engine, data);
}

static void test_no_script_runs_after_close(void) {
  atomic_store(&marked, false);
  p2pml_engine *engine = p2pml_engine_new();
  CHECK(p2pml_engine_post(engine, add_test_globals, no_discard, NULL));
  eval_task task = {.code = "mark()"};
  CHECK(p2pml_engine_post(engine, close_and_eval, no_discard, &task));
  stop(engine, start(engine));
  CHECK(!task.ok && task.result.closed && task.result.error == NULL);
  CHECK(!atomic_load(&marked));
  p2pml_eval_result_free(&task.result);
}

static void test_a_script_that_closes_the_engine_fails(void) {
  p2pml_engine *engine = p2pml_engine_new();
  CHECK(p2pml_engine_post(engine, add_test_globals, no_discard, NULL));
  // The script completes, but its result comes after the close.
  eval_task task = {.code = "closeEngine(); 'done'"};
  post_eval(engine, &task);
  stop(engine, start(engine));
  CHECK(!task.ok && task.result.closed);
  CHECK(task.result.value == NULL && task.result.error == NULL && task.result.stack == NULL);
  p2pml_eval_result_free(&task.result);
}

// Evaluates one script in a new engine.
static eval_task eval_once(const char *code) {
  p2pml_engine *engine = p2pml_engine_new();
  eval_task task = {.code = code};
  post_eval(engine, &task);
  post_close(engine);
  stop(engine, start(engine));
  return task;
}

static void test_eval_gives_the_value(void) {
  eval_task task = eval_once("[1 + 1, typeof undefined, null].join(' ')");
  CHECK(task.ok);
  CHECK(strcmp(task.result.value, "2 undefined ") == 0);
  CHECK(task.result.error == NULL && task.result.stack == NULL);
  p2pml_eval_result_free(&task.result);
}

static void test_eval_gives_cesu8(void) {
  // U+1F600 as a surrogate pair, each half in three bytes: modified UTF-8.
  eval_task task = eval_once("'\\u{1F600}'");
  CHECK(task.ok);
  CHECK(strcmp(task.result.value, "\xED\xA0\xBD\xED\xB8\x80") == 0);
  p2pml_eval_result_free(&task.result);
}

static void test_eval_gives_the_exception_and_its_stack(void) {
  eval_task task = eval_once("function fail() { throw new TypeError('bad value'); }\nfail();");
  CHECK(!task.ok);
  CHECK(task.result.value == NULL);
  CHECK(strcmp(task.result.error, "TypeError: bad value") == 0);
  CHECK(task.result.stack != NULL);
  CHECK(strstr(task.result.stack, "at fail (test.js:1") != NULL);
  p2pml_eval_result_free(&task.result);
}

static void test_eval_of_a_thrown_primitive_has_no_stack(void) {
  eval_task task = eval_once("throw 42");
  CHECK(!task.ok);
  CHECK(strcmp(task.result.error, "42") == 0);
  CHECK(task.result.stack == NULL);
  p2pml_eval_result_free(&task.result);
}

static void test_eval_of_a_value_string_cannot_convert(void) {
  eval_task task = eval_once("({ toString() { throw new Error('no'); } })");
  CHECK(task.ok);
  CHECK(strcmp(task.result.value, "<a value that cannot be converted to a string>") == 0);
  p2pml_eval_result_free(&task.result);
}

// Runs `code` in a new engine with the test globals, until the script closes
// the engine, such as with setTimeout(closeEngine, 50). The script must not
// throw.
static void run_script(const char *code, captured_log *log) {
  p2pml_engine *engine = p2pml_engine_new();
  CHECK(engine != NULL);
  CHECK(p2pml_engine_post(engine, add_test_globals, no_discard, NULL));
  eval_task task = {.code = code};
  post_eval(engine, &task);
  stop(engine, start_with_log(engine, log));
  if (!task.ok && !task.result.closed) {
    fprintf(stderr, "the script threw: %s\n", task.result.error);
  }
  CHECK(task.ok || task.result.closed);
  p2pml_eval_result_free(&task.result);
}

// Checks line `index`, which the test checked is in the log: its level, and
// its text, or only the start of its text when `whole` is false.
static void check_line(const captured_log *log, int index, p2pml_log_level level, const char *text,
                       bool whole) {
  const char *line = log->lines[index];
  if (log->levels[index] != level ||
      (whole ? strcmp(line, text) : strncmp(line, text, strlen(text))) != 0) {
    fprintf(stderr, "line %d (level %d): %s\nexpected (level %d): %s\n", index,
            (int)log->levels[index], line, (int)level, text);
    abort();
  }
}

static void test_timers_run_by_deadline_then_by_call(void) {
  captured_log log = {0};
  run_script("setTimeout(() => console.log('c'), 20);"
             "setTimeout(() => console.log('a'), 0);"
             "setTimeout(() => console.log('b'), 0);"
             "setTimeout(() => console.log('d'), 20);"
             "setTimeout(closeEngine, 40);",
             &log);
  CHECK(log.count == 4);
  check_line(&log, 0, P2PML_LOG_INFO, "a", true);
  check_line(&log, 1, P2PML_LOG_INFO, "b", true);
  check_line(&log, 2, P2PML_LOG_INFO, "c", true);
  check_line(&log, 3, P2PML_LOG_INFO, "d", true);
  free_log(&log);
}

static void test_clear_timeout(void) {
  captured_log log = {0};
  // `second` is due in the same pass as `first`, which clears it.
  run_script("setTimeout(() => { console.log('first'); clearTimeout(second); }, 0);"
             "const second = setTimeout(() => console.log('second'), 0);"
             "clearTimeout(setTimeout(() => console.log('third'), 5));"
             "clearTimeout(123456); clearTimeout(); clearTimeout('x');"
             "setTimeout(closeEngine, 20);",
             &log);
  CHECK(log.count == 1);
  check_line(&log, 0, P2PML_LOG_INFO, "first", true);
  free_log(&log);
}

static void test_timer_ids_and_arguments(void) {
  captured_log log = {0};
  run_script("const a = setTimeout(console.log, 0, 'one', 2, 'three');"
             "const b = setTimeout(function () { 'use strict'; console.log(this === window); }, 0);"
             "console.log(typeof a, a > 0, b > a);"
             "setTimeout(closeEngine, 10);",
             &log);
  CHECK(log.count == 3);
  check_line(&log, 0, P2PML_LOG_INFO, "number true true", true);
  check_line(&log, 1, P2PML_LOG_INFO, "one 2 three", true);
  // `this` is the global object, also in strict code.
  check_line(&log, 2, P2PML_LOG_INFO, "true", true);
  free_log(&log);
}

static void test_timer_delays_are_converted_as_in_a_browser(void) {
  captured_log log = {0};
  run_script("const fired = [];"
             "setTimeout(() => fired.push('30'), 30);"
             "setTimeout(() => fired.push(\"'20'\"), '20');"
             "setTimeout(() => fired.push('10'), 10);"
             // Each is 0: WebIDL's `long` takes them modulo 2 ** 32.
             "for (const delay of [NaN, -5, Infinity, 2 ** 31, 2 ** 32, undefined, null])"
             "  setTimeout(() => fired.push(String(delay)), delay);"
             "setTimeout(() => { console.log(fired.join()); closeEngine(); }, 50);"
             "try {"
             "  setTimeout(() => {}, { valueOf() { throw new Error('no delay'); } });"
             "} catch (e) { console.log(e.message); }",
             &log);
  CHECK(log.count == 2);
  check_line(&log, 0, P2PML_LOG_INFO, "no delay", true);
  check_line(&log, 1, P2PML_LOG_INFO,
             "NaN,-5,Infinity,2147483648,4294967296,undefined,null,10,'20',30", true);
  free_log(&log);
}

static void test_deeply_nested_timers_wait_at_least_4_ms(void) {
  captured_log log = {0};
  // The first call is not in a timer; the timer it sets is nested 1 deep. The
  // timer set by the one nested 6 deep is the first to wait: gap 6. Only gap 5
  // is checked to be short, so that a pause of the thread rarely fails it.
  run_script("const times = [];"
             "(function tick() {"
             "  times.push(performance.now());"
             "  if (times.length < 10) { setTimeout(tick, 0); return; }"
             "  const gaps = times.slice(1).map((time, i) => time - times[i]);"
             "  console.log(gaps[5] < 4, gaps.slice(6).every((gap) => gap >= 4));"
             "  closeEngine();"
             "})();",
             &log);
  CHECK(log.count == 1);
  check_line(&log, 0, P2PML_LOG_INFO, "true true", true);
  free_log(&log);
}

static void test_a_timer_callback_must_be_a_function(void) {
  captured_log log = {0};
  run_script("for (const args of [['code', 0], []]) {"
             "  try { setTimeout(...args); } catch (e) { console.log(e.name); }"
             "}"
             "closeEngine();",
             &log);
  CHECK(log.count == 2);
  check_line(&log, 0, P2PML_LOG_INFO, "TypeError", true);
  check_line(&log, 1, P2PML_LOG_INFO, "TypeError", true);
  free_log(&log);
}

static void test_an_exception_from_a_timer_is_reported(void) {
  captured_log log = {0};
  run_script("setTimeout(function broken() { throw new Error('timer failed'); }, 0);"
             "setTimeout(() => { console.log('next'); closeEngine(); }, 5);",
             &log);
  CHECK(log.count == 2);
  check_line(&log, 0, P2PML_LOG_ERROR, "Uncaught Error: timer failed\n    at broken (test.js:1",
             false);
  check_line(&log, 1, P2PML_LOG_INFO, "next", true);
  free_log(&log);
}

static void test_promise_jobs_run_after_each_timer(void) {
  captured_log log = {0};
  run_script(
      "const steps = [];"
      "setTimeout(() => {"
      "  steps.push('timer 1'); Promise.resolve().then(() => steps.push('job'));"
      "}, 0);"
      "setTimeout(() => { steps.push('timer 2'); console.log(steps.join()); closeEngine(); }, 0);",
      &log);
  CHECK(log.count == 1);
  check_line(&log, 0, P2PML_LOG_INFO, "timer 1,job,timer 2", true);
  free_log(&log);
}

static void test_performance_now(void) {
  captured_log log = {0};
  run_script("const start = performance.now();"
             "console.log(typeof start, start >= 0);"
             "setTimeout(() => {"
             "  const waited = performance.now() - start;"
             "  console.log(waited >= 20, waited < 10000);"
             "  closeEngine();"
             "}, 20);",
             &log);
  CHECK(log.count == 2);
  check_line(&log, 0, P2PML_LOG_INFO, "number true", true);
  check_line(&log, 1, P2PML_LOG_INFO, "true true", true);
  free_log(&log);
}

static void test_console(void) {
  captured_log log = {0};
  run_script("console.debug('d'); console.log('l'); console.info('i');"
             "console.warn('w'); console.error('e');"
             "console.log('text', 1, null, undefined, { a: 1 }, [1, 2]);"
             "function fail() { return new TypeError('bad'); }\n"
             "console.log('error:', fail());"
             "console.log();"
             "console.log('\\u{1F600}');"
             "console.log(Symbol('s'));"
             "console.log(window === globalThis);"
             "closeEngine();",
             &log);
  CHECK(log.count == 11);
  check_line(&log, 0, P2PML_LOG_DEBUG, "d", true);
  check_line(&log, 1, P2PML_LOG_INFO, "l", true);
  check_line(&log, 2, P2PML_LOG_INFO, "i", true);
  check_line(&log, 3, P2PML_LOG_WARN, "w", true);
  check_line(&log, 4, P2PML_LOG_ERROR, "e", true);
  check_line(&log, 5, P2PML_LOG_INFO, "text 1 null undefined [object Object] 1,2", true);
  // The stack follows the message, without a line break at its end.
  check_line(&log, 6, P2PML_LOG_INFO, "error: TypeError: bad\n    at fail (test.js:1", false);
  CHECK(strchr(log.lines[6], '\0')[-1] != '\n');
  check_line(&log, 7, P2PML_LOG_INFO, "", true);
  // CESU-8, as eval results.
  check_line(&log, 8, P2PML_LOG_INFO, "\xED\xA0\xBD\xED\xB8\x80", true);
  check_line(&log, 9, P2PML_LOG_INFO, "<a value that cannot be converted to a string>", true);
  check_line(&log, 10, P2PML_LOG_INFO, "true", true);
  free_log(&log);
}

static void test_unhandled_rejections_are_reported_once(void) {
  captured_log log = {0};
  run_script("Promise.reject(new Error('lost'));"
             // A handler added by a Promise job is in time.
             "const handled = Promise.reject(new Error('handled'));"
             "Promise.resolve().then(() => handled.catch(() => {}));"
             "(async () => { throw new RangeError('async'); })();"
             "setTimeout(() => {"
             "  const late = Promise.reject(42);"
             // After the report: not reported again.
             "  setTimeout(() => { late.catch(() => {}); closeEngine(); }, 0);"
             "}, 0);",
             &log);
  CHECK(log.count == 3);
  check_line(&log, 0, P2PML_LOG_ERROR, "Uncaught (in promise) Error: lost\n    at <eval>", false);
  check_line(&log, 1, P2PML_LOG_ERROR, "Uncaught (in promise) RangeError: async\n", false);
  check_line(&log, 2, P2PML_LOG_ERROR, "Uncaught (in promise) 42", true);
  free_log(&log);
}

static void test_an_exception_from_a_job_is_reported(void) {
  captured_log log = {0};
  // The garbage collector queues the registry's callback as a job.
  run_script("globalThis.registry = new FinalizationRegistry(() => {"
             "  throw new Error('cleanup failed');"
             "});"
             "registry.register({}, 'held');"
             "runGc();"
             "setTimeout(closeEngine, 10);",
             &log);
  CHECK(log.count == 1);
  check_line(&log, 0, P2PML_LOG_ERROR, "Uncaught Error: cleanup failed", false);
  free_log(&log);
}

// A timer whose callback runs until the engine closes. The close's error is
// not reported (close_when_marked() fails the test on any line), also when it
// rejects the Promise of an async function, or stops the conversion of a
// rejection's reason for its report.
static void test_the_close_error_is_not_reported(void) {
  const char *codes[] = {"setTimeout(() => { mark(); for (;;) {} }, 0)",
                         "setTimeout(async () => { mark(); for (;;) {} }, 0)",
                         "setTimeout(() => {"
                         "  Promise.reject({ toString() { mark(); for (;;) {} } });"
                         "}, 0)"};
  for (size_t i = 0; i < sizeof codes / sizeof codes[0]; i++) {
    eval_task task = close_when_marked(codes[i]);
    CHECK(task.ok);
    p2pml_eval_result_free(&task.result);
  }
}

// A console call after the close stops the JS that calls it, also when the
// close stopped the conversion of its argument.
static void test_console_stops_js_after_close(void) {
  const char *codes[] = {"for (;;) console.log({ toString() { mark(); for (;;) {} } })",
                         "for (;;) { (async () => { mark(); for (;;) {} })(); console.log('x'); }",
                         // The error is uncatchable. One string, in parentheses.
                         ("for (;;) { try { console.log({ toString() { mark(); for (;;) {} } }) } "
                          "catch (e) {} }")};
  for (size_t i = 0; i < sizeof codes / sizeof codes[0]; i++) {
    eval_task task = close_when_marked(codes[i]);
    CHECK(!task.ok && task.result.closed);
    p2pml_eval_result_free(&task.result);
  }
}

static void test_no_timer_starts_after_close(void) {
  atomic_store(&marked, false);
  // The timer is due before the task ends, which closes the engine.
  run_script("setTimeout(mark, 0); for (const end = Date.now() + 5; Date.now() < end;) {}"
             "closeEngine();",
             NULL);
  CHECK(!atomic_load(&marked));
}

static void test_close_frees_pending_timers_and_rejections(void) {
  // Freeing the runtime checks that no JS value leaked.
  run_script("setTimeout(() => {}, 100000, { held: [1, 2] });"
             "Promise.reject(new Error('dropped'));"
             "closeEngine();",
             NULL);
}

// Runs each JS test file (test/js) after harness.js, in a new engine. Fails
// on the first log line, such as the report of a failed test, and when a file
// runs no test.
static void test_js_files(void) {
  for (int i = 1; i < p2pml_test_scripts_count; i++) {
    p2pml_engine *engine = p2pml_engine_new();
    CHECK(engine != NULL);
    eval_task harness = {.code = (const char *)p2pml_test_scripts[0].source};
    eval_task tests = {.code = (const char *)p2pml_test_scripts[i].source};
    eval_task count = {.code = "testsRun"};
    post_eval(engine, &harness);
    post_eval(engine, &tests);
    post_eval(engine, &count);
    post_close(engine);
    stop(engine, start(engine));
    if (!harness.ok) {
      fprintf(stderr, "harness.js threw: %s\n", harness.result.error);
    }
    if (!tests.ok) {
      fprintf(stderr, "%s threw: %s\n", p2pml_test_scripts[i].name, tests.result.error);
    }
    CHECK(harness.ok && tests.ok && count.ok && atoi(count.result.value) > 0);
    p2pml_eval_result_free(&harness.result);
    p2pml_eval_result_free(&tests.result);
    p2pml_eval_result_free(&count.result);
  }
}

int main(void) {
  test_close_without_tasks();
  test_tasks_run_in_order();
  test_tasks_from_many_threads_run_once();
  test_promise_jobs_run_before_the_next_task();
  test_close_from_a_task_discards_the_rest();
  test_close_before_run_discards_all();
  test_free_without_run_discards_all();
  test_no_promise_job_runs_after_close();
  test_close_stops_running_js();
  test_no_script_runs_after_close();
  test_a_script_that_closes_the_engine_fails();
  test_eval_gives_the_value();
  test_eval_gives_cesu8();
  test_eval_gives_the_exception_and_its_stack();
  test_eval_of_a_thrown_primitive_has_no_stack();
  test_eval_of_a_value_string_cannot_convert();
  test_timers_run_by_deadline_then_by_call();
  test_clear_timeout();
  test_timer_ids_and_arguments();
  test_timer_delays_are_converted_as_in_a_browser();
  test_deeply_nested_timers_wait_at_least_4_ms();
  test_a_timer_callback_must_be_a_function();
  test_an_exception_from_a_timer_is_reported();
  test_promise_jobs_run_after_each_timer();
  test_performance_now();
  test_console();
  test_unhandled_rejections_are_reported_once();
  test_an_exception_from_a_job_is_reported();
  test_the_close_error_is_not_reported();
  test_console_stops_js_after_close();
  test_no_timer_starts_after_close();
  test_close_frees_pending_timers_and_rejections();
  test_js_files();
  puts("engine tests passed");
  return 0;
}
