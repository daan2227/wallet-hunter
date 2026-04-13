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
    USE_SCALAR_4x64
    USE_FIELD_5x52
    USE_NUM_NONE
    USE_FIELD_INV_NUM
    USE_SCALAR_INV_NUM
    ECMULT_WINDOW_SIZE=15
    ECMULT_GEN_PREC_BITS=4
    USE_ENDOMORPHISM
)

target_compile_options(secp256k1 PRIVATE
    -O3
    -march=armv8.2-a+crypto+sha2+aes+dotprod
    -mtune=cortex-a55
    -ffast-math
    -funroll-loops
    -fomit-frame-pointer
)

add_library(hunter_jni SHARED hunter_jni.cpp
        mnemonic.cpp
        bip32.cpp
        recovery_engine.cpp
)
target_compile_options(hunter_jni PRIVATE
    -O3
    -march=armv8.2-a+crypto+sha2+aes+dotprod
    -mtune=cortex-a55
    -ffast-math
    -funroll-loops
    -fvectorize
    -fslp-vectorize
    -finline-functions
    -fomit-frame-pointer
    -falign-functions=64
    -falign-loops=64
)

target_include_directories(hunter_jni PRIVATE secp256k1/include)

find_library(log-lib log)

target_link_libraries(hunter_jni
    secp256k1
    openssl::ssl
    openssl::crypto
    ${log-lib}
)
