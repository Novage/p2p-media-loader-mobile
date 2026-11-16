# libp2pml: QuickJS and the engine. The Android build (AGP's externalNativeBuild)
# makes the shared library for each ABI. The host build (P2PML_HOST, from the
# Gradle task hostNativeBuild) makes the same library for the JVM host tests,
# and the C tests with sanitizers.

cmake_minimum_required(VERSION 3.22)
project(p2pml C)

set(CMAKE_C_STANDARD 11)
# gnu11, not c11: the code uses POSIX and platform functions, such as strdup.
set(CMAKE_C_EXTENSIONS ON)
# Only the JNI functions are exported, which also lets the linker drop the
# parts of QuickJS the engine does not use.
set(CMAKE_C_VISIBILITY_PRESET hidden)

option(P2PML_HOST "Build for the host: the JNI library for JVM tests and the C tests" OFF)
set(P2PML_CLANG_TIDY "" CACHE FILEPATH "clang-tidy to run on our sources, every finding an error")

find_package(Threads REQUIRED)

set(QUICKJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/quickjs)
file(STRINGS ${QUICKJS_DIR}/VERSION QUICKJS_VERSION)

# QuickJS as released, with the defines and code flags of its own Makefile.
# The optimization follows the build type: a debug build runs it slower.
function(add_quickjs name)
  add_library(${name} STATIC
    ${QUICKJS_DIR}/quickjs.c
    ${QUICKJS_DIR}/libregexp.c
    ${QUICKJS_DIR}/libunicode.c
    ${QUICKJS_DIR}/cutils.c
    ${QUICKJS_DIR}/dtoa.c)
  target_compile_definitions(${name} PRIVATE _GNU_SOURCE CONFIG_VERSION="${QUICKJS_VERSION}")
  target_compile_options(${name} PRIVATE -fwrapv -funsigned-char -w)
  set_target_properties(${name} PROPERTIES POSITION_INDEPENDENT_CODE ON)
  # SYSTEM: our warning flags do not apply to its headers.
  target_include_directories(${name} SYSTEM PUBLIC ${QUICKJS_DIR})
  target_link_libraries(${name} PUBLIC Threads::Threads)
  if(NOT APPLE)
    target_link_libraries(${name} PUBLIC m)
  endif()
endfunction()

function(use_our_checks target)
  target_compile_options(${target} PRIVATE -Wall -Wextra -Wpedantic -Werror)
  if(P2PML_CLANG_TIDY)
    set_target_properties(${target} PROPERTIES
      C_CLANG_TIDY "${P2PML_CLANG_TIDY};--warnings-as-errors=*;--quiet")
  endif()
endfunction()

add_quickjs(quickjs)

add_library(p2pml SHARED src/engine.c src/jni.c)
target_link_libraries(p2pml PRIVATE quickjs)
use_our_checks(p2pml)

if(P2PML_HOST)
  # The NDK's jni.h, which needs only the C library's headers: a JDK such as
  # Android Studio's may have no JNI headers. Copied alone, so that the rest
  # of the Android sysroot stays out of the host build.
  set(P2PML_JNI_H "" CACHE FILEPATH "jni.h for the host build")
  if(NOT EXISTS "${P2PML_JNI_H}")
    message(FATAL_ERROR "P2PML_JNI_H must name a jni.h, such as the NDK's")
  endif()
  configure_file(${P2PML_JNI_H} ${CMAKE_BINARY_DIR}/jni/jni.h COPYONLY)
  target_include_directories(p2pml SYSTEM PRIVATE ${CMAKE_BINARY_DIR}/jni)

  # The C tests: the engine with AddressSanitizer and UndefinedBehaviorSanitizer,
  # QuickJS included. Asserts stay on, so JS_FreeRuntime checks that no JS
  # object leaked.
  set(SANITIZERS -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer)
  add_quickjs(quickjs_sanitized)
  target_compile_options(quickjs_sanitized PRIVATE ${SANITIZERS})
  add_executable(engine_test test/engine_test.c src/engine.c)
  target_include_directories(engine_test PRIVATE src)
  target_compile_options(engine_test PRIVATE ${SANITIZERS})
  target_link_options(engine_test PRIVATE ${SANITIZERS})
  target_link_libraries(engine_test PRIVATE quickjs_sanitized)
  use_our_checks(engine_test)

  enable_testing()
  add_test(NAME engine COMMAND engine_test)
  # A broken loop hangs instead of failing.
  set_tests_properties(engine PROPERTIES TIMEOUT 60)
endif()
