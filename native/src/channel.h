// Items that the platform runs for JS, such as the requests of fetch: their
// ids, the open ones, one JS listener, and their events, which the platform
// posts from any thread and the engine gives to JS in tasks. See
// specs/engine.md, "The platform's network clients".

#ifndef P2PML_CHANNEL_H
#define P2PML_CHANNEL_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "engine.h"
#include "quickjs.h"

// The channels of an engine.
typedef enum {
  P2PML_CHANNEL_HTTP,
  P2PML_CHANNEL_WS,
  P2PML_CHANNEL_RTC,
  P2PML_CHANNEL_COUNT,
} p2pml_channel_id;

// The most arguments that an event has.
#define P2PML_CHANNEL_MAX_ARGS 8

typedef enum {
  // `number`, as a JS number.
  P2PML_ARG_INT,
  // `number` (0 or not), as a JS boolean.
  P2PML_ARG_BOOL,
  // `length` bytes of UTF-8 at `pointer`, as a JS string.
  P2PML_ARG_STRING,
  // `length` NUL-terminated strings at `pointer` (a `const char *const *`),
  // as a JS array of strings.
  P2PML_ARG_STRINGS,
  // `length` bytes at `pointer`, as a Uint8Array.
  P2PML_ARG_BYTES,
  // None: JS gets undefined.
  P2PML_ARG_UNDEFINED,
} p2pml_arg_type;

// An argument of an event. The pointers stay the caller's: posting copies.
typedef struct {
  p2pml_arg_type type;
  int32_t number;
  const void *pointer;
  size_t length;
} p2pml_arg;

// A string argument of NUL-terminated `text`.
static inline p2pml_arg p2pml_text_arg(const char *text) {
  return (p2pml_arg){.type = P2PML_ARG_STRING, .pointer = text, .length = strlen(text)};
}

// What a channel does with its events. The names are NULL-terminated lists,
// and the two lists share no name (an ending event that JS did not take would
// not fail).
typedef struct {
  // The events after which an item is no longer open, such as "end".
  const char *const *ending;
  // The events that JS must take: when it cannot (out of memory, or a defect
  // of the polyfill), the channel stops the item and gives JS `failure` with
  // `failure_args` in its place.
  const char *const *needed;
  const char *failure;
  // Up to P2PML_CHANNEL_MAX_ARGS, and no bytes: they go to JS as they are,
  // with no copy.
  const p2pml_arg *failure_args;
  size_t failure_arg_count;
} p2pml_channel_rules;

// Any thread: posts event `name` of item `id`, with its arguments, as a task
// that gives it to the channel's listener as listener(id, name, ...args).
// `name` must live as long as the program (a literal). False once the
// engine is closed, when out of memory, or with more than
// P2PML_CHANNEL_MAX_ARGS arguments: the event is then lost. An event of an
// item that is not open when the task runs is dropped.
bool p2pml_channel_post(p2pml_engine *engine, p2pml_channel_id which, int32_t id, const char *name,
                        size_t arg_count, const p2pml_arg *args);

// The engine thread only, below.
typedef struct p2pml_channel p2pml_channel;

// `stop(data, id)` stops an item that the channel no longer keeps open: at a
// failure, and at the free. `rules` must live as long as the channel. NULL
// when out of memory.
p2pml_channel *p2pml_channel_new(const p2pml_channel_rules *rules,
                                 void (*stop)(void *data, int32_t id), void *data);
// Stops each open item and frees the channel, before the runtime is freed.
void p2pml_channel_free(p2pml_channel *channel, JSContext *context);
// A new id, open from now on; 0 when out of memory.
int32_t p2pml_channel_add(p2pml_channel *channel);
// Takes `id` out of the open ones and stops it, if it was open; true then.
bool p2pml_channel_cancel(p2pml_channel *channel, int32_t id);
// The function that gets every event; `listener` stays the caller's.
void p2pml_channel_listen(p2pml_channel *channel, JSContext *context, JSValueConst listener);
// Gives event `name` of item `id` to `listener`, as listener(id, name,
// ...args), with up to P2PML_CHANNEL_MAX_ARGS arguments. Bytes must be
// malloc'd: those that go to JS become its ArrayBuffer's, and their pointer
// is set to NULL; the caller frees the pointers that stay set. `listener`
// stays the caller's. False, after its report, when JS throws, also when out
// of memory.
bool p2pml_listener_call(p2pml_engine *engine, JSValueConst listener, int32_t id, const char *name,
                         size_t arg_count, p2pml_arg *args);
// The strings of JS array `array`, as a new array of `*count` C strings (the
// opposite of P2PML_ARG_STRINGS), for p2pml_strings_free. NULL, with a JS
// exception, when a conversion fails or when out of memory. `array` stays the
// caller's.
const char **p2pml_strings_new(JSContext *context, JSValueConst array, uint32_t *count);
// Frees the strings of p2pml_strings_new.
void p2pml_strings_free(JSContext *context, const char **strings, uint32_t count);
// The engine's channel `which` (engine.c).
p2pml_channel *p2pml_engine_channel(p2pml_engine *engine, p2pml_channel_id which);

#endif
