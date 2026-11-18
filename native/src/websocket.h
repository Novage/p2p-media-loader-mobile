// The sockets of WebSocket (src/polyfills/websocket.js): the platform's
// WebSocket client does the network work; the engine gives its events to JS.
// See specs/engine.md, "The platform's network clients".

#ifndef P2PML_WEBSOCKET_H
#define P2PML_WEBSOCKET_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "channel.h"
#include "engine.h"
#include "quickjs.h"

// The platform's WebSocket client. The engine calls it on the engine thread;
// the strings and bytes stay the engine's, valid only during the call.
struct p2pml_ws_client {
  // Opens socket `id` to `url` (ws: or wss:), asking for `protocol_count`
  // subprotocols. The platform then delivers the socket's events (below) from
  // any thread: an open, any messages, then a close; or a failed close at any
  // point.
  void (*open)(void *data, int32_t id, const char *url, size_t protocol_count,
               const char *const *protocols);
  // Sends a message: `length` bytes, UTF-8 text or binary.
  void (*send)(void *data, int32_t id, bool binary, const uint8_t *bytes, size_t length);
  // Starts the closing handshake: `code` (-1 for none) and the UTF-8 `reason`
  // of `reason_length` bytes. The close event comes when it ends.
  void (*close)(void *data, int32_t id, int code, const uint8_t *reason, size_t reason_length);
  // Stops socket `id` at once: no event of the platform reaches JS after this.
  // The platform can still close the connection with a close frame of any code
  // (WsBridge does). The engine calls it for a close() while connecting, for
  // an open or a message that JS cannot take (JS then gets the failed close of
  // the channel), and for each socket that is open when the engine closes.
  void (*cancel)(void *data, int32_t id);
  void *data;
};

// The events of a socket, from any thread, as p2pml_http_response's: each
// copies its arguments; false once the engine is closed, or when out of memory.
bool p2pml_ws_open(p2pml_engine *engine, int32_t id, const char *protocol, const char *extensions);
// `bytes` are UTF-8 text unless `binary`.
bool p2pml_ws_message(p2pml_engine *engine, int32_t id, bool binary, const uint8_t *bytes,
                      size_t length);
// The end of a socket: `code` and the UTF-8 `reason` as the close frame gave
// them (1005 and "" when it had none); `was_clean` when the closing handshake
// completed; `failed` when the connection failed (JS then gets an error event
// first, and the code is 1006).
bool p2pml_ws_close(p2pml_engine *engine, int32_t id, int code, const uint8_t *reason,
                    size_t reason_length, bool was_clean, bool failed);

// The engine's own use (engine.c), on the engine thread.
typedef struct p2pml_ws p2pml_ws;

// NULL when out of memory.
p2pml_ws *p2pml_ws_new(const p2pml_ws_client *client);
// The engine's state (engine.c).
p2pml_ws *p2pml_engine_ws(p2pml_engine *engine);
// The channel of the sockets.
p2pml_channel *p2pml_ws_channel(p2pml_ws *ws);
// Adds wsOpen, wsSend, wsClose, wsCancel and wsListen to `natives`, which
// stays the caller's. False when out of memory.
bool p2pml_ws_add(JSContext *context, JSValueConst natives);
// Stops the open sockets and frees the state, before the runtime is freed.
void p2pml_ws_free(p2pml_ws *ws, JSContext *context);

#endif
