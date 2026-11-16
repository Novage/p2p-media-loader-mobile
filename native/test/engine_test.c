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

#define CHECK(condition)                                                                           \
  do {                                                                                             \
    if (!(condition)) {                                                                            \
      fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__, __LINE__, #condition);                \
      abort();                                                                                     \
    }                                                                                              \
  } while (0)

static void *run_engine(void *engine) {
  CHECK(p2pml_engine_run(engine));
  return NULL;
}

static pthread_t start(p2pml_engine *engine) {
  pthread_t thread;
  CHECK(pthread_create(&thread, NULL, run_engine, engine) == 0);
  return thread;
}

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

// Gives the script a global mark() that sets `marked`, and closeEngine().
static void add_test_globals(p2pml_engine *engine, void *data) {
  (void)data;
  engine_to_close = engine;
  JSContext *context = p2pml_engine_context(engine);
  JSValue global = JS_GetGlobalObject(context);
  JS_SetPropertyStr(context, global, "mark", JS_NewCFunction(context, mark, "mark", 0));
  JS_SetPropertyStr(context, global, "closeEngine",
                    JS_NewCFunction(context, close_from_js, "closeEngine", 0));
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

static void test_close_stops_running_js(void) {
  atomic_store(&marked, false);
  p2pml_engine *engine = p2pml_engine_new();
  CHECK(p2pml_engine_post(engine, add_test_globals, no_discard, NULL));
  // The script cannot catch the error that stops it.
  eval_task task = {.code = "mark(); for (;;) { try { for (;;) {} } catch (e) {} }"};
  post_eval(engine, &task);
  pthread_t thread = start(engine);
  while (!atomic_load(&marked)) {
    sched_yield();
  }
  p2pml_engine_close(engine);
  stop(engine, thread);
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
  puts("engine tests passed");
  return 0;
}
