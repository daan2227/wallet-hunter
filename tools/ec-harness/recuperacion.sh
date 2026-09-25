#!/bin/sh
# La recuperacion de frases de verdad, sin JNI. Necesita secp256k1:
#     tools/ec-harness/recuperacion.sh app/src/main/cpp/secp256k1
# (la CI lo clona ahi antes de compilar el APK). Con un numero detras mide
# frases por segundo con esos hilos en vez de probar:
#     tools/ec-harness/recuperacion.sh <secp> 4
set -e
SECP=$(cd "$1" && pwd)
cd "$(dirname "$0")"
T=$(mktemp -d)
D="-DSECP256K1_BUILD -DENABLE_MODULE_EXTRAKEYS -DENABLE_MODULE_SCHNORRSIG -DECMULT_WINDOW_SIZE=15 -DECMULT_GEN_PREC_BITS=4"
gcc -O2 -w -c -o "$T/secp.o" "$SECP/src/secp256k1.c" -I"$SECP" -I"$SECP/include" -I"$SECP/src" $D
gcc -O2 -w -c -o "$T/pre1.o" "$SECP/src/precomputed_ecmult.c" -I"$SECP" -I"$SECP/src" -DSECP256K1_BUILD
gcc -O2 -w -c -o "$T/pre2.o" "$SECP/src/precomputed_ecmult_gen.c" -I"$SECP" -I"$SECP/src" -DSECP256K1_BUILD
C=../../app/src/main/cpp
g++ -O2 -Wno-deprecated-declarations -Wno-unused-result -DRECOVERY_SIN_JNI -I$C -I"$SECP/include" -o recuperacion recuperacion.cpp \
    $C/bip32.cpp $C/mnemonic.cpp $C/sha512.cpp "$T/secp.o" "$T/pre1.o" "$T/pre2.o" -lcrypto -lpthread
rm -rf "$T"
shift
./recuperacion "$@"
