#include "encoding.h"

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// U+FFFD, the replacement character, in UTF-8.
static const uint8_t REPLACEMENT[] = {0xEF, 0xBF, 0xBD};

// Checks the UTF-8 sequence at `bytes` (`left` > 0 of them), as the WHATWG
// UTF-8 decoder does: no overlong form, no surrogate, nothing above U+10FFFF.
// Returns its length if it is valid. Else returns 0 and sets `*invalid` to
// the length of its maximal invalid subpart (1 to 3), which becomes one
// U+FFFD.
static size_t check_sequence(const uint8_t *bytes, size_t left, size_t *invalid) {
  uint8_t lead = bytes[0];
  if (lead < 0x80) {
    return 1;
  }
  size_t needed;
  // The range of the first continuation byte; the others are 80 to BF.
  uint8_t low = 0x80;
  uint8_t high = 0xBF;
  if (lead >= 0xC2 && lead <= 0xDF) {
    needed = 1;
  } else if (lead >= 0xE0 && lead <= 0xEF) {
    needed = 2;
    low = lead == 0xE0 ? 0xA0 : low;
    high = lead == 0xED ? 0x9F : high;
  } else if (lead >= 0xF0 && lead <= 0xF4) {
    needed = 3;
    low = lead == 0xF0 ? 0x90 : low;
    high = lead == 0xF4 ? 0x8F : high;
  } else {
    *invalid = 1;
    return 0;
  }
  for (size_t i = 1; i <= needed; i++) {
    if (i >= left || bytes[i] < low || bytes[i] > high) {
      *invalid = i;
      return 0;
    }
    low = 0x80;
    high = 0xBF;
  }
  return needed + 1;
}

// Writes `bytes` to `out` with U+FFFD for each maximal invalid subpart, and
// returns the length written. With `out` NULL, only returns the length.
// `*replaced` tells whether a part was invalid.
static size_t repair(const uint8_t *bytes, size_t length, uint8_t *out, bool *replaced) {
  size_t written = 0;
  size_t i = 0;
  *replaced = false;
  while (i < length) {
    size_t invalid = 0;
    size_t valid = check_sequence(bytes + i, length - i, &invalid);
    *replaced = *replaced || valid == 0;
    const uint8_t *part = valid > 0 ? bytes + i : REPLACEMENT;
    size_t part_length = valid > 0 ? valid : sizeof REPLACEMENT;
    if (out != NULL) {
      memcpy(out + written, part, part_length);
    }
    written += part_length;
    i += valid > 0 ? valid : invalid;
  }
  return written;
}

// utf8Encode(string): a new Uint8Array of the string in UTF-8. The string
// has no lone surrogate (the polyfill gives a USVString), which QuickJS would
// keep, as ED A0-BF xx. QuickJS's convention: the arguments stay QuickJS's,
// and the returned value is new, for the caller to free.
static JSValue utf8_encode(JSContext *context, JSValueConst this_value, int argc,
                           JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  size_t length = 0;
  const char *text = JS_ToCStringLen(context, &length, argv[0]);
  if (text == NULL) {
    return JS_EXCEPTION;
  }
  JSValue buffer = JS_NewArrayBufferCopy(context, (const uint8_t *)text, length);
  JS_FreeCString(context, text);
  if (JS_IsException(buffer)) {
    return buffer;
  }
  // QuickJS reads all three arguments: the buffer, its offset and length.
  JSValueConst arguments[] = {buffer, JS_UNDEFINED, JS_UNDEFINED};
  JSValue array = JS_NewTypedArray(context, 3, arguments, JS_TYPED_ARRAY_UINT8);
  JS_FreeValue(context, buffer);
  return array;
}

// utf8Decode(bytes, fatal, ignoreBOM): the string of `bytes`, a non-empty
// Uint8Array. A leading BOM is dropped unless `ignoreBOM`. Invalid UTF-8
// throws a TypeError if `fatal`, else each maximal invalid subpart becomes
// U+FFFD. Values as utf8Encode's.
static JSValue utf8_decode(JSContext *context, JSValueConst this_value, int argc,
                           JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  size_t offset = 0;
  size_t length = 0;
  JSValue buffer = JS_GetTypedArrayBuffer(context, argv[0], &offset, &length, NULL);
  if (JS_IsException(buffer)) {
    return buffer;
  }
  size_t size = 0;
  // The array keeps the buffer alive.
  const uint8_t *bytes = JS_GetArrayBuffer(context, &size, buffer);
  JS_FreeValue(context, buffer);
  if (bytes == NULL) {
    return JS_EXCEPTION;
  }
  bytes += offset;
  if (!JS_ToBool(context, argv[2]) && length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB &&
      bytes[2] == 0xBF) {
    bytes += 3;
    length -= 3;
  }
  if (JS_ToBool(context, argv[1])) {
    bool replaced = false;
    repair(bytes, length, NULL, &replaced);
    if (replaced) {
      return JS_ThrowTypeError(context, "The encoded data is not valid UTF-8");
    }
  }
  return p2pml_new_string_from_utf8(context, bytes, length);
}

JSValue p2pml_new_string_from_utf8(JSContext *context, const uint8_t *bytes, size_t length) {
  bool replaced = false;
  size_t repaired_length = repair(bytes, length, NULL, &replaced);
  // QuickJS's own decoder replaces invalid bytes in other ways: only valid
  // UTF-8 goes to it.
  if (!replaced) {
    return JS_NewStringLen(context, (const char *)bytes, length);
  }
  uint8_t *repaired = malloc(repaired_length);
  if (repaired == NULL) {
    return JS_ThrowOutOfMemory(context);
  }
  repair(bytes, length, repaired, &replaced);
  JSValue text = JS_NewStringLen(context, (const char *)repaired, repaired_length);
  free(repaired);
  return text;
}

static const JSCFunctionListEntry encoding_functions[] = {
    JS_CFUNC_DEF("utf8Encode", 1, utf8_encode),
    JS_CFUNC_DEF("utf8Decode", 3, utf8_decode),
};

bool p2pml_add_encoding(JSContext *context, JSValueConst natives) {
  return JS_SetPropertyFunctionList(
             context, natives, encoding_functions,
             (int)(sizeof encoding_functions / sizeof encoding_functions[0])) == 0;
}
