#!/bin/sh
# Comprobar que no queda ninguna referencia a los nombres que se acaban de
# mover, renombrar o borrar.
#
# POR QUE EXISTE: dos veces en la misma sesion he movido una variable local a
# otra funcion y he dejado atras un uso. Las dos veces lo encontro el CI quince
# minutos despues, y la segunda fue en LA MISMA LINEA que la primera.
#
# Contar llaves y parentesis no ve esto: el fichero queda perfectamente
# equilibrado y el identificador simplemente ya no existe ahi.
#
# Probé a detectarlo automaticamente analizando ambitos y salieron veinte falsos
# positivos por fichero: hacerlo bien es escribir un compilador. Asi que esto no
# adivina nada, se le dicen los nombres:
#
#     tools/tras-renombrar.sh rapidos hilosActuales bigCores
#
# Devuelve error si alguno sigue apareciendo.
set -e
cd "$(dirname "$0")/.."
[ $# -gt 0 ] || { echo "uso: $0 <nombre> [nombre...]"; exit 2; }
malo=0
for n in "$@"; do
    hits=$(grep -rn "\\b$n\\b" --include=*.kt --include=*.cpp --include=*.h \
           app/src/main/ 2>/dev/null || true)
    if [ -n "$hits" ]; then
        echo "=== '$n' sigue apareciendo ==="
        echo "$hits"
        malo=1
    else
        echo "ok   '$n' no queda en ningun sitio"
    fi
done
exit $malo
