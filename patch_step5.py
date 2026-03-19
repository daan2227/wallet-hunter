#!/usr/bin/env python3
"""
patch_step5.py
Agrega la función JNI Java_com_hunter_btc_recovery_RecoveryEngine_bruteForceSeeds
al archivo nativo principal del proyecto.
Ejecutar desde la raiz del proyecto: python3 patch_step5.py
"""

import os, sys, re

PROJECT_ROOT = os.getcwd()

# ── Localizar native-lib.cpp (o el archivo JNI principal) ───────────────────
def find_jni_file():
    for root, dirs, files in os.walk(os.path.join(PROJECT_ROOT, "app", "src", "main", "cpp")):
        for f in files:
            if f.endswith(".cpp"):
                path = os.path.join(root, f)
                content = open(path).read()
                if "JNIEXPORT" in content and "JNIEnv" in content:
                    return path
    return None

jni_path = find_jni_file()
if not jni_path:
    print("ERROR: No se encontró archivo JNI (.cpp con JNIEXPORT)")
    sys.exit(1)

print(f"Archivo JNI encontrado: {jni_path}")
jni_dir = os.path.dirname(jni_path)

# ── Detectar package del proyecto ────────────────────────────────────────────
def find_package():
    for root, dirs, files in os.walk(os.path.join(PROJECT_ROOT, "app", "src", "main", "java")):
        for f in files:
            if f.endswith(".kt"):
                try:
                    content = open(os.path.join(root, f)).read()
                    for line in content.splitlines():
                        if line.startswith("package "):
                            pkg = line.replace("package ", "").strip().rstrip(";")
                            return ".".join(pkg.split(".")[:3])
                except:
                    pass
    return "com.hunter.btc"

package = find_package()
jni_class = package.replace(".", "_") + "_recovery_RecoveryEngine"
print(f"Package: {package}  →  JNI class: {jni_class}")

