#pragma once
/* Exporta la clave pública extendida (xpub / ypub / zpub, y sus versiones de
 * testnet) de la cuenta de una cartera de semilla: m/<purpose>'/<coin>'/0'.
 *
 * Con esto una cartera se puede vigilar en otro sitio sin la semilla. La
 * importación de xpub ya existía; esto hace el camino contrario.
 *
 * Va en su propio fichero para poder probarlo en el escritorio contra el vector
 * publicado de BIP84 (tools/ec-harness/xpub.cpp). La derivación es la misma de
 * siempre; hunter_jni.cpp tiene la suya, y las dos tienen que dar lo mismo.
 */
#include <openssl/crypto.h>   /* OPENSSL_cleanse */
#include <stdint.h>
#include <string.h>
#include <string>
#include <openssl/hmac.h>
#include <openssl/sha.h>
#include <openssl/ripemd.h>
#include <secp256k1.h>
#include "addr_encode.h"   /* b58enc: base58check con el checksum incluido */

struct XNodo { uint8_t key[32]; uint8_t chain[32]; };

static void xpub_hmac(const uint8_t *k, int kl, const uint8_t *d, int dl, uint8_t out[64]) {
    unsigned int n = 64;
    HMAC(EVP_sha512(), k, kl, d, dl, out, &n);
}

/* Hijo de [par] con el índice [idx] (endurecido si idx >= 2^31). false si el
   resultado no es una clave válida (caso de probabilidad despreciable). */
static bool xpub_hijo(secp256k1_context *ctx, const XNodo &par, uint32_t idx, XNodo &hijo) {
    uint8_t data[37]; uint8_t I[64];
    if (idx >= 0x80000000u) { data[0] = 0; memcpy(data + 1, par.key, 32); }
    else {
        secp256k1_pubkey pk; size_t l = 33;
        if (!secp256k1_ec_pubkey_create(ctx, &pk, par.key)) return false;
        secp256k1_ec_pubkey_serialize(ctx, data, &l, &pk, SECP256K1_EC_COMPRESSED);
    }
    data[33] = (uint8_t)(idx >> 24); data[34] = (uint8_t)(idx >> 16);
    data[35] = (uint8_t)(idx >> 8);  data[36] = (uint8_t)idx;
    xpub_hmac(par.chain, 32, data, 37, I);
    memcpy(hijo.key, par.key, 32);
    if (!secp256k1_ec_seckey_tweak_add(ctx, hijo.key, I)) return false;
    memcpy(hijo.chain, I + 32, 32);
    return true;
}

static void xpub_hash160_de(const uint8_t *pub, size_t n, uint8_t out[20]) {
    uint8_t s[32]; SHA256(pub, n, s); RIPEMD160(s, 32, out);
}

/* Versión (4 bytes) según el propósito y la red. */
static uint32_t xpub_version(int purpose, bool testnet) {
    if (purpose == 84) return testnet ? 0x045f1cf6u : 0x04b24746u;   /* vpub / zpub */
    if (purpose == 49) return testnet ? 0x044a5262u : 0x049d7cb2u;   /* upub / ypub */
    return testnet ? 0x043587cfu : 0x0488b21eu;                      /* tpub / xpub */
}

/* Serializa un nodo con su profundidad, huella del padre e índice. */
static std::string xpub_serializar(const XNodo &nodo, uint8_t depth, const uint8_t fp[4],
                                   uint32_t hijo, uint32_t version, secp256k1_context *ctx) {
    uint8_t pub33[33]; size_t l = 33;
    secp256k1_pubkey pk;
    if (!secp256k1_ec_pubkey_create(ctx, &pk, nodo.key)) return "";
    secp256k1_ec_pubkey_serialize(ctx, pub33, &l, &pk, SECP256K1_EC_COMPRESSED);
    uint8_t p[78]; int n = 0;
    for (int i = 3; i >= 0; i--) p[n++] = (uint8_t)(version >> (8 * i));
    p[n++] = depth;
    memcpy(p + n, fp, 4); n += 4;
    for (int i = 3; i >= 0; i--) p[n++] = (uint8_t)(hijo >> (8 * i));
    memcpy(p + n, nodo.chain, 32); n += 32;
    memcpy(p + n, pub33, 33); n += 33;
    char out[128] = {0};
    b58enc(p, 78, out, sizeof out);
    return out;
}

/*
 * xpub/ypub/zpub de la cuenta m/purpose'/coin'/0' de una semilla BIP39 (64
 * bytes). coin es 0 en mainnet y 1 en testnet. Cadena vacía si algo falla.
 */
static std::string xpub_de_cuenta(const uint8_t seed[64], int purpose, bool testnet, uint32_t cuenta = 0) {
    secp256k1_context *ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    std::string out;
    XNodo m, p1, p2, cta;
    uint8_t I[64];
    // Las claves privadas de los nodos de la ruta y el HMAC de la semilla se borran al salir, también si falla.
    struct Borra {
        XNodo *a, *b, *c, *d; uint8_t *i;
        ~Borra() { OPENSSL_cleanse(a, sizeof(XNodo)); OPENSSL_cleanse(b, sizeof(XNodo));
                   OPENSSL_cleanse(c, sizeof(XNodo)); OPENSSL_cleanse(d, sizeof(XNodo)); OPENSSL_cleanse(i, 64); }
    } borra{&m, &p1, &p2, &cta, I};
    xpub_hmac((const uint8_t *)"Bitcoin seed", 12, seed, 64, I);
    memcpy(m.key, I, 32); memcpy(m.chain, I + 32, 32);
    uint32_t coin = testnet ? 1u : 0u;
    const uint32_t H = 0x80000000u;
    if (xpub_hijo(ctx, m, H + (uint32_t)purpose, p1) &&
        xpub_hijo(ctx, p1, H + coin, p2) &&
        xpub_hijo(ctx, p2, H + cuenta, cta)) {
        /* Huella del padre: primeros 4 bytes de hash160(clave pública del padre). */
        secp256k1_pubkey pk; uint8_t pub33[33]; size_t l = 33;
        secp256k1_ec_pubkey_create(ctx, &pk, p2.key);
        secp256k1_ec_pubkey_serialize(ctx, pub33, &l, &pk, SECP256K1_EC_COMPRESSED);
        uint8_t h160[20]; xpub_hash160_de(pub33, 33, h160);
        out = xpub_serializar(cta, 3, h160, H + cuenta, xpub_version(purpose, testnet), ctx);
    }
    secp256k1_context_destroy(ctx);
    return out;
}
