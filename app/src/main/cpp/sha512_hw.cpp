/* Las instrucciones SHA-512 de ARMv8.2 (FEAT_SHA512). Este fichero se
 * compila con -march=armv8.2-a+sha3 y NADA de el se ejecuta sin comprobar
 * antes que el procesador las tiene (sha512_tiene_hw en sha512.cpp): en uno
 * sin ellas seria una instruccion ilegal y la app se cerraria.
 *
 * La secuencia de rondas es la de sha512-ce-core.S del nucleo de Linux
 * (Ard Biesheuvel), pasada a intrinsecos: cinco registros de estado que rotan
 * su papel en cada doble ronda, y ocho con el mensaje, que se va extendiendo
 * sobre si mismo en las 32 primeras. */
#if defined(__aarch64__)
#include <arm_neon.h>
#include <string.h>
#include "sha512.h"

static const uint64_t K512hw[80]={
    0x428a2f98d728ae22ULL,0x7137449123ef65cdULL,0xb5c0fbcfec4d3b2fULL,0xe9b5dba58189dbbcULL,
    0x3956c25bf348b538ULL,0x59f111f1b605d019ULL,0x923f82a4af194f9bULL,0xab1c5ed5da6d8118ULL,
    0xd807aa98a3030242ULL,0x12835b0145706fbeULL,0x243185be4ee4b28cULL,0x550c7dc3d5ffb4e2ULL,
    0x72be5d74f27b896fULL,0x80deb1fe3b1696b1ULL,0x9bdc06a725c71235ULL,0xc19bf174cf692694ULL,
    0xe49b69c19ef14ad2ULL,0xefbe4786384f25e3ULL,0x0fc19dc68b8cd5b5ULL,0x240ca1cc77ac9c65ULL,
    0x2de92c6f592b0275ULL,0x4a7484aa6ea6e483ULL,0x5cb0a9dcbd41fbd4ULL,0x76f988da831153b5ULL,
    0x983e5152ee66dfabULL,0xa831c66d2db43210ULL,0xb00327c898fb213fULL,0xbf597fc7beef0ee4ULL,
    0xc6e00bf33da88fc2ULL,0xd5a79147930aa725ULL,0x06ca6351e003826fULL,0x142929670a0e6e70ULL,
    0x27b70a8546d22ffcULL,0x2e1b21385c26c926ULL,0x4d2c6dfc5ac42aedULL,0x53380d139d95b3dfULL,
    0x650a73548baf63deULL,0x766a0abb3c77b2a8ULL,0x81c2c92e47edaee6ULL,0x92722c851482353bULL,
    0xa2bfe8a14cf10364ULL,0xa81a664bbc423001ULL,0xc24b8b70d0f89791ULL,0xc76c51a30654be30ULL,
    0xd192e819d6ef5218ULL,0xd69906245565a910ULL,0xf40e35855771202aULL,0x106aa07032bbd1b8ULL,
    0x19a4c116b8d2d0c8ULL,0x1e376c085141ab53ULL,0x2748774cdf8eeb99ULL,0x34b0bcb5e19b48a8ULL,
    0x391c0cb3c5c95a63ULL,0x4ed8aa4ae3418acbULL,0x5b9cca4f7763e373ULL,0x682e6ff3d6b2b8a3ULL,
    0x748f82ee5defb2fcULL,0x78a5636f43172f60ULL,0x84c87814a1f0ab72ULL,0x8cc702081a6439ecULL,
    0x90befffa23631e28ULL,0xa4506cebde82bde9ULL,0xbef9a3f7b2c67915ULL,0xc67178f2e372532bULL,
    0xca273eceea26619cULL,0xd186b8c721c0c207ULL,0xeada7dd6cde0eb1eULL,0xf57d4f7fee6ed178ULL,
    0x06f067aa72176fbaULL,0x0a637dc5a2c898a6ULL,0x113f9804bef90daeULL,0x1b710b35131c471bULL,
    0x28db77f523047d84ULL,0x32caab7b40c72493ULL,0x3c9ebe0a15c9bebcULL,0x431d67c49c100d4cULL,
    0x4cc5d4becb3e42b6ULL,0x597f299cfc657e2aULL,0x5fcb6fab3ad6faecULL,0x6c44198c4a475817ULL};

/* Una doble ronda (la r-esima de 40) sobre el estado S[5] y el mensaje W[8].
 * i0..i4: que registro hace de ab, cd, ef, gh y el libre en esta ronda. */
