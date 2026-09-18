#!/bin/sh
# Todas las llamadas a las funciones que se le den.
#
# Para usar DESPUES de cambiarle la firma a algo, antes de subirlo. Dos veces
# he roto la compilacion por no mirar todos los sitios que llamaban a lo que
# acababa de cambiar, y las dos se descubrio en CI diez minutos despues.
#
# La segunda fue especialmente tonta y merece quedar escrita, porque el codigo
# NO da ninguna pista al leerlo. A discoverMasters(ctx, onFound) le anadi un
# tercer parametro, onFin, tambien de tipo funcion:
#
#     fun discoverMasters(ctx, onFound: (String,String)->Unit,
#                              onFin: ((Int)->Unit)? = null)
#
# Habia un sitio que la llamaba con la lambda suelta al final:
#
#     discoverMasters(ctx) { ip, _ -> ... }      // se veia igual que antes
#
# y en Kotlin la lambda suelta se engancha al ULTIMO parametro. O sea que paso
# a ser onFin sin que cambiara una letra en esa linea. El compilador lo pilla,
# pero solo si compilas — y aqui lo unico que compila Kotlin es CI.
#
# Uso:  tools/llamadas.sh discoverMasters startWorker kangarooStart
set -e
cd "$(dirname "$0")/.."
[ $# -gt 0 ] || { echo "uso: $0 <nombre> [nombre...]"; exit 2; }

for n in "$@"; do
    printf '=== %s ===\n' "$n"
    # La definicion, para tener la firma delante al comparar.
    grep -rn "fun $n(" app/src/main/java app/src/main/cpp 2>/dev/null \
        | sed 's/^/  DEF  /' || true
    # Y las llamadas.
    hay=$(grep -rn "$n(" app/src/main/java app/src/main/cpp 2>/dev/null \
          | grep -v "fun $n(" | grep -v '^\s*//' || true)
    if [ -n "$hay" ]; then
        echo "$hay" | sed 's/^/  USO  /'
    else
        echo "  (ninguna llamada)"
    fi
    echo
done
echo "Comprueba que cada USO cuadra con su DEF. Ojo especialmente con las"
echo "lambdas sueltas al final: se enganchan al ULTIMO parametro, no al que"
echo "parece al leerlo."
