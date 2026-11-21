// The JNI functions of com.novage.p2pml.engine.NativeEngine. Only the glue
// between Kotlin and engine.c.

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "engine.h"
#include "http.h"
#include "player.h"
#include "websocket.h"

static JavaVM *java_vm;
static jmethodID runnable_run;
static jclass js_exception_class;
static jmethodID js_exception_init;
static jclass closed_exception_class;
static jmethodID log_sink_write;
static jclass string_class;
static jmethodID http_sink_start;
static jmethodID http_sink_cancel;
static jmethodID ws_sink_open;
static jmethodID ws_sink_send;
static jmethodID ws_sink_close;
static jmethodID ws_sink_cancel;
static jmethodID player_sink_value;
static jmethodID player_sink_segment;
static jmethodID player_sink_fail;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)reserved;
  java_vm = vm;
  JNIEnv *env;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }
  jclass runnable = (*env)->FindClass(env, "java/lang/Runnable");
  if (runnable == NULL) {
    return JNI_ERR;
  }
  runnable_run = (*env)->GetMethodID(env, runnable, "run", "()V");
  jclass exception = (*env)->FindClass(env, "com/novage/p2pml/engine/JsException");
  if (runnable_run == NULL || exception == NULL) {
    return JNI_ERR;
  }
  js_exception_class = (*env)->NewGlobalRef(env, exception);
  js_exception_init =
      (*env)->GetMethodID(env, exception, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
  if (js_exception_class == NULL || js_exception_init == NULL) {
    return JNI_ERR;
  }
  jclass closed = (*env)->FindClass(env, "com/novage/p2pml/engine/JsEngineClosedException");
  if (closed == NULL) {
    return JNI_ERR;
  }
  closed_exception_class = (*env)->NewGlobalRef(env, closed);
  // ThrowNew needs this constructor: missing, the library does not load.
  if (closed_exception_class == NULL ||
      (*env)->GetMethodID(env, closed, "<init>", "(Ljava/lang/String;)V") == NULL) {
    return JNI_ERR;
  }
  jclass log_sink = (*env)->FindClass(env, "com/novage/p2pml/engine/NativeEngine$LogSink");
  if (log_sink == NULL) {
    return JNI_ERR;
  }
  log_sink_write = (*env)->GetMethodID(env, log_sink, "write", "(ILjava/lang/String;)V");
  if (log_sink_write == NULL) {
    return JNI_ERR;
  }
  jclass string = (*env)->FindClass(env, "java/lang/String");
  jclass http_sink = (*env)->FindClass(env, "com/novage/p2pml/engine/NativeEngine$HttpSink");
  if (string == NULL || http_sink == NULL) {
    return JNI_ERR;
  }
  string_class = (*env)->NewGlobalRef(env, string);
  http_sink_start = (*env)->GetMethodID(
      env, http_sink, "start", "(ILjava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V");
  http_sink_cancel = (*env)->GetMethodID(env, http_sink, "cancel", "(I)V");
  if (string_class == NULL || http_sink_start == NULL || http_sink_cancel == NULL) {
    return JNI_ERR;
  }
  jclass ws_sink = (*env)->FindClass(env, "com/novage/p2pml/engine/NativeEngine$WsSink");
  if (ws_sink == NULL) {
    return JNI_ERR;
  }
  ws_sink_open =
      (*env)->GetMethodID(env, ws_sink, "open", "(ILjava/lang/String;[Ljava/lang/String;)V");
  ws_sink_send = (*env)->GetMethodID(env, ws_sink, "send", "(IZ[B)V");
  ws_sink_close = (*env)->GetMethodID(env, ws_sink, "close", "(II[B)V");
  ws_sink_cancel = (*env)->GetMethodID(env, ws_sink, "cancel", "(I)V");
  if (ws_sink_open == NULL || ws_sink_send == NULL || ws_sink_close == NULL ||
      ws_sink_cancel == NULL) {
    return JNI_ERR;
  }
  jclass player_sink = (*env)->FindClass(env, "com/novage/p2pml/engine/NativeEngine$PlayerSink");
  if (player_sink == NULL) {
    return JNI_ERR;
  }
  player_sink_value = (*env)->GetMethodID(env, player_sink, "value", "(I[B)V");
  player_sink_segment = (*env)->GetMethodID(env, player_sink, "segment", "(I[BDZ)V");
  player_sink_fail = (*env)->GetMethodID(env, player_sink, "fail", "(I[B[B)V");
  if (player_sink_value == NULL || player_sink_segment == NULL || player_sink_fail == NULL) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}

