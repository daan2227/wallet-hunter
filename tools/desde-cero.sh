#!/bin/sh
# Corre el banco como lo va a correr CI: en otro sitio y sólo con lo que está en
# git.
#
# POR QUÉ HACE FALTA. `tools/ec-harness/run.sh` pasaba aquí y fallaba en el
# runner, porque `vectores.cpp` tenía metida una ruta absoluta de esta máquina
# en un #include:
#
#     #include "/home/user/wallet-hunter/app/src/main/cpp/jac_batch.h"
#
# En esta máquina esa ruta existe, así que las pruebas salían verdes y no
# probaban lo que parecía. Lo mismo le pasaba a tools/signer-harness/rebuild.sh.
#
# Correr desde otro directorio pilla las rutas absolutas; exportar sólo lo que
# sigue git pilla los ficheros que hacen falta y nadie subió. Las dos cosas se
# ven en CI, pero CI cuesta minutos y este repositorio los tiene contados: esto
# da la misma respuesta en un minuto y sin gastar cuota.
set -e
cd "$(dirname "$0")/.."

DESTINO=$(mktemp -d)
trap 'rm -rf "$DESTINO"' EXIT

echo "Copiando sólo lo que sigue git a $DESTINO"
git ls-files -z | xargs -0 tar cf - | (cd "$DESTINO" && tar xf -)

echo "Corriendo el banco desde ahí"
echo
cd "$DESTINO"
tools/ec-harness/run.sh
