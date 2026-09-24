/* fe_mul (ensamblador en ARM64) contra fe_mul_c (C). Solo corre en ARM64 o con qemu:
 *
 *   aarch64-linux-gnu-g++ -O2 -static -o fe_arm64 fe_arm64.cpp
 *   qemu-aarch64 ./fe_arm64
 *
 * No va en run.sh porque la CI es x86: ahi no hay nada que comparar. */
#include <stdio.h>
#include "../../app/src/main/cpp/fe_arm64.h"
#ifndef FE_ARM64
int main(){ printf("no es ARM64: nada que probar\n"); return 0; }
#else
static uint64_t s=0x9E3779B97F4A7C15ULL; static uint64_t rnd(){ s^=s<<13; s^=s>>7; s^=s<<17; return s; }
static const uint64_t esp[]={0,1,2,0xFFFFFFFFFFFFFFFFULL,0xFFFFFFFEFFFFFC2FULL,0xFFFFFFFEFFFFFC2EULL,0x8000000000000000ULL,0x1000003D1ULL};
int main(){
    long mal=0, n=3000000;
    for(long it=0;it<n;it++){
        fe_t a,b,r1,r2;
        for(int k=0;k<4;k++){ a[k]=(rnd()%3==0)?esp[rnd()%8]:rnd(); b[k]=(rnd()%3==0)?esp[rnd()%8]:rnd(); }
        if(it<64){ for(int k=0;k<4;k++){ a[k]=esp[(it>>3)&7]; b[k]=esp[it&7]; } }
        fe_mul_c(r1,a,b); fe_mul(r2,a,b);
        if(memcmp(r1,r2,32)){ if(mal<3) printf("MAL en %ld\n",it); mal++; }
    }
    printf("%s  fe_mul (asm) == fe_mul_c en %ld productos (%ld distintos)\n", mal?"MAL":"OK ", n, mal);
    long mal2=0;
    for(long it=0;it<n;it++){
        fe_t a,r1,r2;
        for(int k=0;k<4;k++) a[k]=(rnd()%3==0)?esp[rnd()%8]:rnd();
        if(it<4096){ for(int k=0;k<4;k++) a[k]=esp[(it>>(3*k))&7]; }
        fe_sqr_c(r1,a); fe_sqr(r2,a);
        fe_t r3; fe_mul_c(r3,a,a);
        if(memcmp(r1,r2,32)||memcmp(r1,r3,32)){ if(mal2<3) printf("MAL sqr en %ld\n",it); mal2++; }
    }
    printf("%s  fe_sqr (asm) == fe_sqr_c == fe_mul_c(a,a) en %ld (%ld distintos)\n", mal2?"MAL":"OK ", n, mal2);
    return (mal||mal2)!=0;
}
#endif