// Every thread that runs or frees an engine is a Java thread.
static JNIEnv *current_env(void) {
  JNIEnv *env = NULL;
  (*java_vm)->GetEnv(java_vm, (void **)&env, JNI_VERSION_1_6);
  return env;
}

// Kotlin holds the engine's pointer as a long.
static p2pml_engine *engine_of(jlong handle) {
  return (p2pml_engine *)(intptr_t)handle; // NOLINT(performance-no-int-to-ptr)
}

// An exception from Kotlin on the engine thread, such as OutOfMemoryError,
// stays pending and closes the engine: it ends the engine thread once
// NativeEngine.run returns, and no JNI call that a pending exception forbids
// may run before that.
static void close_on_exception(JNIEnv *env, p2pml_engine *engine) {
  if ((*env)->ExceptionCheck(env)) {
    p2pml_engine_close(engine);
  }
}

static void run_task(p2pml_engine *engine, void *data) {
  JNIEnv *env = current_env();
  jobject task = data;
  (*env)->CallVoidMethod(env, task, runnable_run);
  // The Kotlin side catches exceptions, so this is an Error.
  close_on_exception(env, engine);
  (*env)->DeleteGlobalRef(env, task);
}

static void discard_task(void *data) {
  JNIEnv *env = current_env();
  (*env)->DeleteGlobalRef(env, data);
}

static void throw_out_of_memory(JNIEnv *env) {
  jclass error = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
  if (error != NULL) {
    (*env)->ThrowNew(env, error, "Out of memory in the JS engine");
  }
}

// The bytes of `bytes` with a NUL after them, which the caller frees, and
// their count in `length` if it is not NULL. NULL with an OutOfMemoryError
// when out of memory.
static char *copy_bytes(JNIEnv *env, jbyteArray bytes, jsize *length) {
  jsize count = (*env)->GetArrayLength(env, bytes);
  char *copy = malloc((size_t)count + 1);
  if (copy == NULL) {
    throw_out_of_memory(env);
    return NULL;
  }
  (*env)->GetByteArrayRegion(env, bytes, 0, count, (jbyte *)copy);
  copy[count] = '\0';
  if (length != NULL) {
    *length = count;
  }
  return copy;
}

JNIEXPORT jlong JNICALL Java_com_novage_p2pml_engine_NativeEngine_create(JNIEnv *env, jclass type,
                                                                         jbyteArray user_agent) {
  (void)type;
  char *agent = copy_bytes(env, user_agent, NULL);
  if (agent == NULL) {
    return 0;
  }
  p2pml_engine *engine = p2pml_engine_new(agent);
  free(agent);
  if (engine == NULL) {
    throw_out_of_memory(env);
    return 0;
  }
  return (jlong)(intptr_t)engine;
}

// A Kotlin sink of a running engine (the log, the HTTP client): `env` and
// `sink` are run()'s, valid on the engine thread until run() returns, and the
// sink is called only there and then.
typedef struct {
  JNIEnv *env;
  p2pml_engine *engine;
  jobject sink;
} engine_sink;

