#pragma once
/* EXPERIMENTAL — multiplicación de cuerpo con NEON, 2 a la vez.
 *
 * NEON (AArch64) no tiene multiplicación 64x64, solo 32x32->64 (vmull_u32, dos
 * carriles). Así que el número se trocea en 10 limbs de 28 bits y se procesan
 * DOS elementos de cuerpo en paralelo (un carril cada uno). El producto (la
 * parte cara) va en NEON; la reducción mod p se reutiliza del escalar
 * (fe_reduce8), que ya está probada.
 *
 * Es un BANCO para medir en el móvil si NEON gana al umulh escalar; NO está en
 * el hot loop. La corrección se verifica en qemu (tools/ec-harness/neon.cpp)
 * contra fe_mul_c. */
#include "jac_batch.h"

#if defined(__aarch64__)
#include <arm_neon.h>

/* fe_t (4x64) -> 10 limbs de 28 bits. El valor es < 2^256, así que el limb 9
 * solo tiene 4 bits reales. */
static inline void fe_to28(const uint64_t a[4], uint32_t l[10]){
    for(int k=0;k<10;k++){
        int bit=28*k, w=bit>>6, off=bit&63;
        uint64_t v = a[w] >> off;
        if(off > 64-28 && w<3) v |= a[w+1] << (64-off);
        l[k] = (uint32_t)(v & 0x0FFFFFFFu);
    }
}

/* 19 limbs de 28 bits (producto, < 2^512) -> t[8] de 64 bits, y reduce. */
static inline void fe_from28_reduce(const uint64_t p[19], fe_t r){
    uint64_t t[9]={0};
    int bit=0;
    for(int k=0;k<19;k++){
        int w=bit>>6, off=bit&63;
        unsigned __int128 v=(unsigned __int128)p[k] << off;
        unsigned __int128 s=(unsigned __int128)t[w] + (uint64_t)v;
        t[w]=(uint64_t)s;
        uint64_t c=(uint64_t)(s>>64) + (uint64_t)(v>>64);
        int ww=w+1;
        while(c && ww<9){ unsigned __int128 s2=(unsigned __int128)t[ww]+c; t[ww]=(uint64_t)s2; c=(uint64_t)(s2>>64); ww++; }
        bit+=28;
    }
    fe_reduce8(r, t);   /* t[8] = 0: el producto cabe en 512 bits */
}

/* r0 = a0*b0 y r1 = a1*b1, los dos a la vez. */
static inline void fe_mul2(fe_t r0,const fe_t a0,const fe_t b0,
                           fe_t r1,const fe_t a1,const fe_t b1){
    uint32_t a0l[10],a1l[10],b0l[10],b1l[10];
    fe_to28(a0,a0l); fe_to28(a1,a1l); fe_to28(b0,b0l); fe_to28(b1,b1l);
    uint32x2_t la[10], lb[10];
    for(int k=0;k<10;k++){
        la[k]=vset_lane_u32(a1l[k], vdup_n_u32(a0l[k]), 1);
        lb[k]=vset_lane_u32(b1l[k], vdup_n_u32(b0l[k]), 1);
    }
    uint64x2_t acc[19];
    for(int k=0;k<19;k++) acc[k]=vdupq_n_u64(0);
    for(int i=0;i<10;i++)
        for(int j=0;j<10;j++)
            acc[i+j]=vaddq_u64(acc[i+j], vmull_u32(la[i], lb[j]));
    /* Normalizar a limbs de 28 bits, por carril (los carriles son independientes). */
    const uint64x2_t mask=vdupq_n_u64(0x0FFFFFFFu);
    uint64x2_t carry=vdupq_n_u64(0);
    uint64_t p0[19], p1[19];
    for(int k=0;k<19;k++){
        uint64x2_t v=vaddq_u64(acc[k], carry);
        carry=vshrq_n_u64(v,28);
        uint64x2_t low=vandq_u64(v,mask);
        p0[k]=vgetq_lane_u64(low,0);
        p1[k]=vgetq_lane_u64(low,1);
    }
    fe_from28_reduce(p0, r0);
    fe_from28_reduce(p1, r1);
}
#endif
