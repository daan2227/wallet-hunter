#!/bin/sh
# Compila las cabeceras REALES del motor como binarios de escritorio y las
# pone a prueba. No son copias ni maquetas: cada .cpp de aqui hace #include
# del fichero que se compila para el movil, asi que lo que pase aqui es lo que
# pasa alli.
#
# Antes esto solo lanzaba "vectores" y los demas habia que acordarse de
# ejecutarlos a mano, que es como no tenerlos.
set -e
cd "$(dirname "$0")"

# campo    - fe_sqr y fe_inv contra las versiones lentas y evidentes
# vectores - claves publicas de 2G..9G contra los valores conocidos
# prim     - doblar y sumar, incluidas las llamadas con origen y destino iguales
# hex      - lectura de rangos en hexadecimal, pares e impares
# persist  - guardar y recuperar la tabla de puntos distinguidos
# kang     - logaritmos discretos de los que YA sabemos la respuesta
# coste    - reparto del tiempo por operacion
# reparto  - PBKDF2 frente a hash160
# reparte  - Kangaroo repartido: exportar e importar distinguidos entre aparatos
# direcciones - el codificador de direcciones, mainnet y testnet
PRUEBAS="campo vectores prim hex persist kang reparte direcciones coste reparto"

# reparto mide PBKDF2, que lo pone OpenSSL; los demas no lo necesitan.
flags_de() {
    case "$1" in
        reparto)     echo "-lpthread -lcrypto" ;;
        direcciones) echo "-lcrypto -Wno-deprecated-declarations" ;;
        *)       echo "-lpthread" ;;
    esac
}

fallos=0
for t in $PRUEBAS; do
    # shellcheck disable=SC2046
    g++ -O2 -o "$t" "$t.cpp" $(flags_de "$t")
    printf '=== %s ===\n' "$t"
    if ./"$t"; then :; else
        printf '*** %s FALLA ***\n' "$t"
        fallos=$((fallos+1))
    fi
    echo
done

if [ "$fallos" -gt 0 ]; then
    printf '%s prueba(s) con fallos\n' "$fallos"
    exit 1
fi
echo "Todas las pruebas correctas."
