#!/bin/sh
# Prueba de punta a punta de LightClient.kt contra un nodo simulado por TCP.
# Necesita: g++, openssl (libcrypto), un JDK y el compilador de Kotlin que trae Gradle.
# Uso: ./ligero_e2e.sh [directorio_de_trabajo]
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
RAIZ=$(cd "$AQUI/../.." && pwd)
W=${1:-$(mktemp -d)}
G=${GRADLE_LIB:-/opt/gradle-8.14.3/lib}
JI=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}/include
mkdir -p "$W"
g++ -O1 -std=c++17 -pthread -I"$RAIZ/app/src/main/cpp" -o "$W/servidor_sim" "$AQUI/servidor_sim.cpp" -lcrypto -Wno-deprecated-declarations
g++ -shared -fPIC -O1 -std=c++17 -I"$JI" -I"$JI/linux" -I"$RAIZ/app/src/main/cpp" -o "$W/libligero_host.so" "$RAIZ/app/src/main/cpp/ligero_jni.cpp" -lcrypto -Wno-deprecated-declarations
CP=$G/kotlin-compiler-embeddable-2.0.21.jar:$G/kotlin-stdlib-2.0.21.jar:$G/kotlin-script-runtime-2.0.21.jar:$G/kotlin-reflect-2.0.21.jar:$G/kotlin-daemon-embeddable-2.0.21.jar:$G/trove4j-1.0.20200330.jar:$G/kotlinx-coroutines-core-jvm-1.6.4.jar:$G/annotations-24.0.1.jar
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$G/kotlin-stdlib-2.0.21.jar" -d "$W/clases" \
  "$RAIZ/app/src/main/java/com/hunter/btc/LightNative.kt" "$RAIZ/app/src/main/java/com/hunter/btc/LightClient.kt" "$AQUI/LigeroE2E.kt"
java -Dhunter.ligero.lib=ligero_host -Djava.library.path="$W" -cp "$W/clases:$G/kotlin-stdlib-2.0.21.jar" LigeroE2EKt "$W/servidor_sim"