// Writes a line through NativeEngine.LogSink. A Java exception from it closes
// the engine (close_on_exception).
static void write_log(void *data, p2pml_log_level level, const char *message) {
  engine_sink *log = data;
  JNIEnv *env = log->env;
  if ((*env)->ExceptionCheck(env)) {
    return;
  }
  // The strings are CESU-8, which NewStringUTF reads as modified UTF-8.
  jstring text = (*env)->NewStringUTF(env, message);
  if (text != NULL) {
    (*env)->CallVoidMethod(env, log->sink, log_sink_write, (jint)level, text);
    // run() can write many lines: its local references stay until it returns.
    (*env)->DeleteLocalRef(env, text);
  }
  close_on_exception(env, log->engine);
}

// A Java array of `count` strings; NULL with an exception.
static jobjectArray strings_of(JNIEnv *env, size_t count, const char *const *texts) {
  jobjectArray array = (*env)->NewObjectArray(env, (jsize)count, string_class, NULL);
  for (size_t i = 0; array != NULL && i < count; i++) {
    // UTF-8 of header text, which is ASCII or Latin-1: also modified UTF-8.
    jstring text = (*env)->NewStringUTF(env, texts[i]);
    if (text == NULL) {
      (*env)->DeleteLocalRef(env, array);
      return NULL;
    }
    (*env)->SetObjectArrayElement(env, array, (jsize)i, text);
    (*env)->DeleteLocalRef(env, text);
  }
  return array;
}

static void http_start(void *data, int32_t id, const char *method, const char *url,
                       size_t header_count, const char *const *headers) {
  engine_sink *http = data;
  JNIEnv *env = http->env;
  if ((*env)->ExceptionCheck(env)) {
    return;
  }
  jstring method_text = (*env)->NewStringUTF(env, method);
  jstring url_text = method_text != NULL ? (*env)->NewStringUTF(env, url) : NULL;
  jobjectArray pairs = url_text != NULL ? strings_of(env, 2 * header_count, headers) : NULL;
  if (pairs != NULL) {
    (*env)->CallVoidMethod(env, http->sink, http_sink_start, (jint)id, method_text, url_text,
                           pairs);
  }
  (*env)->DeleteLocalRef(env, pairs);
  (*env)->DeleteLocalRef(env, url_text);
  (*env)->DeleteLocalRef(env, method_text);
  close_on_exception(env, http->engine);
}

// Calls `method`, a method (I)V of the sink, for item `id`.
static void call_with_id(engine_sink *sink, jmethodID method, int32_t id) {
  JNIEnv *env = sink->env;
  if ((*env)->ExceptionCheck(env)) {
    return;
  }
  (*env)->CallVoidMethod(env, sink->sink, method, (jint)id);
  close_on_exception(env, sink->engine);
}

static void http_cancel(void *data, int32_t id) { call_with_id(data, http_sink_cancel, id); }

// A new Java byte array of `length` bytes; NULL with an exception.
static jbyteArray byte_array_of(JNIEnv *env, const uint8_t *bytes, size_t length) {
  jbyteArray array = (*env)->NewByteArray(env, (jsize)length);
  if (array != NULL) {
    (*env)->SetByteArrayRegion(env, array, 0, (jsize)length, (const jbyte *)bytes);
  }
  return array;
}

static void ws_open(void *data, int32_t id, const char *url, size_t protocol_count,
                    const char *const *protocols) {
  engine_sink *ws = data;
  JNIEnv *env = ws->env;
  if ((*env)->ExceptionCheck(env)) {
    return;
  }
  // A WHATWG href and HTTP tokens: ASCII, also modified UTF-8.
  jstring url_text = (*env)->NewStringUTF(env, url);
  jobjectArray list = url_text != NULL ? strings_of(env, protocol_count, protocols) : NULL;
  if (list != NULL) {
    (*env)->CallVoidMethod(env, ws->sink, ws_sink_open, (jint)id, url_text, list);
  }
  (*env)->DeleteLocalRef(env, list);
  (*env)->DeleteLocalRef(env, url_text);
  close_on_exception(env, ws->engine);
}

