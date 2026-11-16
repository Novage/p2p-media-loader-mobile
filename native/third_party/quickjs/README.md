# QuickJS

Bellard's QuickJS, release 2026-06-04, from
<https://bellard.org/quickjs/quickjs-2026-06-04.tar.xz> (sha256
`b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a`). MIT
licence: [LICENSE](LICENSE).

This folder holds only the files the engine compiles, unchanged: the engine,
libregexp, libunicode, cutils and dtoa, their headers, LICENSE and VERSION.
`native/CMakeLists.txt` compiles them with the flags of the release's own
Makefile.

To update:

1. Download the new release and check its sha256 against a second download.
2. Replace the files in this folder with the new ones, and add any new file
   that they include.
3. Update the version, URL and sha256 above.
4. Build and run the tests (see `AGENTS.md`), and read the release's
   Changelog for changes to the API that `native/src` uses.
