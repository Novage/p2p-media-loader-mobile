#include "rtc.h"

#include <pthread.h>
#include <rtc/rtc.h>
#include <stdint.h>
#include <stdlib.h>

// The JS functions below follow QuickJS's convention: the arguments stay
// QuickJS's, and the returned value is new, for the caller to free. They run
// on the engine thread; libdatachannel's callbacks run on its own threads.

struct p2pml_rtc {
  // The open peer connections, their listener and their events. An item is a
  // peer connection; its data channels are numbers of libdatachannel.
  p2pml_channel *channel;
};

// No event ends a peer connection: JS closes it (RTCPeerConnection.close), as
// in a browser, also after it failed.
static const char *const ENDING[] = {NULL};
// A data channel, its open or a message that JS did not take fails the peer
// connection: else JS could miss a message and go on.
static const char *const NEEDED[] = {"datachannel", "open", "message", NULL};
// The failure has no arguments: an array, not NULL, because channel.c copies
// from it (memcpy may not take NULL, also for 0 bytes).
static const p2pml_arg NO_ARGS[1] = {{.type = P2PML_ARG_INT}};
static const p2pml_channel_rules RULES = {
    .ending = ENDING,
    .needed = NEEDED,
    .failure = "fail",
    .failure_args = NO_ARGS,
    .failure_arg_count = 0,
};

// A peer connection of libdatachannel, the engine and the item it belongs
// to, and its data channels, which are deleted before it.
typedef struct {
  int pc;
  p2pml_engine *engine;
  int32_t item;
  int *channels;
  size_t channel_count;
  size_t channel_capacity;
} peer;

// The peer connections of every engine of the process. libdatachannel calls
// back from its own threads, also while or just after a peer connection is
// deleted, with the peer connection's id as the user pointer (it copies it
// to the data channels). A callback posts to an engine only while it holds
// `table_lock` and finds its id here; a peer connection leaves the table
// before it is deleted. So no callback reaches an engine after its peer
// connection stopped. No rtc* function is called with the lock held: a
// callback that runs inside it, on the same thread, takes the lock too.
static pthread_mutex_t table_lock = PTHREAD_MUTEX_INITIALIZER;
static peer *table;
static size_t table_count;
static size_t table_capacity;

// With `table_lock` held: the index of peer connection `pc`, or -1.
static long index_of_pc(int pc) {
  for (size_t i = 0; i < table_count; i++) {
    if (table[i].pc == pc) {
      return (long)i;
    }
  }
  return -1;
}

// With `table_lock` held: the index of `item` of `engine`, or -1.
static long index_of_item(const p2pml_engine *engine, int32_t item) {
  for (size_t i = 0; i < table_count; i++) {
    if (table[i].engine == engine && table[i].item == item) {
      return (long)i;
    }
  }
  return -1;
}

// With `table_lock` held: appends `value` to an array of ints. False when out
// of memory.
static bool append(int **array, size_t *count, size_t *capacity, int value) {
  if (*count == *capacity) {
    size_t larger = *capacity == 0 ? 4 : 2 * *capacity;
    int *grown = realloc(*array, larger * sizeof **array);
    if (grown == NULL) {
      return false;
    }
    *array = grown;
    *capacity = larger;
  }
  (*array)[(*count)++] = value;
  return true;
}

static bool table_add(int pc, p2pml_engine *engine, int32_t item) {
  pthread_mutex_lock(&table_lock);
  bool added = true;
  if (table_count == table_capacity) {
    size_t larger = table_capacity == 0 ? 8 : 2 * table_capacity;
    peer *grown = realloc(table, larger * sizeof *table);
    added = grown != NULL;
    if (added) {
      table = grown;
      table_capacity = larger;
    }
  }
  if (added) {
    table[table_count++] = (peer){.pc = pc, .engine = engine, .item = item};
  }
  pthread_mutex_unlock(&table_lock);
  return added;
}

// The peer connection of `item`, or RTC_ERR_INVALID once it stopped.
static int pc_of(const p2pml_engine *engine, int32_t item) {
  pthread_mutex_lock(&table_lock);
  long index = index_of_item(engine, item);
  int pc = index < 0 ? RTC_ERR_INVALID : table[index].pc;
  pthread_mutex_unlock(&table_lock);
  return pc;
}

