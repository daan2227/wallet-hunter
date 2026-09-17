/* El tamano de lote OPTIMO del bucle de fuerza bruta.
 *
 * El deslizador de la app es uno solo y lo comparten tres motores: el escaner
 * en modo clave directa, el puzzle por fuerza bruta y Kangaroo. Para Kangaroo
 * ya esta medido que por encima de ~2048 empeora. Aqui se mide el OTRO bucle:
 * generacion secuencial de puntos + inversion por lotes + hash160, que es
 * literalmente lo que hacen worker_rawkey y worker_puzzle.
 *
 * No tienen por que coincidir: este bucle no lleva una tabla de puntos
 * distinguidos machacando la cache, asi que puede aguantar lotes mayores. */
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <chrono>
#include "../../app/src/main/cpp/jac_batch.h"
#include "../../app/src/main/cpp/sha256_ripemd160.h"

static volatile uint64_t sink = 0;
static void con_hash(int, const uint8_t *p33, void*){
    uint8_t h[20]; hash160_inline(p33, h); sink ^= h[0];
}
int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    uint8_t g[65]={0x04};
    const char*X="C6047F9441ED7D6D3045406E95C07CD85C778E4B8CEF3CA7ABAC09B95C709EE5";
    const char*Y="1AE168FEA63DC339A3C58419466CEAEEF7F632653266D0E1236431A950CFE52A";
    for(int i=0;i<32;i++){unsigned v;sscanf(X+i*2,"%2x",&v);g[1+i]=v;}
    for(int i=0;i<32;i++){unsigned v;sscanf(Y+i*2,"%2x",&v);g[33+i]=v;}

    const int LOTES[]={64,128,256,512,1024,2048,4096,8192,16000,32000};
    printf("  lote    claves/s     memoria\n");
    for(unsigned k=0;k<sizeof(LOTES)/sizeof(LOTES[0]);k++){
        int N=LOTES[k];
        JP *pts=(JP*)malloc(sizeof(JP)*N);
        fe_t *pfx=(fe_t*)malloc(sizeof(fe_t)*N);
        if(!pts||!pfx){ printf("  %5d  sin memoria\n",N); free(pts); free(pfx); continue; }
        /* Mismo numero total de claves en todos, para comparar justo. */
        long long OBJETIVO=4000000;
        int rondas=(int)(OBJETIVO/N); if(rondas<1) rondas=1;
        auto t0=std::chrono::high_resolution_clock::now();
        for(int r=0;r<rondas;r++){
            jp_from_affine(&pts[0],g);
            for(int i=1;i<N;i++) jp_add_G(&pts[i],&pts[i-1]);
            jac_batch_hash160(pts,N,pfx,con_hash,NULL);
        }
        auto t1=std::chrono::high_resolution_clock::now();
        double s=std::chrono::duration<double>(t1-t0).count();
        printf("  %5d   %7.2f M     %5.1f KB\n",
               N, (double)N*rondas/s/1e6, (sizeof(JP)+sizeof(fe_t))*N/1024.0);
        free(pts); free(pfx);
    }
    return (int)(sink&0);
}
