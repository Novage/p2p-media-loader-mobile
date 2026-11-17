// The HTTP requests of fetch (src/polyfills/fetch.js): the platform's HTTP
// client does the network work; the engine gives its results to JS. See
// specs/engine.md, "The platform's HTTP client".

#ifndef P2PML_HTTP_H
#define P2PML_HTTP_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "engine.h"
#include "quickjs.h"

// The platform's HTTP client. The engine calls it on the engine thread; the
// strings stay the engine's, valid only during the call.
struct p2pml_http_client {
  // Starts request `id`: `headers` has `header_count` names and values, in
  // turn (2 * header_count strings). The platform then delivers the request's
  // events (below) from any thread: a response, any data, then an end; or a
  // failure at any point.
  void (*start)(void *data, int32_t id, const char *method, const char *url, size_t header_count,
                const char *const *headers);
  // Stops request `id`: no event of it reaches JS after this, so the platform
  // may still deliver some. The engine calls it for each request that is open
  // when it closes, also from p2pml_engine_run before it returns.
  void (*cancel)(void *data, int32_t id);
  void *data;
};

// The events of a request, from any thread. Each copies its arguments; the
// engine gives them to JS in a task. False once the engine is closed, or when
// out of memory: the event is then lost. Events of a request that is not open
// (cancelled, ended, failed, or unknown) are dropped. The response's headers
// are as start's.
bool p2pml_http_response(p2pml_engine *engine, int32_t id, int status, const char *status_text,
                         const char *url, size_t header_count, const char *const *headers);
bool p2pml_http_data(p2pml_engine *engine, int32_t id, const uint8_t *bytes, size_t length);
bool p2pml_http_end(p2pml_engine *engine, int32_t id);
bool p2pml_http_fail(p2pml_engine *engine, int32_t id, const char *message);

// The engine's own use (engine.c), on the engine thread.
typedef struct p2pml_http p2pml_http;

// NULL when out of memory.
p2pml_http *p2pml_http_new(const p2pml_http_client *client);
// The engine's state (engine.c).
p2pml_http *p2pml_engine_http(p2pml_engine *engine);
// Adds httpStart, httpCancel and httpListen to `natives`, which stays the
// caller's. False when out of memory.
bool p2pml_http_add(JSContext *context, JSValueConst natives);
// Cancels the open requests and frees the state, before the runtime is freed.
void p2pml_http_free(p2pml_http *http, JSContext *context);

#endif