// Any thread: posts event `name` of peer connection `pc`, if it did not stop.
static void post(int pc, const char *name, size_t count, const p2pml_arg *args) {
  pthread_mutex_lock(&table_lock);
  long index = index_of_pc(pc);
  if (index >= 0) {
    p2pml_channel_post(table[index].engine, P2PML_CHANNEL_RTC, table[index].item, name, count,
                       args);
  }
  pthread_mutex_unlock(&table_lock);
}

static int pc_of_pointer(void *pointer) { return (int)(intptr_t)pointer; }

static void RTC_API on_gathering(int pc, rtcGatheringState state, void *pointer) {
  (void)pointer;
  const char *name = state == RTC_GATHERING_COMPLETE     ? "complete"
                     : state == RTC_GATHERING_INPROGRESS ? "gathering"
                                                         : "new";
  p2pml_arg arg = p2pml_text_arg(name);
  post(pc, "gathering", 1, &arg);
}

static void RTC_API on_ice(int pc, rtcIceState state, void *pointer) {
  (void)pointer;
  static const char *const NAMES[] = {"new",    "checking",     "connected", "completed",
                                      "failed", "disconnected", "closed"};
  if ((unsigned)state >= sizeof NAMES / sizeof NAMES[0]) {
    return;
  }
  p2pml_arg arg = p2pml_text_arg(NAMES[state]);
  post(pc, "ice", 1, &arg);
}

static void RTC_API on_open(int dc, void *pointer) {
  p2pml_arg arg = {.type = P2PML_ARG_INT, .number = dc};
  post(pc_of_pointer(pointer), "open", 1, &arg);
}

static void RTC_API on_closed(int dc, void *pointer) {
  p2pml_arg arg = {.type = P2PML_ARG_INT, .number = dc};
  post(pc_of_pointer(pointer), "channelclose", 1, &arg);
}

static void RTC_API on_low(int dc, void *pointer) {
  p2pml_arg arg = {.type = P2PML_ARG_INT, .number = dc};
  post(pc_of_pointer(pointer), "low", 1, &arg);
}

// A string (`size` negative, NUL-terminated) or binary data.
static void RTC_API on_message(int dc, const char *message, int size, void *pointer) {
  p2pml_arg args[] = {
      {.type = P2PML_ARG_INT, .number = dc},
      size < 0 ? p2pml_text_arg(message)
               : (p2pml_arg){.type = P2PML_ARG_BYTES, .pointer = message, .length = (size_t)size},
  };
  post(pc_of_pointer(pointer), "message", 2, args);
}

// Data channel `dc`'s callbacks. Its open, if it was missed, and the
// messages that wait for it come inside these calls.
static void watch_channel(int dc) {
  rtcSetOpenCallback(dc, on_open);
  rtcSetClosedCallback(dc, on_closed);
  rtcSetBufferedAmountLowCallback(dc, on_low);
  rtcSetMessageCallback(dc, on_message);
}

// With `table_lock` not held: keeps `dc` with peer connection `pc`, so that
// it is deleted before it. False when `pc` stopped, or when out of memory.
static bool keep_channel(int pc, int dc) {
  pthread_mutex_lock(&table_lock);
  long index = index_of_pc(pc);
  bool kept = index >= 0 && append(&table[index].channels, &table[index].channel_count,
                                   &table[index].channel_capacity, dc);
  pthread_mutex_unlock(&table_lock);
  return kept;
}

// A data channel that the remote peer opened: its datachannel event goes
// before its callbacks are set, so that JS gets it before its open and its
// messages, which libdatachannel keeps until then.
static void RTC_API on_channel(int pc, int dc, void *pointer) {
  (void)pointer;
  if (!keep_channel(pc, dc)) {
    return;
  }
  char label[256] = "";
  if (rtcGetDataChannelLabel(dc, label, sizeof label) < 0) {
    label[0] = '\0';
  }
  p2pml_arg args[] = {{.type = P2PML_ARG_INT, .number = dc}, p2pml_text_arg(label)};
  post(pc, "datachannel", 2, args);
  watch_channel(dc);
}

// The channel's stop: an item that JS closed or that failed, or that is open
// when the engine closes. It leaves the table first, so that no callback
// posts for it any more; then its data channels and the peer connection are
// deleted. libdatachannel's process-wide threads stay: rtcCleanup would
// destroy the objects of every engine.
static void stop(void *data, int32_t item) {
  const p2pml_engine *engine = data;
  pthread_mutex_lock(&table_lock);
  long index = index_of_item(engine, item);
  peer gone = {.pc = RTC_ERR_INVALID};
  if (index >= 0) {
    gone = table[index];
    table[index] = table[--table_count];
  }
  pthread_mutex_unlock(&table_lock);
  if (index < 0) {
    return;
  }
  for (size_t i = 0; i < gone.channel_count; i++) {
    rtcDeleteDataChannel(gone.channels[i]);
  }
  free(gone.channels);
  rtcDeletePeerConnection(gone.pc);
}

