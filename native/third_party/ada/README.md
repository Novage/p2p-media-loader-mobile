# Ada

Ada, the WHATWG URL parser of Node.js, release v4.0.0, from
<https://github.com/ada-url/ada/releases/tag/v4.0.0>: the single-header
files of the release, unchanged.

| File      | sha256                                                             |
| --------- | ------------------------------------------------------------------ |
| `ada.cpp` | `1c722f8f5355d6bf2bf3601870684edc875aa8f56b4d4735440d96263dd0207c` |
| `ada.h`   | `0010b17b3ff489e13998e86d921b16e1643e370ee3276c584f1b0c4b72bdf628` |
| `ada_c.h` | `75641082708f01f61f4a8924ae6df19e172e737ced7265965b01a83ebec14f7c` |

Licence: MIT or Apache-2.0, at your choice ([LICENSE-MIT](LICENSE-MIT),
[LICENSE-APACHE](LICENSE-APACHE)); the library uses it under MIT.

Ada is C++20. `native/CMakeLists.txt` compiles it as a library of its own,
without exceptions, RTTI and URLPattern (`ADA_INCLUDE_URL_PATTERN=0`). Our
code calls it only through its C API, `ada_c.h` (`native/src/url.c`).

To update:

1. Download `ada.cpp`, `ada.h` and `ada_c.h` of the new release, and check
   their sha256 against the digests that the release page lists.
2. Replace the files in this folder, and the licences if they changed.
3. Update the version and hashes above.
4. Build and run the tests (see `AGENTS.md`): the WPT URL data in
   `native/test/third_party/wpt-url/` must still pass. Read the release notes
   for changes to `ada_c.h`.
