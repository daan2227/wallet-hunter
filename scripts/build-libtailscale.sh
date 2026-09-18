#!/bin/bash
# Compila libtailscale para android/arm64 como archivo C.
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
# por cada una y juntar los .a: Go no hace binarios gordos.
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
# un nivel mas alto, el .a pide simbolos que el movil no tiene y revienta al
# cargar, no al compilar.
API=26

SALIDA="${1:-$(pwd)/app/src/main/cpp/tailscale}"

# ── El NDK ────────────────────────────────────────────────────────────────
NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
    raiz="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    [ -n "$raiz" ] || { echo "No se encuentra el NDK: ni ANDROID_NDK_HOME ni ANDROID_SDK_ROOT"; exit 1; }
    # El que instala el workflow.
    NDK="$raiz/ndk/25.1.8937393"
fi
[ -d "$NDK" ] || { echo "No existe el NDK en $NDK"; exit 1; }

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

# ── Compilar ──────────────────────────────────────────────────────────────
mkdir -p "$SALIDA"
cd "$tmp/libtailscale"
export GOTOOLCHAIN=auto          # el go.mod pide una version mas nueva que la del runner
export CGO_ENABLED=1
export GOOS=android
export GOARCH=arm64
export CC

# -buildmode=c-archive mete tambien tailscale.c, que es la fachada que convierte
# los simbolos exportados de Go en el API tailscale_* que usa el JNI.
go build -buildmode=c-archive -o "$SALIDA/libtailscale.a" .

# El .h que genera cgo NO es el que hay que incluir: trae los prototipos de los
# simbolos de Go (TsnetDial y compania). El bueno es el tailscale.h del repo,
# que declara el API tailscale_*.
cp tailscale.h "$SALIDA/tailscale.h"

echo
echo "Listo:"
ls -lh "$SALIDA/libtailscale.a" "$SALIDA/tailscale.h"
echo
echo "Comprobacion de que es arm64 de verdad y no del anfitrion:"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" --just-symbol-name \
    "$SALIDA/libtailscale.a" 2>/dev/null | grep -c tailscale_ \
    | sed 's/^/  simbolos tailscale_*: /'
file "$SALIDA/libtailscale.a" 2>/dev/null || true