# ── 1. Crear recovery_engine.cpp (motor de fuerza bruta) ─────────────────────
engine_cpp = f"""#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "mnemonic.h"
#include "bip32.h"

// Reutilizar la función de generación de dirección BTC ya existente en el proyecto
// Declaración externa — debe existir en native-lib.cpp o address_utils.cpp
extern std::string privkey_to_btc_address(const uint8_t privkey[32]);

#define LOG_TAG "RecoveryEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Helpers ───────────────────────────────────────────────────────────────────

// Convierte jstring a std::string
static std::string jstr(JNIEnv* env, jstring js) {{
    if (!js) return "";
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}}

// Carga el wordlist desde assets
static std::vector<std::string> load_wordlist(JNIEnv* env, jobject asset_manager_obj) {{
    // El wordlist ya fue cargado en Kotlin y se pasa como jobjectArray
    // Ver firma de la función JNI abajo
    return {{}};
}}

// ── Motor iterativo de combinaciones ─────────────────────────────────────────
// Genera combinaciones sin recursión para mejor rendimiento en ARM
//
// slots:          vector de palabras, "" indica slot faltante
// wordlist:       2048 palabras BIP39
// missing_indices: posiciones de los slots faltantes
// target_address: dirección BTC objetivo (vacía = buscar cualquier con saldo)
// progress_cb:    llamado cada 10000 intentos con el progreso
//
// Retorna la frase completa si encontró match, "" si no encontró nada.

static std::string brute_force(
    std::vector<std::string>& slots,
    const std::vector<std::string>& wordlist,
    const std::vector<int>& missing_indices,
    const std::string& target_address,
    volatile bool* cancelled,
    JNIEnv* env,
    jobject callback,
    jmethodID on_progress
) {{
    int n_missing = (int)missing_indices.size();
    int wl_size   = (int)wordlist.size();  // 2048

    // Contadores para cada slot faltante (índice en wordlist)
    std::vector<int> counters(n_missing, 0);

    // Total de combinaciones
    long long total = 1;
    for (int i = 0; i < n_missing; i++) total *= wl_size;

    long long attempts = 0;

    while (true) {{
        // Aplicar combinación actual a los slots
        for (int i = 0; i < n_missing; i++) {{
            slots[missing_indices[i]] = wordlist[counters[i]];
        }}

        // Construir mnemonic completo
        std::string mnemonic;
        for (int i = 0; i < (int)slots.size(); i++) {{
            if (i > 0) mnemonic += " ";
            mnemonic += slots[i];
        }}

        // seed = PBKDF2(mnemonic)
        uint8_t seed[64];
        if (mnemonic_to_seed(mnemonic, "", seed)) {{

            // Derivar clave privada BIP44: m/44'/0'/0'/0/0
            uint8_t privkey[32];
            if (bip44_derive_privkey(seed, 0, privkey)) {{

                // Generar dirección BTC
                std::string address = privkey_to_btc_address(privkey);

                // Comparar con target
                bool match = false;
                if (!target_address.empty()) {{
                    match = (address == target_address);
                }}
                // Si no hay target, solo reportamos progreso (modo sin target)

                if (match) {{
                    LOGI("¡Match encontrado! %s", mnemonic.c_str());
                    return mnemonic;
                }}
            }}
        }}

        attempts++;

        // Reportar progreso cada 5000 intentos
        if (attempts % 5000 == 0 && callback && on_progress) {{
            jstring jmnemonic = env->NewStringUTF(
                slots[missing_indices[0]].c_str()  // palabra actual del primer slot
            );
            env->CallVoidMethod(callback, on_progress,
                                (jlong)attempts, (jlong)total, jmnemonic);
            env->DeleteLocalRef(jmnemonic);

            // Verificar cancelación
            if (*cancelled) {{
                LOGI("Recovery cancelado por el usuario");
                return "";
            }}
        }}

        // Avanzar contadores (como un odómetro)
        int carry = 1;
        for (int i = n_missing - 1; i >= 0 && carry; i--) {{
            counters[i] += carry;
            if (counters[i] >= wl_size) {{
                counters[i] = 0;
                carry = 1;
            }} else {{
                carry = 0;
            }}
        }}

        // Si hubo carry en el primer dígito, terminamos
        if (carry) break;
    }}

    return "";  // no encontrado
}}

// ── Bandera de cancelación global para esta sesión ───────────────────────────
static volatile bool g_recovery_cancelled = false;

// ── Función JNI principal ─────────────────────────────────────────────────────
extern "C" {{

/**
 * Inicia el brute force de seed phrase.
 *
 * @param slots_arr      String[] con las palabras conocidas, "" para faltantes
 * @param wordlist_arr   String[] con las 2048 palabras BIP39
 * @param missing_arr    int[]    con los índices de los slots faltantes
 * @param target_address String   dirección BTC objetivo (puede ser vacía)
 * @param callback       Object   instancia de RecoveryEngine para callbacks
 * @return String con la frase encontrada, o null si no se encontró
 */
JNIEXPORT jstring JNICALL
Java_{jni_class}_bruteForceSeeds(
    JNIEnv*     env,
    jobject     thiz,
    jobjectArray slots_arr,
    jobjectArray wordlist_arr,
    jintArray    missing_arr,
    jstring      target_address_j)
{{
    g_recovery_cancelled = false;

    // ── Cargar slots ──────────────────────────────────────────────────────────
    int slots_count = env->GetArrayLength(slots_arr);
    std::vector<std::string> slots(slots_count);
    for (int i = 0; i < slots_count; i++) {{
        auto js = (jstring)env->GetObjectArrayElement(slots_arr, i);
        slots[i] = jstr(env, js);
        env->DeleteLocalRef(js);
    }}

    // ── Cargar wordlist ───────────────────────────────────────────────────────
    int wl_count = env->GetArrayLength(wordlist_arr);
    std::vector<std::string> wordlist(wl_count);
    for (int i = 0; i < wl_count; i++) {{
        auto js = (jstring)env->GetObjectArrayElement(wordlist_arr, i);
        wordlist[i] = jstr(env, js);
        env->DeleteLocalRef(js);
    }}

    // ── Cargar missing indices ────────────────────────────────────────────────
    int missing_count = env->GetArrayLength(missing_arr);
    jint* raw_missing = env->GetIntArrayElements(missing_arr, nullptr);
    std::vector<int> missing_indices(raw_missing, raw_missing + missing_count);
    env->ReleaseIntArrayElements(missing_arr, raw_missing, JNI_ABORT);

    // ── Target address ────────────────────────────────────────────────────────
    std::string target = jstr(env, target_address_j);

    LOGI("Iniciando recovery: %d palabras, %d faltantes, target=%s",
         slots_count, missing_count, target.empty() ? "(ninguno)" : target.c_str());

    // ── Obtener método onProgress del callback (thiz) ─────────────────────────
    jclass cls = env->GetObjectClass(thiz);
    jmethodID on_progress = env->GetMethodID(cls, "onProgress", "(JJLjava/lang/String;)V");
    // Si no existe el método, on_progress será null y no se llamará

    // ── Ejecutar brute force ──────────────────────────────────────────────────
    std::string result = brute_force(
        slots, wordlist, missing_indices, target,
        &g_recovery_cancelled,
        env, thiz, on_progress
    );

    if (result.empty()) {{
        return nullptr;  // no encontrado
    }}

    return env->NewStringUTF(result.c_str());
}}

/**
 * Cancela el brute force en curso.
 */
JNIEXPORT void JNICALL
Java_{jni_class}_cancelRecovery(JNIEnv* env, jobject thiz)
{{
    g_recovery_cancelled = true;
    LOGI("cancelRecovery() llamado");
}}

}} // extern "C"
"""

