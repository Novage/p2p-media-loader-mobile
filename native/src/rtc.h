// The peer connections of RTCPeerConnection (src/polyfills/webrtc.js), over
// libdatachannel's C API (rtc.h), which the engine calls itself: the same C
// serves every platform. See specs/engine.md, "The WebRTC library".

#ifndef P2PML_RTC_H
#define P2PML_RTC_H

#include <stdbool.h>

#include "channel.h"
#include "engine.h"
#include "quickjs.h"

// The engine's own use (engine.c), on the engine thread.
typedef struct p2pml_rtc p2pml_rtc;

// NULL when out of memory.
p2pml_rtc *p2pml_rtc_new(p2pml_engine *engine);
// The engine's state (engine.c).
p2pml_rtc *p2pml_engine_rtc(p2pml_engine *engine);
// The channel of the peer connections.
p2pml_channel *p2pml_rtc_channel(p2pml_rtc *rtc);
// Adds the rtc natives to `natives`, which stays the caller's. False when out
// of memory.
bool p2pml_rtc_add(JSContext *context, JSValueConst natives);
// Deletes the open peer connections and frees the state, before the runtime
// is freed. No callback of libdatachannel reaches the engine after this.
void p2pml_rtc_free(p2pml_rtc *rtc, JSContext *context);

#endif
