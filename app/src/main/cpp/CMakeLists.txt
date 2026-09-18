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

# ── tsnet empotrado, en su PROPIA librería ────────────────────────────────
#
# libtsbridge.so, NO dentro de libhunter_jni.so. Estuvo dentro y rompió la app
# entera: libtailscale.so trae el runtime de Go, que arranca al CARGAR la
# librería, así que enlazarla dentro hacía que Go arrancara al abrir la app
# —antes de que nadie hubiera pedido nada de Tailscale— y System.loadLibrary
# fallaba. Lo que se veía era una pantalla negra con un "Building Puzzle..."
# colgado y ni un mensaje.
#
# Separada, se carga SÓLO cuando alguien enciende el nodo. Si Go no arranca, el
# botón dice que no se puede y la app sigue funcionando entera.
#
# Es además lo correcto aunque no hubiera fallado: 14 MB de runtime de Go no
# tienen por qué iniciarse para buscar claves en local.
set(TS_SO  "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtailscale.so")
set(TS_INC "${CMAKE_CURRENT_SOURCE_DIR}/tailscale")
if(EXISTS "${TS_SO}" AND EXISTS "${TS_INC}/tailscale.h")
    message(STATUS "tsnet: construyendo libtsbridge.so contra ${TS_SO}")
    add_library(tailscale SHARED IMPORTED)
    set_target_properties(tailscale PROPERTIES IMPORTED_LOCATION "${TS_SO}")
    add_library(tsbridge SHARED ts_jni.cpp)
    target_include_directories(tsbridge PRIVATE "${TS_INC}")
    target_compile_definitions(tsbridge PRIVATE TIENE_TAILSCALE)
    target_link_libraries(tsbridge tailscale)
else()
    message(STATUS "tsnet: sin libtailscale.so, no se construye libtsbridge.so")
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

# hunter_jni NO enlaza tailscale. Ver arriba: hacerlo arrancaba el runtime de
# Go al abrir la app y la tumbaba antes de pintar nada.