engine_path = os.path.join(jni_dir, "recovery_engine.cpp")
with open(engine_path, "w") as f:
    f.write(engine_cpp)
print(f"✓ Creado: recovery_engine.cpp")

# ── 2. Crear RecoveryEngine.kt (wrapper Kotlin) ───────────────────────────────
kt_dir = os.path.join(PROJECT_ROOT, "app", "src", "main", "java",
                      *package.split("."), "recovery")
os.makedirs(kt_dir, exist_ok=True)

recovery_engine_kt = f"""package {package}.recovery

import android.content.Context
import android.content.res.AssetManager

/**
 * Wrapper Kotlin para el motor nativo de recovery.
 * Carga el wordlist BIP39 y llama al JNI bruteForceSeeds().
 */
class RecoveryEngine(private val context: Context) {{

    // ── Callback de progreso ──────────────────────────────────────────────────
    interface ProgressListener {{
        fun onProgress(attempts: Long, total: Long, currentWord: String)
        fun onFound(mnemonic: String)
        fun onNotFound()
        fun onCancelled()
    }}

    var listener: ProgressListener? = null
    private var wordlist: Array<String>? = null

    // ── Cargar wordlist desde assets ──────────────────────────────────────────
    fun loadWordlist(): Boolean {{
        return try {{
            val words = context.assets
                .open("bip39_english.txt")
                .bufferedReader()
                .readLines()
                .filter {{ it.isNotBlank() }}
                .toTypedArray()
            wordlist = words
            words.size == 2048
        }} catch (e: Exception) {{
            false
        }}
    }}

    // ── Iniciar recovery en background ────────────────────────────────────────
    fun startRecovery(parsed: ParsedPhrase, targetAddress: String) {{
        val wl = wordlist ?: return

        // Convertir slots a String[] para JNI (string vacío = faltante)
        val slotsArray: Array<String> = parsed.slots
            .map {{ it ?: "" }}
            .toTypedArray()

        val missingArray: IntArray = parsed.missingIndices.toIntArray()

        Thread {{
            val result = bruteForceSeeds(
                slotsArray,
                wl,
                missingArray,
                targetAddress
            )
            if (result != null) {{
                listener?.onFound(result)
            }} else {{
                if (g_cancelled) {{
                    listener?.onCancelled()
                }} else {{
                    listener?.onNotFound()
                }}
            }}
        }}.start()
    }}

    fun cancel() {{
        g_cancelled = true
        cancelRecovery()
    }}

    // Llamado desde C++ cada 5000 intentos
    fun onProgress(attempts: Long, total: Long, currentWord: String) {{
        listener?.onProgress(attempts, total, currentWord)
    }}

    // ── JNI ───────────────────────────────────────────────────────────────────
    private external fun bruteForceSeeds(
        slots: Array<String>,
        wordlist: Array<String>,
        missingIndices: IntArray,
        targetAddress: String
    ): String?

    private external fun cancelRecovery()

    companion object {{
        @Volatile private var g_cancelled = false
    }}
}}
"""

kt_path = os.path.join(kt_dir, "RecoveryEngine.kt")
with open(kt_path, "w") as f:
    f.write(recovery_engine_kt)
print(f"✓ Creado: RecoveryEngine.kt")

# ── 3. Parchear CMakeLists.txt ────────────────────────────────────────────────
cmake_path = os.path.join(jni_dir, "CMakeLists.txt")
cmake_content = open(cmake_path).read()

if "recovery_engine.cpp" not in cmake_content:
    cmake_content = cmake_content.replace(
        "bip32.cpp",
        "bip32.cpp\n        recovery_engine.cpp"
    )
    with open(cmake_path, "w") as f:
        f.write(cmake_content)
    print("✓ recovery_engine.cpp agregado a CMakeLists.txt")
else:
    print("→ recovery_engine.cpp ya estaba en CMakeLists.txt")

# ── 4. Resumen ────────────────────────────────────────────────────────────────
print(f"""
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Paso 5 completado:

  cpp/recovery_engine.cpp         ← motor brute force JNI
  recovery/RecoveryEngine.kt      ← wrapper Kotlin + callback

IMPORTANTE: recovery_engine.cpp usa:
  extern privkey_to_btc_address(privkey)

Necesitas verificar que esa función existe en tu native-lib.cpp
con exactamente ese nombre. Corre:

  grep -n "privkey_to_btc_address" app/src/main/cpp/*.cpp

Si tiene otro nombre, avísame y lo ajusto antes del Paso 6.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
""")