p2pml_rtc *p2pml_rtc_new(p2pml_engine *engine) {
  p2pml_rtc *rtc = calloc(1, sizeof *rtc);
  if (rtc == NULL) {
    return NULL;
  }
  rtc->channel = p2pml_channel_new(&RULES, stop, engine);
  if (rtc->channel == NULL) {
    free(rtc);
    return NULL;
  }
  return rtc;
}

void p2pml_rtc_free(p2pml_rtc *rtc, JSContext *context) {
  if (rtc == NULL) {
    return;
  }
  p2pml_channel_free(rtc->channel, context);
  free(rtc);
}

p2pml_channel *p2pml_rtc_channel(p2pml_rtc *rtc) { return rtc->channel; }

static p2pml_rtc *rtc_of(JSContext *context) {
  return p2pml_engine_rtc(JS_GetContextOpaque(context));
}

// The natives return libdatachannel's codes (negative: RTC_ERR_*) where it
// fails; the polyfill turns them into the W3C errors.

// rtcPeer(iceServers, relayOnly): a new peer connection, as an item id, or
// a negative code. `iceServers` is an array of libdatachannel's URLs.
static JSValue rtc_peer(JSContext *context, JSValueConst this_value, int argc, JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  p2pml_engine *engine = JS_GetContextOpaque(context);
  p2pml_rtc *rtc = p2pml_engine_rtc(engine);
  uint32_t count = 0;
  const char **servers = p2pml_strings_new(context, argv[0], &count);
  if (servers == NULL) {
    return JS_EXCEPTION;
  }
  rtcConfiguration config = {
      .iceServers = servers,
      .iceServersCount = (int)count,
      .iceTransportPolicy =
          JS_ToBool(context, argv[1]) ? RTC_TRANSPORT_POLICY_RELAY : RTC_TRANSPORT_POLICY_ALL,
      // The W3C steps: the polyfill creates and sets each description.
      .disableAutoNegotiation = true,
  };
  int pc = rtcCreatePeerConnection(&config);
  p2pml_strings_free(context, servers, count);
  if (pc < 0) {
    return JS_NewInt32(context, pc);
  }
  // The id, not memory: a callback can come after the stop (see `table`).
  rtcSetUserPointer(pc, (void *)(intptr_t)pc); // NOLINT(performance-no-int-to-ptr)
  int32_t item = p2pml_channel_add(rtc->channel);
  if (item == 0 || !table_add(pc, engine, item)) {
    if (item != 0) {
      p2pml_channel_cancel(rtc->channel, item);
    }
    rtcDeletePeerConnection(pc);
    return JS_ThrowOutOfMemory(context);
  }
  rtcSetGatheringStateChangeCallback(pc, on_gathering);
  rtcSetIceStateChangeCallback(pc, on_ice);
  rtcSetDataChannelCallback(pc, on_channel);
  return JS_NewInt32(context, item);
}

// A string that `get` writes into a buffer, such as a description, a
// negative code, or JS_EXCEPTION when out of memory: a new value, for the
// caller to free. Each call of rtcCreateOffer makes a new offer, so the size
// is not asked first: the buffer grows until the string fits.
static JSValue string_of(JSContext *context, int pc, int (*get)(int, char *, int)) {
  for (int size = 16 * 1024; size <= 1024 * 1024; size *= 2) {
    char *buffer = malloc((size_t)size);
    if (buffer == NULL) {
      return JS_ThrowOutOfMemory(context);
    }
    int written = get(pc, buffer, size);
    JSValue value = written >= 0 ? JS_NewString(context, buffer) : JS_NewInt32(context, written);
    free(buffer);
    if (written != RTC_ERR_TOO_SMALL) {
      return value;
    }
  }
  return JS_NewInt32(context, RTC_ERR_TOO_SMALL);
}

