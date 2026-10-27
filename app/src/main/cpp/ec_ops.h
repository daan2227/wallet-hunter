#pragma once
/*
 * Operaciones de curva sueltas para lo que se arma en Kotlin: carteras vigiladas desde una xpub
 * (derivación pública BIP32 y salida Taproot BIP86), Silent Payments (BIP352), multifirma y la
 * plantilla de herencia. Sin estado y sin JNI: se prueba en el banco (tools/ec-harness/ec_ops.cpp)
 * contra los vectores oficiales de BIP352 y de BIP32/BIP86.
 *
 * Todo trabaja con bytes: públicas comprimidas de 33, x-only de 32, escalares de 32 big-endian.
 * Cada función devuelve false si algo no es válido (clave fuera de la curva, escalar 0 o >= n…).
 */
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>
#include <secp256k1.h>
#include <secp256k1_extrakeys.h>
#include <secp256k1_schnorrsig.h>
#include <openssl/sha.h>
#include <openssl/hmac.h>
#include <openssl/evp.h>

namespace ecops {

inline void sha256(const uint8_t *d, size_t n, uint8_t out[32]) { SHA256(d, n, out); }

/* SHA256(SHA256(tag) || SHA256(tag) || msg), BIP340. */
inline void tagged(const char *tag, const uint8_t *msg, size_t n, uint8_t out[32]) {
    uint8_t th[32]; sha256((const uint8_t*)tag, strlen(tag), th);
    SHA256_CTX c; SHA256_Init(&c); SHA256_Update(&c, th, 32); SHA256_Update(&c, th, 32);
    SHA256_Update(&c, msg, n); SHA256_Final(out, &c);
}

inline bool pub_parse(const secp256k1_context *ctx, const uint8_t p33[33], secp256k1_pubkey *o) {
    return secp256k1_ec_pubkey_parse(ctx, o, p33, 33) == 1;
}
inline void pub_ser(const secp256k1_context *ctx, const secp256k1_pubkey *p, uint8_t o33[33]) {
    size_t l = 33; secp256k1_ec_pubkey_serialize(ctx, o33, &l, p, SECP256K1_EC_COMPRESSED);
}

/* a·G */
inline bool pub_de_priv(const secp256k1_context *ctx, const uint8_t k[32], uint8_t o33[33]) {
    secp256k1_pubkey p; if (!secp256k1_ec_pubkey_create(ctx, &p, k)) return false;
    pub_ser(ctx, &p, o33); return true;
}
/* P·t */
inline bool pub_mul(const secp256k1_context *ctx, const uint8_t p33[33], const uint8_t t[32], uint8_t o33[33]) {
    secp256k1_pubkey p; if (!pub_parse(ctx, p33, &p) || !secp256k1_ec_pubkey_tweak_mul(ctx, &p, t)) return false;
    pub_ser(ctx, &p, o33); return true;
}
/* P + t·G */
inline bool pub_add_tweak(const secp256k1_context *ctx, const uint8_t p33[33], const uint8_t t[32], uint8_t o33[33]) {
    secp256k1_pubkey p; if (!pub_parse(ctx, p33, &p) || !secp256k1_ec_pubkey_tweak_add(ctx, &p, t)) return false;
    pub_ser(ctx, &p, o33); return true;
}
/* P1 + P2 + … */
inline bool pub_sumar(const secp256k1_context *ctx, const std::vector<std::vector<uint8_t>> &ps, uint8_t o33[33]) {
    if (ps.empty()) return false;
    std::vector<secp256k1_pubkey> v(ps.size()); std::vector<const secp256k1_pubkey*> pv(ps.size());
    for (size_t i = 0; i < ps.size(); i++) { if (ps[i].size() != 33 || !pub_parse(ctx, ps[i].data(), &v[i])) return false; pv[i] = &v[i]; }
    secp256k1_pubkey r; if (!secp256k1_ec_pubkey_combine(ctx, &r, pv.data(), pv.size())) return false;
    pub_ser(ctx, &r, o33); return true;
}
/* (a + b) mod n, (a·b) mod n, −a mod n */
inline bool priv_add(const secp256k1_context *ctx, const uint8_t a[32], const uint8_t b[32], uint8_t o[32]) {
    memcpy(o, a, 32); return secp256k1_ec_seckey_tweak_add(ctx, o, b) == 1;
}
inline bool priv_mul(const secp256k1_context *ctx, const uint8_t a[32], const uint8_t b[32], uint8_t o[32]) {
    memcpy(o, a, 32); return secp256k1_ec_seckey_tweak_mul(ctx, o, b) == 1;
}
inline bool priv_neg(const secp256k1_context *ctx, const uint8_t a[32], uint8_t o[32]) {
    memcpy(o, a, 32); return secp256k1_ec_seckey_negate(ctx, o) == 1;
}

/* Σ k_i mod n admitiendo sumas intermedias nulas (con tweak_add, a + (−a) falla aunque luego se sume
   algo más). [neg][i] niega k_i antes (Taproot de y impar). false si el total es 0 o alguna k_i no vale. */
inline bool priv_sumar(const secp256k1_context *ctx, const std::vector<std::vector<uint8_t>> &ks,
                       const std::vector<bool> &neg, uint8_t o[32]) {
    static const uint8_t N[32] = {0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xfe,
                                  0xba,0xae,0xdc,0xe6,0xaf,0x48,0xa0,0x3b,0xbf,0xd2,0x5e,0x8c,0xd0,0x36,0x41,0x41};
    uint8_t acc[32] = {0};
    for (size_t j = 0; j < ks.size(); j++) {
        if (ks[j].size() != 32 || !secp256k1_ec_seckey_verify(ctx, ks[j].data())) return false;
        uint8_t k[32]; memcpy(k, ks[j].data(), 32);
        if (j < neg.size() && neg[j] && !secp256k1_ec_seckey_negate(ctx, k)) return false;
        uint8_t r[32]; int c = 0;
        for (int i = 31; i >= 0; i--) { int v = acc[i] + k[i] + c; r[i] = (uint8_t)v; c = v >> 8; }
        bool ge = c != 0;
        if (!ge) { ge = true; for (int i = 0; i < 32; i++) { if (r[i] != N[i]) { ge = r[i] > N[i]; break; } } }
        if (ge) { int b = 0; for (int i = 31; i >= 0; i--) { int v = r[i] - N[i] - b; b = v < 0; r[i] = (uint8_t)(v + (b ? 256 : 0)); } }
        memcpy(acc, r, 32);
    }
    memcpy(o, acc, 32);
    return secp256k1_ec_seckey_verify(ctx, o) == 1;
}

/* Salida Taproot sin scripts (BIP86): Q = P + H_TapTweak(x(P))·G con P de y par. Devuelve x(Q). */
inline bool tr_salida(const secp256k1_context *ctx, const uint8_t interna_x[32], uint8_t q32[32]) {
    secp256k1_xonly_pubkey xo; if (!secp256k1_xonly_pubkey_parse(ctx, &xo, interna_x)) return false;
    uint8_t t[32]; tagged("TapTweak", interna_x, 32, t);
    secp256k1_pubkey q; if (!secp256k1_xonly_pubkey_tweak_add(ctx, &q, &xo, t)) return false;
    secp256k1_xonly_pubkey qx; int par = 0;
    if (!secp256k1_xonly_pubkey_from_pubkey(ctx, &qx, &par, &q)) return false;
    secp256k1_xonly_pubkey_serialize(ctx, q32, &qx); return true;
}

/* Derivación pública BIP32 (sin endurecer) de una xpub: clave 33 + cadena 32, índices < 2^31. */
inline bool ckd_pub_ruta(const secp256k1_context *ctx, const uint8_t clave33[33], const uint8_t cadena[32],
                         const std::vector<uint32_t> &ruta, uint8_t o33[33]) {
    uint8_t k[33], c[32]; memcpy(k, clave33, 33); memcpy(c, cadena, 32);
    for (uint32_t i : ruta) {
        if (i & 0x80000000u) return false;
        uint8_t d[37]; memcpy(d, k, 33);
        d[33] = (uint8_t)(i >> 24); d[34] = (uint8_t)(i >> 16); d[35] = (uint8_t)(i >> 8); d[36] = (uint8_t)i;
        uint8_t I[64]; unsigned int il = 64;
        HMAC(EVP_sha512(), c, 32, d, 37, I, &il);
        if (!pub_add_tweak(ctx, k, I, k)) return false;
        memcpy(c, I + 32, 32);
    }
    memcpy(o33, k, 33); return true;
}

/* ── Nostr (NIP-01/04/44) ─────────────────────────────────────────────────────────────────────── */

/* x-only (32) de una privada: la "pubkey" de Nostr. */
inline bool xonly_de_priv(const secp256k1_context *ctx, const uint8_t k[32], uint8_t x32[32]) {
    secp256k1_keypair kp; if (!secp256k1_keypair_create(ctx, &kp, k)) return false;
    secp256k1_xonly_pubkey xo; int par = 0;
    if (!secp256k1_keypair_xonly_pub(ctx, &xo, &par, &kp)) return false;
    secp256k1_xonly_pubkey_serialize(ctx, x32, &xo); return true;
}
/* Firma Schnorr BIP340 de un mensaje de 32 bytes con [aux] de 32 (aleatorio en uso normal). */
inline bool schnorr_firmar(const secp256k1_context *ctx, const uint8_t k[32], const uint8_t msg[32], const uint8_t aux[32], uint8_t sig[64]) {
    secp256k1_keypair kp; if (!secp256k1_keypair_create(ctx, &kp, k)) return false;
    return secp256k1_schnorrsig_sign32(ctx, sig, msg, &kp, aux) == 1;
}
/* Verifica una firma BIP340 de [msg] (32) por la x-only [px]. */
inline bool schnorr_verificar(const secp256k1_context *ctx, const uint8_t sig[64], const uint8_t msg[32], const uint8_t px[32]) {
    secp256k1_xonly_pubkey xo; if (!secp256k1_xonly_pubkey_parse(ctx, &xo, px)) return false;
    return secp256k1_schnorrsig_verify(ctx, sig, msg, 32, &xo) == 1;
}
/* Secreto compartido de Nostr: la x de k·P, con P la x-only [px] levantada con y par (NIP-04 y NIP-44). */
inline bool ecdh_x(const secp256k1_context *ctx, const uint8_t k[32], const uint8_t px[32], uint8_t out[32]) {
    uint8_t p33[33]; p33[0] = 0x02; memcpy(p33 + 1, px, 32);
    uint8_t r[33]; if (!pub_mul(ctx, p33, k, r)) return false;
    memcpy(out, r + 1, 32); return true;
}

/* ── Silent Payments (BIP352), lado del que envía ──────────────────────────────────────────────
 * [a] suma de las privadas de las entradas elegibles (ya negadas las Taproot de y impar), [outpointMin]
 * el menor outpoint (txid en orden de la transacción ‖ vout LE, 36 bytes) de TODAS las entradas, y los
 * destinatarios (pública de escaneo, pública de gasto) en el orden de las salidas. Devuelve las claves
 * x-only de las salidas P2TR, en ese mismo orden. Los destinatarios con la misma clave de escaneo
 * comparten secreto y van numerados k = 0, 1, … en el orden en que aparecen. */
struct Destinatario { uint8_t scan[33]; uint8_t spend[33]; };

inline bool sp_salidas(const secp256k1_context *ctx, const uint8_t a[32], const uint8_t outpointMin[36],
                       const std::vector<Destinatario> &dest, std::vector<std::vector<uint8_t>> &out,
                       uint8_t *compartidoPrimero = nullptr) {
    out.clear();
    uint8_t A[33]; if (!pub_de_priv(ctx, a, A)) return false;
    uint8_t m[36 + 33]; memcpy(m, outpointMin, 36); memcpy(m + 36, A, 33);
    uint8_t ih[32]; tagged("BIP0352/Inputs", m, sizeof m, ih);
    uint8_t ia[32]; if (!priv_mul(ctx, a, ih, ia)) return false;          /* input_hash · a */
    std::vector<std::vector<uint8_t>> scans; std::vector<uint32_t> cuenta;
    for (const auto &d : dest) {
        std::vector<uint8_t> s(d.scan, d.scan + 33);
        size_t g = std::find(scans.begin(), scans.end(), s) - scans.begin();
        if (g == scans.size()) { scans.push_back(s); cuenta.push_back(0); }
        uint8_t ecdh[33]; if (!pub_mul(ctx, d.scan, ia, ecdh)) return false;
        if (compartidoPrimero && out.empty()) memcpy(compartidoPrimero, ecdh, 33);
        /* BIP352: como mucho K_max = 2323 salidas por clave de escaneo (el receptor no mira más allá). */
        if (cuenta[g] >= 2323) return false;
        uint32_t k = cuenta[g]++;
        uint8_t ss[37]; memcpy(ss, ecdh, 33);
        ss[33] = (uint8_t)(k >> 24); ss[34] = (uint8_t)(k >> 16); ss[35] = (uint8_t)(k >> 8); ss[36] = (uint8_t)k;
        uint8_t t[32]; tagged("BIP0352/SharedSecret", ss, sizeof ss, t);
        uint8_t P[33]; if (!pub_add_tweak(ctx, d.spend, t, P)) return false;
        out.emplace_back(P + 1, P + 33);
    }
    return true;
}

} // namespace ecops
