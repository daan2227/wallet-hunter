cmake_minimum_required(VERSION 3.22.1)
project(hunter_btc)

find_package(openssl REQUIRED CONFIG)

add_library(secp256k1 STATIC
    secp256k1/src/secp256k1.c
    secp256k1/src/precomputed_ecmult.c
    secp256k1/src/precomputed_ecmult_gen.c
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
    ECMULT_WINDOW_SIZE=15
    ECMULT_GEN_PREC_BITS=4
)

add_library(hunter_jni SHARED hunter_jni.cpp)
target_compile_options(hunter_jni PRIVATE
    -O3
    -march=armv8-a+crypto
    -ffast-math
    -funroll-loops
)

target_include_directories(hunter_jni PRIVATE secp256k1/include)

find_library(log-lib log)

target_link_libraries(hunter_jni
    secp256k1
    openssl::ssl
    openssl::crypto
    ${log-lib}
)