// Text crosses as UTF-8 bytes, not as a jstring: JNI's strings are modified
// UTF-8, which differs above U+FFFF.
static void ws_send(void *data, int32_t id, bool binary, const uint8_t *bytes, size_t length) {
  engine_sink *ws = data;
  JNIEnv *env = ws->env;
  if ((*env)->ExceptionCheck(env)) {
    return;
  }
  jbyteArray array = byte_array_of(env, bytes, length);
  if (array != NULL) {
    (*env)->CallVoidMethod(env, ws->sink, ws_sink_send, (jint)id, (jboolean)binary, array);
  }
  (*env)->DeleteLocalRef(env, array);
  close_on_exception(env, ws->engine);
}

static void ws_close(void *data, int32_t id, int code, const uint8_t *reason,
                     size_t reason_length) {
  engine_sink *ws = data;
  JNIEnv *env = ws->env;
  if ((*env)->ExceptionCheck(env)) {
    return;
  }
  jbyteArray array = byte_array_of(env, reason, reason_length);
  if (array != NULL) {
    (*env)->CallVoidMethod(env, ws->sink, ws_sink_close, (jint)id, (jint)code, array);
  }
  (*env)->DeleteLocalRef(env, array);
  close_on_exception(env, ws->engine);
}

static void ws_cancel(void *data, int32_t id) { call_with_id(data, ws_sink_cancel, id); }

// Calls `method`, a method (I[B)V of the sink, for item `id`, with a copy of
// the bytes.
static void call_with_bytes(engine_sink *sink, jmethodID method, int32_t id, const uint8_t *bytes,
                            size_t length) {
  JNIEnv *env = sink->env;
  if ((*env)->ExceptionCheck(env)) {
    return;
  }
  jbyteArray array = byte_array_of(env, bytes, length);
  if (array != NULL) {
    (*env)->CallVoidMethod(env, sink->sink, method, (jint)id, array);
  }
  (*env)->DeleteLocalRef(env, array);
  close_on_exception(env, sink->engine);
}

// Texts cross as UTF-8 bytes, as ws_send's text.
static void player_value(void *data, int32_t id, const uint8_t *json, size_t length) {
  call_with_bytes(data, player_sink_value, id, json, length);
}

static void player_segment(void *data, int32_t id, const uint8_t *bytes, size_t length,
                           double bandwidth, bool main) {
  engine_sink *player = data;
  JNIEnv *env = player->env;
  if ((*env)->ExceptionCheck(env)) {
    return;
  }
  jbyteArray array = byte_array_of(env, bytes, length);
  if (array != NULL) {
    (*env)->CallVoidMethod(env, player->sink, player_sink_segment, (jint)id, array,
                           (jdouble)bandwidth, (jboolean)main);
  }
  (*env)->DeleteLocalRef(env, array);
  close_on_exception(env, player->engine);
}

static void player_fail(void *data, int32_t id, const uint8_t *type, size_t type_length,
                        const uint8_t *message, size_t message_length) {
  engine_sink *player = data;
  JNIEnv *env = player->env;
  if ((*env)->ExceptionCheck(env)) {
    return;
  }
  jbyteArray types = byte_array_of(env, type, type_length);
  jbyteArray messages = types != NULL ? byte_array_of(env, message, message_length) : NULL;
  if (messages != NULL) {
    (*env)->CallVoidMethod(env, player->sink, player_sink_fail, (jint)id, types, messages);
  }
  (*env)->DeleteLocalRef(env, messages);
  (*env)->DeleteLocalRef(env, types);
  close_on_exception(env, player->engine);
}

