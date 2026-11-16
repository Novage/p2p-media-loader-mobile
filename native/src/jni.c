// The JNI functions of com.novage.p2pml.engine.NativeEngine. Only the glue
// between Kotlin and engine.c.

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "engine.h"

static JavaVM *java_vm;
static jmethodID runnable_run;
static jclass js_exception_class;
static jmethodID js_exception_init;
static jclass closed_exception_class;

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

static void run_task(p2pml_engine *engine, void *data) {
  JNIEnv *env = current_env();
  jobject task = data;
  (*env)->CallVoidMethod(env, task, runnable_run);
  // The Kotlin side catches exceptions, so this is an Error, such as
  // OutOfMemoryError. It ends the engine thread once NativeEngine.run returns, and
  // no JNI call that a pending exception forbids may run before that.
  if ((*env)->ExceptionCheck(env)) {
    p2pml_engine_close(engine);
  }
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

JNIEXPORT jlong JNICALL Java_com_novage_p2pml_engine_NativeEngine_create(JNIEnv *env, jclass type) {
  (void)type;
  p2pml_engine *engine = p2pml_engine_new();
  if (engine == NULL) {
    throw_out_of_memory(env);
    return 0;
  }
  return (jlong)(intptr_t)engine;
}

JNIEXPORT jboolean JNICALL Java_com_novage_p2pml_engine_NativeEngine_run(JNIEnv *env, jclass type,
                                                                         jlong handle) {
  (void)env;
  (void)type;
  return p2pml_engine_run(engine_of(handle));
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

// The strings are CESU-8, which NewStringUTF reads as modified UTF-8.
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
  jsize length = (*env)->GetArrayLength(env, code);
  // QuickJS needs a NUL after the code.
  char *source = malloc((size_t)length + 1);
  if (source == NULL) {
    throw_out_of_memory(env);
    return NULL;
  }
  (*env)->GetByteArrayRegion(env, code, 0, length, (jbyte *)source);
  source[length] = '\0';
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
  if (result.closed) {
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
