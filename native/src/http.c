#include "http.h"

#include <stdlib.h>
#include <string.h>

// The JS functions below follow QuickJS's convention: the arguments stay
// QuickJS's, and the returned value is new, for the caller to free.

struct p2pml_http {
  p2pml_http_client client;
  int32_t last_id;
  // The requests that are open: started, and not ended, failed or cancelled.
  int32_t *open;
  size_t open_count;
  size_t open_capacity;
  // natives.httpListen's function, or undefined.
  JSValue listener;
};

p2pml_http *p2pml_http_new(const p2pml_http_client *client) {
  p2pml_http *http = calloc(1, sizeof *http);
  if (http == NULL) {
    return NULL;
  }
  http->client = *client;
  http->listener = JS_UNDEFINED;
  return http;
}

void p2pml_http_free(p2pml_http *http, JSContext *context) {
  if (http == NULL) {
    return;
  }
  for (size_t i = 0; i < http->open_count; i++) {
    http->client.cancel(http->client.data, http->open[i]);
  }
  free(http->open);
  JS_FreeValue(context, http->listener);
  free(http);
}

static size_t find_open(const p2pml_http *http, int32_t id) {
  for (size_t i = 0; i < http->open_count; i++) {
    if (http->open[i] == id) {
      return i;
    }
  }
  return http->open_count;
}

static bool add_open(p2pml_http *http, int32_t id) {
  if (http->open_count == http->open_capacity) {
    size_t capacity = http->open_capacity == 0 ? 8 : http->open_capacity * 2;
    int32_t *open = realloc(http->open, capacity * sizeof *open);
    if (open == NULL) {
      return false;
    }
    http->open = open;
    http->open_capacity = capacity;
  }
  http->open[http->open_count++] = id;
  return true;
}

// True when `id` was open.
static bool remove_open(p2pml_http *http, int32_t id) {
  size_t i = find_open(http, id);
  if (i == http->open_count) {
    return false;
  }
  http->open[i] = http->open[--http->open_count];
  return true;
}

static p2pml_http *http_of(JSContext *context) {
  return p2pml_engine_http(JS_GetContextOpaque(context));
}

// httpStart(method, url, headers): starts a request and returns its id.
// `headers` is an array of names and values, in turn.
static JSValue http_start(JSContext *context, JSValueConst this_value, int argc,
                          JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  p2pml_http *http = http_of(context);
  JSValue count_value = JS_GetPropertyStr(context, argv[2], "length");
  uint32_t count = 0;
  int converted = JS_ToUint32(context, &count, count_value);
  JS_FreeValue(context, count_value);
  if (converted < 0) {
    return JS_EXCEPTION;
  }
  size_t pairs = count / 2;
  const char **texts = (const char **)calloc((size_t)count + 1, sizeof *texts);
  if (texts == NULL) {
    return JS_ThrowOutOfMemory(context);
  }
  bool ok = true;
  for (uint32_t i = 0; ok && i < 2 * pairs; i++) {
    JSValue text = JS_GetPropertyUint32(context, argv[2], i);
    texts[i] = JS_ToCString(context, text);
    JS_FreeValue(context, text);
    ok = texts[i] != NULL;
  }
  const char *method = ok ? JS_ToCString(context, argv[0]) : NULL;
  const char *url = method != NULL ? JS_ToCString(context, argv[1]) : NULL;
  JSValue id = JS_EXCEPTION;
  if (url != NULL && add_open(http, http->last_id + 1)) {
    int32_t started = ++http->last_id;
    http->client.start(http->client.data, started, method, url, pairs, texts);
    id = JS_NewInt32(context, started);
  } else if (url != NULL) {
    JS_ThrowOutOfMemory(context);
  }
  JS_FreeCString(context, url);
  JS_FreeCString(context, method);
  // JS_FreeCString does nothing for NULL: the texts after a failure.
  for (size_t i = 0; i < 2 * pairs; i++) {
    JS_FreeCString(context, texts[i]);
  }
  free((void *)texts);
  return id;
}

// httpCancel(id): cancels the request, if it is open.
static JSValue http_cancel(JSContext *context, JSValueConst this_value, int argc,
                           JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  p2pml_http *http = http_of(context);
  int32_t id = 0;
  if (JS_ToInt32(context, &id, argv[0]) < 0) {
    return JS_EXCEPTION;
  }
  if (remove_open(http, id)) {
    http->client.cancel(http->client.data, id);
  }
  return JS_UNDEFINED;
}