#define DRONDA(S,W,i0,i1,i2,i3,i4,r,ext,n0,n1,n2,n3,n4) do{ \
    uint64x2_t k_=vaddq_u64(vld1q_u64(K512hw+2*(r)),W[n0]); \
    uint64x2_t fg_=vextq_u64(S[i2],S[i3],1); \
    uint64x2_t de_=vextq_u64(S[i1],S[i2],1); \
    k_=vextq_u64(k_,k_,1); \
    S[i3]=vaddq_u64(S[i3],k_); \
    if(ext){ uint64x2_t m_=vextq_u64(W[n3],W[n4],1); \
             W[n0]=vsha512su0q_u64(W[n0],W[n1]); \
             S[i3]=vsha512hq_u64(S[i3],fg_,de_); \
             W[n0]=vsha512su1q_u64(W[n0],W[n2],m_); } \
    else     S[i3]=vsha512hq_u64(S[i3],fg_,de_); \
    S[i4]=vaddq_u64(S[i1],S[i3]); \
    S[i3]=vsha512h2q_u64(S[i3],S[i1],S[i0]); \
}while(0)

#define RONDAS(X) \
    X(0,1,2,3,4,0,1,0,1,7,4,5) \
    X(3,0,4,2,1,1,1,1,2,0,5,6) \
    X(2,3,1,4,0,2,1,2,3,1,6,7) \
    X(4,2,0,1,3,3,1,3,4,2,7,0) \
    X(1,4,3,0,2,4,1,4,5,3,0,1) \
    X(0,1,2,3,4,5,1,5,6,4,1,2) \
    X(3,0,4,2,1,6,1,6,7,5,2,3) \
    X(2,3,1,4,0,7,1,7,0,6,3,4) \
    X(4,2,0,1,3,8,1,0,1,7,4,5) \
    X(1,4,3,0,2,9,1,1,2,0,5,6) \
    X(0,1,2,3,4,10,1,2,3,1,6,7) \
    X(3,0,4,2,1,11,1,3,4,2,7,0) \
    X(2,3,1,4,0,12,1,4,5,3,0,1) \
    X(4,2,0,1,3,13,1,5,6,4,1,2) \
    X(1,4,3,0,2,14,1,6,7,5,2,3) \
    X(0,1,2,3,4,15,1,7,0,6,3,4) \
    X(3,0,4,2,1,16,1,0,1,7,4,5) \
    X(2,3,1,4,0,17,1,1,2,0,5,6) \
    X(4,2,0,1,3,18,1,2,3,1,6,7) \
    X(1,4,3,0,2,19,1,3,4,2,7,0) \
    X(0,1,2,3,4,20,1,4,5,3,0,1) \
    X(3,0,4,2,1,21,1,5,6,4,1,2) \
    X(2,3,1,4,0,22,1,6,7,5,2,3) \
    X(4,2,0,1,3,23,1,7,0,6,3,4) \
    X(1,4,3,0,2,24,1,0,1,7,4,5) \
    X(0,1,2,3,4,25,1,1,2,0,5,6) \
    X(3,0,4,2,1,26,1,2,3,1,6,7) \
    X(2,3,1,4,0,27,1,3,4,2,7,0) \
    X(4,2,0,1,3,28,1,4,5,3,0,1) \
    X(1,4,3,0,2,29,1,5,6,4,1,2) \
    X(0,1,2,3,4,30,1,6,7,5,2,3) \
    X(3,0,4,2,1,31,1,7,0,6,3,4) \
    X(2,3,1,4,0,32,0,0,1,7,4,5) \
    X(4,2,0,1,3,33,0,1,2,0,5,6) \
    X(1,4,3,0,2,34,0,2,3,1,6,7) \
    X(0,1,2,3,4,35,0,3,4,2,7,0) \
    X(3,0,4,2,1,36,0,4,5,3,0,1) \
    X(2,3,1,4,0,37,0,5,6,4,1,2) \
    X(4,2,0,1,3,38,0,6,7,5,2,3) \
    X(1,4,3,0,2,39,0,7,0,6,3,4)

/* st (4 vectores: ab cd ef gh) += compresion de w (8 vectores). */
static inline __attribute__((always_inline)) void comp(uint64x2_t st[4], const uint64x2_t w[8]){
    uint64x2_t S[5]={st[0],st[1],st[2],st[3],st[0]};
    uint64x2_t W[8]; for(int i=0;i<8;i++) W[i]=w[i];
#define X(i0,i1,i2,i3,i4,r,e,n0,n1,n2,n3,n4) DRONDA(S,W,i0,i1,i2,i3,i4,r,e,n0,n1,n2,n3,n4);
    RONDAS(X)
#undef X
    for(int i=0;i<4;i++) st[i]=vaddq_u64(st[i],S[i]);
}

/* Las dos a la vez, ronda a ronda: dos cadenas de dependencias independientes
 * que el procesador puede solapar. */
