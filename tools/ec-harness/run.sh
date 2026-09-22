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
# lote     - tamano de lote optimo del bucle de fuerza bruta
# semilla  - dos aparatos no pueden salir por el mismo sitio
# resueltos- los puzzles con clave conocida: clave, publica y direccion cuadran
# velocidad- saltos por segundo: el otro factor, que constante NO mide
# ciclos   - cuanto miden los ciclos esteriles: dimensiona KG_VENTANA
# orden    - aritmetica modulo el orden del grupo, atada a la curva
# saltos   - punto y distancia avanzan a la par, tambien en rangos grandes
# constante- cuantas raices de W cuesta Kangaroo, que es LA cifra del motor
PRUEBAS="campo vectores prim hex persist kang semilla reparte direcciones coste reparto lote constante saltos orden ciclos velocidad resueltos"

# reparto mide PBKDF2, que lo pone OpenSSL; los demas no lo necesitan.
flags_de() {
    case "$1" in
        reparto)     echo "-lpthread -lcrypto" ;;
        direcciones) echo "-lcrypto -Wno-deprecated-declarations" ;;
        resueltos)   echo "-lpthread -lcrypto -Wno-deprecated-declarations" ;;
        *)       echo "-lpthread" ;;
    esac
}

# constante tarda lo que se le pida: son logaritmos discretos de verdad. Aqui
# corre pequena (28 bits, 120 tandas, ~10 s) porque lo que tiene que hacer en
# cada vuelta es avisar si el coste se ha disparado, no afinar decimales. Para
# comparar variantes se lanza a mano con mas tandas:
#     ./constante 30 256 5 400 1
args_de() {
    case "$1" in
        constante) echo "28 64 5 120" ;;
        velocidad) echo "139 512 1.5" ;;
        *)         echo "" ;;
    esac
}

fallos=0
for t in $PRUEBAS; do
    # shellcheck disable=SC2046
    g++ -O2 -o "$t" "$t.cpp" $(flags_de "$t")
    printf '=== %s ===\n' "$t"
    # shellcheck disable=SC2086
    if ./"$t" $(args_de "$t"); then :; else
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
