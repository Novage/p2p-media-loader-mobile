#include "websocket.h"

#include <stdlib.h>

// The JS functions below follow QuickJS's convention: the arguments stay
// QuickJS's, and the returned value is new, for the caller to free.

struct p2pml_ws {
  p2pml_ws_client client;
  // The open sockets, their listener and their events; a cancel goes through
  // it, so that it forgets the socket.
  p2pml_channel *channel;
};

static const char *const ENDING[] = {"close", NULL};
// An open or a message that JS did not take fails the socket: else JS could
// miss a message and go on, or wait for an open that it lost.
static const char *const NEEDED[] = {"open", "message", NULL};
// A failed close, as when the connection fails: 1006, not clean.
static const p2pml_arg FAILURE_ARGS[] = {
    {.type = P2PML_ARG_INT, .number = 1006},
    {.type = P2PML_ARG_STRING, .pointer = "", .length = 0},
    {.type = P2PML_ARG_BOOL, .number = 0},
    {.type = P2PML_ARG_BOOL, .number = 1},
};
static const p2pml_channel_rules RULES = {
    .ending = ENDING,
    .needed = NEEDED,
    .failure = "close",
    .failure_args = FAILURE_ARGS,
    .failure_arg_count = sizeof FAILURE_ARGS / sizeof FAILURE_ARGS[0],
};

p2pml_ws *p2pml_ws_new(const p2pml_ws_client *client) {
  p2pml_ws *ws = calloc(1, sizeof *ws);
  if (ws == NULL) {
    return NULL;
  }
  ws->client = *client;
  ws->channel = p2pml_channel_new(&RULES, client->cancel, client->data);
  if (ws->channel == NULL) {
    free(ws);
    return NULL;
  }
  return ws;
}

void p2pml_ws_free(p2pml_ws *ws, JSContext *context) {
  if (ws == NULL) {
    return;
  }
  p2pml_channel_free(ws->channel, context);
  free(ws);
}

p2pml_channel *p2pml_ws_channel(p2pml_ws *ws) { return ws->channel; }

static p2pml_ws *ws_of(JSContext *context) { return p2pml_engine_ws(JS_GetContextOpaque(context)); }

// wsOpen(url, protocols): opens a socket and returns its id. `protocols` is
// an array of strings.
static JSValue ws_open(JSContext *context, JSValueConst this_value, int argc, JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  p2pml_ws *ws = ws_of(context);
  uint32_t count = 0;
  const char **protocols = p2pml_strings_new(context, argv[1], &count);
  if (protocols == NULL) {
    return JS_EXCEPTION;
  }
  const char *url = JS_ToCString(context, argv[0]);
  JSValue id = JS_EXCEPTION;
  int32_t opened = url != NULL ? p2pml_channel_add(ws->channel) : 0;
  if (opened != 0) {
    ws->client.open(ws->client.data, opened, url, count, protocols);
    id = JS_NewInt32(context, opened);
  } else if (url != NULL) {
    JS_ThrowOutOfMemory(context);
  }
  JS_FreeCString(context, url);
  p2pml_strings_free(context, protocols, count);
  return id;
}

// wsSend(id, data): sends a string as UTF-8 text, or a Uint8Array as binary.
static JSValue ws_send(JSContext *context, JSValueConst this_value, int argc, JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  p2pml_ws *ws = ws_of(context);
  int32_t id = 0;
  if (JS_ToInt32(context, &id, argv[0]) < 0) {
    return JS_EXCEPTION;
  }
  if (JS_IsString(argv[1])) {
    size_t length = 0;
    const char *text = JS_ToCStringLen(context, &length, argv[1]);
    if (text == NULL) {
      return JS_EXCEPTION;
    }
    ws->client.send(ws->client.data, id, false, (const uint8_t *)text, length);
    JS_FreeCString(context, text);
    return JS_UNDEFINED;
  }
  size_t offset = 0;
  size_t length = 0;
  JSValue buffer = JS_GetTypedArrayBuffer(context, argv[1], &offset, &length, NULL);
  if (JS_IsException(buffer)) {
    return buffer;
  }
  size_t size = 0;
  const uint8_t *bytes = JS_GetArrayBuffer(context, &size, buffer);
  JS_FreeValue(context, buffer);
  if (bytes == NULL) {
    return JS_EXCEPTION;
  }
  ws->client.send(ws->client.data, id, true, bytes + offset, length);
  return JS_UNDEFINED;
}

