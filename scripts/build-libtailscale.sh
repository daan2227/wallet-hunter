#!/bin/bash
# Compila libtailscale para android/arm64 como libreria compartida.
#
# QUE ES ESTO
#
# libtailscale es tsnet —el nodo de Tailscale— con una fachada en C. Al
# empotrarlo, la app tiene su PROPIO nodo en el tailnet en vez de depender de
# que la app de Tailscale este instalada y encendida enrutando el movil entero.
#
# Lo que devuelve tailscale_listen(), tailscale_accept() y tailscale_dial() son
# descriptores de fichero corrientes. Eso es lo que hace esto viable sin
# reescribir el protocolo: el reparto del cluster sigue mandando sus lineas JSON
# igual, solo que por otro descriptor.
#
# POR QUE UNA SOLA ARQUITECTURA
#
# app/build.gradle tiene abiFilters "arm64-v8a" y nada mas, asi que hay que
# cruzar-compilar una vez. Si algun dia se anaden mas ABIs, hay que repetir esto
# por cada una y meter cada .so en su carpeta de jniLibs: Go no hace binarios gordos.
#
# LO QUE YA ESTA COMPROBADO
#
#   - tailscale.com/tsnet compila para GOOS=android GOARCH=arm64. Era el riesgo
#     grande: si tsnet no soportara Android, no habria nada que hacer.
#   - Go 1.25.5 —que es lo que pide el go.mod— se descarga solo con
#     GOTOOLCHAIN=auto.
#
# Lo que NO se puede comprobar fuera de CI es el enlazado con cgo, porque hace
# falta el clang del NDK y aqui no hay NDK. Por eso esto vive en un trabajo
# aparte del que compila el APK: si falla, el APK sigue saliendo.
set -euo pipefail

# Commit fijo y no la rama: una libreria que se mueve sola por debajo convierte
# "ha dejado de compilar" en un misterio en vez de en un cambio que se ve.
REPO=https://github.com/tailscale/libtailscale.git
COMMIT=59d4bb82744915815178e0f0776d60026a397ee7   # 2026-08-31

# minSdk del proyecto. Tiene que cuadrar con app/build.gradle: si aqui se pone
# un nivel mas alto, la .so pide simbolos que el movil no tiene y revienta al
# cargar, no al compilar.
API=26

