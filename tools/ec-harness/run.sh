#!/bin/sh
# Compila el jac_batch.h real y comprueba sus claves públicas.
set -e
cd "$(dirname "$0")"
g++ -O2 -o vectores vectores.cpp
./vectores
