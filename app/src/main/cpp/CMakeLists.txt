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
    USE_FIELD_INV_BUILTIN
    USE_SCALAR_INV_BUILTIN
    ECMULT_WINDOW_SIZE=15
    ECMULT_GEN_PREC_BITS=4
    # Taproot: firmas Schnorr (BIP340) y el API x-only para tocar el par de
    # claves. secp256k1.c incluye estos módulos sólo si están definidos, así que
    # sin ellos el enlazado falla al llamar a schnorrsig_sign32. schnorrsig
    # depende de extrakeys.
    ENABLE_MODULE_EXTRAKEYS
    ENABLE_MODULE_SCHNORRSIG
    # Recuperar la pública de una firma: para "sign/verify message" al estilo
    # Bitcoin (firma compacta recuperable) y para comprobar quién firmó.
    ENABLE_MODULE_RECOVERY
)

target_compile_options(secp256k1 PRIVATE
    -O3
    -march=armv8-a+crypto
    -ffast-math
    -funroll-loops
    -fomit-frame-pointer
)

add_library(hunter_jni SHARED hunter_jni.cpp
        mnemonic.cpp
        bip32.cpp
        recovery_engine.cpp
        gpu/gpu_jni.cpp
        sha512.cpp
        sha512_hw.cpp
)
# Solo este fichero lleva las instrucciones SHA-512 (ARMv8.2): el resto de la
# app tiene que arrancar en procesadores sin ellas, y lo que hay aqui dentro
# no se llama sin comprobarlo antes (sha512_tiene_hw).
set_source_files_properties(sha512_hw.cpp PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+sha3")
# El modo 3 de sha512.cpp usa la compresion de OpenSSL (ya enlazada).
set_source_files_properties(sha512.cpp PROPERTIES COMPILE_DEFINITIONS "SHA512_CON_OPENSSL")
target_compile_options(hunter_jni PRIVATE
    -O3
    -march=armv8-a+crypto
    -ffast-math
    -funroll-loops
    -fvectorize
    -finline-functions
    -fomit-frame-pointer
)

target_include_directories(hunter_jni PRIVATE secp256k1/include)

find_library(log-lib log)

# openssl::ssl se enlazaba sin usarse: el C++ sólo incluye sha.h, ripemd.h,
# hmac.h, evp.h y bn.h — ni una línea de TLS; lo de red va en Kotlin sobre JSSE.
# Enlazarlo metía libssl.so (0,48 MB) en el APK para nada.
# vulkan: sólo para la prueba de la GPU (gpu/gpu_jni.cpp). libvulkan.so viene
# con Android desde la API 24; si el móvil no tiene Vulkan, la prueba lo dice.
target_link_libraries(hunter_jni
    secp256k1
    openssl::crypto
    ${log-lib}
    vulkan
)

