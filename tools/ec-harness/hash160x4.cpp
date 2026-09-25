/* hash160: el RIPEMD-160 desenrollado y hash160_x4 (cuatro a la vez, NEON y
 * SHA-256 del procesador) contra la version de antes, y lo que tarda cada uno.
 *   g++ -O2 -o hash160x4 hash160x4.cpp                  (x86: la parte escalar)
 *   aarch64-linux-gnu-g++ -O2 -static -march=armv8-a+crypto -o h4 hash160x4.cpp
 *   qemu-aarch64 -cpu max ./h4                          (la de NEON) */
#include <stdio.h>
#include <chrono>
#include "../../app/src/main/cpp/hash160x4.h"
static uint64_t s=0x9E3779B97F4A7C15ULL; static uint8_t azar(){ s^=s<<13; s^=s>>7; s^=s<<17; return (uint8_t)s; }
static void ref(const uint8_t *p,uint8_t *o){ uint8_t sh[32]; sha256_33_sw(p,sh); ripemd160_32_ref(sh,o); }
int main(){
    int fallos=0;
    const uint8_t g33[33]={0x02,0x79,0xBE,0x66,0x7E,0xF9,0xDC,0xBB,0xAC,0x55,0xA0,0x62,0x95,0xCE,0x87,0x0B,0x07,0x02,0x9B,0xFC,0xDB,0x2D,0xCE,0x28,0xD9,0x59,0xF2,0x81,0x5B,0x16,0xF8,0x17,0x98};
    const uint8_t esp[20]={0x75,0x1e,0x76,0xe8,0x19,0x91,0x96,0xd4,0x54,0x94,0x1c,0x45,0xd1,0xb3,0xa3,0x23,0xf1,0x43,0x3b,0xd6};
    uint8_t h[20]; hash160_inline(g33,h);
    int ok=!memcmp(h,esp,20); printf("%s  hash160(1*G) = 1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH\n",ok?"OK ":"MAL"); fallos+=!ok;
    long mal1=0,mal4=0;
    for(int it=0;it<300000;it++){
        uint8_t p[4][33],a[20],o[4][20]; const uint8_t *pp[4];
        for(int k=0;k<4;k++){ for(int i=0;i<33;i++) p[k][i]=azar(); p[k][0]=2+(p[k][0]&1); pp[k]=p[k]; }
        hash160_inline(p[0],h); ref(p[0],a); if(memcmp(h,a,20)) mal1++;
        hash160_x4(pp,o);
        for(int k=0;k<4;k++){ ref(p[k],a); if(memcmp(o[k],a,20)) mal4++; }
    }
    printf("%s  hash160_inline == el de antes en 300000 (%ld distintos)\n",mal1?"MAL":"OK ",mal1); fallos+=mal1!=0;
    printf("%s  hash160_x4 == el de antes en 1200000 (%ld distintos)%s\n",mal4?"MAL":"OK ",mal4,
#ifdef HASH160_X4
           " [NEON]"
#else
           " [sin NEON: 4 x hash160_inline]"
#endif
    ); fallos+=mal4!=0;
    uint8_t in[4][33]; for(int k=0;k<4;k++){ for(int i=0;i<33;i++) in[k][i]=(uint8_t)(i*37+k); in[k][0]=2; }
    const uint8_t *pp[4]={in[0],in[1],in[2],in[3]};
    const int N=400000; uint8_t o[4][20];
    auto t0=std::chrono::steady_clock::now();
    for(int i=0;i<N;i++){ ref(in[0],h); in[0][5]^=h[0]; }
    double a=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
    t0=std::chrono::steady_clock::now();
    for(int i=0;i<N;i++){ hash160_inline(in[0],h); in[0][5]^=h[0]; }
    double b=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
    t0=std::chrono::steady_clock::now();
    for(int i=0;i<N/4;i++){ hash160_x4(pp,o); in[0][5]^=o[0][0]; }
    double c=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
    printf("     antes %.2f M/s, ahora %.2f M/s, x4 %.2f M/s\n",N/a/1e6,N/b/1e6,N/c/1e6);
    return fallos;
}
