#include "url.h"

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "ada_c.h"
#include "encoding.h"

// The JS functions below follow QuickJS's convention: the arguments stay
// QuickJS's, and the returned value is new, for the caller to free. The glue
// (url.js) passes strings that are well formed (no lone surrogate), which
// JS_ToCStringLen2 gives as UTF-8, as Ada takes them. No ada_url outlives a
// call.

typedef ada_string ada_getter(ada_url url);
typedef bool ada_setter(ada_url url, const char *input, size_t length);

// Ada's setters of search and hash cannot fail.
static bool set_search(ada_url url, const char *input, size_t length) {
  ada_set_search(url, input, length);
  return true;
}

static bool set_hash(ada_url url, const char *input, size_t length) {
  ada_set_hash(url, input, length);
  return true;
}

// The parts of a URL, in the order URL's attributes list them, except origin.
static const struct {
  const char *name;
  ada_getter *get;
  ada_setter *set;
} PARTS[] = {
    // The href setter is a new parse (url.js), not urlSet.
    {"href", ada_get_href, NULL},
    {"protocol", ada_get_protocol, ada_set_protocol},
    {"username", ada_get_username, ada_set_username},
    {"password", ada_get_password, ada_set_password},
    {"host", ada_get_host, ada_set_host},
    {"hostname", ada_get_hostname, ada_set_hostname},
    {"port", ada_get_port, ada_set_port},
    {"pathname", ada_get_pathname, ada_set_pathname},
    {"search", ada_get_search, set_search},
    {"hash", ada_get_hash, set_hash},
};

// A new object of the parts of a valid URL, origin included.
static JSValue parts_of(JSContext *context, ada_url url) {
  JSValue parts = JS_NewObject(context);
  if (JS_IsException(parts)) {
    return parts;
  }
  for (size_t i = 0; i < sizeof PARTS / sizeof PARTS[0]; i++) {
    ada_string part = PARTS[i].get(url);
    if (JS_SetPropertyStr(context, parts, PARTS[i].name,
                          JS_NewStringLen(context, part.data, part.length)) < 0) {
      JS_FreeValue(context, parts);
      return JS_EXCEPTION;
    }
  }
  ada_owned_string origin = ada_get_origin(url);
  int set = JS_SetPropertyStr(context, parts, "origin",
                              JS_NewStringLen(context, origin.data, origin.length));
  ada_free_owned_string(origin);
  if (set < 0) {
    JS_FreeValue(context, parts);
    return JS_EXCEPTION;
  }
  return parts;
}

// Parses `input` against `base` unless it is undefined; both stay the
// caller's. NULL with an exception when a string cannot be converted; else an
// ada_url to free, which can be invalid.
static ada_url parse(JSContext *context, JSValueConst input, JSValueConst base) {
  size_t length = 0;
  const char *text = JS_ToCStringLen2(context, &length, input, false);
  if (text == NULL) {
    return NULL;
  }
  ada_url url = NULL;
  if (JS_IsUndefined(base)) {
    url = ada_parse(text, length);
  } else {
    size_t base_length = 0;
    const char *base_text = JS_ToCStringLen2(context, &base_length, base, false);
    if (base_text != NULL) {
      url = ada_parse_with_base(text, length, base_text, base_length);
      JS_FreeCString(context, base_text);
    }
  }
  JS_FreeCString(context, text);
  return url;
}

// urlParse(input, base): the parts of the URL, or null when it is not one.
static JSValue url_parse(JSContext *context, JSValueConst this_value, int argc,
                         JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  ada_url url = parse(context, argv[0], argv[1]);
  if (url == NULL) {
    return JS_EXCEPTION;
  }
  JSValue parts = ada_is_valid(url) ? parts_of(context, url) : JS_NULL;
  ada_free(url);
  return parts;
}

// urlSet(href, part, value): the parts after the setter of `part`, or null
// when the setter fails (the URL is then as it was).
static JSValue url_set(JSContext *context, JSValueConst this_value, int argc, JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  const char *part = JS_ToCString(context, argv[1]);
  if (part == NULL) {
    return JS_EXCEPTION;
  }
  ada_setter *set = NULL;
  for (size_t i = 0; set == NULL && i < sizeof PARTS / sizeof PARTS[0]; i++) {
    if (strcmp(part, PARTS[i].name) == 0) {
      set = PARTS[i].set;
    }
  }
  JS_FreeCString(context, part);
  if (set == NULL) {
    return JS_ThrowTypeError(context, "urlSet: unknown part");
  }
  ada_url url = parse(context, argv[0], JS_UNDEFINED);
  if (url == NULL) {
    return JS_EXCEPTION;
  }
  size_t length = 0;
  const char *value = JS_ToCStringLen2(context, &length, argv[2], false);
  JSValue parts = JS_EXCEPTION;
  if (value != NULL) {
    parts = ada_is_valid(url) && set(url, value, length) ? parts_of(context, url) : JS_NULL;
    JS_FreeCString(context, value);
  }
  ada_free(url);
  return parts;
}