JNIEXPORT jboolean JNICALL Java_com_novage_p2pml_engine_NativeEngine_run(JNIEnv *env, jclass type,
                                                                         jlong handle, jobject sink,
                                                                         jobject http_sink,
                                                                         jobject ws_sink,
                                                                         jobject player_sink) {
  (void)type;
  engine_sink log = {.env = env, .engine = engine_of(handle), .sink = sink};
  engine_sink http = {.env = env, .engine = log.engine, .sink = http_sink};
  engine_sink ws = {.env = env, .engine = log.engine, .sink = ws_sink};
  engine_sink player = {.env = env, .engine = log.engine, .sink = player_sink};
  p2pml_http_client http_client = {.start = http_start, .cancel = http_cancel, .data = &http};
  p2pml_ws_client ws_client = {
      .open = ws_open, .send = ws_send, .close = ws_close, .cancel = ws_cancel, .data = &ws};
  p2pml_player_client player_client = {
      .value = player_value, .segment = player_segment, .fail = player_fail, .data = &player};
  return p2pml_engine_run(log.engine, write_log, &log, &http_client, &ws_client, &player_client);
}

static void free_texts(char **texts, jsize count) {
  for (jsize i = 0; i < count; i++) {
    free(texts[i]);
  }
  free((void *)texts);
}

// Copies of the strings of a Java array, for free_texts. Each local reference
// is deleted at once: a response can have more headers than Android 7 allows
// local references (512). NULL, after an OutOfMemoryError when it is JNI's,
// when out of memory.
static char **texts_of(JNIEnv *env, jobjectArray array, jsize count) {
  char **texts = (char **)calloc((size_t)count + 1, sizeof *texts);
  if (texts == NULL) {
    throw_out_of_memory(env);
    return NULL;
  }
  for (jsize i = 0; i < count; i++) {
    jstring string = (*env)->GetObjectArrayElement(env, array, i);
    const char *chars = string != NULL ? (*env)->GetStringUTFChars(env, string, NULL) : NULL;
    texts[i] = chars != NULL ? strdup(chars) : NULL;
    if (chars != NULL) {
      (*env)->ReleaseStringUTFChars(env, string, chars);
    }
    (*env)->DeleteLocalRef(env, string);
    if (texts[i] == NULL) {
      if (chars != NULL) {
        throw_out_of_memory(env);
      }
      free_texts(texts, i);
      return NULL;
    }
  }
  return texts;
}

JNIEXPORT jboolean JNICALL Java_com_novage_p2pml_engine_NativeEngine_httpResponse(
    JNIEnv *env, jclass type, jlong handle, jint id, jint status, jstring status_text, jstring url,
    jobjectArray headers) {
  (void)type;
  jsize count = (*env)->GetArrayLength(env, headers);
  char **texts = texts_of(env, headers, count);
  const char *status_chars =
      texts != NULL ? (*env)->GetStringUTFChars(env, status_text, NULL) : NULL;
  const char *url_chars = status_chars != NULL ? (*env)->GetStringUTFChars(env, url, NULL) : NULL;
  bool posted = url_chars != NULL &&
                p2pml_http_response(engine_of(handle), id, status, status_chars, url_chars,
                                    (size_t)count / 2, (const char *const *)texts);
  if (url_chars != NULL) {
    (*env)->ReleaseStringUTFChars(env, url, url_chars);
  }
  if (status_chars != NULL) {
    (*env)->ReleaseStringUTFChars(env, status_text, status_chars);
  }
  if (texts != NULL) {
    free_texts(texts, count);
  }
  return posted;
}

JNIEXPORT jboolean JNICALL Java_com_novage_p2pml_engine_NativeEngine_httpData(
    JNIEnv *env, jclass type, jlong handle, jint id, jbyteArray bytes, jint length) {
  (void)type;
  // Critical: no copy into a JNI buffer; the engine copies once, and nothing
  // here calls JNI or waits long.
  jbyte *elements = (*env)->GetPrimitiveArrayCritical(env, bytes, NULL);
  if (elements == NULL) {
    return false;
  }
  bool posted = p2pml_http_data(engine_of(handle), id, (const uint8_t *)elements, (size_t)length);
  (*env)->ReleasePrimitiveArrayCritical(env, bytes, elements, JNI_ABORT);
  return posted;
}

JNIEXPORT jboolean JNICALL Java_com_novage_p2pml_engine_NativeEngine_httpEnd(JNIEnv *env,
                                                                             jclass type,
                                                                             jlong handle,
                                                                             jint id) {
  (void)env;
  (void)type;
  return p2pml_http_end(engine_of(handle), id);
}

