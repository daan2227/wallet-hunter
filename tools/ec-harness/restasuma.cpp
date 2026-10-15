/* fe_sub y fe_add sin saltos (jac_batch.h) dan EXACTAMENTE lo mismo que las
 * versiones con if de antes, copiadas aquí como referencia: entradas al azar
 * y casos límite (0, 1, p-1, iguales, cerca de p, alias r==a / r==b). */
#include <stdio.h>
#include <string.h>
#include "../../app/src/main/cpp/jac_batch.h"
static void ref_sub(fe_t r,const fe_t a,const fe_t b){
    uint64_t borrow=0;
    for(int i=0;i<4;i++){ __uint128_t t=(__uint128_t)a[i]-b[i]-borrow; r[i]=(uint64_t)t; borrow=(t>>127)&1; }
    if(borrow){ uint64_t carry=0; for(int i=0;i<4;i++){ __uint128_t t=(__uint128_t)r[i]+FP[i]+carry; r[i]=(uint64_t)t; carry=(uint64_t)(t>>64); } }
}
static void ref_add(fe_t r,const fe_t a,const fe_t b){
    uint64_t carry=0;
    for(int i=0;i<4;i++){ __uint128_t t=(__uint128_t)a[i]+b[i]+carry; r[i]=(uint64_t)t; carry=(uint64_t)(t>>64); }
    if(carry||fe_cmp(r,FP)>=0){ uint64_t borrow=0; for(int i=0;i<4;i++){ __uint128_t t=(__uint128_t)r[i]-FP[i]-borrow; r[i]=(uint64_t)t; borrow=(t>>127)&1; } }
}
static uint64_t s=0x243F6A8885A308D3ULL;
static uint64_t rnd(){ s^=s<<13; s^=s>>7; s^=s<<17; return s; }
static void azar(fe_t x){ do{ for(int i=0;i<4;i++) x[i]=rnd(); } while(fe_cmp(x,FP)>=0); }
int main(){
    int f=0; long n=0;
    fe_t esp[8]; memset(esp,0,sizeof esp);
    esp[1][0]=1;                                  /* 1 */
    memcpy(esp[2],FP,32); esp[2][0]-=1;           /* p-1 */
    memcpy(esp[3],FP,32); esp[3][0]-=2;           /* p-2 */
    esp[4][0]=0x1000003D0ULL;                     /* 2^256-p - 1 */
    for(int i=0;i<4;i++) esp[5][i]=~0ULL>>1;      /* 2^255 aprox */
    esp[6][3]=1;                                  /* 2^192 */
    memcpy(esp[7],FP,32); esp[7][0]-=0x1000003D1ULL; /* p - (2^256-p) */
    auto prueba=[&](const fe_t a,const fe_t b){
        fe_t r1,r2,r3,r4;
        fe_sub(r1,a,b); ref_sub(r2,a,b); fe_add(r3,a,b); ref_add(r4,a,b);
        if(memcmp(r1,r2,32)||memcmp(r3,r4,32)) f++;
        fe_t x; memcpy(x,a,32); fe_sub(x,x,b); if(memcmp(x,r2,32)) f++;   /* r == a */
        memcpy(x,b,32); fe_add(x,a,x); if(memcmp(x,r4,32)) f++;           /* r == b */
        n++;
    };
    for(int i=0;i<8;i++) for(int j=0;j<8;j++) prueba(esp[i],esp[j]);
    for(int i=0;i<3000000;i++){ fe_t a,b; azar(a); azar(b); prueba(a,b); }
    for(int i=0;i<200000;i++){ fe_t a; azar(a); prueba(a,a); prueba(a,esp[i&7]); prueba(esp[i&7],a); }
    printf("%s  fe_sub/fe_add == versión anterior en %ld casos (%d distintos)\n", f?"MAL":"OK ", n, f);
    printf(f?"FALLOS\n":"TODO CORRECTO\n"); return f?1:0;
}
