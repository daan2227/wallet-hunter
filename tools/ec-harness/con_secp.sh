#!/bin/sh
# Las pruebas que necesitan secp256k1 (la CI lo clona en app/src/main/cpp/
# antes de compilar el APK):
#   recuperacion - la recuperacion de frases de verdad, sin JNI
#   escaner      - el escaner de claves: cada clave publica es la de su privada
#     tools/ec-harness/con_secp.sh app/src/main/cpp/secp256k1
# Para medir, a mano despues: ./recuperacion <hilos>, ./escaner <grupos>
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
g++ -O2 -Wno-unused-result -I$C -I"$SECP/include" -o escaner escaner.cpp "$T/secp.o" "$T/pre1.o" "$T/pre2.o"
rm -rf "$T"
echo "=== recuperacion ==="; ./recuperacion
echo "=== escaner ===";      ./escaner
