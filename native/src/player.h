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

// The platform's side of the bridge: the answers to the loads, one for each
// load. The engine calls it on the engine thread; the bytes stay the engine's,
// valid only during the call.
struct p2pml_player_client {
  // The core does not load segment `id`: the player loads it itself.
  void (*miss)(void *data, int32_t id);
  // The whole segment of load `id`.
  void (*segment)(void *data, int32_t id, const uint8_t *bytes, size_t length);
  // Load `id` failed: `message`, UTF-8, says why.
  void (*fail)(void *data, int32_t id, const uint8_t *message, size_t length);
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
// Adds playerListen, playerMiss, playerData and playerFail to `natives`,
// which stays the caller's. False when out of memory.
bool p2pml_player_add(JSContext *context, JSValueConst natives);
// Frees the state, before the runtime is freed.
void p2pml_player_free(p2pml_player *player, JSContext *context);

#endif
