#include "engine.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

typedef struct task {
  struct task *next;
  p2pml_task_run *run;
  p2pml_task_discard *discard;
  void *data;
} task;

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
};

p2pml_engine *p2pml_engine_new(void) {
  p2pml_engine *engine = calloc(1, sizeof *engine);
  if (engine == NULL) {
    return NULL;
  }
  if (pthread_mutex_init(&engine->mutex, NULL) != 0) {
    free(engine);
    return NULL;
  }
  if (pthread_cond_init(&engine->wake, NULL) != 0) {
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

// Takes the next task, or returns NULL once the engine is closed.
static task *take_task(p2pml_engine *engine) {
  pthread_mutex_lock(&engine->mutex);
  while (!atomic_load(&engine->closed) && engine->head == NULL) {
    pthread_cond_wait(&engine->wake, &engine->mutex);
  }
  task *next = NULL;
  if (!atomic_load(&engine->closed)) {
    next = engine->head;
    engine->head = next->next;
    if (engine->head == NULL) {
      engine->tail = NULL;
    }
  }
  pthread_mutex_unlock(&engine->mutex);
  return next;
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
      // A job throws when a FinalizationRegistry callback throws, or when
      // QuickJS fails, such as out of memory: a Promise reaction turns an
      // exception into a rejection, also the error of the close. Not reported
      // yet: the report of uncaught errors comes with the console.
      JS_FreeValue(engine->context, JS_GetException(engine->context));
    }
  }
}

// Stops JS that still runs when the engine closes, such as an endless loop.
// QuickJS calls it from time to time while JS runs; its error is uncatchable.
static int interrupt_if_closed(JSRuntime *runtime, void *opaque) {
  (void)runtime;
  p2pml_engine *engine = opaque;
  return atomic_load(&engine->closed);
}

bool p2pml_engine_run(p2pml_engine *engine) {
  // Created on this thread, which QuickJS takes as the top of its stack.
  engine->runtime = JS_NewRuntime();
  if (engine->runtime != NULL) {
    JS_SetInterruptHandler(engine->runtime, interrupt_if_closed, engine);
    engine->context = JS_NewContext(engine->runtime);
  }
  bool started = engine->context != NULL;
  if (!started) {
    p2pml_engine_close(engine);
  }
  task *next;
  while ((next = take_task(engine)) != NULL) {
    next->run(engine, next->data);
    free(next);
    run_jobs(engine);
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
  if (JS_IsObject(exception)) {
    JSValue stack = JS_GetPropertyStr(context, exception, "stack");
    if (JS_IsException(stack)) {
      JS_FreeValue(context, JS_GetException(context));
    } else if (!JS_IsUndefined(stack)) {
      result->stack = to_string(context, stack);
    }
    JS_FreeValue(context, stack);
  }
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
