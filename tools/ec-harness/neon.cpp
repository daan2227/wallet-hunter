/* fe_mul2 (NEON, 2 a la vez) contra fe_mul_c (C). Solo ARM64 / qemu:
 *   aarch64-linux-gnu-g++ -O2 -static -march=armv8-a -o neon neon.cpp
 *   qemu-aarch64 ./neon
 * El ritmo bajo qemu no dice nada; la exactitud sí. */
#include <stdio.h>
#include <string.h>
#include "../../app/src/main/cpp/fe_neon.h"
#ifndef __aarch64__
int main(){ printf("no es ARM64: nada que probar\n"); return 0; }
#else
static uint64_t s=0x9E3779B97F4A7C15ULL;
static uint64_t rnd(){ s^=s<<13; s^=s>>7; s^=s<<17; return s; }
static const uint64_t esp[]={0,1,2,0xFFFFFFFFFFFFFFFFULL,0xFFFFFFFEFFFFFC2FULL,
                             0xFFFFFFFEFFFFFC2EULL,0x8000000000000000ULL,0x1000003D1ULL};
int main(){
    long mal=0, n=2000000;
    for(long it=0;it<n;it++){
        fe_t a0,b0,a1,b1,r0,r1,c0,c1;
        for(int k=0;k<4;k++){
            a0[k]=(rnd()%3==0)?esp[rnd()%8]:rnd(); b0[k]=(rnd()%3==0)?esp[rnd()%8]:rnd();
            a1[k]=(rnd()%3==0)?esp[rnd()%8]:rnd(); b1[k]=(rnd()%3==0)?esp[rnd()%8]:rnd();
        }
        if(it<64){ for(int k=0;k<4;k++){ a0[k]=esp[(it>>3)&7]; b0[k]=esp[it&7]; a1[k]=a0[k]; b1[k]=b0[k]; } }
        fe_mul2(r0,a0,b0, r1,a1,b1);
        fe_mul_c(c0,a0,b0); fe_mul_c(c1,a1,b1);
        if(memcmp(r0,c0,32)||memcmp(r1,c1,32)){ if(mal<3) printf("MAL en %ld\n",it); mal++; }
    }
    printf("%s  fe_mul2 (NEON) == fe_mul_c en %ld pares (%ld distintos)\n",
           mal?"MAL":"OK ", n, mal);
    return mal?1:0;
}
#endif
