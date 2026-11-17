// The byte work of TextEncoder and TextDecoder (src/polyfills/encoding.js):
// UTF-8 as the WHATWG Encoding Standard defines it.

#ifndef P2PML_ENCODING_H
#define P2PML_ENCODING_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "quickjs.h"

// Adds utf8Encode and utf8Decode to `natives`, the object of C helpers that
// the polyfills get; `natives` stays the caller's. False when out of memory.
bool p2pml_add_encoding(JSContext *context, JSValueConst natives);

// A new string of the UTF-8 `bytes`, as the WHATWG "UTF-8 decode without BOM"
// gives it: U+FFFD for each maximal invalid subpart. JS_EXCEPTION when out of
// memory.
JSValue p2pml_new_string_from_utf8(JSContext *context, const uint8_t *bytes, size_t length);

#endif
