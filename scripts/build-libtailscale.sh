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

SALIDA="${1:-$(pwd)/app/src/main/cpp/tailscale}"

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

# ── Compilar ──────────────────────────────────────────────────────────────
mkdir -p "$SALIDA"
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
go build -buildmode=c-shared -o "$SALIDA/libtailscale.so" .

# El .h que genera cgo NO es el que hay que incluir: trae los prototipos de los
# simbolos de Go (TsnetDial y compania). El bueno es el tailscale.h del repo,
# que declara el API tailscale_*.
cp tailscale.h "$SALIDA/tailscale.h"

echo
echo "Listo:"
ls -lh "$SALIDA/libtailscale.so" "$SALIDA/tailscale.h"
echo
# Que sea arm64 DE VERDAD y no del anfitrion: si la cruz-compilacion se cae sin
# avisar y sale un .so de x86_64, aqui no falla nada y el movil revienta al
# cargarla. Asi que se comprueba y se corta.
arq=$(file -b "$SALIDA/libtailscale.so" 2>/dev/null || echo "?")
echo "  $arq"
case "$arq" in
    *aarch64*|*ARM\ aarch64*) echo "  OK  es arm64" ;;
    *) echo "  MAL  no es arm64"; exit 1 ;;
esac
n=$("$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" --dynamic \
    --defined-only --just-symbol-name "$SALIDA/libtailscale.so" 2>/dev/null \
    | grep -c '^tailscale_' || true)
echo "  simbolos tailscale_* exportados: $n"
[ "$n" -ge 15 ] || { echo "  MAL  esperaba al menos 15"; exit 1; }
