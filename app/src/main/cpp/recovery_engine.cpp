#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include <openssl/sha.h>
#include <openssl/ripemd.h>
#include <secp256k1.h>
#include "mnemonic.h"
#include "bip32.h"

#define LOG_TAG "RecoveryEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define MAX_ADDR 64

// ── Funciones copiadas de hunter_jni.cpp ─────────────────────────────────────
// (declaradas static aquí para no colisionar con el TU de origen)

static const char B58CHARS[] =
    "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

static void b58enc_local(const uint8_t* data, size_t len, char* out, size_t out_sz) {
    uint8_t buf[128] = {0};
    size_t  blen = sizeof(buf);
    // contar ceros al inicio
    int zeros = 0;
    while (zeros < (int)len && data[zeros] == 0) zeros++;

    // conversión base256 → base58
    for (size_t i = zeros; i < len; i++) {
        int carry = data[i];
        for (int j = (int)blen - 1; j >= 0; j--) {
            carry += 256 * buf[j];
            buf[j] = carry % 58;
            carry /= 58;
        }
    }
    // encontrar primer no-cero en buf
    size_t start = 0;
    while (start < blen && buf[start] == 0) start++;

    size_t out_len = zeros + (blen - start);
    if (out_len >= out_sz) { out[0] = 0; return; }

    for (int i = 0; i < zeros; i++) out[i] = '1';
    for (size_t i = start; i < blen; i++) out[zeros + (i - start)] = B58CHARS[buf[i]];
    out[out_len] = '\0';
}

// privkey(32) → compressed pubkey(33)
static void get_pub33_local(secp256k1_context* ctx,
                             const uint8_t* pk, uint8_t* out) {
    secp256k1_pubkey pubkey;
    secp256k1_ec_pubkey_create(ctx, &pubkey, pk);
    size_t len = 33;
    secp256k1_ec_pubkey_serialize(ctx, out, &len, &pubkey, SECP256K1_EC_COMPRESSED);
}

// privkey → HASH160 (SHA256 → RIPEMD160 de la pubkey comprimida)
static void pk_to_h160_local(secp256k1_context* ctx,
                              const uint8_t* pk, uint8_t* h160_out) {
    uint8_t pub[33];
    get_pub33_local(ctx, pk, pub);
    uint8_t sha[32];
    SHA256(pub, 33, sha);
    RIPEMD160(sha, 32, h160_out);
}

// HASH160 → dirección P2PKH Base58Check (1xxx...)
static void h160_to_addr_local(const uint8_t* h160, char* addr_out) {
    // payload: version(1) + hash160(20) + checksum(4) = 25 bytes
    uint8_t payload[25];
    payload[0] = 0x00; // mainnet P2PKH
    memcpy(payload + 1, h160, 20);

    // checksum = SHA256(SHA256(payload[0..20]))
    uint8_t tmp[32], chk[32];
    SHA256(payload, 21, tmp);
    SHA256(tmp, 32, chk);
    memcpy(payload + 21, chk, 4);

    b58enc_local(payload, 25, addr_out, MAX_ADDR);
}