// wsClose(id, code, reason): starts the closing handshake; `code` is -1 for
// none, `reason` a string.
static JSValue ws_close(JSContext *context, JSValueConst this_value, int argc, JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  p2pml_ws *ws = ws_of(context);
  int32_t id = 0;
  int32_t code = 0;
  if (JS_ToInt32(context, &id, argv[0]) < 0 || JS_ToInt32(context, &code, argv[1]) < 0) {
    return JS_EXCEPTION;
  }
  size_t length = 0;
  const char *reason = JS_ToCStringLen(context, &length, argv[2]);
  if (reason == NULL) {
    return JS_EXCEPTION;
  }
  ws->client.close(ws->client.data, id, code, (const uint8_t *)reason, length);
  JS_FreeCString(context, reason);
  return JS_UNDEFINED;
}

// wsCancel(id): stops the socket at once, if it is open.
static JSValue ws_cancel(JSContext *context, JSValueConst this_value, int argc,
                         JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  int32_t id = 0;
  if (JS_ToInt32(context, &id, argv[0]) < 0) {
    return JS_EXCEPTION;
  }
  p2pml_channel_cancel(ws_of(context)->channel, id);
  return JS_UNDEFINED;
}

// wsListen(listener): the function that gets every event, as
// listener(id, "open", protocol, extensions),
// listener(id, "message", data) with a string (text) or a Uint8Array
// (binary), and
// listener(id, "close", code, reason, wasClean, failed).
static JSValue ws_listen(JSContext *context, JSValueConst this_value, int argc,
                         JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  p2pml_channel_listen(ws_of(context)->channel, context, argv[0]);
  return JS_UNDEFINED;
}

static const JSCFunctionListEntry ws_functions[] = {
    JS_CFUNC_DEF("wsOpen", 2, ws_open),     JS_CFUNC_DEF("wsSend", 2, ws_send),
    JS_CFUNC_DEF("wsClose", 3, ws_close),   JS_CFUNC_DEF("wsCancel", 1, ws_cancel),
    JS_CFUNC_DEF("wsListen", 1, ws_listen),
};

bool p2pml_ws_add(JSContext *context, JSValueConst natives) {
  return JS_SetPropertyFunctionList(context, natives, ws_functions,
                                    (int)(sizeof ws_functions / sizeof ws_functions[0])) == 0;
}

bool p2pml_ws_open(p2pml_engine *engine, int32_t id, const char *protocol, const char *extensions) {
  p2pml_arg args[] = {p2pml_text_arg(protocol), p2pml_text_arg(extensions)};
  return p2pml_channel_post(engine, P2PML_CHANNEL_WS, id, "open", sizeof args / sizeof args[0],
                            args);
}

bool p2pml_ws_message(p2pml_engine *engine, int32_t id, bool binary, const uint8_t *bytes,
                      size_t length) {
  p2pml_arg arg = {
      .type = binary ? P2PML_ARG_BYTES : P2PML_ARG_STRING, .pointer = bytes, .length = length};
  return p2pml_channel_post(engine, P2PML_CHANNEL_WS, id, "message", 1, &arg);
}

bool p2pml_ws_close(p2pml_engine *engine, int32_t id, int code, const uint8_t *reason,
                    size_t reason_length, bool was_clean, bool failed) {
  p2pml_arg args[] = {
      {.type = P2PML_ARG_INT, .number = code},
      {.type = P2PML_ARG_STRING, .pointer = reason, .length = reason_length},
      {.type = P2PML_ARG_BOOL, .number = was_clean},
      {.type = P2PML_ARG_BOOL, .number = failed},
  };
  return p2pml_channel_post(engine, P2PML_CHANNEL_WS, id, "close", sizeof args / sizeof args[0],
                            args);
}