JNIEXPORT jboolean JNICALL Java_com_novage_p2pml_engine_NativeEngine_httpFail(JNIEnv *env,
                                                                              jclass type,
                                                                              jlong handle, jint id,
                                                                              jstring message) {
  (void)type;
  const char *text = (*env)->GetStringUTFChars(env, message, NULL);
  if (text == NULL) {
    return false;
  }
  bool posted = p2pml_http_fail(engine_of(handle), id, text);
  (*env)->ReleaseStringUTFChars(env, message, text);
  return posted;
}

JNIEXPORT jboolean JNICALL Java_com_novage_p2pml_engine_NativeEngine_post(JNIEnv *env, jclass type,
                                                                          jlong handle,
                                                                          jobject task) {
  (void)type;
  jobject ref = (*env)->NewGlobalRef(env, task);
  if (ref == NULL) {
    return false;
  }
  if (!p2pml_engine_post(engine_of(handle), run_task, discard_task, ref)) {
    (*env)->DeleteGlobalRef(env, ref);
    return false;
  }
  return true;
}

JNIEXPORT void JNICALL Java_com_novage_p2pml_engine_NativeEngine_close(JNIEnv *env, jclass type,
                                                                       jlong handle) {
  (void)env;
  (void)type;
  p2pml_engine_close(engine_of(handle));
}

JNIEXPORT void JNICALL Java_com_novage_p2pml_engine_NativeEngine_free(JNIEnv *env, jclass type,
                                                                      jlong handle) {
  (void)env;
  (void)type;
  p2pml_engine_free(engine_of(handle));
}

// The strings are CESU-8, as in write_log().
static void throw_js_exception(JNIEnv *env, const p2pml_eval_result *result) {
  jstring message = (*env)->NewStringUTF(env, result->error);
  if (message == NULL) {
    return;
  }
  jstring stack = NULL;
  if (result->stack != NULL) {
    stack = (*env)->NewStringUTF(env, result->stack);
    if (stack == NULL) {
      return;
    }
  }
  jobject exception = (*env)->NewObject(env, js_exception_class, js_exception_init, message, stack);
  if (exception != NULL) {
    (*env)->Throw(env, exception);
  }
}

JNIEXPORT jstring JNICALL Java_com_novage_p2pml_engine_NativeEngine_evaluateScript(
    JNIEnv *env, jclass type, jlong handle, jbyteArray code, jstring file_name) {
  (void)type;
  jsize length;
  // QuickJS needs a NUL after the code.
  char *source = copy_bytes(env, code, &length);
  if (source == NULL) {
    return NULL;
  }
  const char *name = (*env)->GetStringUTFChars(env, file_name, NULL);
  if (name == NULL) {
    free(source);
    return NULL;
  }
  p2pml_eval_result result;
  bool ok = p2pml_engine_eval(engine_of(handle), source, (size_t)length, name, &result);
  (*env)->ReleaseStringUTFChars(env, file_name, name);
  free(source);
  jstring value = NULL;
  if ((*env)->ExceptionCheck(env)) {
    // From the log, while the script ran (see write_log): it goes to Kotlin.
  } else if (result.closed) {
    (*env)->ThrowNew(env, closed_exception_class, "The JS engine is closed");
  } else if (ok ? result.value == NULL : result.error == NULL) {
    throw_out_of_memory(env);
  } else if (ok) {
    value = (*env)->NewStringUTF(env, result.value);
  } else {
    throw_js_exception(env, &result);
  }
  p2pml_eval_result_free(&result);
  return value;
}

