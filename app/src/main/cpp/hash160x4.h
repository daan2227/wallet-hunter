#pragma once
/* Cuatro hash160 a la vez.
 *
 * En el escaner de claves, con la curva hecha por lotes y el endomorfismo, casi
 * todo el tiempo es hash160: SHA-256 y luego RIPEMD-160 de cada clave publica.
 *
 * - SHA-256: las cuatro con las instrucciones del procesador, ronda a ronda
 *   entrelazadas. Cada sha256h tarda varios ciclos en dar su resultado y la
 *   ronda siguiente lo necesita; con cuatro cadenas independientes esa espera
 *   se llena con las otras tres.
 * - RIPEMD-160: no tiene instrucciones propias, pero son operaciones de 32 bits
 *   y un registro NEON tiene cuatro: cada instruccion avanza las cuatro.
 *
 * Mismo resultado que hash160_inline (tools/ec-harness/hash160x4.cpp, con qemu).
 * Donde no hay NEON con SHA-256, es hash160_inline cuatro veces. */
#include "sha256_ripemd160.h"

#if defined(__aarch64__) && defined(SHA256_HW)
#define HASH160_X4 1

static inline void hash160_x4(const uint8_t *const pub[4], uint8_t out[4][20]){
    static const uint32_t IV[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    const uint32x4_t IV0=vld1q_u32(IV), IV1=vld1q_u32(IV+4);
    uint32x4_t S0[4],S1[4],M0[4],M1[4],M2[4],M3[4];
    for(int h=0;h<4;h++){
        uint8_t blk[64]={0};
        memcpy(blk,pub[h],33); blk[33]=0x80; blk[62]=0x01; blk[63]=0x08;
        M0[h]=vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(blk)));
        M1[h]=vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(blk+16)));
        M2[h]=vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(blk+32)));
        M3[h]=vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(blk+48)));
        S0[h]=IV0; S1[h]=IV1;
    }
#define SHA4_R(Ma,Mb,Mc,Md,k) { const uint32_t *K_=&K256[k]; \
    for(int h=0;h<4;h++){ uint32x4_t T0=vaddq_u32(Ma[h],vld1q_u32(K_)); uint32x4_t T2=S0[h]; \
        Ma[h]=vsha256su0q_u32(Ma[h],Mb[h]); \
        S0[h]=vsha256hq_u32(S0[h],S1[h],T0); S1[h]=vsha256h2q_u32(S1[h],T2,T0); \
        Ma[h]=vsha256su1q_u32(Ma[h],Mc[h],Md[h]); } }
#define SHA4_F(Ma,k) { const uint32_t *K_=&K256[k]; \
    for(int h=0;h<4;h++){ uint32x4_t T0=vaddq_u32(Ma[h],vld1q_u32(K_)); uint32x4_t T2=S0[h]; \
        S0[h]=vsha256hq_u32(S0[h],S1[h],T0); S1[h]=vsha256h2q_u32(S1[h],T2,T0); } }
    SHA4_R(M0,M1,M2,M3, 0) SHA4_R(M1,M2,M3,M0, 4) SHA4_R(M2,M3,M0,M1, 8) SHA4_R(M3,M0,M1,M2,12)
    SHA4_R(M0,M1,M2,M3,16) SHA4_R(M1,M2,M3,M0,20) SHA4_R(M2,M3,M0,M1,24) SHA4_R(M3,M0,M1,M2,28)
    SHA4_R(M0,M1,M2,M3,32) SHA4_R(M1,M2,M3,M0,36) SHA4_R(M2,M3,M0,M1,40) SHA4_R(M3,M0,M1,M2,44)
    SHA4_F(M0,48) SHA4_F(M1,52) SHA4_F(M2,56) SHA4_F(M3,60)
#undef SHA4_R
#undef SHA4_F
    /* Trasponer: de "cuatro palabras de un hash" a "la misma palabra de
       cuatro hashes", que es como las quiere RIPEMD-160 en NEON. */
    uint32_t lo[16],hi[16];
    for(int h=0;h<4;h++){ vst1q_u32(lo+4*h,vaddq_u32(S0[h],IV0)); vst1q_u32(hi+4*h,vaddq_u32(S1[h],IV1)); }
    uint32x4x4_t L=vld4q_u32(lo), H=vld4q_u32(hi);
#define BSW(v) vreinterpretq_u32_u8(vrev32q_u8(vreinterpretq_u8_u32(v)))
    const uint32x4_t X0=BSW(L.val[0]),X1=BSW(L.val[1]),X2=BSW(L.val[2]),X3=BSW(L.val[3]);
    const uint32x4_t X4=BSW(H.val[0]),X5=BSW(H.val[1]),X6=BSW(H.val[2]),X7=BSW(H.val[3]);
#undef BSW
    const uint32x4_t X8=vdupq_n_u32(0x80),X9=vdupq_n_u32(0),X10=X9,X11=X9,X12=X9,X13=X9,
                     X14=vdupq_n_u32(256),X15=X9;
    uint32x4_t al=vdupq_n_u32(0x67452301),bl=vdupq_n_u32(0xEFCDAB89),cl=vdupq_n_u32(0x98BADCFE),
               dl=vdupq_n_u32(0x10325476),el=vdupq_n_u32(0xC3D2E1F0);
    uint32x4_t ar=al,br=bl,cr=cl,dr=dl,er=el;
#define V_ROL(x,s) vsriq_n_u32(vshlq_n_u32(x,s),x,32-(s))
#define V_F1(x,y,z) veorq_u32(veorq_u32(x,y),z)
#define V_F2(x,y,z) vbslq_u32(x,y,z)
#define V_F3(x,y,z) veorq_u32(vornq_u32(x,y),z)
#define V_F4(x,y,z) vbslq_u32(z,x,y)
#define V_F5(x,y,z) veorq_u32(x,vornq_u32(y,z))
#undef RMD_P
#define RMD_P(F,a,b,c,d,e,x,k,s) { a=vaddq_u32(vaddq_u32(a,V_##F(b,c,d)),vaddq_u32(x,vdupq_n_u32(k))); \
                                   a=vaddq_u32(V_ROL(a,s),e); c=V_ROL(c,10); }
    RMD_RONDAS
#undef RMD_P
#define RMD_P(F,a,b,c,d,e,x,k,s) { a += F(b,c,d) + (x) + (k); a = ROL32(a,s) + e; c = ROL32(c,10); }
#undef V_ROL
#undef V_F1
#undef V_F2
#undef V_F3
#undef V_F4
#undef V_F5
    uint32x4x4_t R;
    R.val[0]=vaddq_u32(vdupq_n_u32(0xEFCDAB89),vaddq_u32(cl,dr));
    R.val[1]=vaddq_u32(vdupq_n_u32(0x98BADCFE),vaddq_u32(dl,er));
    R.val[2]=vaddq_u32(vdupq_n_u32(0x10325476),vaddq_u32(el,ar));
    R.val[3]=vaddq_u32(vdupq_n_u32(0xC3D2E1F0),vaddq_u32(al,br));
    uint32x4_t R4=vaddq_u32(vdupq_n_u32(0x67452301),vaddq_u32(bl,cr));
    uint32_t o[16],o4[4];
    vst4q_u32(o,R); vst1q_u32(o4,R4);
    for(int h=0;h<4;h++){ memcpy(out[h],o+4*h,16); memcpy(out[h]+16,o4+h,4); }
}
#else
static inline void hash160_x4(const uint8_t *const pub[4], uint8_t out[4][20]){
    for(int h=0;h<4;h++) hash160_inline(pub[h],out[h]);
}
#endif
