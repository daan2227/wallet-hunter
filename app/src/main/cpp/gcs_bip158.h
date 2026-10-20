#pragma once
/* Filtro GCS (Golomb-Rice) de BIP158, el "basic filter" de los bloques.
 *
 * Un filtro es un conjunto de elementos (scripts) codificado en pocos bytes. Se
 * comprueba si un script está en el bloque sin bajar el bloque. Puede dar
 * falsos positivos (con probabilidad ~1/M por elemento), nunca falsos negativos.
 * Para la cartera: si el filtro coincide, se pide el bloque completo; si no, el
 * bloque no tiene nada nuestro.
 */
#include <stdint.h>
#include <string.h>
#include <algorithm>
#include <vector>
#include <stdexcept>
#include "siphash.h"

namespace gcs {

const int P = 19;               /* bits del resto de Golomb-Rice */
const uint64_t M = 784931;      /* tasa de falsos positivos: 1/M */

/* Reparto uniforme de x en [0, n): (x * n) >> 64, sin sesgo apreciable. */
static inline uint64_t rango(uint64_t x, uint64_t n) {
    return (uint64_t)(((unsigned __int128)x * n) >> 64);
}

/* Valor de un elemento dentro del filtro: SipHash con la clave del bloque. */
static inline uint64_t valor(uint64_t k0, uint64_t k1, const std::vector<uint8_t> &e, uint64_t F) {
    return rango(siphash24(k0, k1, e.data(), e.size()), F);
}

struct Escritor {
    std::vector<uint8_t> bytes; int bit = 0;
    void bit1(int b) {
        if (bit == 0) bytes.push_back(0);
        if (b) bytes.back() |= (uint8_t)(0x80 >> bit);
        bit = (bit + 1) & 7;
    }
    void bits(uint64_t v, int n) { for (int i = n - 1; i >= 0; i--) bit1((v >> i) & 1); }
};

struct Lector {
    const uint8_t *p; size_t n; size_t pos = 0;   /* pos en bits */
    int bit() {
        if (pos >= n * 8) throw std::runtime_error("filtro cortado");
        int b = (p[pos >> 3] >> (7 - (pos & 7))) & 1; pos++; return b;
    }
    uint64_t bits(int k) { uint64_t v = 0; for (int i = 0; i < k; i++) v = (v << 1) | bit(); return v; }
};

/* Varint de Bitcoin (CompactSize). */
static void varint_put(std::vector<uint8_t> &o, uint64_t v) {
    if (v < 0xfd) { o.push_back((uint8_t)v); }
    else if (v <= 0xffff) { o.push_back(0xfd); o.push_back((uint8_t)v); o.push_back((uint8_t)(v >> 8)); }
    else if (v <= 0xffffffffULL) { o.push_back(0xfe); for (int i = 0; i < 4; i++) o.push_back((uint8_t)(v >> (8 * i))); }
    else { o.push_back(0xff); for (int i = 0; i < 8; i++) o.push_back((uint8_t)(v >> (8 * i))); }
}

static uint64_t varint_get(const uint8_t *p, size_t n, size_t &pos) {
    if (pos >= n) throw std::runtime_error("varint cortado");
    uint8_t f = p[pos++];
    int k = f == 0xfd ? 2 : f == 0xfe ? 4 : f == 0xff ? 8 : 0;
    if (k == 0) return f;
    if (pos + k > n) throw std::runtime_error("varint cortado");
    uint64_t v = 0;
    for (int i = k - 1; i >= 0; i--) v = (v << 8) | p[pos + i];
    pos += k;
    return v;
}

/* Filtro de un conjunto de elementos (ya sin repetidos NI vacíos: quien llama los
 * filtra). Devuelve los bytes del filtro: varint(N) seguido de los bits. */
static std::vector<uint8_t> construir(const std::vector<std::vector<uint8_t>> &elementos,
                                      uint64_t k0, uint64_t k1) {
    std::vector<uint8_t> out;
    /* Un conjunto: cada script cuenta una vez (BIP158). Un mismo script de salida
       repetido no añade un elemento más. */
    std::vector<std::vector<uint8_t>> unicos(elementos);
    std::sort(unicos.begin(), unicos.end());
    unicos.erase(std::unique(unicos.begin(), unicos.end()), unicos.end());
    uint64_t N = unicos.size();
    varint_put(out, N);
    if (N == 0) return out;
    uint64_t F = N * M;
    std::vector<uint64_t> v;
    v.reserve(N);
    for (auto &e : unicos) v.push_back(valor(k0, k1, e, F));
    std::sort(v.begin(), v.end());
    v.erase(std::unique(v.begin(), v.end()), v.end());
    Escritor w;
    uint64_t last = 0;
    for (uint64_t x : v) {
        uint64_t delta = x - last;
        uint64_t q = delta >> P;
        for (uint64_t i = 0; i < q; i++) w.bit1(1);
        w.bit1(0);
        w.bits(delta & ((1ULL << P) - 1), P);
        last = x;
    }
    out.insert(out.end(), w.bytes.begin(), w.bytes.end());
    return out;
}

/* ¿Está [elemento] en el filtro? Decodifica una vez y compara. Un filtro roto
 * (cortado, o con N que no cuadra) lanza excepción: quien llama debe tomarlo
 * como "no lo sé", no como "no está". */
static bool contiene(const std::vector<uint8_t> &filtro, uint64_t k0, uint64_t k1,
                     const std::vector<uint8_t> &elemento) {
    size_t pos = 0;
    uint64_t N = varint_get(filtro.data(), filtro.size(), pos);
    if (N == 0) return false;
    uint64_t F = N * M;
    uint64_t objetivo = valor(k0, k1, elemento, F);
    Lector r{filtro.data() + pos, filtro.size() - pos};
    uint64_t x = 0;
    for (uint64_t i = 0; i < N; i++) {
        uint64_t q = 0;
        while (r.bit() == 1) q++;
        uint64_t delta = (q << P) | r.bits(P);
        x += delta;
        if (x == objetivo) return true;
        if (x > objetivo) return false;   /* están ordenados: ya no puede estar */
    }
    return false;
}

}  // namespace gcs