// ── Helper jstring → std::string ─────────────────────────────────────────────
static std::string jstr(JNIEnv* env, jstring js) {
    if (!js) return "";
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

// ── Motor iterativo de combinaciones ─────────────────────────────────────────
static std::string brute_force(
    std::vector<std::string>& slots,
    const std::vector<std::string>& wordlist,
    const std::vector<int>& missing_indices,
    const std::string& target_address,
    volatile bool* cancelled,
    JNIEnv* env,
    jobject callback,
    jmethodID on_progress)
{
    int   n_missing = (int)missing_indices.size();
    int   wl_size   = (int)wordlist.size();  // 2048

    std::vector<int> counters(n_missing, 0);

    long long total = 1;
    for (int i = 0; i < n_missing; i++) total *= wl_size;

    long long attempts = 0;

    // Contexto secp256k1 reutilizable
    secp256k1_context* ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN);

    while (true) {
        // Aplicar combinación actual
        for (int i = 0; i < n_missing; i++)
            slots[missing_indices[i]] = wordlist[counters[i]];

        // Construir mnemonic
        std::string mnemonic;
        mnemonic.reserve(200);
        for (int i = 0; i < (int)slots.size(); i++) {
            if (i > 0) mnemonic += ' ';
            mnemonic += slots[i];
        }

        // seed = PBKDF2-HMAC-SHA512(mnemonic)
        uint8_t seed[64];
        if (mnemonic_to_seed(mnemonic, "", seed)) {

            // Derivar clave privada BIP44: m/44'/0'/0'/0/0
            uint8_t privkey[32];
            if (bip44_derive_privkey(seed, 0, privkey)) {

                // privkey → h160 → dirección
                uint8_t h160[20];
                char    addr[MAX_ADDR];
                pk_to_h160_local(ctx, privkey, h160);
                h160_to_addr_local(h160, addr);

                if (!target_address.empty() && target_address == addr) {
                    secp256k1_context_destroy(ctx);
                    LOGI("MATCH: %s", mnemonic.c_str());
                    return mnemonic;
                }
            }
        }

        attempts++;

        // Progreso cada 5000 intentos
        if (attempts % 5000 == 0 && callback && on_progress) {
            jstring jword = env->NewStringUTF(
                slots[missing_indices[0]].c_str());
            env->CallVoidMethod(callback, on_progress,
                                (jlong)attempts, (jlong)total, jword);
            env->DeleteLocalRef(jword);

            if (*cancelled) {
                secp256k1_context_destroy(ctx);
                return "";
            }
        }

        // Avanzar contadores (odómetro)
        int carry = 1;
        for (int i = n_missing - 1; i >= 0 && carry; i--) {
            counters[i] += carry;
            if (counters[i] >= wl_size) { counters[i] = 0; carry = 1; }
            else                        { carry = 0; }
        }
        if (carry) break; // completado
    }

    secp256k1_context_destroy(ctx);
    return "";
}

// ── Bandera de cancelación ────────────────────────────────────────────────────
static volatile bool g_recovery_cancelled = false;

// ── JNI ───────────────────────────────────────────────────────────────────────
extern "C" {

JNIEXPORT jstring JNICALL
Java_com_hunter_btc_recovery_RecoveryEngine_bruteForceSeeds(
    JNIEnv*      env,
    jobject      thiz,
    jobjectArray slots_arr,
    jobjectArray wordlist_arr,
    jintArray    missing_arr,
    jstring      target_j)
{
    g_recovery_cancelled = false;

    // Cargar slots
    int slots_count = env->GetArrayLength(slots_arr);
    std::vector<std::string> slots(slots_count);
    for (int i = 0; i < slots_count; i++) {
        auto js = (jstring)env->GetObjectArrayElement(slots_arr, i);
        slots[i] = jstr(env, js);
        env->DeleteLocalRef(js);
    }

    // Cargar wordlist
    int wl_count = env->GetArrayLength(wordlist_arr);
    std::vector<std::string> wordlist(wl_count);
    for (int i = 0; i < wl_count; i++) {
        auto js = (jstring)env->GetObjectArrayElement(wordlist_arr, i);
        wordlist[i] = jstr(env, js);
        env->DeleteLocalRef(js);
    }

    // Missing indices
    int   missing_count = env->GetArrayLength(missing_arr);
    jint* raw           = env->GetIntArrayElements(missing_arr, nullptr);
    std::vector<int> missing_indices(raw, raw + missing_count);
    env->ReleaseIntArrayElements(missing_arr, raw, JNI_ABORT);

    std::string target = jstr(env, target_j);

    LOGI("Recovery: slots=%d missing=%d target=%s",
         slots_count, missing_count,
         target.empty() ? "(ninguno)" : target.c_str());

    // Obtener callback onProgress
    jclass    cls         = env->GetObjectClass(thiz);
    jmethodID on_progress = env->GetMethodID(cls, "onProgress",
                                             "(JJLjava/lang/String;)V");

    std::string result = brute_force(
        slots, wordlist, missing_indices, target,
        &g_recovery_cancelled,
        env, thiz, on_progress);

    return result.empty() ? nullptr : env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_hunter_btc_recovery_RecoveryEngine_cancelRecovery(JNIEnv* env, jobject thiz)
{
    g_recovery_cancelled = true;
}

} // extern "C"
