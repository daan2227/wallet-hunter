/* ¿Cuánto del coste por clave se va en el hash160? Si el dataset trajera
   claves públicas en vez de direcciones, ese trozo se podría saltar. */
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <chrono>
#include "../../app/src/main/cpp/jac_batch.h"
#include "../../app/src/main/cpp/sha256_ripemd160.h"

static const int N = 4096;
static JP pts[N];
static fe_t pfx[N];
static volatile uint64_t sink = 0;

static void con_hash(int, const uint8_t *p33, void*){
    uint8_t h[20]; hash160_inline(p33, h); sink ^= h[0];
}
static void sin_hash(int, const uint8_t *p33, void*){
    /* Comparar la x de la clave pública directamente: 8 bytes bastan para
       descartar, y el resto sólo se mira en el candidato que pasa. */
    uint64_t x8; memcpy(&x8, p33+1, 8); sink ^= x8;
}
int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    /* Arranca en 2G para no caer en el doblado. */
    uint8_t g[65]={0x04};
    const char*X="C6047F9441ED7D6D3045406E95C07CD85C778E4B8CEF3CA7ABAC09B95C709EE5";
    const char*Y="1AE168FEA63DC339A3C58419466CEAEEF7F632653266D0E1236431A950CFE52A";
    for(int i=0;i<32;i++){unsigned v;sscanf(X+i*2,"%2x",&v);g[1+i]=v;}
    for(int i=0;i<32;i++){unsigned v;sscanf(Y+i*2,"%2x",&v);g[33+i]=v;}

    const int RONDAS = 40;
    for(int modo=0;modo<2;modo++){
        auto t0=std::chrono::high_resolution_clock::now();
        for(int r=0;r<RONDAS;r++){
            jp_from_affine(&pts[0],g);
            for(int i=1;i<N;i++) jp_add_G(&pts[i],&pts[i-1]);
            jac_batch_hash160(pts,N,pfx, modo? sin_hash : con_hash, NULL);
        }
        auto t1=std::chrono::high_resolution_clock::now();
        double s=std::chrono::duration<double>(t1-t0).count();
        printf("%-28s %.3f s   %.2f M claves/s\n",
               modo? "sin hash (compara la x)" : "con hash160 (como ahora)",
               s, (double)N*RONDAS/s/1e6);
    }
    return (int)(sink&0);
}
