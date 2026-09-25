/* El escaner de claves (escaner_raw.h) contra secp256k1: cada una de las
 * 6 x 1025 claves publicas de un grupo tiene que ser la de la clave privada
 * que dice esc_clave, y el centro tiene que avanzar 1025.
 * Se compila con con_secp.sh (necesita secp256k1). Con un argumento, mide. */
#include <stdio.h>
#include <stdlib.h>
#include <chrono>
#include "../../app/src/main/cpp/escaner_raw.h"
#include "../../app/src/main/cpp/sha256_ripemd160.h"

struct Visto { uint8_t h[6][ESC_GRUPO][20]; long n; };
static void anotar(const uint8_t *h,int j,int v,void *c){ Visto *V=(Visto*)c; memcpy(V->h[v][j+ESC_M],h,20); V->n++; }
static void contar(const uint8_t *h,int,int,void *c){ (*(long*)c)+=h[0]; }

int main(int argc,char**argv){
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    static EscTabla T; esc_tabla_crear(&T);
    static fe_t dx[ESC_M+1],pfx[ESC_M+1];
    uint8_t kc[32]; uint64_t s=0x1234567890ABCDEFULL;
    for(int i=0;i<32;i++){ s^=s<<13; s^=s>>7; s^=s<<17; kc[i]=(uint8_t)s; }
    auto centro=[&](const uint8_t *k,fe_t x,fe_t y){
        secp256k1_pubkey pk; secp256k1_ec_pubkey_create(ctx,&pk,k);
        uint8_t p65[65]; size_t l=65; secp256k1_ec_pubkey_serialize(ctx,p65,&l,&pk,SECP256K1_EC_UNCOMPRESSED);
        JP P; jp_from_affine(&P,p65); memcpy(x,P.x,32); memcpy(y,P.y,32);
    };
    fe_t cx,cy; centro(kc,cx,cy);
    if(argc>1){
        long sal=0; int G=atoi(argv[1]); auto t0=std::chrono::steady_clock::now();
        for(int g=0;g<G;g++) esc_grupo(&T,cx,cy,dx,pfx,contar,&sal);
        double seg=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
        printf("%.2f M claves/s (%d grupos, 1 hilo) %ld\n",(double)G*ESC_GRUPO*6/seg/1e6,G,sal&1);
        return 0;
    }
    static Visto V; V.n=0;
    int fallos=0;
    int ok=esc_grupo(&T,cx,cy,dx,pfx,anotar,&V);
    long mal=0;
    for(int v=0;v<6;v++) for(int j=-ESC_M;j<=ESC_M;j++){
        uint8_t k[32]; if(!esc_clave(ctx,kc,j,v,k)){ mal++; continue; }
        secp256k1_pubkey pk; secp256k1_ec_pubkey_create(ctx,&pk,k);
        uint8_t p33[33]; size_t l=33; secp256k1_ec_pubkey_serialize(ctx,p33,&l,&pk,SECP256K1_EC_COMPRESSED);
        uint8_t h[20]; hash160_inline(p33,h);
        if(memcmp(h,V.h[v][j+ESC_M],20)) mal++;
    }
    printf("%s  %ld claves de un grupo, %ld que no son la de su clave privada\n",(ok&&V.n==6L*ESC_GRUPO&&!mal)?"OK ":"MAL",V.n,mal);
    fallos+=!(ok&&V.n==6L*ESC_GRUPO&&!mal);
    /* El centro avanza ESC_GRUPO. */
    uint8_t k2[32]; memcpy(k2,kc,32);
    uint8_t tw[32]={0}; tw[30]=(uint8_t)(ESC_GRUPO>>8); tw[31]=(uint8_t)ESC_GRUPO;
    secp256k1_ec_seckey_tweak_add(ctx,k2,tw);
    fe_t x2,y2; centro(k2,x2,y2);
    int av=!memcmp(x2,cx,32)&&!memcmp(y2,cy,32);
    printf("%s  el centro siguiente es el de la clave + %d\n",av?"OK ":"MAL",ESC_GRUPO); fallos+=!av;
    /* Y el grupo siguiente tambien cuadra (el primero no era casualidad). */
    V.n=0; esc_grupo(&T,cx,cy,dx,pfx,anotar,&V); mal=0;
    for(int t=0;t<200;t++){
        int v=t%6, j=(t*37)%ESC_GRUPO-ESC_M; uint8_t k[32]; esc_clave(ctx,k2,j,v,k);
        secp256k1_pubkey pk; secp256k1_ec_pubkey_create(ctx,&pk,k);
        uint8_t p33[33]; size_t l=33; secp256k1_ec_pubkey_serialize(ctx,p33,&l,&pk,SECP256K1_EC_COMPRESSED);
        uint8_t h[20]; hash160_inline(p33,h); if(memcmp(h,V.h[v][j+ESC_M],20)) mal++;
    }
    printf("%s  segundo grupo: 200 claves comprobadas, %ld mal\n",mal?"MAL":"OK ",mal); fallos+=mal!=0;
    printf("\n%s\n",fallos?"HAY FALLOS":"TODO CORRECTO");
    return fallos;
}
