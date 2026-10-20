#pragma once
/* SipHash-2-4 (Aumasson y Bernstein). Lo usa el filtro de bloque de BIP158 para
 * repartir cada script en el intervalo del filtro. Sin dependencias. */
#include <stdint.h>
#include <stddef.h>

static inline uint64_t sip_rotl(uint64_t x, int b) { return (x << b) | (x >> (64 - b)); }

static inline uint64_t sip_le64(const uint8_t *p) {
    uint64_t v = 0;
    for (int i = 7; i >= 0; i--) v = (v << 8) | p[i];
    return v;
}

static uint64_t siphash24(uint64_t k0, uint64_t k1, const uint8_t *in, size_t n) {
    uint64_t v0 = 0x736f6d6570736575ULL ^ k0;
    uint64_t v1 = 0x646f72616e646f6dULL ^ k1;
    uint64_t v2 = 0x6c7967656e657261ULL ^ k0;
    uint64_t v3 = 0x7465646279746573ULL ^ k1;
    auto round = [&]() {
        v0 += v1; v1 = sip_rotl(v1, 13); v1 ^= v0; v0 = sip_rotl(v0, 32);
        v2 += v3; v3 = sip_rotl(v3, 16); v3 ^= v2;
        v0 += v3; v3 = sip_rotl(v3, 21); v3 ^= v0;
        v2 += v1; v1 = sip_rotl(v1, 17); v1 ^= v2; v2 = sip_rotl(v2, 32);
    };
    size_t i = 0;
    for (; i + 8 <= n; i += 8) {
        uint64_t m = sip_le64(in + i);
        v3 ^= m; round(); round(); v0 ^= m;
    }
    uint64_t b = ((uint64_t)n) << 56;
    for (size_t j = 0; i + j < n; j++) b |= ((uint64_t)in[i + j]) << (8 * j);
    v3 ^= b; round(); round(); v0 ^= b;
    v2 ^= 0xff; round(); round(); round(); round();
    return v0 ^ v1 ^ v2 ^ v3;
}
