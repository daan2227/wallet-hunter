/* El minero nativo (app/src/main/cpp/minero.h) con la compresión de software:
 *
 *   g++ -O2 -o minero minero.cpp && ./minero
 *
 * 1) Busca el nonce del bloque 1 en una ventana alrededor del real, con el objetivo de sus bits.
 * 2) Compara el mejor hash de una tanda con el doble SHA-256 hecho a mano (sha256_block normal, sin midstate).
 * 3) Mide la velocidad. */
#include <stdio.h>
#include <chrono>
#include "../../app/src/main/cpp/minero.h"

static void hexb(const char *h, uint8_t *o, int n){ for(int i=0;i<n;i++){ unsigned v; sscanf(h+2*i,"%2x",&v); o[i]=(uint8_t)v; } }

static void dsha80(const uint8_t *c, uint8_t *out){
    uint32_t s[8]; memcpy(s,MIN_H0,32); sha256_block(s,c);
    uint8_t b[64]={0}; memcpy(b,c+64,16); b[16]=0x80; b[62]=2; b[63]=0x80; sha256_block(s,b);
    uint8_t b2[64]={0}; for(int i=0;i<8;i++){ b2[4*i]=s[i]>>24; b2[4*i+1]=s[i]>>16; b2[4*i+2]=s[i]>>8; b2[4*i+3]=s[i]; }
    b2[32]=0x80; b2[62]=1; uint32_t t[8]; memcpy(t,MIN_H0,32); sha256_block(t,b2);
    for(int i=0;i<8;i++){ out[4*i]=t[i]>>24; out[4*i+1]=t[i]>>16; out[4*i+2]=t[i]>>8; out[4*i+3]=t[i]; }
}

int main(){
    /* Cabecera del bloque 1 con nonce 0. */
    uint8_t cab[80];
    hexb("010000006fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677ba1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d00000000",cab,80);
    uint32_t obj[8]={0x00000000,0xffff0000,0,0,0,0,0,0};
    MinResultado r;
    uint32_t real=2573394689u;
    uint64_t h=min_tanda<sha256_block>(cab,real-100000,200000,obj,&r);
    int f=0;
    printf("%s  bloque 1: nonce %lld tras %llu hashes (esperado %u)\n", r.encontrado==(int64_t)real?"OK ":"MAL", (long long)r.encontrado,(unsigned long long)h,real);
    f|=r.encontrado!=(int64_t)real || h!=100001;
    printf("%s  mejor hash de la tanda: %d ceros\n", r.mejor_ceros==32?"OK ":"MAL", r.mejor_ceros); f|=r.mejor_ceros!=32;

    /* Objetivo imposible: recorre la tanda entera; el mejor nonce, rehecho a mano, da esos ceros. */
    uint32_t cero[8]={0};
    h=min_tanda<sha256_block>(cab,12345,50000,cero,&r);
    uint8_t c2[80]; memcpy(c2,cab,80); uint32_t n=r.mejor_nonce; c2[76]=n; c2[77]=n>>8; c2[78]=n>>16; c2[79]=n>>24;
    uint8_t d[32]; dsha80(c2,d);
    int z=0; for(int i=31;i>=0;i--){ if(d[i]==0){ z+=8; continue; } z+=__builtin_clz((uint32_t)d[i])-24; break; }
    printf("%s  50000 nonces sin premio, mejor %d ceros == %d a mano\n", (r.encontrado==-1&&h==50000&&z==r.mejor_ceros)?"OK ":"MAL", r.mejor_ceros, z);
    f|=!(r.encontrado==-1&&h==50000&&z==r.mejor_ceros);

    /* Al final del espacio de nonces se para en 0xffffffff. */
    h=min_tanda<sha256_block>(cab,0xfffffff0u,100,cero,&r);
    printf("%s  se para en el último nonce (%llu hashes)\n", h==16?"OK ":"MAL",(unsigned long long)h); f|=h!=16;

    auto t0=std::chrono::steady_clock::now();
    h=min_tanda<sha256_block>(cab,0,3000000,cero,&r);
    double s=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
    printf("     %.2f MH/s (software, un hilo, esta máquina)\n", h/s/1e6);
    return f;
}
