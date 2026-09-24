#pragma once
/* Multiplicacion de cuerpo en ensamblador ARM64, para medir contra la de C.
 *
 * El producto de 256x256 bits a 512 se hace a mano con mul/umulh y cadenas de
 * adds/adcs, fila a fila. La reduccion modulo p sigue siendo la de C
 * (fe_reduce8): es la parte con mas casos raros y la que ya esta probada.
 *
 * NO esta en el bucle del motor. Existe para que la prueba de rendimiento de
 * la app (Debug) la compare con fe_mul en el propio movil: en C, clang ya
 * traduce __uint128_t a mul + umulh, asi que la ganancia no se puede dar por
 * hecha. Se decide con numeros de un movil de verdad.
 *
 * Comprobada contra fe_mul con qemu-aarch64: tools/ec-harness/fe_arm64.cpp. */
#include "jac_batch.h"

#if defined(__aarch64__)
#define FE_ARM64 1

/* Una fila: t[i..i+4] += a * b[0..3]. Para i=0, t[0..4] = a*b. */
#define FE_FILA(ai, T0, T1, T2, T3, T4, PRIMERA)                              \
    asm volatile(                                                             \
        "mul   %[l0], %[a], %[b0]\n\t"                                        \
        "umulh %[h0], %[a], %[b0]\n\t"                                        \
        "mul   %[l1], %[a], %[b1]\n\t"                                        \
        "umulh %[h1], %[a], %[b1]\n\t"                                        \
        "mul   %[l2], %[a], %[b2]\n\t"                                        \
        "umulh %[h2], %[a], %[b2]\n\t"                                        \
        "mul   %[l3], %[a], %[b3]\n\t"                                        \
        "umulh %[h3], %[a], %[b3]\n\t"                                        \
        "adds  %[l1], %[l1], %[h0]\n\t"                                       \
        "adcs  %[l2], %[l2], %[h1]\n\t"                                       \
        "adcs  %[l3], %[l3], %[h2]\n\t"                                       \
        "adc   %[h3], %[h3], xzr\n\t"                                         \
        : [l0]"=&r"(l0),[l1]"=&r"(l1),[l2]"=&r"(l2),[l3]"=&r"(l3),            \
          [h0]"=&r"(h0),[h1]"=&r"(h1),[h2]"=&r"(h2),[h3]"=&r"(h3)             \
        : [a]"r"(ai),[b0]"r"(b[0]),[b1]"r"(b[1]),[b2]"r"(b[2]),[b3]"r"(b[3])  \
        : "cc");                                                              \
    if(PRIMERA){ T0=l0; T1=l1; T2=l2; T3=l3; T4=h3; }                         \
    else asm volatile(                                                        \
        "adds  %[t0], %[t0], %[l0]\n\t"                                       \
        "adcs  %[t1], %[t1], %[l1]\n\t"                                       \
        "adcs  %[t2], %[t2], %[l2]\n\t"                                       \
        "adcs  %[t3], %[t3], %[l3]\n\t"                                       \
        "adc   %[t4], %[h3], xzr\n\t"                                         \
        : [t0]"+r"(T0),[t1]"+r"(T1),[t2]"+r"(T2),[t3]"+r"(T3),[t4]"=r"(T4)    \
        : [l0]"r"(l0),[l1]"r"(l1),[l2]"r"(l2),[l3]"r"(l3),[h3]"r"(h3)         \
        : "cc");

static inline void fe_mul_asm(fe_t r,const fe_t a,const fe_t b){
    uint64_t t[8];
    uint64_t l0,l1,l2,l3,h0,h1,h2,h3;
    FE_FILA(a[0], t[0],t[1],t[2],t[3],t[4], 1)
    FE_FILA(a[1], t[1],t[2],t[3],t[4],t[5], 0)
    FE_FILA(a[2], t[2],t[3],t[4],t[5],t[6], 0)
    FE_FILA(a[3], t[3],t[4],t[5],t[6],t[7], 0)
    fe_reduce8(r,t);
}
#undef FE_FILA
#endif
