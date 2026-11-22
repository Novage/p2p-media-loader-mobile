// The player bridge (src/polyfills/player.js): the library's player
// integration gives the core its manifests and its segment loads, and gets an
// answer for each load. See specs/engine.md, "The player bridge".

#ifndef P2PML_PLAYER_H
#define P2PML_PLAYER_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "channel.h"
#include "engine.h"
#include "quickjs.h"

// The platform's side of the bridge: the answers to its calls, one for each,
// and the core's events that it listens to. The engine calls it on the engine
// thread; the bytes stay the engine's, valid only during the call. Texts are
// UTF-8.
struct p2pml_player_client {
  // The answer of query `id`: `length` bytes of JSON.
  void (*value)(void *data, int32_t id, const uint8_t *json, size_t length);
  // The whole segment of load `id`, the bandwidth (bits per second) that the
  // core measured for it, and whether its stream is of the main type.
  void (*segment)(void *data, int32_t id, const uint8_t *bytes, size_t length, double bandwidth,
                  bool main);
  // Load `id` failed: the type of the error, such as "failed" or "aborted",
  // and its message.
  void (*fail)(void *data, int32_t id, const uint8_t *type, size_t type_length,
               const uint8_t *message, size_t message_length);
  // The core's event `name`, with its details as JSON.
  void (*core_event)(void *data, const uint8_t *name, size_t name_length, const uint8_t *json,
                     size_t json_length);
  void *data;
};

// Engine thread, in a task: gives event `name` to the bridge's listener, as
// listener(id, name, ...texts), with up to P2PML_CHANNEL_MAX_ARGS texts: each
// a P2PML_ARG_STRING, or a P2PML_ARG_UNDEFINED for none. The texts stay the
// caller's. False when JS did not take it: no listener, an exception (after
// its report), out of memory, or once the engine is closed.
bool p2pml_player_event(p2pml_engine *engine, const char *name, int32_t id, size_t text_count,
                        p2pml_arg *texts);

// The engine's own use (engine.c), on the engine thread.
typedef struct p2pml_player p2pml_player;

// NULL when out of memory.
p2pml_player *p2pml_player_new(const p2pml_player_client *client);
// The engine's state (engine.c).
p2pml_player *p2pml_engine_player(p2pml_engine *engine);
// Adds playerListen, playerValue, playerData, playerFail and playerCoreEvent
// to `natives`, which stays the caller's. False when out of memory.
bool p2pml_player_add(JSContext *context, JSValueConst natives);
// Frees the state, before the runtime is freed.
void p2pml_player_free(p2pml_player *player, JSContext *context);

#endif
