cmake_minimum_required(VERSION 3.22.1)
project(hunter_btc)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -D_FILE_OFFSET_BITS=64")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -D_FILE_OFFSET_BITS=64")

# ---- secp256k1 desde fuente (incluida en el proyecto) ----
add_library(secp256k1 STATIC
    secp256k1/src/secp256k1.c
)
target_include_directories(secp256k1 PUBLIC
    secp256k1
    secp256k1/include
    secp256k1/src
)
target_compile_definitions(secp256k1 PRIVATE
    SECP256K1_BUILD
    USE_SCALAR_10X26
    USE_FIELD_10X26
    USE_NUM_NONE
    USE_FIELD_INV_BUILTIN
    USE_SCALAR_INV_BUILTIN
    ENABLE_MODULE_RECOVERY=0
)

# ---- libreria JNI principal ----
add_library(hunter_jni SHARED hunter_jni.cpp)

target_include_directories(hunter_jni PRIVATE
    secp256k1/include
)

find_library(log-lib  log)
find_library(ssl-lib  ssl)
find_library(crypto-lib crypto)

target_link_libraries(hunter_jni
    secp256k1
    ${ssl-lib}
    ${crypto-lib}
    ${log-lib}
)
