/* SHA-512 / HMAC / PBKDF2: la parte comun y el software. Ver sha512.h. */
#include "sha512.h"
#include <string.h>
#include <atomic>
#include <chrono>
#if defined(__aarch64__) && defined(__linux__)
#include <sys/auxv.h>
#ifndef HWCAP_SHA512
#define HWCAP_SHA512 (1UL << 21)
#endif
#endif

static const uint64_t K512[80]={
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
static const uint64_t IV512[8]={
    0x6a09e667f3bcc908ULL,0xbb67ae8584caa73bULL,0x3c6ef372fe94f82bULL,0xa54ff53a5f1d36f1ULL,
    0x510e527fade682d1ULL,0x9b05688c2b3e6c1fULL,0x1f83d9abfb41bd6bULL,0x5be0cd19137e2179ULL};

#define ROR64(x,n) (((x)>>(n))|((x)<<(64-(n))))

static inline __attribute__((always_inline)) void bloque_sw(uint64_t st[8], const uint64_t m[16]){
    uint64_t w[16]; memcpy(w,m,128);
    uint64_t a=st[0],b=st[1],c=st[2],d=st[3],e=st[4],f=st[5],g=st[6],h=st[7];
    for(int i=0;i<80;i++){
        uint64_t wi;
        if(i<16) wi=w[i];
        else{
            uint64_t w15=w[(i-15)&15], w2=w[(i-2)&15];
            uint64_t s0=ROR64(w15,1)^ROR64(w15,8)^(w15>>7);
            uint64_t s1=ROR64(w2,19)^ROR64(w2,61)^(w2>>6);
            wi=w[i&15]+=s0+s1+w[(i-7)&15];
        }
        uint64_t t1=h+(ROR64(e,14)^ROR64(e,18)^ROR64(e,41))+((e&f)^(~e&g))+K512[i]+wi;
        uint64_t t2=(ROR64(a,28)^ROR64(a,34)^ROR64(a,39))+((a&b)^(a&c)^(b&c));
        h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
    }
    st[0]+=a; st[1]+=b; st[2]+=c; st[3]+=d; st[4]+=e; st[5]+=f; st[6]+=g; st[7]+=h;
}

/* ---- el modo ---- */

static int hw_disponible(){
#if defined(__aarch64__) && defined(__linux__)
    return (getauxval(AT_HWCAP) & HWCAP_SHA512) ? 1 : 0;
#else
    return 0;
#endif
}
int sha512_tiene_hw(void){ static int v=-1; if(v<0) v=hw_disponible(); return v; }

static std::atomic<int> g_modo{-1};
static std::atomic<int> g_pedido{-1};

const char *sha512_nombre_modo(int m){
    switch(m){ case 1: return "SHA-512 CPU"; case 2: return "SHA-512 CPU x2"; default: return "software"; }
}

static int calibrar();
int sha512_modo(void){
    int m=g_modo.load(std::memory_order_relaxed);
    if(m>=0) return m;
    int p=g_pedido.load();
    if(p>=0) m=(p>0 && !sha512_tiene_hw())?0:p;
    else m=sha512_tiene_hw()?calibrar():0;
    g_modo.store(m);
    return m;
}
void sha512_fijar_modo(int m){ g_pedido.store(m); g_modo.store(-1); }

void sha512_bloque(uint64_t st[8], const uint64_t w[16]){
#if defined(__aarch64__)
    if(sha512_modo()>0){ sha512_bloque_hw(st,w); return; }
#endif
    bloque_sw(st,w);
}

/* ---- mensajes de bytes ---- */

static inline uint64_t be64(const uint8_t *p){
    uint64_t v=0; for(int i=0;i<8;i++) v=(v<<8)|p[i]; return v;
}
static inline void put_be64(uint8_t *p, uint64_t v){ for(int i=7;i>=0;i--){ p[i]=(uint8_t)v; v>>=8; } }

/* out = resumen continuando desde st0, que ya lleva 'previo' bytes hechos
 * (multiplo de 128), del mensaje m[0..n). */
static void seguir(const uint64_t st0[8], uint64_t previo, const uint8_t *m, size_t n, uint64_t out[8]){
    uint64_t st[8]; memcpy(st,st0,64);
    uint64_t w[16];
    size_t i=0;
    for(; i+128<=n; i+=128){ for(int j=0;j<16;j++) w[j]=be64(m+i+8*j); sha512_bloque(st,w); }
    uint8_t b[256]; size_t r=n-i; memset(b,0,sizeof b); memcpy(b,m+i,r); b[r]=0x80;
    size_t tot=(r+1+16<=128)?128:256;
    uint64_t bits=(previo+n)*8;
    put_be64(b+tot-8,bits);
    for(size_t o=0;o<tot;o+=128){ for(int j=0;j<16;j++) w[j]=be64(b+o+8*j); sha512_bloque(st,w); }
    memcpy(out,st,64);
}

void sha512(const uint8_t *m, size_t n, uint8_t out[64]){
    uint64_t st[8]; seguir(IV512,0,m,n,st);
    for(int i=0;i<8;i++) put_be64(out+8*i,st[i]);
}

/* Los estados tras el bloque ipad y el bloque opad: HMAC los reusa siempre. */
static void hmac_estados(const uint8_t *k, size_t kn, uint64_t is[8], uint64_t os[8]){
    uint8_t kb[128]; memset(kb,0,128);
    if(kn>128) sha512(k,kn,kb); else memcpy(kb,k,kn);
    uint64_t w[16];
    memcpy(is,IV512,64); for(int j=0;j<16;j++) w[j]=be64(kb+8*j)^0x3636363636363636ULL; sha512_bloque(is,w);
    memcpy(os,IV512,64); for(int j=0;j<16;j++) w[j]=be64(kb+8*j)^0x5c5c5c5c5c5c5c5cULL; sha512_bloque(os,w);
}

/* Bloque de un resumen de 64 bytes ya hecho 128: el relleno es fijo. */
static inline void relleno64(uint64_t w[16], const uint64_t d[8]){
    memcpy(w,d,64); w[8]=0x8000000000000000ULL;
    for(int j=9;j<15;j++) w[j]=0;
    w[15]=(128+64)*8;
}

static void hmac_con_estados(const uint64_t is[8], const uint64_t os[8], const uint8_t *m, size_t mn, uint64_t out[8]){
    uint64_t in[8]; seguir(is,128,m,mn,in);
    uint64_t w[16]; relleno64(w,in);
    memcpy(out,os,64); sha512_bloque(out,w);
}

void hmac_sha512(const uint8_t *k, size_t kn, const uint8_t *m, size_t mn, uint8_t out[64]){
    uint64_t is[8],os[8],r[8];
    hmac_estados(k,kn,is,os);
    hmac_con_estados(is,os,m,mn,r);
    for(int i=0;i<8;i++) put_be64(out+8*i,r[i]);
}

/* ---- PBKDF2 ---- */

void pbkdf2_bucle_sw(const uint64_t is[8], const uint64_t os[8], uint64_t u[8], uint64_t acc[8], uint32_t n){
    uint64_t w[16], t[8];
    w[8]=0x8000000000000000ULL;
    for(int j=9;j<15;j++) w[j]=0;
    w[15]=(128+64)*8;
    for(uint32_t v=0; v<n; v++){
        memcpy(w,u,64); memcpy(t,is,64); bloque_sw(t,w);
        memcpy(w,t,64); memcpy(u,os,64); bloque_sw(u,w);
        for(int j=0;j<8;j++) acc[j]^=u[j];
    }
}

/* Primera vuelta: U1 = HMAC(pw, sal || 00 00 00 01). */
static void primera(const uint8_t *pw, size_t pwn, const uint8_t *sal, size_t saln,
                    uint64_t is[8], uint64_t os[8], uint64_t u[8]){
    hmac_estados(pw,pwn,is,os);
    uint8_t tmp[256]; const uint8_t *m=tmp; size_t mn=saln+4;
    uint8_t *dyn=NULL;
    if(mn>sizeof tmp){ dyn=new uint8_t[mn]; m=dyn; }
    uint8_t *d=dyn?dyn:tmp;
    memcpy(d,sal,saln); d[saln]=0; d[saln+1]=0; d[saln+2]=0; d[saln+3]=1;
    hmac_con_estados(is,os,m,mn,u);
    delete[] dyn;
}

void pbkdf2_sha512(const uint8_t *pw, size_t pwn, const uint8_t *sal, size_t saln,
                   uint32_t vueltas, uint8_t out[64]){
    uint64_t is[8],os[8],u[8],acc[8];
    primera(pw,pwn,sal,saln,is,os,u);
    memcpy(acc,u,64);
    if(vueltas>1){
#if defined(__aarch64__)
        if(sha512_modo()>0) pbkdf2_bucle_hw(is,os,u,acc,vueltas-1); else
#endif
        pbkdf2_bucle_sw(is,os,u,acc,vueltas-1);
    }
    for(int i=0;i<8;i++) put_be64(out+8*i,acc[i]);
}

void pbkdf2_sha512_x2(const uint8_t *pwA, size_t nA, const uint8_t *pwB, size_t nB,
                      const uint8_t *sal, size_t saln, uint32_t vueltas,
                      uint8_t outA[64], uint8_t outB[64]){
#if defined(__aarch64__)
    if(sha512_modo()==2 && vueltas>1){
        uint64_t isA[8],osA[8],uA[8],accA[8], isB[8],osB[8],uB[8],accB[8];
        primera(pwA,nA,sal,saln,isA,osA,uA); memcpy(accA,uA,64);
        primera(pwB,nB,sal,saln,isB,osB,uB); memcpy(accB,uB,64);
        pbkdf2_bucle_hw2(isA,osA,uA,accA,isB,osB,uB,accB,vueltas-1);
        for(int i=0;i<8;i++){ put_be64(outA+8*i,accA[i]); put_be64(outB+8*i,accB[i]); }
        return;
    }
#endif
    pbkdf2_sha512(pwA,nA,sal,saln,vueltas,outA);
    pbkdf2_sha512(pwB,nB,sal,saln,vueltas,outB);
}

static size_t sal_bip39(const char *pass, size_t pn, uint8_t *buf, size_t cap){
    if(8+pn>cap) pn=cap-8;
    memcpy(buf,"mnemonic",8); if(pn) memcpy(buf+8,pass,pn);
    return 8+pn;
}
void bip39_semilla(const char *frase, size_t n, const char *pass, size_t pn, uint8_t out[64]){
    uint8_t sal[1024]; size_t sn=sal_bip39(pass,pn,sal,sizeof sal);
    pbkdf2_sha512((const uint8_t*)frase,n,sal,sn,2048,out);
}
void bip39_semilla_x2(const char *fA, size_t nA, const char *fB, size_t nB,
                      const char *pass, size_t pn, uint8_t outA[64], uint8_t outB[64]){
    uint8_t sal[1024]; size_t sn=sal_bip39(pass,pn,sal,sizeof sal);
    pbkdf2_sha512_x2((const uint8_t*)fA,nA,(const uint8_t*)fB,nB,sal,sn,2048,outA,outB);
}

/* Con instrucciones, ¿entrelazar dos compensa? Depende del nucleo: se mide
 * una vez (unos 20 ms) y se queda el que mas frases por segundo da. */
static int calibrar(){
#if defined(__aarch64__)
    uint64_t is[8],os[8],u[8],acc[8];
    for(int i=0;i<8;i++){ is[i]=IV512[i]; os[i]=IV512[7-i]; u[i]=i; acc[i]=0; }
    uint64_t is2[8],os2[8],u2[8],acc2[8];
    memcpy(is2,is,64); memcpy(os2,os,64); memcpy(u2,u,64); memcpy(acc2,acc,64);
    const uint32_t N=2048;
    double mejor1=1e9, mejor2=1e9;
    for(int r=0;r<3;r++){
        auto t0=std::chrono::steady_clock::now();
        pbkdf2_bucle_hw(is,os,u,acc,N); pbkdf2_bucle_hw(is2,os2,u2,acc2,N);
        auto t1=std::chrono::steady_clock::now();
        pbkdf2_bucle_hw2(is,os,u,acc,is2,os2,u2,acc2,N);
        auto t2=std::chrono::steady_clock::now();
        double a=std::chrono::duration<double>(t1-t0).count(), b=std::chrono::duration<double>(t2-t1).count();
        if(a<mejor1) mejor1=a; if(b<mejor2) mejor2=b;
    }
    return (mejor2<mejor1*0.97)?2:1;
#else
    return 0;
#endif
}
