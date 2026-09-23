#!/bin/sh
# Comprobaciones de Kotlin que aqui no puede hacer el compilador.
#
# En este entorno NO hay forma de compilar Kotlin: el unico que lo hace es CI, y
# cada fallo cuesta una vuelta de varios minutos. Van tres, y las tres de cosas
# que se ven a simple vista SI SABES QUE MIRAR:
#
#   1. una referencia colgada al renombrar        -> tools/tras-renombrar.sh
#   2. una lambda suelta que se engancho al
#      parametro nuevo al cambiar una firma       -> tools/llamadas.sh
#   3. un return@etiqueta que no existe           -> esto
#
# No pretende ser un analizador de Kotlin. Cada comprobacion corresponde a un
# fallo real que ya ha pasado, y se anade cuando pasa.
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/hunter/btc
fallos=0

# ── return@etiqueta sobre una PROPIEDAD ───────────────────────────────────
#
# Las etiquetas implicitas de Kotlin salen del NOMBRE DE LA FUNCION a la que se
# pasa la lambda:
#
#     lista.forEach { return@forEach }        vale
#     runOnUiThread { return@runOnUiThread }  vale
#
# Pero si la lambda se ASIGNA a una propiedad no hay etiqueta ninguna:
#
#     NetworkManager.onBlock = { return@onBlock }   NO COMPILA
#
# y el mensaje que da —"Unresolved reference: @onBlock"— no dice por que, asi
# que es facil mirarlo y no ver nada raro.
echo "=== return@ sobre propiedades ==="
for et in $(grep -rho 'return@[a-zA-Z_][a-zA-Z0-9_]*' "$SRC" 2>/dev/null \
            | sed 's/return@//' | sort -u); do
    # ¿Existe una asignacion "algo.et = {" o "et = {" en el mismo arbol?
    if grep -rqE "(^|[. ])$et *= *\{" "$SRC" 2>/dev/null; then
        # Y ¿se usa de verdad como return@, fuera de un comentario?
        usos=$(grep -rn "return@$et" "$SRC" 2>/dev/null \
               | grep -vE '^[^:]*:[0-9]+: *(//|\*)' || true)
        if [ -n "$usos" ]; then
            echo "  MAL  return@$et — '$et' es una propiedad, no una funcion."
            echo "       No hay etiqueta implicita: usa un if en vez del return."
            echo "$usos" | sed 's/^/       /'
            fallos=$((fallos+1))
        fi
    fi
done
[ "$fallos" -eq 0 ] && echo "  ninguno"

# ── Estado declarado y nunca leido ────────────────────────────────────────
#
# El cuarto fallo de la lista, y el que mas caro ha salido: una funcion escrita
# entera y sin un cable que la conecte compila en verde y no se ve leyendo el
# fichero, porque lo que falta no esta escrito en ninguna parte. Han sido
# cuatro —el cierre por inactividad, la proteccion termica, el limite de CPU de
# Kangaroo y los ocho idiomas—, asi que el quinto lo busca una maquina.
echo
if command -v python3 >/dev/null 2>&1; then
    python3 "$(dirname "$0")/estado-muerto.py" --estricto || fallos=$((fallos+1))
else
    echo "=== estado declarado y nunca leido ==="
    echo "  (sin python3, saltado)"
fi

echo
if [ "$fallos" -gt 0 ]; then
    printf '%s problema(s). Arreglalos antes de subir.\n' "$fallos"
    exit 1
fi
echo "Sin problemas conocidos."