JNIEXPORT jboolean JNICALL Java_com_novage_p2pml_engine_NativeEngine_wsOpen(
    JNIEnv *env, jclass type, jlong handle, jint id, jstring protocol, jstring extensions) {
  (void)type;
  // HTTP tokens: ASCII.
  const char *protocol_chars = (*env)->GetStringUTFChars(env, protocol, NULL);
  const char *extensions_chars =
      protocol_chars != NULL ? (*env)->GetStringUTFChars(env, extensions, NULL) : NULL;
  bool posted = extensions_chars != NULL &&
                p2pml_ws_open(engine_of(handle), id, protocol_chars, extensions_chars);
  if (extensions_chars != NULL) {
    (*env)->ReleaseStringUTFChars(env, extensions, extensions_chars);
  }
  if (protocol_chars != NULL) {
    (*env)->ReleaseStringUTFChars(env, protocol, protocol_chars);
  }
  return posted;
}

JNIEXPORT jboolean JNICALL Java_com_novage_p2pml_engine_NativeEngine_wsMessage(
    JNIEnv *env, jclass type, jlong handle, jint id, jboolean binary, jbyteArray bytes) {
  (void)type;
  jsize length = (*env)->GetArrayLength(env, bytes);
  // As httpData: the engine copies once.
  jbyte *elements = (*env)->GetPrimitiveArrayCritical(env, bytes, NULL);
  if (elements == NULL) {
    return false;
  }
  bool posted =
      p2pml_ws_message(engine_of(handle), id, binary, (const uint8_t *)elements, (size_t)length);
  (*env)->ReleasePrimitiveArrayCritical(env, bytes, elements, JNI_ABORT);
  return posted;
}

JNIEXPORT jboolean JNICALL Java_com_novage_p2pml_engine_NativeEngine_wsClose(
    JNIEnv *env, jclass type, jlong handle, jint id, jint code, jbyteArray reason,
    jboolean was_clean, jboolean failed) {
  (void)type;
  jsize length = (*env)->GetArrayLength(env, reason);
  jbyte *elements = (*env)->GetPrimitiveArrayCritical(env, reason, NULL);
  if (elements == NULL) {
    return false;
  }
  bool posted = p2pml_ws_close(engine_of(handle), id, code, (const uint8_t *)elements,
                               (size_t)length, was_clean, failed);
  (*env)->ReleasePrimitiveArrayCritical(env, reason, elements, JNI_ABORT);
  return posted;
}

// Engine thread, in a task: gives event `name` (ASCII) of call `id` (a query or
// a load; 0 for an event with no answer) to the player bridge, with its texts,
// UTF-8 bytes or null for none. False when JS did not take it, also with too
// many texts, and with an OutOfMemoryError when a copy fails.
JNIEXPORT jboolean JNICALL Java_com_novage_p2pml_engine_NativeEngine_playerEvent(
    JNIEnv *env, jclass type, jlong handle, jstring name, jint id, jobjectArray texts) {
  (void)type;
  jsize count = (*env)->GetArrayLength(env, texts);
  if (count > P2PML_CHANNEL_MAX_ARGS) {
    return false;
  }
  char *copies[P2PML_CHANNEL_MAX_ARGS] = {NULL};
  p2pml_arg args[P2PML_CHANNEL_MAX_ARGS];
  bool copied = true;
  for (jsize i = 0; copied && i < count; i++) {
    jbyteArray text = (*env)->GetObjectArrayElement(env, texts, i);
    args[i] = (p2pml_arg){.type = P2PML_ARG_UNDEFINED};
    if (text != NULL) {
      jsize length = 0;
      copies[i] = copy_bytes(env, text, &length);
      copied = copies[i] != NULL;
      args[i] =
          (p2pml_arg){.type = P2PML_ARG_STRING, .pointer = copies[i], .length = (size_t)length};
    }
    (*env)->DeleteLocalRef(env, text);
  }
  const char *chars = copied ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
  bool given =
      chars != NULL && p2pml_player_event(engine_of(handle), chars, id, (size_t)count, args);
  if (chars != NULL) {
    (*env)->ReleaseStringUTFChars(env, name, chars);
  }
  for (jsize i = 0; i < count; i++) {
    free(copies[i]);
  }
  return given;
}
