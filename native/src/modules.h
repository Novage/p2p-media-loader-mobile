// The ES modules that JS can import: the web core's bundle, built into the
// library (CMakeLists.txt, embed_js). See specs/engine.md, "Modules".

#ifndef P2PML_MODULES_H
#define P2PML_MODULES_H

#include "quickjs.h"

// Lets JS in `runtime` import the built-in modules by name.
void p2pml_add_modules(JSRuntime *runtime);

#endif
