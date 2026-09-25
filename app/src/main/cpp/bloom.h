#pragma once
/* Bloom filter para 55M entradas, ~1% FP rate
   RAM: 55M * 10 bits = ~68MB
   Evita bsearch (cache miss en 1.1GB) para el 99% de claves */
#include <stdint.h>
#include <string.h>
#include <stdlib.h>

/* v -> [0, nbits) con una multiplicacion en vez de una division de 64 bits
 * (la "reduccion rapida" de Lemire). Se usa igual al construir y al consultar,
 * y el filtro se construye al cargar la lista, asi que no hay nada guardado
 * con la cuenta de antes. */
static inline uint64_t bloom_rango(uint64_t v, uint64_t nbits){
    return (uint64_t)(((__uint128_t)v * nbits) >> 64);
}

typedef struct {
    uint8_t *bits;
    uint64_t nbits;
    uint64_t nset;
} Bloom;

static inline uint64_t bloom_hash1(const uint8_t *h, uint64_t nbits) {
    uint64_t v;
    memcpy(&v, h, 8);
    v ^= v >> 33; v *= 0xff51afd7ed558ccdULL; v ^= v >> 33;
    return bloom_rango(v, nbits);
}
static inline uint64_t bloom_hash2(const uint8_t *h, uint64_t nbits) {
    uint64_t v;
    memcpy(&v, h + 4, 8);
    v ^= v >> 33; v *= 0xc4ceb9fe1a85ec53ULL; v ^= v >> 33;
    return bloom_rango(v, nbits);
}
static inline uint64_t bloom_hash3(const uint8_t *h, uint64_t nbits) {
    uint64_t v;
    memcpy(&v, h + 8, 8);
    v ^= v >> 33; v *= 0x9e3779b97f4a7c15ULL; v ^= v >> 33;
    return bloom_rango(v, nbits);
}
static inline uint64_t bloom_hash4(const uint8_t *h, uint64_t nbits) {
    uint64_t v;
    memcpy(&v, h + 12, 8);
    v ^= v >> 33; v *= 0x6c62272e07bb0142ULL; v ^= v >> 33;
    return bloom_rango(v, nbits);
}

static inline void bloom_set(Bloom *b, const uint8_t *h160) {
    uint64_t i1=bloom_hash1(h160,b->nbits);
    uint64_t i2=bloom_hash2(h160,b->nbits);
    uint64_t i3=bloom_hash3(h160,b->nbits);
    uint64_t i4=bloom_hash4(h160,b->nbits);
    b->bits[i1>>3]|=(1<<(i1&7));
    b->bits[i2>>3]|=(1<<(i2&7));
    b->bits[i3>>3]|=(1<<(i3&7));
    b->bits[i4>>3]|=(1<<(i4&7));
    b->nset++;
}

static inline int bloom_check(const Bloom *b, const uint8_t *h160) {
    if(!b->bits) return 1; /* not ready, allow through */
    uint64_t i1=bloom_hash1(h160,b->nbits);
    if(!(b->bits[i1>>3]&(1<<(i1&7)))) return 0;
    uint64_t i2=bloom_hash2(h160,b->nbits);
    if(!(b->bits[i2>>3]&(1<<(i2&7)))) return 0;
    uint64_t i3=bloom_hash3(h160,b->nbits);
    if(!(b->bits[i3>>3]&(1<<(i3&7)))) return 0;
    uint64_t i4=bloom_hash4(h160,b->nbits);
    if(!(b->bits[i4>>3]&(1<<(i4&7)))) return 0;
    return 1;
}

static Bloom bloom_create(uint64_t n_entries) {
    Bloom b;
    b.nbits = n_entries * 10; /* 10 bits per entry ~1% FP */
    uint64_t nbytes = (b.nbits + 7) / 8;
    b.bits = (uint8_t*)calloc(nbytes, 1);
    b.nset = 0;
    return b;
}

static void bloom_free(Bloom *b) {
    if(b->bits) { free(b->bits); b->bits=nullptr; }
    b->nbits=0; b->nset=0;
}
