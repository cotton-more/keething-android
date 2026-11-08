# The native Argon2 (keething-spec unlock.faster-argon2): the vendored reference code and its JNI bridge.
cmake_minimum_required(VERSION 3.22)
project(keething_argon2 C)

set(ARGON2 ${CMAKE_CURRENT_SOURCE_DIR}/phc-winner-argon2)
add_library(keething_argon2 SHARED
    keething_argon2.c
    ${ARGON2}/src/core.c
    ${ARGON2}/src/ref.c
    ${ARGON2}/src/blake2/blake2b.c)
target_include_directories(keething_argon2 PRIVATE ${ARGON2}/include ${ARGON2}/src)
# keething_argon2.c fills the lanes with workers of its own; the reference code's threads are not built.
target_compile_definitions(keething_argon2 PRIVATE ARGON2_NO_THREADS)
set_target_properties(keething_argon2 PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON)
# -O3 in every build: the debug build's instrumented tests run it too. The paths of this checkout are kept out of the
# library, so that a build elsewhere (F-Droid's) makes the same bytes.
target_compile_options(keething_argon2 PRIVATE
    -O3 -fvisibility=hidden -Wall
    -ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.
    -ffile-prefix-map=${CMAKE_BINARY_DIR}=.)
