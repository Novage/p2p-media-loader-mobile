#include "player.h"

#include <stdlib.h>

// The JS functions below follow QuickJS's convention: the arguments stay
// QuickJS's, and the returned value is new, for the caller to free.

struct p2pml_player {
  p2pml_player_client client;
  // The listener of the events, or undefined.
  JSValue listener;
};

p2pml_player *p2pml_player_new(const p2pml_player_client *client) {
  p2pml_player *player = calloc(1, sizeof *player);
  if (player == NULL) {
    return NULL;
  }
  player->client = *client;
  player->listener = JS_UNDEFINED;
  return player;
}

void p2pml_player_free(p2pml_player *player, JSContext *context) {
  if (player == NULL) {
    return;
  }
  JS_FreeValue(context, player->listener);
  free(player);
}

static p2pml_player *player_of(JSContext *context) {
  return p2pml_engine_player(JS_GetContextOpaque(context));
}

bool p2pml_player_event(p2pml_engine *engine, const char *name, int32_t id, size_t text_count,
                        p2pml_arg *texts) {
  p2pml_player *player = p2pml_engine_player(engine);
  JSContext *context = p2pml_engine_context(engine);
  if (text_count > P2PML_CHANNEL_MAX_ARGS || !JS_IsFunction(context, player->listener)) {
    return false;
  }
  return p2pml_listener_call(engine, player->listener, id, name, text_count, texts);
}

// playerListen(listener): the function that gets every event, as
// listener(id, name, ...texts).
static JSValue player_listen(JSContext *context, JSValueConst this_value, int argc,
                             JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  p2pml_player *player = player_of(context);
  JS_FreeValue(context, player->listener);
  player->listener = JS_DupValue(context, argv[0]);
  return JS_UNDEFINED;
}

// playerMiss(id), playerData(id, Uint8Array) and playerFail(id, message):
// the answer to load `id`.
static JSValue player_answer(JSContext *context, JSValueConst this_value, int argc,
                             JSValueConst *argv, int kind) {
  (void)this_value;
  (void)argc;
  p2pml_player_client *client = &player_of(context)->client;
  int32_t id = 0;
  if (JS_ToInt32(context, &id, argv[0]) < 0) {
    return JS_EXCEPTION;
  }
  if (kind == 0) {
    client->miss(client->data, id);
  } else if (kind == 1) {
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
    client->segment(client->data, id, bytes + offset, length);
  } else {
    size_t length = 0;
    const char *message = JS_ToCStringLen(context, &length, argv[1]);
    if (message == NULL) {
      return JS_EXCEPTION;
    }
    client->fail(client->data, id, (const uint8_t *)message, length);
    JS_FreeCString(context, message);
  }
  return JS_UNDEFINED;
}

static const JSCFunctionListEntry player_functions[] = {
    JS_CFUNC_DEF("playerListen", 1, player_listen),
    JS_CFUNC_MAGIC_DEF("playerMiss", 1, player_answer, 0),
    JS_CFUNC_MAGIC_DEF("playerData", 2, player_answer, 1),
    JS_CFUNC_MAGIC_DEF("playerFail", 2, player_answer, 2),
};

bool p2pml_player_add(JSContext *context, JSValueConst natives) {
  return JS_SetPropertyFunctionList(context, natives, player_functions,
                                    (int)(sizeof player_functions / sizeof player_functions[0])) ==
         0;
}