static inline __attribute__((always_inline)) void comp2(uint64x2_t sa[4], const uint64x2_t wa[8],
                                                         uint64x2_t sb[4], const uint64x2_t wb[8]){
    uint64x2_t SA[5]={sa[0],sa[1],sa[2],sa[3],sa[0]}, SB[5]={sb[0],sb[1],sb[2],sb[3],sb[0]};
    uint64x2_t WA[8], WB[8]; for(int i=0;i<8;i++){ WA[i]=wa[i]; WB[i]=wb[i]; }
#define X(i0,i1,i2,i3,i4,r,e,n0,n1,n2,n3,n4) DRONDA(SA,WA,i0,i1,i2,i3,i4,r,e,n0,n1,n2,n3,n4); \
                                             DRONDA(SB,WB,i0,i1,i2,i3,i4,r,e,n0,n1,n2,n3,n4);
    RONDAS(X)
#undef X
    for(int i=0;i<4;i++){ sa[i]=vaddq_u64(sa[i],SA[i]); sb[i]=vaddq_u64(sb[i],SB[i]); }
}

void sha512_bloque_hw(uint64_t st[8], const uint64_t w[16]){
    uint64x2_t s[4], m[8];
    for(int i=0;i<4;i++) s[i]=vld1q_u64(st+2*i);
    for(int i=0;i<8;i++) m[i]=vld1q_u64(w+2*i);
    comp(s,m);
    for(int i=0;i<4;i++) vst1q_u64(st+2*i,s[i]);
}

static const uint64_t RELLENO[8]={0x8000000000000000ULL,0,0,0,0,0,0,(128+64)*8};

void pbkdf2_bucle_hw(const uint64_t is[8], const uint64_t os[8], uint64_t u[8], uint64_t acc[8], uint32_t n){
    uint64x2_t IS[4],OS[4],U[4],A[4],m[8],t[4];
    for(int i=0;i<4;i++){ IS[i]=vld1q_u64(is+2*i); OS[i]=vld1q_u64(os+2*i);
                          U[i]=vld1q_u64(u+2*i); A[i]=vld1q_u64(acc+2*i); m[4+i]=vld1q_u64(RELLENO+2*i); }
    for(uint32_t v=0; v<n; v++){
        for(int i=0;i<4;i++){ m[i]=U[i]; t[i]=IS[i]; }
        comp(t,m);
        for(int i=0;i<4;i++){ m[i]=t[i]; U[i]=OS[i]; }
        comp(U,m);
        for(int i=0;i<4;i++) A[i]=veorq_u64(A[i],U[i]);
    }
    for(int i=0;i<4;i++){ vst1q_u64(u+2*i,U[i]); vst1q_u64(acc+2*i,A[i]); }
}

void pbkdf2_bucle_hw2(const uint64_t isA[8], const uint64_t osA[8], uint64_t uA[8], uint64_t accA[8],
                      const uint64_t isB[8], const uint64_t osB[8], uint64_t uB[8], uint64_t accB[8], uint32_t n){
    uint64x2_t ISa[4],OSa[4],Ua[4],Aa[4],ma[8],ta[4];
    uint64x2_t ISb[4],OSb[4],Ub[4],Ab[4],mb[8],tb[4];
    for(int i=0;i<4;i++){
        ISa[i]=vld1q_u64(isA+2*i); OSa[i]=vld1q_u64(osA+2*i); Ua[i]=vld1q_u64(uA+2*i); Aa[i]=vld1q_u64(accA+2*i);
        ISb[i]=vld1q_u64(isB+2*i); OSb[i]=vld1q_u64(osB+2*i); Ub[i]=vld1q_u64(uB+2*i); Ab[i]=vld1q_u64(accB+2*i);
        ma[4+i]=mb[4+i]=vld1q_u64(RELLENO+2*i);
    }
    for(uint32_t v=0; v<n; v++){
        for(int i=0;i<4;i++){ ma[i]=Ua[i]; ta[i]=ISa[i]; mb[i]=Ub[i]; tb[i]=ISb[i]; }
        comp2(ta,ma,tb,mb);
        for(int i=0;i<4;i++){ ma[i]=ta[i]; Ua[i]=OSa[i]; mb[i]=tb[i]; Ub[i]=OSb[i]; }
        comp2(Ua,ma,Ub,mb);
        for(int i=0;i<4;i++){ Aa[i]=veorq_u64(Aa[i],Ua[i]); Ab[i]=veorq_u64(Ab[i],Ub[i]); }
    }
    for(int i=0;i<4;i++){
        vst1q_u64(uA+2*i,Ua[i]); vst1q_u64(accA+2*i,Aa[i]);
        vst1q_u64(uB+2*i,Ub[i]); vst1q_u64(accB+2*i,Ab[i]);
    }
}
#endif
