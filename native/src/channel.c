#include "channel.h"

#include <stdlib.h>
#include <string.h>

struct p2pml_channel {
  const p2pml_channel_rules *rules;
  void (*stop)(void *data, int32_t id);
  void *data;
  int32_t last_id;
  // The items that are open: added, and not ended, failed or removed.
  int32_t *open;
  size_t open_count;
  size_t open_capacity;
  // The listener, or undefined.
  JSValue listener;
};

p2pml_channel *p2pml_channel_new(const p2pml_channel_rules *rules,
                                 void (*stop)(void *data, int32_t id), void *data) {
  p2pml_channel *channel = calloc(1, sizeof *channel);
  if (channel == NULL) {
    return NULL;
  }
  channel->rules = rules;
  channel->stop = stop;
  channel->data = data;
  channel->listener = JS_UNDEFINED;
  return channel;
}

void p2pml_channel_free(p2pml_channel *channel, JSContext *context) {
  if (channel == NULL) {
    return;
  }
  for (size_t i = 0; i < channel->open_count; i++) {
    channel->stop(channel->data, channel->open[i]);
  }
  free(channel->open);
  JS_FreeValue(context, channel->listener);
  free(channel);
}

static size_t find_open(const p2pml_channel *channel, int32_t id) {
  for (size_t i = 0; i < channel->open_count; i++) {
    if (channel->open[i] == id) {
      return i;
    }
  }
  return channel->open_count;
}

int32_t p2pml_channel_add(p2pml_channel *channel) {
  if (channel->open_count == channel->open_capacity) {
    size_t capacity = channel->open_capacity == 0 ? 8 : channel->open_capacity * 2;
    int32_t *open = realloc(channel->open, capacity * sizeof *open);
    if (open == NULL) {
      return 0;
    }
    channel->open = open;
    channel->open_capacity = capacity;
  }
  int32_t id = ++channel->last_id;
  channel->open[channel->open_count++] = id;
  return id;
}

// True when `id` was open.
static bool remove_open(p2pml_channel *channel, int32_t id) {
  size_t i = find_open(channel, id);
  if (i == channel->open_count) {
    return false;
  }
  channel->open[i] = channel->open[--channel->open_count];
  return true;
}

bool p2pml_channel_cancel(p2pml_channel *channel, int32_t id) {
  bool open = remove_open(channel, id);
  if (open) {
    channel->stop(channel->data, id);
  }
  return open;
}

void p2pml_channel_listen(p2pml_channel *channel, JSContext *context, JSValueConst listener) {
  JS_FreeValue(context, channel->listener);
  channel->listener = JS_DupValue(context, listener);
}

static bool named(const char *const *names, const char *name) {
  for (; *names != NULL; names++) {
    if (strcmp(*names, name) == 0) {
      return true;
    }
  }
  return false;
}

// An event, copied for its task: its arguments own their memory.
typedef struct {
  p2pml_channel_id which;
  int32_t id;
  const char *name;
  size_t arg_count;
  p2pml_arg args[];
} event;

static void free_event(void *data) {
  event *freed = data;
  for (size_t i = 0; i < freed->arg_count; i++) {
    p2pml_arg *arg = &freed->args[i];
    if (arg->type == P2PML_ARG_STRINGS && arg->pointer != NULL) {
      char **strings = (char **)arg->pointer;
      for (size_t j = 0; j < arg->length; j++) {
        free(strings[j]);
      }
    }
    free((void *)arg->pointer);
  }
  free(freed);
}

// A copy of `length` bytes; malloc(0) can give NULL, so never 0. NULL when out
// of memory.
static void *copy_of(const void *bytes, size_t length) {
  void *copy = malloc(length > 0 ? length : 1);
  if (copy != NULL && length > 0) {
    memcpy(copy, bytes, length);
  }
  return copy;
}

// A copy of an event and its arguments. NULL when out of memory.
static event *new_event(p2pml_channel_id which, int32_t id, const char *name, size_t arg_count,
                        const p2pml_arg *args) {
  event *made = calloc(1, sizeof *made + arg_count * sizeof made->args[0]);
  if (made == NULL) {
    return NULL;
  }
  made->which = which;
  made->id = id;
  made->name = name;
  bool copied = true;
  for (size_t i = 0; copied && i < arg_count; i++) {
    p2pml_arg *arg = &made->args[i];
    *arg = (p2pml_arg){.type = args[i].type, .number = args[i].number, .length = args[i].length};
    made->arg_count = i + 1;
    if (args[i].type == P2PML_ARG_STRING || args[i].type == P2PML_ARG_BYTES) {
      arg->pointer = copy_of(args[i].pointer, args[i].length);
      copied = arg->pointer != NULL;
    } else if (args[i].type == P2PML_ARG_STRINGS) {
      const char *const *strings = (const char *const *)args[i].pointer;
      // calloc(0) can give NULL, so never 0.
      char **copies = (char **)calloc(args[i].length + 1, sizeof *copies);
      arg->pointer = (const void *)copies;
      copied = copies != NULL;
      for (size_t j = 0; copied && j < args[i].length; j++) {
        copies[j] = strdup(strings[j]);
        copied = copies[j] != NULL;
      }
    }
  }
  if (!copied) {
    free_event(made);
    return NULL;
  }
  return made;
}