// searchParse(query): [[name, value], ...] of an
// application/x-www-form-urlencoded string. Ada drops a leading "?", as the
// URLSearchParams constructor does: a URL's query is passed with its "?".
static JSValue search_parse(JSContext *context, JSValueConst this_value, int argc,
                            JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  size_t length = 0;
  const char *query = JS_ToCStringLen2(context, &length, argv[0], false);
  if (query == NULL) {
    return JS_EXCEPTION;
  }
  ada_url_search_params params = ada_parse_search_params(query, length);
  JS_FreeCString(context, query);
  ada_url_search_params_entries_iter entries = ada_search_params_get_entries(params);
  JSValue list = JS_NewArray(context);
  for (uint32_t i = 0; !JS_IsException(list) && ada_search_params_entries_iter_has_next(entries);
       i++) {
    ada_string_pair entry = ada_search_params_entries_iter_next(entries);
    // Ada gives the percent-decoded bytes; the standard decodes them as UTF-8
    // with U+FFFD for each invalid part.
    JSValue pair = JS_NewArray(context);
    bool made =
        !JS_IsException(pair) &&
        JS_SetPropertyUint32(context, pair, 0,
                             p2pml_new_string_from_utf8(context, (const uint8_t *)entry.key.data,
                                                        entry.key.length)) >= 0 &&
        JS_SetPropertyUint32(context, pair, 1,
                             p2pml_new_string_from_utf8(context, (const uint8_t *)entry.value.data,
                                                        entry.value.length)) >= 0;
    if (!made) {
      JS_FreeValue(context, pair);
    }
    // The list takes the pair, also when this fails.
    if (!made || JS_DefinePropertyValueUint32(context, list, i, pair, JS_PROP_C_W_E) < 0) {
      JS_FreeValue(context, list);
      list = JS_EXCEPTION;
    }
  }
  ada_free_search_params_entries_iter(entries);
  ada_free_search_params(params);
  return list;
}

// Appends pairs[i] to `params`; `pairs` stays the caller's. False with an
// exception when a value cannot be read or converted.
static bool append_pair(JSContext *context, ada_url_search_params params, JSValueConst pairs,
                        uint32_t i) {
  JSValue pair = JS_GetPropertyUint32(context, pairs, i);
  JSValue name = JS_GetPropertyUint32(context, pair, 0);
  JSValue value = JS_GetPropertyUint32(context, pair, 1);
  size_t name_length = 0;
  size_t value_length = 0;
  const char *name_text = JS_ToCStringLen2(context, &name_length, name, false);
  const char *value_text =
      name_text != NULL ? JS_ToCStringLen2(context, &value_length, value, false) : NULL;
  bool appended = value_text != NULL;
  if (appended) {
    ada_search_params_append(params, name_text, name_length, value_text, value_length);
  }
  JS_FreeCString(context, value_text);
  JS_FreeCString(context, name_text);
  JS_FreeValue(context, value);
  JS_FreeValue(context, name);
  JS_FreeValue(context, pair);
  return appended;
}

// searchSerialize(pairs): the application/x-www-form-urlencoded string of
// [[name, value], ...].
static JSValue search_serialize(JSContext *context, JSValueConst this_value, int argc,
                                JSValueConst *argv) {
  (void)this_value;
  (void)argc;
  JSValue count_value = JS_GetPropertyStr(context, argv[0], "length");
  uint32_t count = 0;
  int converted = JS_ToUint32(context, &count, count_value);
  JS_FreeValue(context, count_value);
  if (converted < 0) {
    return JS_EXCEPTION;
  }
  ada_url_search_params params = ada_parse_search_params("", 0);
  bool appended = true;
  for (uint32_t i = 0; appended && i < count; i++) {
    appended = append_pair(context, params, argv[0], i);
  }
  JSValue text = JS_EXCEPTION;
  if (appended) {
    ada_owned_string serialized = ada_search_params_to_string(params);
    text = JS_NewStringLen(context, serialized.data, serialized.length);
    ada_free_owned_string(serialized);
  }
  ada_free_search_params(params);
  return text;
}

static const JSCFunctionListEntry url_functions[] = {
    JS_CFUNC_DEF("urlParse", 2, url_parse),
    JS_CFUNC_DEF("urlSet", 3, url_set),
    JS_CFUNC_DEF("searchParse", 1, search_parse),
    JS_CFUNC_DEF("searchSerialize", 1, search_serialize),
};

bool p2pml_add_url(JSContext *context, JSValueConst natives) {
  return JS_SetPropertyFunctionList(context, natives, url_functions,
                                    (int)(sizeof url_functions / sizeof url_functions[0])) == 0;
}
