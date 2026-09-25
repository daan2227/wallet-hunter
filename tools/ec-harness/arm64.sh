#!/bin/sh
# Lo que solo existe en ARM64, con qemu: el ensamblador de fe_mul, SHA-256 y
# SHA-512 con las instrucciones del procesador. Necesita
#     apt-get install g++-aarch64-linux-gnu qemu-user
# El ritmo bajo qemu no dice nada; la exactitud si.
set -e
cd "$(dirname "$0")"
CXX=aarch64-linux-gnu-g++
C=../../app/src/main/cpp
T=$(mktemp -d)
$CXX -O2 -static -o "$T/fe_arm64" fe_arm64.cpp
$CXX -O2 -static -march=armv8-a+crypto -o "$T/sha256_hw" sha256_hw.cpp
$CXX -O2 -static -march=armv8-a+crypto -o "$T/hash160x4" hash160x4.cpp
$CXX -O2 -march=armv8.2-a+sha3 -c -o "$T/hw.o" $C/sha512_hw.cpp
$CXX -O2 -static -DSIN_OPENSSL -o "$T/pbkdf2" pbkdf2.cpp $C/sha512.cpp "$T/hw.o"
echo "=== fe_arm64 ===";               qemu-aarch64 -cpu max "$T/fe_arm64"
echo "=== sha256_hw ===";              qemu-aarch64 -cpu max "$T/sha256_hw"
echo "=== hash160x4 (NEON) ===";      qemu-aarch64 -cpu max "$T/hash160x4"
echo "=== pbkdf2, con SHA-512 ===";    qemu-aarch64 -cpu max "$T/pbkdf2"
# Sin las instrucciones: tiene que darse cuenta y quedarse en software, no
# morir con una instruccion ilegal.
echo "=== pbkdf2, sin SHA-512 ===";    qemu-aarch64 -cpu cortex-a72 "$T/pbkdf2"
rm -rf "$T"
