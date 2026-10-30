#pragma once
/* Minero: doble SHA-256 de una cabecera de 80 bytes probando nonces, con el "midstate" (la compresión de
 * los primeros 64 bytes, que no cambian, se hace una vez) y las instrucciones SHA-256 del procesador si
 * las tiene (sha256_block_hw, la misma que usa hash160). Por hash: dos compresiones.
 *
 * El hash "como se escribe" (el de los exploradores) es el digest al revés: su palabra más alta es la
 * última del estado con los bytes dados la vuelta. Probado contra el bloque 1 en tools/ec-harness/minero.cpp
 * (con la compresión de software; la de hardware está probada aparte en sha256_hw.cpp). */
#include "sha256_ripemd160.h"

static const uint32_t MIN_H0[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};

static inline uint32_t min_bswap(uint32_t x){ return __builtin_bswap32(x); }

struct MinResultado {
    int64_t encontrado;   /* nonce que cumple el objetivo, o -1 */
    int mejor_ceros;      /* ceros del mejor hash de la tanda */
    uint32_t mejor_nonce;
};

/* ¿hash (estado final) ≤ objetivo? objetivo: 8 palabras, la más significativa primero. */
static inline int min_cumple(const uint32_t *st, const uint32_t *obj){
    for(int i=0;i<8;i++){
        uint32_t v=min_bswap(st[7-i]);
        if(v<obj[i]) return 1;
        if(v>obj[i]) return 0;
    }
    return 1;
}

static inline int min_ceros(const uint32_t *st){
    int n=0;
    for(int i=7;i>=0;i--){ uint32_t v=min_bswap(st[i]); if(v==0) n+=32; else return n+__builtin_clz(v); }
    return n;
}

/* Prueba [cuantos] nonces desde [desde] (sin pasar de 0xffffffff). BLOQUE es la compresión a usar. */
template<void (*BLOQUE)(uint32_t*, const uint8_t*)>
static uint64_t min_tanda(const uint8_t *cab, uint32_t desde, uint32_t cuantos, const uint32_t *obj, MinResultado *r){
    uint32_t mid[8]; memcpy(mid,MIN_H0,32); BLOQUE(mid,cab);
    uint8_t b1[64]; memset(b1,0,64); memcpy(b1,cab+64,16); b1[16]=0x80; b1[62]=0x02; b1[63]=0x80;      /* 640 bits */
    uint8_t b2[64]; memset(b2,0,64); b2[32]=0x80; b2[62]=0x01; b2[63]=0x00;                           /* 256 bits */
    r->encontrado=-1; r->mejor_ceros=-1; r->mejor_nonce=desde;
    uint64_t hechos=0;
    uint32_t n=desde;
    for(uint32_t k=0;k<cuantos;k++,n++){
        b1[12]=(uint8_t)n; b1[13]=(uint8_t)(n>>8); b1[14]=(uint8_t)(n>>16); b1[15]=(uint8_t)(n>>24);
        uint32_t s1[8]; memcpy(s1,mid,32); BLOQUE(s1,b1);
        for(int i=0;i<8;i++){ b2[4*i]=(uint8_t)(s1[i]>>24); b2[4*i+1]=(uint8_t)(s1[i]>>16); b2[4*i+2]=(uint8_t)(s1[i]>>8); b2[4*i+3]=(uint8_t)s1[i]; }
        uint32_t s2[8]; memcpy(s2,MIN_H0,32); BLOQUE(s2,b2);
        hechos++;
        /* Casi nunca hay ceros: se mira primero la palabra alta, que es lo barato. */
        uint32_t alta=min_bswap(s2[7]);
        int z = alta ? __builtin_clz(alta) : min_ceros(s2);
        if(z>r->mejor_ceros){ r->mejor_ceros=z; r->mejor_nonce=n; }
        if(alta<=obj[0] && min_cumple(s2,obj)){ r->encontrado=n; break; }
        if(n==0xffffffffu) break;
    }
    return hechos;
}

static inline uint64_t min_tanda_auto(const uint8_t *cab, uint32_t desde, uint32_t cuantos, const uint32_t *obj, MinResultado *r){
#ifdef SHA256_HW
    if(sha256_tiene_hw()) return min_tanda<sha256_block_hw>(cab,desde,cuantos,obj,r);
#endif
    return min_tanda<sha256_block>(cab,desde,cuantos,obj,r);
}