// httpListen(listener): the function that gets every event, as
// listener(id, "response", status, statusText, url, headers),
// listener(id, "data", bytes), listener(id, "end") and
// listener(id, "fail", message).
static JSValue http_listen(JSContext *context, JSValueConst this_value, int argc,
                           JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  p2pml_http *http = http_of(context);
  JS_FreeValue(context, http->listener);
  http->listener = JS_DupValue(context, argv[0]);
  return JS_UNDEFINED;
}

static const JSCFunctionListEntry http_functions[] = {
    JS_CFUNC_DEF("httpStart", 3, http_start),
    JS_CFUNC_DEF("httpCancel", 1, http_cancel),
    JS_CFUNC_DEF("httpListen", 1, http_listen),
};

bool p2pml_http_add(JSContext *context, JSValueConst natives) {
  return JS_SetPropertyFunctionList(context, natives, http_functions,
                                    (int)(sizeof http_functions / sizeof http_functions[0])) == 0;
}

typedef enum { EVENT_RESPONSE, EVENT_DATA, EVENT_END, EVENT_FAIL } event_kind;

static const char *const EVENT_NAMES[] = {"response", "data", "end", "fail"};

// An event of a request, copied for its task.
typedef struct {
  event_kind kind;
  int32_t id;
  int status;
  // The status text, or the failure's message.
  char *text;
  char *url;
  size_t header_count;
  // The names and values, in turn.
  char **headers;
  uint8_t *bytes;
  size_t length;
} event;

static void free_event(void *data) {
  event *freed = data;
  free(freed->text);
  free(freed->url);
  for (size_t i = 0; freed->headers != NULL && i < 2 * freed->header_count; i++) {
    free(freed->headers[i]);
  }
  free((void *)freed->headers);
  free(freed->bytes);
  free(freed);
}

static void free_bytes(JSRuntime *runtime, void *opaque, void *bytes) {
  (void)runtime;
  (void)opaque;
  free(bytes);
}

// The arguments of the listener after the id and the name, new values for
// the caller to free. Takes the event's bytes. Count in `*count`; false with
// an exception when out of memory, and then nothing to free.
static bool arguments_of(JSContext *context, event *happened, JSValue *arguments, int *count) {
  *count = 0;
  switch (happened->kind) {
  case EVENT_RESPONSE: {
    JSValue headers = JS_NewArray(context);
    for (size_t i = 0; !JS_IsException(headers) && i < 2 * happened->header_count; i++) {
      JSValue text = JS_NewString(context, happened->headers[i]);
      if (JS_IsException(text) ||
          JS_DefinePropertyValueUint32(context, headers, (uint32_t)i, text, JS_PROP_C_W_E) < 0) {
        JS_FreeValue(context, headers);
        headers = JS_EXCEPTION;
      }
    }
    arguments[0] = JS_NewInt32(context, happened->status);
    arguments[1] = JS_NewString(context, happened->text);
    arguments[2] = JS_NewString(context, happened->url);
    arguments[3] = headers;
    *count = 4;
    break;
  }
  case EVENT_DATA: {
    JSValue buffer =
        JS_NewArrayBuffer(context, happened->bytes, happened->length, free_bytes, NULL, false);
    if (!JS_IsException(buffer)) {
      // The buffer owns the bytes now.
      happened->bytes = NULL;
    }
    JSValueConst typed[] = {buffer, JS_UNDEFINED, JS_UNDEFINED};
    arguments[0] = JS_IsException(buffer)
                       ? JS_EXCEPTION
                       : JS_NewTypedArray(context, 3, typed, JS_TYPED_ARRAY_UINT8);
    JS_FreeValue(context, buffer);
    *count = 1;
    break;
  }
  case EVENT_END:
    break;
  case EVENT_FAIL:
    arguments[0] = JS_NewString(context, happened->text);
    *count = 1;
    break;
  }
  for (int i = 0; i < *count; i++) {
    if (JS_IsException(arguments[i])) {
      for (int j = 0; j < *count; j++) {
        JS_FreeValue(context, arguments[j]);
      }
      *count = 0;
      return false;
    }
  }
  return true;
}