// rtcCreateOffer(id) and rtcCreateAnswer(id) (magic 1): a description's SDP,
// not set, or a negative code.
static JSValue rtc_create(JSContext *context, JSValueConst this_value, int argc, JSValueConst *argv,
                          int magic) {
  (void)this_value;
  (void)argc;
  int32_t item = 0;
  if (JS_ToInt32(context, &item, argv[0]) < 0) {
    return JS_EXCEPTION;
  }
  int pc = pc_of(JS_GetContextOpaque(context), item);
  if (pc < 0) {
    return JS_NewInt32(context, pc);
  }
  return string_of(context, pc, magic == 0 ? rtcCreateOffer : rtcCreateAnswer);
}

// rtcSetLocal(id, type) and rtcSetRemote(id, type, sdp): 0 or a negative
// code.
static JSValue rtc_set(JSContext *context, JSValueConst this_value, int argc, JSValueConst *argv,
                       int magic) {
  (void)this_value;
  (void)argc;
  int32_t item = 0;
  if (JS_ToInt32(context, &item, argv[0]) < 0) {
    return JS_EXCEPTION;
  }
  const char *type = JS_ToCString(context, argv[1]);
  const char *sdp = type != NULL && magic == 1 ? JS_ToCString(context, argv[2]) : NULL;
  if (type == NULL || (magic == 1 && sdp == NULL)) {
    JS_FreeCString(context, type);
    return JS_EXCEPTION;
  }
  int pc = pc_of(JS_GetContextOpaque(context), item);
  int code = pc < 0       ? pc
             : magic == 0 ? rtcSetLocalDescription(pc, type)
                          : rtcSetRemoteDescription(pc, sdp, type);
  JS_FreeCString(context, sdp);
  JS_FreeCString(context, type);
  return JS_NewInt32(context, code);
}

// rtcLocalDescription(id): [type, sdp], with the candidates that it has so
// far, or null.
static JSValue rtc_local(JSContext *context, JSValueConst this_value, int argc,
                         JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  int32_t item = 0;
  if (JS_ToInt32(context, &item, argv[0]) < 0) {
    return JS_EXCEPTION;
  }
  int pc = pc_of(JS_GetContextOpaque(context), item);
  char type[16] = "";
  if (pc < 0 || rtcGetLocalDescriptionType(pc, type, sizeof type) < 0) {
    return JS_NULL;
  }
  JSValue sdp = string_of(context, pc, rtcGetLocalDescription);
  if (!JS_IsString(sdp)) {
    return JS_IsException(sdp) ? sdp : JS_NULL;
  }
  JSValue pair = JS_NewArray(context);
  JSValue type_value = JS_IsException(pair) ? JS_EXCEPTION : JS_NewString(context, type);
  // JS_SetPropertyUint32 takes the value, also when it fails.
  if (JS_IsException(type_value) || JS_SetPropertyUint32(context, pair, 0, type_value) < 0) {
    JS_FreeValue(context, sdp);
    JS_FreeValue(context, pair);
    return JS_EXCEPTION;
  }
  if (JS_SetPropertyUint32(context, pair, 1, sdp) < 0) {
    JS_FreeValue(context, pair);
    return JS_EXCEPTION;
  }
  return pair;
}

// rtcChannel(id, label): a new data channel, ordered and reliable, as its
// number, or a negative code.
static JSValue rtc_channel(JSContext *context, JSValueConst this_value, int argc,
                           JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  int32_t item = 0;
  if (JS_ToInt32(context, &item, argv[0]) < 0) {
    return JS_EXCEPTION;
  }
  const char *label = JS_ToCString(context, argv[1]);
  if (label == NULL) {
    return JS_EXCEPTION;
  }
  int pc = pc_of(JS_GetContextOpaque(context), item);
  int dc = pc < 0 ? pc : rtcCreateDataChannel(pc, label);
  JS_FreeCString(context, label);
  if (dc < 0) {
    return JS_NewInt32(context, dc);
  }
  if (!keep_channel(pc, dc)) {
    rtcDeleteDataChannel(dc);
    return JS_ThrowOutOfMemory(context);
  }
  watch_channel(dc);
  return JS_NewInt32(context, dc);
}

