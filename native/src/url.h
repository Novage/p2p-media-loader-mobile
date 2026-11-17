// The parsing and serializing of URL and URLSearchParams
// (src/polyfills/url.js): Ada, through its C API.

#ifndef P2PML_URL_H
#define P2PML_URL_H

#include <stdbool.h>

#include "quickjs.h"

// Adds urlParse, urlSet, searchParse and searchSerialize to `natives`, the
// object of C helpers that the polyfills get; `natives` stays the caller's.
// False when out of memory.
bool p2pml_add_url(JSContext *context, JSValueConst natives);

#endif
