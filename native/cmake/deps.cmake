# The external sources that the native build compiles: QuickJS, Ada, and
# WebRTC's libdatachannel with what it needs (see specs/engine.md, "Where the
# code is"). Run as a script by the Gradle task fetchNativeDeps:
#
#   cmake -DDEPS_DIR=<dir> -P deps.cmake
#
# Each archive is checked by its sha256 and never patched. A source whose
# extracted copy has the same sha256 is not downloaded or extracted again.
# How to update a pin: .agents/skills/update-dependencies/SKILL.md.

# 3.24: ARCHIVE_EXTRACT ... TOUCH.
cmake_minimum_required(VERSION 3.24)

if(NOT DEPS_DIR)
  message(FATAL_ERROR "Set DEPS_DIR")
endif()

# fetch(<folder> <url> <sha256>): the archive's top folder becomes
# DEPS_DIR/<folder>; an archive with no single top folder becomes it whole.
function(fetch folder url sha256)
  set(destination "${DEPS_DIR}/${folder}")
  set(stamp "${destination}/.sha256")
  if(EXISTS "${stamp}")
    file(READ "${stamp}" extracted)
    if(extracted STREQUAL sha256)
      return()
    endif()
  endif()
  get_filename_component(name "${folder}" NAME)
  set(archive "${DEPS_DIR}/downloads/${name}")
  # A download that stalls for 60 s fails, not the job's timeout.
  file(DOWNLOAD "${url}" "${archive}" EXPECTED_HASH SHA256=${sha256} TLS_VERIFY ON
       INACTIVITY_TIMEOUT 60 STATUS status)
  list(GET status 0 code)
  if(NOT code EQUAL 0)
    message(FATAL_ERROR "${url}: ${status}")
  endif()
  set(temporary "${DEPS_DIR}/downloads/${name}.extract")
  file(REMOVE_RECURSE "${temporary}" "${destination}")
  # TOUCH: files newer than the last build's objects, also when the new pin's
  # files are older, so that ninja and CMake build them again.
  file(ARCHIVE_EXTRACT INPUT "${archive}" DESTINATION "${temporary}" TOUCH)
  file(GLOB top LIST_DIRECTORIES true "${temporary}/*")
  list(LENGTH top count)
  if(count EQUAL 1 AND IS_DIRECTORY "${top}")
    file(RENAME "${top}" "${destination}")
    file(REMOVE_RECURSE "${temporary}")
  else()
    file(RENAME "${temporary}" "${destination}")
  endif()
  file(REMOVE "${archive}")
  file(WRITE "${stamp}" "${sha256}")
endfunction()

# QuickJS, Bellard's release: the engine compiles a part of it
# (native/CMakeLists.txt, add_quickjs). bellard.org is its only source.
fetch(quickjs
  https://bellard.org/quickjs/quickjs-2026-06-04.tar.xz
  b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a)
# Ada, the WHATWG URL parser of Node.js: the single-header files of its
# release (ada.cpp, ada.h, ada_c.h), which have no top folder.
fetch(ada
  https://github.com/ada-url/ada/releases/download/v4.0.0/singleheader.zip
  d7fa5aea7b3805c91111231d1aaaeefc39ae20562a9f599e13c22471ee576b9f)

# libdatachannel at a release tag. Its submodules, which its archive lacks,
# go where its CMakeLists.txt finds them, at the commits that the tag pins.
fetch(libdatachannel
  https://github.com/paullouisageneau/libdatachannel/archive/refs/tags/v0.24.6.tar.gz
  22a66aebebd8dae561d8e5181da96f2fcc203e067c5b2186c07b39af913464c0)
fetch(libdatachannel/deps/libjuice
  https://github.com/paullouisageneau/libjuice/archive/b89c792e3612faf2f12cf35bcc56857313a06be3.tar.gz
  713b22174e8cb2e06538fa9bac2650bda21b83c86f5128813ab4dcf8f5bea9ed)
fetch(libdatachannel/deps/usrsctp
  https://github.com/paullouisageneau/usrsctp/archive/fec583d54493f879d2ae44a743423bf8a04371ab.tar.gz
  e5c114afe73c9a0ec419fab5f5b3f63f3ce57b09b90d06ea85e63dca8aed3e7c)
fetch(libdatachannel/deps/plog
  https://github.com/SergiusTheBest/plog/archive/94899e0b926ac1b0f4750bfbd495167b4a6ae9ef.tar.gz
  92a08bce559b5f28aa88d3fd9071567414b9f43a83fe2a05a6dd14f1da536072)
# Mbed TLS 3.6 LTS: the release asset, which has the generated files.
fetch(mbedtls
  https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.8/mbedtls-3.6.8.tar.bz2
  bc54b818e87f49c0b3281dd85d24adaede0e63b4e822e3fbd49c005a38b9595b)
