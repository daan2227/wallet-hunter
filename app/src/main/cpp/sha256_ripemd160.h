#pragma once
#include <stdint.h>
#include <string.h>

/* =========================================================
   SHA256 inline optimizado para ARM
   ========================================================= */
#define ROR32(x,n) (((x)>>(n))|((x)<<(32-(n))))
#define CH(x,y,z)  (((x)&(y))^(~(x)&(z)))
#define MAJ(x,y,z) (((x)&(y))^((x)&(z))^((y)&(z)))
#define EP0(x)  (ROR32(x,2)^ROR32(x,13)^ROR32(x,22))
#define EP1(x)  (ROR32(x,6)^ROR32(x,11)^ROR32(x,25))
#define SIG0(x) (ROR32(x,7)^ROR32(x,18)^((x)>>3))
#define SIG1(x) (ROR32(x,17)^ROR32(x,19)^((x)>>10))

static const uint32_t K256[64]={
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

static inline void sha256_block(uint32_t *st, const uint8_t *data){
    uint32_t w[64],a,b,c,d,e,f,g,h,t1,t2;
    for(int i=0;i<16;i++) w[i]=((uint32_t)data[i*4]<<24)|((uint32_t)data[i*4+1]<<16)|((uint32_t)data[i*4+2]<<8)|data[i*4+3];
    for(int i=16;i<64;i++) w[i]=SIG1(w[i-2])+w[i-7]+SIG0(w[i-15])+w[i-16];
    a=st[0];b=st[1];c=st[2];d=st[3];e=st[4];f=st[5];g=st[6];h=st[7];
    for(int i=0;i<64;i++){
        t1=h+EP1(e)+CH(e,f,g)+K256[i]+w[i];
        t2=EP0(a)+MAJ(a,b,c);
        h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
    }
    st[0]+=a;st[1]+=b;st[2]+=c;st[3]+=d;st[4]+=e;st[5]+=f;st[6]+=g;st[7]+=h;
}

#if defined(__aarch64__) && (defined(__ARM_FEATURE_SHA2) || defined(__ARM_FEATURE_CRYPTO))
#include <arm_neon.h>
#define SHA256_HW 1
/* Un bloque de SHA-256 con las instrucciones del procesador (ARMv8 Crypto:
 * sha256h, sha256h2, sha256su0, sha256su1). Las tienen todos los ARMv8 de
 * movil de la ultima decada, y el proyecto ya compila con
 * -march=armv8-a+crypto (CMakeLists.txt).
 *
 * La version de software de arriba hace las 64 rondas a mano; en la fuerza
 * bruta el hash160 es la mitad del tiempo por clave, y SHA-256 la mayor
 * parte de eso. Comprobada contra la de software con qemu-aarch64
 * (tools/ec-harness/sha256_hw.cpp). */
static inline void sha256_block_hw(uint32_t *st, const uint8_t *data){
    uint32x4_t S0=vld1q_u32(&st[0]), S1=vld1q_u32(&st[4]);
    const uint32x4_t G0=S0, G1=S1;
    uint32x4_t M0=vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data)));
    uint32x4_t M1=vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data+16)));
    uint32x4_t M2=vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data+32)));
    uint32x4_t M3=vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data+48)));
    uint32x4_t T0,T2;
#define SHA_R(Ma,Mb,Mc,Md,k) \
    T0=vaddq_u32(Ma,vld1q_u32(&K256[k])); T2=S0; \
    Ma=vsha256su0q_u32(Ma,Mb); \
    S0=vsha256hq_u32(S0,S1,T0); S1=vsha256h2q_u32(S1,T2,T0); \
    Ma=vsha256su1q_u32(Ma,Mc,Md);
