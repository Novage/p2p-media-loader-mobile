#include "http.h"

#include <stdlib.h>
#include <string.h>

// The JS functions below follow QuickJS's convention: the arguments stay
// QuickJS's, and the returned value is new, for the caller to free.

struct p2pml_http {
  p2pml_http_client client;
  // The open requests, their listener and their events; a cancel goes
  // through it, so that it forgets the request.
  p2pml_channel *channel;
};

static const char *const ENDING[] = {"end", "fail", NULL};
// A response or data that JS did not take fails the request: else JS could get
// a body with a gap, or wait for a response that it lost.
static const char *const NEEDED[] = {"response", "data", NULL};
static const char LOST[] = "the engine could not take an event";
static const p2pml_arg FAILURE_ARGS[] = {
    {.type = P2PML_ARG_STRING, .pointer = LOST, .length = sizeof LOST - 1},
};
static const p2pml_channel_rules RULES = {
    .ending = ENDING,
    .needed = NEEDED,
    .failure = "fail",
    .failure_args = FAILURE_ARGS,
    .failure_arg_count = sizeof FAILURE_ARGS / sizeof FAILURE_ARGS[0],
};

p2pml_http *p2pml_http_new(const p2pml_http_client *client) {
  p2pml_http *http = calloc(1, sizeof *http);
  if (http == NULL) {
    return NULL;
  }
  http->client = *client;
  http->channel = p2pml_channel_new(&RULES, client->cancel, client->data);
  if (http->channel == NULL) {
    free(http);
    return NULL;
  }
  return http;
}

void p2pml_http_free(p2pml_http *http, JSContext *context) {
  if (http == NULL) {
    return;
  }
  p2pml_channel_free(http->channel, context);
  free(http);
}

p2pml_channel *p2pml_http_channel(p2pml_http *http) { return http->channel; }

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
  int32_t started = url != NULL ? p2pml_channel_add(http->channel) : 0;
  if (started != 0) {
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
  int32_t id = 0;
  if (JS_ToInt32(context, &id, argv[0]) < 0) {
    return JS_EXCEPTION;
  }
  p2pml_channel_cancel(http_of(context)->channel, id);
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
  p2pml_channel_listen(http_of(context)->channel, context, argv[0]);
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

static p2pml_arg text_arg(const char *text) {
  return (p2pml_arg){.type = P2PML_ARG_STRING, .pointer = text, .length = strlen(text)};
}

bool p2pml_http_response(p2pml_engine *engine, int32_t id, int status, const char *status_text,
                         const char *url, size_t header_count, const char *const *headers) {
  p2pml_arg args[] = {
      {.type = P2PML_ARG_INT, .number = status},
      text_arg(status_text),
      text_arg(url),
      {.type = P2PML_ARG_STRINGS, .pointer = (const void *)headers, .length = 2 * header_count},
  };
  return p2pml_channel_post(engine, P2PML_CHANNEL_HTTP, id, "response",
                            sizeof args / sizeof args[0], args);
}

bool p2pml_http_data(p2pml_engine *engine, int32_t id, const uint8_t *bytes, size_t length) {
  p2pml_arg arg = {.type = P2PML_ARG_BYTES, .pointer = bytes, .length = length};
  return p2pml_channel_post(engine, P2PML_CHANNEL_HTTP, id, "data", 1, &arg);
}

bool p2pml_http_end(p2pml_engine *engine, int32_t id) {
  return p2pml_channel_post(engine, P2PML_CHANNEL_HTTP, id, "end", 0, NULL);
}

bool p2pml_http_fail(p2pml_engine *engine, int32_t id, const char *message) {
  p2pml_arg arg = text_arg(message);
  return p2pml_channel_post(engine, P2PML_CHANNEL_HTTP, id, "fail", 1, &arg);
}
