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
)
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

# ── tsnet empotrado, si está ──────────────────────────────────────────────
#
# libtailscale.so la produce scripts/build-libtailscale.sh cruzando Go con el
# clang del NDK. Aquí es OPCIONAL a propósito: si no está, el APK compila
# exactamente igual que siempre y lo único que cambia es que las funciones de
# tsnet dicen que no están disponibles.
#
# Eso no es pereza. Compilarla mete la cadena de Go, el árbol entero de
# tailscale.com y 14 MB en la ecuación, y nada de eso puede ser motivo de que
# una app que funciona deje de construirse. El JNI se envuelve en
# #ifdef TIENE_TAILSCALE por el mismo motivo.
set(TS_SO  "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtailscale.so")
set(TS_INC "${CMAKE_CURRENT_SOURCE_DIR}/tailscale")
if(EXISTS "${TS_SO}" AND EXISTS "${TS_INC}/tailscale.h")
    message(STATUS "tsnet: enlazando ${TS_SO}")
    add_library(tailscale SHARED IMPORTED)
    set_target_properties(tailscale PROPERTIES IMPORTED_LOCATION "${TS_SO}")
    target_include_directories(hunter_jni PRIVATE "${TS_INC}")
    target_compile_definitions(hunter_jni PRIVATE TIENE_TAILSCALE)
else()
    message(STATUS "tsnet: sin libtailscale.so, se compila sin él")
endif()

find_library(log-lib log)

# openssl::ssl se enlazaba sin usarse: el C++ sólo incluye sha.h, ripemd.h,
# hmac.h, evp.h y bn.h — ni una línea de TLS; lo de red va en Kotlin sobre JSSE.
# Enlazarlo metía libssl.so (0,48 MB) en el APK para nada.
target_link_libraries(hunter_jni
    secp256k1
    openssl::crypto
    ${log-lib}
)

if(TARGET tailscale)
    target_link_libraries(hunter_jni tailscale)
endif()
