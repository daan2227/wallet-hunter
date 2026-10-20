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
# direcciones - el codificador de direcciones, mainnet y testnet
# lote     - tamano de lote optimo del bucle de fuerza bruta
# semilla  - dos aparatos no pueden salir por el mismo sitio
# coincidencias - la lista de hallazgos no puede crecer sin freno (mato la app)
# resueltos- los puzzles con clave conocida: clave, publica y direccion cuadran
# velocidad- saltos por segundo: el otro factor, que constante NO mide
# ciclos   - cuanto miden los ciclos esteriles: dimensiona KG_VENTANA
# orden    - aritmetica modulo el orden del grupo, atada a la curva
# saltos   - punto y distancia avanzan a la par, tambien en rangos grandes
# hash160x4- RIPEMD-160 desenrollado y cuatro hash160 a la vez
# indice9  - el indice de direcciones de 9 bytes (tipo + 8 bytes de hash)
# pbkdf2   - SHA-512, HMAC y PBKDF2 propios contra OpenSSL
# listabip39- la lista BIP39 del motor es la oficial (SHA-256 de english.txt)
# restasuma- fe_sub y fe_add sin saltos == las de antes (bit a bit)
# opreturn - scriptPubKey de OP_RETURN: pushes de 75/76 bytes, tope de 80
# bip158  - filtros de bloque (GCS) y cabeceras, contra los vectores de Bitcoin Core
# frase    - semilla de palabras, passphrase (BIP39) y cuenta (#n), contra vectores
# rutas    - rutas BIP32 estrictas: lo que no es una ruta valida se rechaza
# destinos - direccion de destino -> script: vectores BIP350, checksum base58
# constante- cuantas raices de W cuesta Kangaroo, que es LA cifra del motor
PRUEBAS="campo vectores prim hex persist kang semilla direcciones opreturn frase bip158 cadena p2p almacen bloque sincro difusion coste reparto lote constante saltos orden ciclos velocidad resueltos coincidencias pbkdf2 hash160x4 indice9 destinos listabip39 rutas restasuma"

# reparto mide PBKDF2, que lo pone OpenSSL; los demas no lo necesitan.
flags_de() {
    case "$1" in
        reparto)     echo "-lpthread -lcrypto" ;;
        direcciones) echo "-lcrypto -Wno-deprecated-declarations" ;;
        opreturn)    echo "-I../../app/src/main/cpp -lcrypto -Wno-deprecated-declarations" ;;
        bip158)      echo "-I../../app/src/main/cpp -lcrypto -Wno-deprecated-declarations" ;;
        cadena)      echo "-I../../app/src/main/cpp -lcrypto -Wno-deprecated-declarations" ;;
        p2p)         echo "-I../../app/src/main/cpp -lcrypto -Wno-deprecated-declarations" ;;
        almacen)     echo "-I../../app/src/main/cpp -lcrypto -Wno-deprecated-declarations" ;;
        bloque)      echo "-I../../app/src/main/cpp -lcrypto -Wno-deprecated-declarations" ;;
        sincro)      echo "-I../../app/src/main/cpp -lcrypto -Wno-deprecated-declarations" ;;
        difusion)    echo "-I../../app/src/main/cpp -lcrypto -Wno-deprecated-declarations" ;;
        frase)       echo "-DSHA512_CON_OPENSSL -I../../app/src/main/cpp ../../app/src/main/cpp/sha512.cpp -lcrypto -Wno-deprecated-declarations" ;;
        resueltos)   echo "-lpthread -lcrypto -Wno-deprecated-declarations" ;;
        listabip39)  echo "-lcrypto -Wno-deprecated-declarations" ;;
        destinos)    echo "-lcrypto -Wno-deprecated-declarations" ;;
        pbkdf2)      echo "-DSHA512_CON_OPENSSL ../../app/src/main/cpp/sha512.cpp -lcrypto -Wno-deprecated-declarations" ;;
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

# Compilar en paralelo: cada binario es independiente y la compilacion es lo
# que mas tarda. Las pruebas en si corren en serie, porque algunas miden tiempo
# (velocidad, ciclos) y con otras compitiendo darian cifras falsas.
rm -f compila_fallo.txt
compila() {
    # shellcheck disable=SC2046
    g++ -O2 -o "$1" "$1.cpp" $(flags_de "$1") 2>"$1.err" || echo "$1" >> compila_fallo.txt
}
for t in $PRUEBAS; do compila "$t" & done
wait

fallos=0
for t in $PRUEBAS; do
    if [ -f compila_fallo.txt ] && grep -qx "$t" compila_fallo.txt; then
        printf '*** %s no compila ***\n' "$t"
        cat "$t.err"
        fallos=$((fallos+1))
        continue
    fi
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