// rtcSend(channel, data): sends a string as text, or a Uint8Array (its view
// only) as binary. 0 or a negative code: RTC_ERR_INVALID above the maximum
// size.
static JSValue rtc_send(JSContext *context, JSValueConst this_value, int argc, JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  int32_t dc = 0;
  if (JS_ToInt32(context, &dc, argv[0]) < 0) {
    return JS_EXCEPTION;
  }
  if (JS_IsString(argv[1])) {
    const char *text = JS_ToCString(context, argv[1]);
    if (text == NULL) {
      return JS_EXCEPTION;
    }
    int code = rtcSendMessage(dc, text, -1);
    JS_FreeCString(context, text);
    return JS_NewInt32(context, code);
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
  if (length > INT32_MAX) {
    return JS_NewInt32(context, RTC_ERR_INVALID);
  }
  return JS_NewInt32(context, rtcSendMessage(dc, (const char *)bytes + offset, (int)length));
}

// rtcBufferedAmount(channel): the bytes waiting to be sent; rtcChannelClose
// (magic 1): closes the channel (its close comes as an event);
// rtcSetLowThreshold(channel, amount) (magic 2): sets the low threshold. A
// value or a negative code.
static JSValue rtc_channel_call(JSContext *context, JSValueConst this_value, int argc,
                                JSValueConst *argv, int magic) {
  (void)this_value;
  (void)argc;
  int32_t dc = 0;
  if (JS_ToInt32(context, &dc, argv[0]) < 0) {
    return JS_EXCEPTION;
  }
  if (magic == 0) {
    return JS_NewInt32(context, rtcGetBufferedAmount(dc));
  }
  if (magic == 1) {
    return JS_NewInt32(context, rtcClose(dc));
  }
  int64_t amount = 0;
  if (JS_ToInt64(context, &amount, argv[1]) < 0) {
    return JS_EXCEPTION;
  }
  int limited = amount < 0 ? 0 : amount > INT32_MAX ? INT32_MAX : (int)amount;
  return JS_NewInt32(context, rtcSetBufferedAmountLowThreshold(dc, limited));
}

// rtcClose(id): deletes the peer connection and its data channels at once;
// no event of it follows.
static JSValue rtc_close(JSContext *context, JSValueConst this_value, int argc,
                         JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  int32_t item = 0;
  if (JS_ToInt32(context, &item, argv[0]) < 0) {
    return JS_EXCEPTION;
  }
  p2pml_channel_cancel(rtc_of(context)->channel, item);
  return JS_UNDEFINED;
}

// rtcListen(listener): the function that gets every event, as
// listener(id, "gathering", state) with "gathering" or "complete",
// listener(id, "ice", state) with a W3C RTCIceConnectionState,
// listener(id, "datachannel", channel, label),
// listener(id, "open", channel), listener(id, "low", channel),
// listener(id, "message", channel, data) with a string or a Uint8Array,
// listener(id, "channelclose", channel), and listener(id, "fail") when JS
// did not take an event that it needs: the peer connection is then deleted.
static JSValue rtc_listen(JSContext *context, JSValueConst this_value, int argc,
                          JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  p2pml_channel_listen(rtc_of(context)->channel, context, argv[0]);
  return JS_UNDEFINED;
}

static const JSCFunctionListEntry rtc_functions[] = {
    JS_CFUNC_DEF("rtcPeer", 2, rtc_peer),
    JS_CFUNC_MAGIC_DEF("rtcCreateOffer", 1, rtc_create, 0),
    JS_CFUNC_MAGIC_DEF("rtcCreateAnswer", 1, rtc_create, 1),
    JS_CFUNC_MAGIC_DEF("rtcSetLocal", 2, rtc_set, 0),
    JS_CFUNC_MAGIC_DEF("rtcSetRemote", 3, rtc_set, 1),
    JS_CFUNC_DEF("rtcLocalDescription", 1, rtc_local),
    JS_CFUNC_DEF("rtcChannel", 2, rtc_channel),
    JS_CFUNC_DEF("rtcSend", 2, rtc_send),
    JS_CFUNC_MAGIC_DEF("rtcBufferedAmount", 1, rtc_channel_call, 0),
    JS_CFUNC_MAGIC_DEF("rtcChannelClose", 1, rtc_channel_call, 1),
    JS_CFUNC_MAGIC_DEF("rtcSetLowThreshold", 2, rtc_channel_call, 2),
    JS_CFUNC_DEF("rtcClose", 1, rtc_close),
    JS_CFUNC_DEF("rtcListen", 1, rtc_listen),
};

bool p2pml_rtc_add(JSContext *context, JSValueConst natives) {
  return JS_SetPropertyFunctionList(context, natives, rtc_functions,
                                    (int)(sizeof rtc_functions / sizeof rtc_functions[0])) == 0;
}