#define SHA_F(Ma,k) \
    T0=vaddq_u32(Ma,vld1q_u32(&K256[k])); T2=S0; \
    S0=vsha256hq_u32(S0,S1,T0); S1=vsha256h2q_u32(S1,T2,T0);
    SHA_R(M0,M1,M2,M3, 0) SHA_R(M1,M2,M3,M0, 4) SHA_R(M2,M3,M0,M1, 8) SHA_R(M3,M0,M1,M2,12)
    SHA_R(M0,M1,M2,M3,16) SHA_R(M1,M2,M3,M0,20) SHA_R(M2,M3,M0,M1,24) SHA_R(M3,M0,M1,M2,28)
    SHA_R(M0,M1,M2,M3,32) SHA_R(M1,M2,M3,M0,36) SHA_R(M2,M3,M0,M1,40) SHA_R(M3,M0,M1,M2,44)
    SHA_F(M0,48) SHA_F(M1,52) SHA_F(M2,56) SHA_F(M3,60)
#undef SHA_R
#undef SHA_F
    vst1q_u32(&st[0],vaddq_u32(S0,G0));
    vst1q_u32(&st[4],vaddq_u32(S1,G1));
}
#endif

/* SHA-256 de una clave publica comprimida (33 bytes), en software. */
static inline void sha256_33_sw(const uint8_t *in, uint8_t *out){
    uint32_t st[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    uint8_t blk[64]={0};
    memcpy(blk,in,33);
    blk[33]=0x80;
    blk[62]=0x01; blk[63]=0x08;
    sha256_block(st,blk);
    for(int i=0;i<8;i++){out[i*4]=(uint8_t)(st[i]>>24);out[i*4+1]=(uint8_t)(st[i]>>16);out[i*4+2]=(uint8_t)(st[i]>>8);out[i*4+3]=(uint8_t)st[i];}
}

/* Estado final de SHA-256 de 33 bytes: con las instrucciones del procesador
 * si las hay. */
static inline void sha256_33_st(const uint8_t *in, uint32_t *st){
    static const uint32_t IV[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    memcpy(st,IV,32);
    uint8_t blk[64]={0};
    memcpy(blk,in,33);
    blk[33]=0x80;
    blk[62]=0x01; blk[63]=0x08; /* length = 33*8 = 264 bits = 0x108 */
#ifdef SHA256_HW
    sha256_block_hw(st,blk);
#else
    sha256_block(st,blk);
#endif
}

/* La que se usa. */
static inline void sha256_33(const uint8_t *in, uint8_t *out){
    uint32_t st[8]; sha256_33_st(in,st);
    for(int i=0;i<8;i++){out[i*4]=(uint8_t)(st[i]>>24);out[i*4+1]=(uint8_t)(st[i]>>16);out[i*4+2]=(uint8_t)(st[i]>>8);out[i*4+3]=(uint8_t)st[i];}
}

/* =========================================================
   RIPEMD160 inline
   ========================================================= */
#define ROL32(x,n) (((x)<<(n))|((x)>>(32-(n))))
#define F1(x,y,z) ((x)^(y)^(z))
#define F2(x,y,z) (((x)&(y))|(~(x)&(z)))
#define F3(x,y,z) (((x)|(~(y)))^(z))
#define F4(x,y,z) (((x)&(z))|((y)&(~(z))))
#define F5(x,y,z) ((x)^((y)|(~(z))))

static const uint32_t KL[5]={0x00000000,0x5A827999,0x6ED9EBA1,0x8F1BBCDC,0xA953FD4E};
static const uint32_t KR[5]={0x50A28BE6,0x5C4DD124,0x6D703EF3,0x7A6D76E9,0x00000000};
static const int RL[80]={
    0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,
    7,4,13,1,10,6,15,3,12,0,9,5,2,14,11,8,
    3,10,14,4,9,15,8,1,2,7,0,6,13,11,5,12,
    1,9,11,10,0,8,12,4,13,3,7,15,14,5,6,2,
    4,0,5,9,7,12,2,10,14,1,3,8,11,6,15,13
};
static const int RR[80]={
    5,14,7,0,9,2,11,4,13,6,15,8,1,10,3,12,
    6,11,3,7,0,13,5,10,14,15,8,12,4,9,1,2,
    15,5,1,3,7,14,6,9,11,8,12,2,10,0,4,13,
    8,6,4,1,3,11,15,0,5,12,2,13,9,7,10,14,
    12,15,10,4,1,5,8,7,6,2,13,14,0,3,9,11
};
static const int SL[80]={
    11,14,15,12,5,8,7,9,11,13,14,15,6,7,9,8,
    7,6,8,13,11,9,7,15,7,12,15,9,11,7,13,12,
    11,13,6,7,14,9,13,15,14,8,13,6,5,12,7,5,
    11,12,14,15,14,15,9,8,9,14,5,6,8,6,5,12,
    9,15,5,11,6,8,13,12,5,12,13,14,11,8,5,6
};
static const int SR[80]={
    8,9,9,11,13,15,15,5,7,7,8,11,14,14,12,6,
    9,13,15,7,12,8,9,11,7,7,12,7,6,15,13,11,
    9,7,15,11,8,6,6,14,12,13,5,14,13,13,7,5,
    15,5,8,11,14,14,6,14,6,9,12,9,12,5,15,8,
    8,5,12,9,12,5,14,6,8,13,6,5,15,13,11,11
};

/* Las 80 rondas desenrolladas, con las palabras, desplazamientos y
 * constantes escritos en cada paso (generadas de las tablas de arriba). El
 * bucle de antes elegia la funcion con un if por ronda y leia cuatro tablas en
 * cada paso; asi el compilador ve constantes y el relleno fijo del mensaje de
 * 32 bytes (X8..X15) se pliega solo. Los nombres rotan en vez de mover los
 * valores: tras 80 pasos (multiplo de 5) vuelven a su sitio. */
#define RMD_P(F,a,b,c,d,e,x,k,s) { a += F(b,c,d) + (x) + (k); a = ROL32(a,s) + e; c = ROL32(c,10); }
#define P1(a,b,c,d,e,x,k,s) RMD_P(F1,a,b,c,d,e,x,k,s)
#define P2(a,b,c,d,e,x,k,s) RMD_P(F2,a,b,c,d,e,x,k,s)
#define P3(a,b,c,d,e,x,k,s) RMD_P(F3,a,b,c,d,e,x,k,s)
#define P4(a,b,c,d,e,x,k,s) RMD_P(F4,a,b,c,d,e,x,k,s)
#define P5(a,b,c,d,e,x,k,s) RMD_P(F5,a,b,c,d,e,x,k,s)
#define RMD_RONDAS \
    P1(al,bl,cl,dl,el,X0,0x00000000u,11) P5(ar,br,cr,dr,er,X5,0x50A28BE6u,8) \
    P1(el,al,bl,cl,dl,X1,0x00000000u,14) P5(er,ar,br,cr,dr,X14,0x50A28BE6u,9) \
    P1(dl,el,al,bl,cl,X2,0x00000000u,15) P5(dr,er,ar,br,cr,X7,0x50A28BE6u,9) \
    P1(cl,dl,el,al,bl,X3,0x00000000u,12) P5(cr,dr,er,ar,br,X0,0x50A28BE6u,11) \
    P1(bl,cl,dl,el,al,X4,0x00000000u,5) P5(br,cr,dr,er,ar,X9,0x50A28BE6u,13) \
    P1(al,bl,cl,dl,el,X5,0x00000000u,8) P5(ar,br,cr,dr,er,X2,0x50A28BE6u,15) \
    P1(el,al,bl,cl,dl,X6,0x00000000u,7) P5(er,ar,br,cr,dr,X11,0x50A28BE6u,15) \
    P1(dl,el,al,bl,cl,X7,0x00000000u,9) P5(dr,er,ar,br,cr,X4,0x50A28BE6u,5) \
    P1(cl,dl,el,al,bl,X8,0x00000000u,11) P5(cr,dr,er,ar,br,X13,0x50A28BE6u,7) \
    P1(bl,cl,dl,el,al,X9,0x00000000u,13) P5(br,cr,dr,er,ar,X6,0x50A28BE6u,7) \
    P1(al,bl,cl,dl,el,X10,0x00000000u,14) P5(ar,br,cr,dr,er,X15,0x50A28BE6u,8) \
    P1(el,al,bl,cl,dl,X11,0x00000000u,15) P5(er,ar,br,cr,dr,X8,0x50A28BE6u,11) \
    P1(dl,el,al,bl,cl,X12,0x00000000u,6) P5(dr,er,ar,br,cr,X1,0x50A28BE6u,14) \
    P1(cl,dl,el,al,bl,X13,0x00000000u,7) P5(cr,dr,er,ar,br,X10,0x50A28BE6u,14) \
    P1(bl,cl,dl,el,al,X14,0x00000000u,9) P5(br,cr,dr,er,ar,X3,0x50A28BE6u,12) \
    P1(al,bl,cl,dl,el,X15,0x00000000u,8) P5(ar,br,cr,dr,er,X12,0x50A28BE6u,6) \
    P2(el,al,bl,cl,dl,X7,0x5A827999u,7) P4(er,ar,br,cr,dr,X6,0x5C4DD124u,9) \
    P2(dl,el,al,bl,cl,X4,0x5A827999u,6) P4(dr,er,ar,br,cr,X11,0x5C4DD124u,13) \
    P2(cl,dl,el,al,bl,X13,0x5A827999u,8) P4(cr,dr,er,ar,br,X3,0x5C4DD124u,15) \
    P2(bl,cl,dl,el,al,X1,0x5A827999u,13) P4(br,cr,dr,er,ar,X7,0x5C4DD124u,7) \
    P2(al,bl,cl,dl,el,X10,0x5A827999u,11) P4(ar,br,cr,dr,er,X0,0x5C4DD124u,12) \
    P2(el,al,bl,cl,dl,X6,0x5A827999u,9) P4(er,ar,br,cr,dr,X13,0x5C4DD124u,8) \
    P2(dl,el,al,bl,cl,X15,0x5A827999u,7) P4(dr,er,ar,br,cr,X5,0x5C4DD124u,9) \
    P2(cl,dl,el,al,bl,X3,0x5A827999u,15) P4(cr,dr,er,ar,br,X10,0x5C4DD124u,11) \
    P2(bl,cl,dl,el,al,X12,0x5A827999u,7) P4(br,cr,dr,er,ar,X14,0x5C4DD124u,7) \
    P2(al,bl,cl,dl,el,X0,0x5A827999u,12) P4(ar,br,cr,dr,er,X15,0x5C4DD124u,7) \
    P2(el,al,bl,cl,dl,X9,0x5A827999u,15) P4(er,ar,br,cr,dr,X8,0x5C4DD124u,12) \
    P2(dl,el,al,bl,cl,X5,0x5A827999u,9) P4(dr,er,ar,br,cr,X12,0x5C4DD124u,7) \
    P2(cl,dl,el,al,bl,X2,0x5A827999u,11) P4(cr,dr,er,ar,br,X4,0x5C4DD124u,6) \
    P2(bl,cl,dl,el,al,X14,0x5A827999u,7) P4(br,cr,dr,er,ar,X9,0x5C4DD124u,15) \
    P2(al,bl,cl,dl,el,X11,0x5A827999u,13) P4(ar,br,cr,dr,er,X1,0x5C4DD124u,13) \
    P2(el,al,bl,cl,dl,X8,0x5A827999u,12) P4(er,ar,br,cr,dr,X2,0x5C4DD124u,11) \
    P3(dl,el,al,bl,cl,X3,0x6ED9EBA1u,11) P3(dr,er,ar,br,cr,X15,0x6D703EF3u,9) \
    P3(cl,dl,el,al,bl,X10,0x6ED9EBA1u,13) P3(cr,dr,er,ar,br,X5,0x6D703EF3u,7) \
    P3(bl,cl,dl,el,al,X14,0x6ED9EBA1u,6) P3(br,cr,dr,er,ar,X1,0x6D703EF3u,15) \
    P3(al,bl,cl,dl,el,X4,0x6ED9EBA1u,7) P3(ar,br,cr,dr,er,X3,0x6D703EF3u,11) \
    P3(el,al,bl,cl,dl,X9,0x6ED9EBA1u,14) P3(er,ar,br,cr,dr,X7,0x6D703EF3u,8) \
    P3(dl,el,al,bl,cl,X15,0x6ED9EBA1u,9) P3(dr,er,ar,br,cr,X14,0x6D703EF3u,6) \
    P3(cl,dl,el,al,bl,X8,0x6ED9EBA1u,13) P3(cr,dr,er,ar,br,X6,0x6D703EF3u,6) \
    P3(bl,cl,dl,el,al,X1,0x6ED9EBA1u,15) P3(br,cr,dr,er,ar,X9,0x6D703EF3u,14) \
    P3(al,bl,cl,dl,el,X2,0x6ED9EBA1u,14) P3(ar,br,cr,dr,er,X11,0x6D703EF3u,12) \
    P3(el,al,bl,cl,dl,X7,0x6ED9EBA1u,8) P3(er,ar,br,cr,dr,X8,0x6D703EF3u,13) \
    P3(dl,el,al,bl,cl,X0,0x6ED9EBA1u,13) P3(dr,er,ar,br,cr,X12,0x6D703EF3u,5) \
    P3(cl,dl,el,al,bl,X6,0x6ED9EBA1u,6) P3(cr,dr,er,ar,br,X2,0x6D703EF3u,14) \
    P3(bl,cl,dl,el,al,X13,0x6ED9EBA1u,5) P3(br,cr,dr,er,ar,X10,0x6D703EF3u,13) \
    P3(al,bl,cl,dl,el,X11,0x6ED9EBA1u,12) P3(ar,br,cr,dr,er,X0,0x6D703EF3u,13) \
    P3(el,al,bl,cl,dl,X5,0x6ED9EBA1u,7) P3(er,ar,br,cr,dr,X4,0x6D703EF3u,7) \
    P3(dl,el,al,bl,cl,X12,0x6ED9EBA1u,5) P3(dr,er,ar,br,cr,X13,0x6D703EF3u,5) \
    P4(cl,dl,el,al,bl,X1,0x8F1BBCDCu,11) P2(cr,dr,er,ar,br,X8,0x7A6D76E9u,15) \
    P4(bl,cl,dl,el,al,X9,0x8F1BBCDCu,12) P2(br,cr,dr,er,ar,X6,0x7A6D76E9u,5) \
    P4(al,bl,cl,dl,el,X11,0x8F1BBCDCu,14) P2(ar,br,cr,dr,er,X4,0x7A6D76E9u,8) \
    P4(el,al,bl,cl,dl,X10,0x8F1BBCDCu,15) P2(er,ar,br,cr,dr,X1,0x7A6D76E9u,11) \
    P4(dl,el,al,bl,cl,X0,0x8F1BBCDCu,14) P2(dr,er,ar,br,cr,X3,0x7A6D76E9u,14) \
    P4(cl,dl,el,al,bl,X8,0x8F1BBCDCu,15) P2(cr,dr,er,ar,br,X11,0x7A6D76E9u,14) \
    P4(bl,cl,dl,el,al,X12,0x8F1BBCDCu,9) P2(br,cr,dr,er,ar,X15,0x7A6D76E9u,6) \
    P4(al,bl,cl,dl,el,X4,0x8F1BBCDCu,8) P2(ar,br,cr,dr,er,X0,0x7A6D76E9u,14) \
    P4(el,al,bl,cl,dl,X13,0x8F1BBCDCu,9) P2(er,ar,br,cr,dr,X5,0x7A6D76E9u,6) \
    P4(dl,el,al,bl,cl,X3,0x8F1BBCDCu,14) P2(dr,er,ar,br,cr,X12,0x7A6D76E9u,9) \
    P4(cl,dl,el,al,bl,X7,0x8F1BBCDCu,5) P2(cr,dr,er,ar,br,X2,0x7A6D76E9u,12) \
    P4(bl,cl,dl,el,al,X15,0x8F1BBCDCu,6) P2(br,cr,dr,er,ar,X13,0x7A6D76E9u,9) \
    P4(al,bl,cl,dl,el,X14,0x8F1BBCDCu,8) P2(ar,br,cr,dr,er,X9,0x7A6D76E9u,12) \
    P4(el,al,bl,cl,dl,X5,0x8F1BBCDCu,6) P2(er,ar,br,cr,dr,X7,0x7A6D76E9u,5) \
    P4(dl,el,al,bl,cl,X6,0x8F1BBCDCu,5) P2(dr,er,ar,br,cr,X10,0x7A6D76E9u,15) \
    P4(cl,dl,el,al,bl,X2,0x8F1BBCDCu,12) P2(cr,dr,er,ar,br,X14,0x7A6D76E9u,8) \
    P5(bl,cl,dl,el,al,X4,0xA953FD4Eu,9) P1(br,cr,dr,er,ar,X12,0x00000000u,8) \
    P5(al,bl,cl,dl,el,X0,0xA953FD4Eu,15) P1(ar,br,cr,dr,er,X15,0x00000000u,5) \
    P5(el,al,bl,cl,dl,X5,0xA953FD4Eu,5) P1(er,ar,br,cr,dr,X10,0x00000000u,12) \
    P5(dl,el,al,bl,cl,X9,0xA953FD4Eu,11) P1(dr,er,ar,br,cr,X4,0x00000000u,9) \
    P5(cl,dl,el,al,bl,X7,0xA953FD4Eu,6) P1(cr,dr,er,ar,br,X1,0x00000000u,12) \
    P5(bl,cl,dl,el,al,X12,0xA953FD4Eu,8) P1(br,cr,dr,er,ar,X5,0x00000000u,5) \
    P5(al,bl,cl,dl,el,X2,0xA953FD4Eu,13) P1(ar,br,cr,dr,er,X8,0x00000000u,14) \
    P5(el,al,bl,cl,dl,X10,0xA953FD4Eu,12) P1(er,ar,br,cr,dr,X7,0x00000000u,6) \
    P5(dl,el,al,bl,cl,X14,0xA953FD4Eu,5) P1(dr,er,ar,br,cr,X6,0x00000000u,8) \
    P5(cl,dl,el,al,bl,X1,0xA953FD4Eu,12) P1(cr,dr,er,ar,br,X2,0x00000000u,13) \
    P5(bl,cl,dl,el,al,X3,0xA953FD4Eu,13) P1(br,cr,dr,er,ar,X13,0x00000000u,6) \
    P5(al,bl,cl,dl,el,X8,0xA953FD4Eu,14) P1(ar,br,cr,dr,er,X14,0x00000000u,5) \
    P5(el,al,bl,cl,dl,X11,0xA953FD4Eu,11) P1(er,ar,br,cr,dr,X0,0x00000000u,15) \
    P5(dl,el,al,bl,cl,X6,0xA953FD4Eu,8) P1(dr,er,ar,br,cr,X3,0x00000000u,13) \
    P5(cl,dl,el,al,bl,X15,0xA953FD4Eu,5) P1(cr,dr,er,ar,br,X9,0x00000000u,11) \
    P5(bl,cl,dl,el,al,X13,0xA953FD4Eu,6) P1(br,cr,dr,er,ar,X11,0x00000000u,11)

/* RIPEMD-160 de 32 bytes dados como 8 palabras ya leidas en little-endian. */
static inline void ripemd160_32w(const uint32_t *w, uint32_t *h){
    const uint32_t X0=w[0],X1=w[1],X2=w[2],X3=w[3],X4=w[4],X5=w[5],X6=w[6],X7=w[7];
    const uint32_t X8=0x80,X9=0,X10=0,X11=0,X12=0,X13=0,X14=256,X15=0;
    uint32_t al=0x67452301,bl=0xEFCDAB89,cl=0x98BADCFE,dl=0x10325476,el=0xC3D2E1F0;
    uint32_t ar=al,br=bl,cr=cl,dr=dl,er=el;
    RMD_RONDAS
    h[0]=0xEFCDAB89u+cl+dr; h[1]=0x98BADCFEu+dl+er; h[2]=0x10325476u+el+ar;
    h[3]=0xC3D2E1F0u+al+br; h[4]=0x67452301u+bl+cr;
}

static inline void ripemd160_32(const uint8_t *in, uint8_t *out){
    uint32_t w[8],h[5];
    for(int i=0;i<8;i++) w[i]=(uint32_t)in[4*i]|((uint32_t)in[4*i+1]<<8)|((uint32_t)in[4*i+2]<<16)|((uint32_t)in[4*i+3]<<24);
    ripemd160_32w(w,h);
    for(int i=0;i<5;i++){out[i*4]=(uint8_t)h[i];out[i*4+1]=(uint8_t)(h[i]>>8);out[i*4+2]=(uint8_t)(h[i]>>16);out[i*4+3]=(uint8_t)(h[i]>>24);}
}

/* La de antes, tal cual: solo para comprobar la nueva y medirlas. */
static inline void ripemd160_32_ref(const uint8_t *in, uint8_t *out){

    uint32_t st[5]={0x67452301,0xEFCDAB89,0x98BADCFE,0x10325476,0xC3D2E1F0};
    uint8_t blk[64]={0};
    memcpy(blk,in,32);
    blk[32]=0x80;
    blk[56]=0x00; blk[57]=0x01; /* 32*8=256 bits little-endian */
    uint32_t w[16];
    for(int i=0;i<16;i++) w[i]=((uint32_t)blk[i*4+3]<<24)|((uint32_t)blk[i*4+2]<<16)|((uint32_t)blk[i*4+1]<<8)|blk[i*4];
    uint32_t al=st[0],bl=st[1],cl=st[2],dl=st[3],el=st[4];
    uint32_t ar=st[0],br=st[1],cr=st[2],dr=st[3],er=st[4];
    for(int i=0;i<80;i++){
        int r=i/16;
        uint32_t fl,fr;
        if(r==0){fl=F1(bl,cl,dl);fr=F5(br,cr,dr);}
        else if(r==1){fl=F2(bl,cl,dl);fr=F4(br,cr,dr);}
        else if(r==2){fl=F3(bl,cl,dl);fr=F3(br,cr,dr);}
        else if(r==3){fl=F4(bl,cl,dl);fr=F2(br,cr,dr);}
        else{fl=F5(bl,cl,dl);fr=F1(br,cr,dr);}
        uint32_t tl=ROL32(al+fl+w[RL[i]]+KL[r],SL[i])+el;
        al=el;el=dl;dl=ROL32(cl,10);cl=bl;bl=tl;
        uint32_t tr=ROL32(ar+fr+w[RR[i]]+KR[r],SR[i])+er;
        ar=er;er=dr;dr=ROL32(cr,10);cr=br;br=tr;
    }
    uint32_t t=st[1]+cl+dr;
    st[1]=st[2]+dl+er;st[2]=st[3]+el+ar;st[3]=st[4]+al+br;st[4]=st[0]+bl+cr;st[0]=t;
    for(int i=0;i<5;i++){out[i*4]=(uint8_t)st[i];out[i*4+1]=(uint8_t)(st[i]>>8);out[i*4+2]=(uint8_t)(st[i]>>16);out[i*4+3]=(uint8_t)(st[i]>>24);}
}

/* Hash160 inline: SHA256(pub33) -> RIPEMD160. De palabra a palabra: el
 * resumen de SHA-256 son palabras big-endian y RIPEMD-160 lee little-endian,
 * asi que basta darles la vuelta, sin pasar por bytes. */
static inline void hash160_inline(const uint8_t *pub33, uint8_t *out20){
    uint32_t st[8],w[8],h[5];
    sha256_33_st(pub33,st);
    for(int i=0;i<8;i++) w[i]=__builtin_bswap32(st[i]);
    ripemd160_32w(w,h);
    memcpy(out20,h,20);   /* little-endian: los bytes salen en su orden */
}