// Gives the event to the listener; `listener` stays the caller's. False, after
// its report, when JS throws.
static bool give_to_js(p2pml_engine *engine, JSContext *context, JSValueConst listener,
                       event *happened) {
  JSValue arguments[6];
  arguments[0] = JS_NewInt32(context, happened->id);
  arguments[1] = JS_NewString(context, EVENT_NAMES[happened->kind]);
  int count = 0;
  JSValue result = JS_EXCEPTION;
  if (!JS_IsException(arguments[1]) && arguments_of(context, happened, arguments + 2, &count)) {
    result = JS_Call(context, listener, JS_UNDEFINED, 2 + count, arguments);
  }
  bool given = !JS_IsException(result);
  if (!given) {
    p2pml_engine_report_exception(engine);
  }
  JS_FreeValue(context, result);
  for (int i = 0; i < 2 + count; i++) {
    JS_FreeValue(context, arguments[i]);
  }
  return given;
}

// The task of an event: gives it to the listener, if the request is open.
static void run_event(p2pml_engine *engine, void *data) {
  event *happened = data;
  p2pml_http *http = p2pml_engine_http(engine);
  bool open = find_open(http, happened->id) < http->open_count;
  if (open && (happened->kind == EVENT_END || happened->kind == EVENT_FAIL)) {
    remove_open(http, happened->id);
  }
  JSContext *context = p2pml_engine_context(engine);
  // A response or data that JS did not take (out of memory, or a defect of the
  // polyfill) fails the request: else JS could get a body with a gap, or wait
  // for a response that it lost.
  if (open && JS_IsFunction(context, http->listener) &&
      !give_to_js(engine, context, http->listener, happened) &&
      (happened->kind == EVENT_RESPONSE || happened->kind == EVENT_DATA) &&
      remove_open(http, happened->id)) {
    http->client.cancel(http->client.data, happened->id);
    event failed = {.kind = EVENT_FAIL, .id = happened->id};
    failed.text = (char *)"the engine could not take an event";
    give_to_js(engine, context, http->listener, &failed);
  }
  free_event(happened);
}

static bool post_event(p2pml_engine *engine, event *happened) {
  if (!p2pml_engine_post(engine, run_event, free_event, happened)) {
    free_event(happened);
    return false;
  }
  return true;
}

static event *new_event(event_kind kind, int32_t id) {
  event *made = calloc(1, sizeof *made);
  if (made != NULL) {
    made->kind = kind;
    made->id = id;
  }
  return made;
}

bool p2pml_http_response(p2pml_engine *engine, int32_t id, int status, const char *status_text,
                         const char *url, size_t header_count, const char *const *headers) {
  event *happened = new_event(EVENT_RESPONSE, id);
  if (happened == NULL) {
    return false;
  }
  happened->status = status;
  happened->text = strdup(status_text);
  happened->url = strdup(url);
  happened->header_count = header_count;
  happened->headers = (char **)calloc(2 * header_count + 1, sizeof *happened->headers);
  bool copied = happened->text != NULL && happened->url != NULL && happened->headers != NULL;
  for (size_t i = 0; copied && i < 2 * header_count; i++) {
    happened->headers[i] = strdup(headers[i]);
    copied = happened->headers[i] != NULL;
  }
  if (!copied) {
    free_event(happened);
    return false;
  }
  return post_event(engine, happened);
}

bool p2pml_http_data(p2pml_engine *engine, int32_t id, const uint8_t *bytes, size_t length) {
  event *happened = new_event(EVENT_DATA, id);
  if (happened == NULL) {
    return false;
  }
  // malloc(0) can give NULL: an empty chunk has one byte that is not used.
  happened->bytes = malloc(length > 0 ? length : 1);
  if (happened->bytes == NULL) {
    free_event(happened);
    return false;
  }
  if (length > 0) {
    memcpy(happened->bytes, bytes, length);
  }
  happened->length = length;
  return post_event(engine, happened);
}

bool p2pml_http_end(p2pml_engine *engine, int32_t id) {
  event *happened = new_event(EVENT_END, id);
  return happened != NULL && post_event(engine, happened);
}

bool p2pml_http_fail(p2pml_engine *engine, int32_t id, const char *message) {
  event *happened = new_event(EVENT_FAIL, id);
  if (happened == NULL) {
    return false;
  }
  happened->text = strdup(message);
  if (happened->text == NULL) {
    free_event(happened);
    return false;
  }
  return post_event(engine, happened);
}
