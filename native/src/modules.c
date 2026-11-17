#include "modules.h"

#include <string.h>

#include "polyfills.h"

// The core's bundle, p2p-media-loader-core-hls.es.js, from the npm package
// that native/package.json pins. CMakeLists.txt (embed_js) generates it.
extern const p2pml_script p2pml_core_bundle[];

#define CORE_MODULE "p2p-media-loader-core"

// Compiles the module `name`; QuickJS evaluates it, and keeps it for the
// imports after the first. NULL with an exception for a name it does not know.
static JSModuleDef *load_module(JSContext *context, const char *name, void *opaque) {
  (void)opaque;
  if (strcmp(name, CORE_MODULE) != 0) {
    JS_ThrowReferenceError(context, "could not load module '%s'", name);
    return NULL;
  }
  const char *source = (const char *)p2pml_core_bundle[0].source;
  // The module's name is its file name: QuickJS finds a loaded module by it.
  JSValue module = JS_Eval(context, source, strlen(source), CORE_MODULE,
                           JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
  if (JS_IsException(module)) {
    return NULL;
  }
  // The context's list of loaded modules holds a reference of its own.
  JSModuleDef *loaded = JS_VALUE_GET_PTR(module);
  JS_FreeValue(context, module);
  return loaded;
}

void p2pml_add_modules(JSRuntime *runtime) {
  JS_SetModuleLoaderFunc(runtime, NULL, load_module, NULL);
}
