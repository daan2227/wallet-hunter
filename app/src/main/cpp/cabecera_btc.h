#pragma once
/* Cabeceras de bloque: hash, proof of work y encadenado. Sin red.
 * Los hashes se guardan en orden interno (el de la serialización); el orden
 * "de mostrar" es el inverso, como en los exploradores. */
#include <stdint.h>
#include <string.h>
#include <stdexcept>
#include <vector>
#include <openssl/sha.h>

namespace btc {

struct Cabecera {
    int32_t version; uint8_t prev[32]; uint8_t raiz[32];
    uint32_t tiempo, bits, nonce;
};

static void dsha(const uint8_t *p, size_t n, uint8_t out[32]) {
    uint8_t t[32]; SHA256(p, n, t); SHA256(t, 32, out);
}

static Cabecera parsear_cabecera(const uint8_t *p, size_t n) {
    if (n < 80) throw std::runtime_error("cabecera corta");
    Cabecera c;
    auto u32 = [&](size_t o) { return (uint32_t)(p[o] | (p[o+1] << 8) | (p[o+2] << 16) | ((uint32_t)p[o+3] << 24)); };
    c.version = (int32_t)u32(0);
    memcpy(c.prev, p + 4, 32); memcpy(c.raiz, p + 36, 32);
    c.tiempo = u32(68); c.bits = u32(72); c.nonce = u32(76);
    return c;
}

/* Hash de la cabecera (orden interno). */
static void hash_cabecera(const uint8_t *p, uint8_t out[32]) { dsha(p, 80, out); }

/* Objetivo (256 bits, big-endian) de un campo nBits. false si el formato no vale
 * (negativo o desbordado): una cabecera así no puede tener prueba de trabajo. */
static bool bits_a_objetivo(uint32_t bits, uint8_t t[32]) {
    uint32_t exp = bits >> 24, mant = bits & 0x007fffff;
    if ((bits & 0x00800000) && mant) return false;
    if (exp > 32) return false;
    memset(t, 0, 32);
    if (exp <= 3) {
        mant >>= 8 * (3 - exp);
        t[31] = (uint8_t)mant; t[30] = (uint8_t)(mant >> 8); t[29] = (uint8_t)(mant >> 16);
    } else {
        int pos = 32 - (int)exp;
        t[pos] = (uint8_t)(mant >> 16); t[pos+1] = (uint8_t)(mant >> 8); t[pos+2] = (uint8_t)mant;
    }
    return true;
}

/* Prueba de trabajo: el hash (como número, big-endian) no puede pasar el objetivo. */
static bool prueba_de_trabajo(const uint8_t *cab80) {
    Cabecera c = parsear_cabecera(cab80, 80);
    uint8_t t[32];
    if (!bits_a_objetivo(c.bits, t)) return false;
    uint8_t h[32]; hash_cabecera(cab80, h);
    uint8_t mostrar[32];
    for (int i = 0; i < 32; i++) mostrar[i] = h[31 - i];
    return memcmp(mostrar, t, 32) <= 0;
}

/* Cabecera de filtro (BIP157): dsha(hash_del_filtro || cabecera_anterior). Todo en
 * orden interno. */
static void cabecera_de_filtro(const std::vector<uint8_t> &filtro, const uint8_t prev[32], uint8_t out[32]) {
    uint8_t fh[32]; dsha(filtro.data(), filtro.size(), fh);
    uint8_t buf[64]; memcpy(buf, fh, 32); memcpy(buf + 32, prev, 32);
    dsha(buf, 64, out);
}

}  // namespace btc
