// The byte work of TextEncoder and TextDecoder (src/polyfills/encoding.js):
// UTF-8 as the WHATWG Encoding Standard defines it.

#ifndef P2PML_ENCODING_H
#define P2PML_ENCODING_H

#include <stdbool.h>

#include "quickjs.h"

// Adds utf8Encode and utf8Decode to `natives`, the object of C helpers that
// the polyfills get; `natives` stays the caller's. False when out of memory.
bool p2pml_add_encoding(JSContext *context, JSValueConst natives);

#endif