# Dos destinos, porque cada cosa la quiere un sitio distinto:
#
#   la .so  -> app/src/main/jniLibs/arm64-v8a/, que es de donde Gradle recoge
#              las librerias nativas prefabricadas y las mete en el APK sin que
#              haya que decirle nada.
#   el .h   -> app/src/main/cpp/tailscale/, que es donde lo busca el JNI.
#
# Se puede dar un directorio por argumento y entonces va todo ahi junto, que es
# lo que hace el trabajo de CI que solo comprueba que compila.
if [ $# -ge 1 ]; then
    SALIDA="$1"; SALIDA_SO="$1"
else
    raiz="$(cd "$(dirname "$0")/.." && pwd)"
    SALIDA="$raiz/app/src/main/cpp/tailscale"
    SALIDA_SO="$raiz/app/src/main/jniLibs/arm64-v8a"
fi

# ── El NDK ────────────────────────────────────────────────────────────────
#
# El MISMO que compila el APK, y por delante de ANDROID_NDK_HOME. El runner
# trae esa variable apuntando a otro (27.3 cuando el proyecto usa 25.1), y
# construir la libreria con un NDK y la app con otro es de los problemas que no
# dan la cara al compilar sino al cargar.
NDK_VER=25.1.8937393
NDK=""
for cand in "${ANDROID_SDK_ROOT:-}/ndk/$NDK_VER" "${ANDROID_HOME:-}/ndk/$NDK_VER" \
            "${ANDROID_NDK_HOME:-}"; do
    if [ -n "$cand" ] && [ -d "$cand" ]; then NDK="$cand"; break; fi
done
[ -n "$NDK" ] || { echo "No se encuentra ningun NDK (buscado $NDK_VER)"; exit 1; }

CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang"
[ -x "$CC" ] || { echo "No existe el compilador $CC"; ls "$NDK/toolchains/llvm/prebuilt/"; exit 1; }
echo "NDK: $NDK"
echo "CC:  $CC"

# ── Traer la fuente ───────────────────────────────────────────────────────
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git clone --quiet "$REPO" "$tmp/libtailscale"
git -C "$tmp/libtailscale" checkout --quiet "$COMMIT"
echo "libtailscale en $COMMIT"

# Nuestro anadido: las interfaces de red se las da Java, porque Android 11+ le
# prohibe a Go preguntarselas al kernel por netlink. Sin esto, levantar el nodo
# muere con "netlinkrib: permission denied". El detalle esta en el propio
# fichero.
PARCHE="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/cpp/tailscale-patch"
[ -d "$PARCHE" ] || { echo "Falta $PARCHE"; exit 1; }
cp "$PARCHE"/*.go "$tmp/libtailscale/"
echo "parche anadido: $(ls "$PARCHE")"

# ── Compilar ──────────────────────────────────────────────────────────────
mkdir -p "$SALIDA" "$SALIDA_SO"
cd "$tmp/libtailscale"
export GOTOOLCHAIN=auto          # el go.mod pide una version mas nueva que la del runner
export CGO_ENABLED=1
export GOOS=android
export GOARCH=arm64
export CC

# c-shared y no c-archive. El primer intento uso c-archive y Go contesto:
#
#     -buildmode=c-archive not supported on android/arm64
#
# No es una pega de rutas ni del NDK: Go NO hace archivos estaticos para
# Android, solo librerias compartidas. Es lo mismo que hace gomobile, y para el
# APK viene mejor —una .so en jniLibs es lo que Android espera— a cambio de que
# haya que empaquetarla en vez de fundirla dentro de libhunter_jni.so.
#
# El build mete tambien tailscale.c, que es la fachada que convierte los
# simbolos exportados de Go en el API tailscale_* que usa el JNI.
#
# -z max-page-size=16384: Android 15 en adelante puede usar paginas de 16 KB, y
# entonces el enlazador del sistema RECHAZA las librerias alineadas a 4 KB, que
# es lo que produce Go por omision. En un movil con paginas de 4 KB esto no
# cambia nada salvo unos pocos KB de relleno, asi que se pone siempre: cuesta
# nada y quita de en medio una causa de "no carga" que no da la cara hasta que
# alguien la instala en el movil equivocado.
# -soname es OBLIGATORIO, y su falta es lo que hizo que no cargara en el movil:
#
#   dlopen failed: library "/home/runner/work/wallet-hunter/wallet-hunter/app/
#   src/main/cpp/../jniLibs/arm64-v8a/libtailscale.so" not found:
#   needed by .../base.apk!/lib/arm64-v8a/libtsbridge.so
#
# O sea que libtsbridge.so pedia LA RUTA ABSOLUTA DE LA MAQUINA DE COMPILACION.
# Go no le pone SONAME a lo que produce con c-shared, y sin SONAME el enlazador
# anota como dependencia la ruta con la que se le nombro — que en el movil no
# existe. Con SONAME anota solo "libtailscale.so" y el enlazador de Android la
# encuentra al lado, dentro del APK.
#
# Es un fallo que NO da la cara al compilar: el APK sale entero y con las dos
# librerias dentro. Solo se ve al instalarlo.
go build -buildmode=c-shared \
    -ldflags '-extldflags "-Wl,-soname,libtailscale.so -Wl,-z,max-page-size=16384"' \
    -o "$SALIDA_SO/libtailscale.so" .

# El .h que genera cgo NO es el que hay que incluir: trae los prototipos de los
# simbolos de Go (TsnetDial y compania). El bueno es el tailscale.h del repo,
# que declara el API tailscale_*.
cp tailscale.h "$SALIDA/tailscale.h"

echo
echo "Listo:"
ls -lh "$SALIDA_SO/libtailscale.so" "$SALIDA/tailscale.h"
echo
# Que sea arm64 DE VERDAD y no del anfitrion: si la cruz-compilacion se cae sin
# avisar y sale un .so de x86_64, aqui no falla nada y el movil revienta al
# cargarla. Asi que se comprueba y se corta.
arq=$(file -b "$SALIDA_SO/libtailscale.so" 2>/dev/null || echo "?")
echo "  $arq"
case "$arq" in
    *aarch64*|*ARM\ aarch64*) echo "  OK  es arm64" ;;
    *) echo "  MAL  no es arm64"; exit 1 ;;
esac
n=$("$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" --dynamic \
    --defined-only --just-symbol-name "$SALIDA_SO/libtailscale.so" 2>/dev/null \
    | grep -c '^tailscale_' || true)
echo "  simbolos tailscale_* exportados: $n"
[ "$n" -ge 15 ] || { echo "  MAL  esperaba al menos 15"; exit 1; }

# Y el nuestro. Que compile no basta: si el fichero del parche se excluyera por
# lo que sea, la compilacion saldria bien y el simbolo no estaria — que es
# exactamente lo que paso al probarlo la primera vez, porque el nombre
# terminaba en _android.go y Go lo trata como condicion de compilacion.
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" --dynamic \
    --defined-only --just-symbol-name "$SALIDA_SO/libtailscale.so" 2>/dev/null \
    | grep -q '^tsnet_set_interfaces$' || {
    echo "  MAL  falta tsnet_set_interfaces: el parche no ha entrado."
    echo "       Sin el, levantar el nodo muere con 'netlinkrib: permission denied'."
    exit 1
}
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" --dynamic \
    --defined-only --just-symbol-name "$SALIDA_SO/libtailscale.so" 2>/dev/null \
    | grep -q '^tsnet_set_dirs$' || {
    echo "  MAL  falta tsnet_set_dirs."
    echo "       Sin el, levantar el nodo muere con 'no safe place found to"
    echo "       store log state'."
    exit 1
}
echo "  OK  tsnet_set_interfaces y tsnet_set_dirs exportados (parche dentro)"

# Y el SONAME, que es lo que fallaba y no daba la cara hasta instalar el APK.
# Sin el, quien enlace contra esta libreria anota como dependencia la RUTA de
# esta maquina, y en el movil no existe. Se comprueba aqui y se corta.
readelf="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
son=$("$readelf" -d "$SALIDA_SO/libtailscale.so" 2>/dev/null \
      | grep -o 'SONAME.*\[.*\]' | sed 's/.*\[\(.*\)\]/\1/')
echo "  SONAME: ${son:-(ninguno)}"
[ "$son" = "libtailscale.so" ] || {
    echo "  MAL  el SONAME tiene que ser libtailscale.so."
    echo "       Sin el, libtsbridge.so pedira la ruta de compilacion y el"
    echo "       movil dira 'library ... not found' al cargarla."
    exit 1
}