static void free_bytes(JSRuntime *runtime, void *opaque, void *bytes) {
  (void)runtime;
  (void)opaque;
  free(bytes);
}

// The JS value of an argument, new, for the caller to free; JS_EXCEPTION when
// out of memory. Bytes go to the new ArrayBuffer, which frees them.
static JSValue value_of(JSContext *context, p2pml_arg *arg) {
  switch (arg->type) {
  case P2PML_ARG_INT:
    return JS_NewInt32(context, arg->number);
  case P2PML_ARG_BOOL:
    return JS_NewBool(context, arg->number != 0);
  case P2PML_ARG_STRING:
    return JS_NewStringLen(context, arg->pointer, arg->length);
  case P2PML_ARG_STRINGS: {
    const char *const *strings = (const char *const *)arg->pointer;
    JSValue array = JS_NewArray(context);
    for (size_t i = 0; !JS_IsException(array) && i < arg->length; i++) {
      JSValue text = JS_NewString(context, strings[i]);
      if (JS_IsException(text) ||
          JS_DefinePropertyValueUint32(context, array, (uint32_t)i, text, JS_PROP_C_W_E) < 0) {
        JS_FreeValue(context, array);
        array = JS_EXCEPTION;
      }
    }
    return array;
  }
  case P2PML_ARG_BYTES: {
    JSValue buffer =
        JS_NewArrayBuffer(context, (uint8_t *)arg->pointer, arg->length, free_bytes, NULL, false);
    if (JS_IsException(buffer)) {
      return buffer;
    }
    // The buffer owns the bytes now.
    arg->pointer = NULL;
    JSValueConst typed[] = {buffer, JS_UNDEFINED, JS_UNDEFINED};
    JSValue array = JS_NewTypedArray(context, 3, typed, JS_TYPED_ARRAY_UINT8);
    JS_FreeValue(context, buffer);
    return array;
  }
  }
  return JS_UNDEFINED;
}

// Gives the event to the listener; `listener` stays the caller's. False, after
// its report, when JS throws, also when out of memory.
static bool give_to_js(p2pml_engine *engine, JSContext *context, JSValueConst listener, int32_t id,
                       const char *name, size_t arg_count, p2pml_arg *args) {
  JSValue arguments[2 + P2PML_CHANNEL_MAX_ARGS];
  size_t count = 2 + arg_count;
  arguments[0] = JS_NewInt32(context, id);
  arguments[1] = JS_NewString(context, name);
  bool made = !JS_IsException(arguments[1]);
  for (size_t i = 0; i < arg_count; i++) {
    arguments[2 + i] = made ? value_of(context, &args[i]) : JS_UNDEFINED;
    made = made && !JS_IsException(arguments[2 + i]);
  }
  JSValue result =
      made ? JS_Call(context, listener, JS_UNDEFINED, (int)count, arguments) : JS_EXCEPTION;
  bool given = !JS_IsException(result);
  if (!given) {
    p2pml_engine_report_exception(engine);
  }
  JS_FreeValue(context, result);
  for (size_t i = 0; i < count; i++) {
    JS_FreeValue(context, arguments[i]);
  }
  return given;
}

// The task of an event: gives it to the listener, if its item is open.
static void run_event(p2pml_engine *engine, void *data) {
  event *happened = data;
  p2pml_channel *channel = p2pml_engine_channel(engine, happened->which);
  const p2pml_channel_rules *rules = channel->rules;
  bool open = find_open(channel, happened->id) < channel->open_count;
  if (open && named(rules->ending, happened->name)) {
    remove_open(channel, happened->id);
  }
  JSContext *context = p2pml_engine_context(engine);
  // A needed event that JS did not take fails its item (see the rules).
  if (open && JS_IsFunction(context, channel->listener) &&
      !give_to_js(engine, context, channel->listener, happened->id, happened->name,
                  happened->arg_count, happened->args) &&
      named(rules->needed, happened->name) && p2pml_channel_cancel(channel, happened->id)) {
    // A copy, because value_of takes a changeable argument and the rules are
    // const; on the stack, because this path runs also when out of memory.
    p2pml_arg failure_args[P2PML_CHANNEL_MAX_ARGS];
    memcpy(failure_args, rules->failure_args, rules->failure_arg_count * sizeof failure_args[0]);
    give_to_js(engine, context, channel->listener, happened->id, rules->failure,
               rules->failure_arg_count, failure_args);
  }
  free_event(happened);
}

bool p2pml_channel_post(p2pml_engine *engine, p2pml_channel_id which, int32_t id, const char *name,
                        size_t arg_count, const p2pml_arg *args) {
  event *happened =
      arg_count <= P2PML_CHANNEL_MAX_ARGS ? new_event(which, id, name, arg_count, args) : NULL;
  if (happened == NULL) {
    return false;
  }
  if (!p2pml_engine_post(engine, run_event, free_event, happened)) {
    free_event(happened);
    return false;
  }
  return true;
}
