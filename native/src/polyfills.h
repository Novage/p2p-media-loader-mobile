// The JS polyfills built into the library. CMakeLists.txt (embed_js) generates
// the list from src/polyfills/*.js. See specs/engine.md, "Polyfills".

#ifndef P2PML_POLYFILLS_H
#define P2PML_POLYFILLS_H

typedef struct {
  // The file's name, for stack traces.
  const char *name;
  // UTF-8, with a NUL after it, as JS_Eval needs.
  const unsigned char *source;
} p2pml_script;

// In the order the engine evaluates them.
extern const p2pml_script p2pml_polyfills[];
extern const int p2pml_polyfills_count;

#endif
