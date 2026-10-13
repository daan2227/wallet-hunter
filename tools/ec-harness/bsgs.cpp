/* Prueba del motor BSGS: resuelve logaritmos cuyo valor se CONOCE y comprueba
 * que sale exactamente ese. Un solver roto "no encuentra nada", que desde fuera
 * se ve igual que "todavía no" — por eso esta prueba compara contra la respuesta.
 *
 *   tools/ec-harness/bsgs.sh /ruta/a/secp256k1
 */
#include "../../app/src/main/cpp/bsgs.h"
#include <secp256k1.h>
#include <stdio.h>
#include <string.h>

static void pub_de_k(secp256k1_context *ctx, uint64_t k, uint8_t out33[33]){
    uint8_t k32[32]={0}; for(int i=0;i<8;i++) k32[31-i]=(uint8_t)(k>>(8*i));
    secp256k1_pubkey pk; secp256k1_ec_pubkey_create(ctx,&pk,k32);
    size_t l=33; secp256k1_ec_pubkey_serialize(ctx,out33,&l,&pk,SECP256K1_EC_COMPRESSED);
}
static void be(uint64_t v, uint8_t o[32]){ memset(o,0,32); for(int i=0;i<8;i++) o[31-i]=(uint8_t)(v>>(8*i)); }

int main(){
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    struct Caso{ uint64_t a,b,k; };
    Caso casos[]={
        {1,          (1ULL<<20),      700000},
        {1,          (1ULL<<20),      1},             /* k == a */
        {0,          (1ULL<<20)-1,    (1ULL<<20)-1},  /* k == b (extremo) */
        {0,          (1ULL<<20)-1,    524288},
        {1,          (1ULL<<24),      12000000},
        {1u<<16,     (1u<<18),        200000},
        {1,          (1ULL<<28),      200000000ULL},
        {1000000000ULL, 1000000000ULL+(1ULL<<22), 1000000000ULL+123456},
    };
    int fallos=0;
    for(auto &c:casos){
        uint8_t a32[32],b32[32],pub[33],out[32]={0}; be(c.a,a32); be(c.b,b32); pub_de_k(ctx,c.k,pub);
        std::atomic<bool> run{true}; std::atomic<uint64_t> prog{0};
        int r=bsgs_solve(ctx,pub,33,a32,b32,22,&run,&prog,out);
        uint64_t got=0; for(int i=24;i<32;i++) got=(got<<8)|out[i];
        if(r==BSGS_HALLADO && got==c.k)
            printf("  OK   k=%llu  (a=%llu b=%llu, %llu tanteos)\n",
                   (unsigned long long)c.k,(unsigned long long)c.a,(unsigned long long)c.b,(unsigned long long)prog.load());
        else { printf("  MAL  k=%llu  r=%d got=%llu\n",(unsigned long long)c.k,r,(unsigned long long)got); fallos++; }
    }
    /* k FUERA del rango: no debe encontrarlo (ni inventar una clave). */
    {
        uint8_t a32[32],b32[32],pub[33],out[32]={0}; be(1000,a32); be(2000,b32); pub_de_k(ctx,5000,pub);
        std::atomic<bool> run{true}; std::atomic<uint64_t> prog{0};
        int r=bsgs_solve(ctx,pub,33,a32,b32,22,&run,&prog,out);
        if(r==BSGS_NO) printf("  OK   fuera de rango: no encontrado\n");
        else { printf("  MAL  fuera de rango r=%d\n",r); fallos++; }
    }
    /* Multi-objetivo: UNA tabla baby compartida, varias pubkeys del mismo rango. */
    {
        uint8_t a32[32],b32[32]; be(1, a32); be(1ULL<<22, b32);
        BsgsCtx c; std::atomic<bool> run{true};
        int rb=bsgs_build(ctx,a32,b32,22,&run,&c);
        if(rb!=BSGS_HALLADO){ printf("  MAL  build multi rb=%d\n",rb); fallos++; }
        else {
            uint64_t ks[]={ 7, 1234567, (1ULL<<22), 3000001, 999999 };
            int ok=0;
            for(uint64_t k:ks){
                uint8_t pub[33]; pub_de_k(ctx,k,pub); uint8_t out[32]={0};
                std::atomic<uint64_t> prog{0};
                int r=bsgs_giant(ctx,&c,pub,33,&run,&prog,out);
                uint64_t got=0; for(int i=24;i<32;i++) got=(got<<8)|out[i];
                if(r==BSGS_HALLADO && got==k) ok++; else printf("  MAL  multi k=%llu r=%d got=%llu\n",(unsigned long long)k,r,(unsigned long long)got);
            }
            bsgs_ctx_free(&c);
            if(ok==(int)(sizeof(ks)/sizeof(ks[0]))) printf("  OK   multi-objetivo: %d/%d con tabla compartida\n",ok,ok);
            else fallos++;
        }
    }
    /* Reanudar: el barrido se trocea (100 pasos por trozo), se guarda el estado
       y se reanuda hasta el final. Tiene que dar LA MISMA clave que sin cortes. */
    {
        struct RC{ uint64_t a,b,k; };
        RC rcs[]={ {1,(1ULL<<20),700000}, {1,(1ULL<<20),1}, {0,(1ULL<<20)-1,(1ULL<<20)-1}, {1,(1ULL<<24),12000000} };
        for(auto &c:rcs){
            uint8_t a32[32],b32[32],pub[33],out[32]={0}; be(c.a,a32); be(c.b,b32); pub_de_k(ctx,c.k,pub);
            BsgsCtx bc; std::atomic<bool> run{true};
            if(bsgs_build(ctx,a32,b32,22,&run,&bc)!=BSGS_HALLADO){ printf("  MAL  reanudar: build\n"); fallos++; continue; }
            BsgsReanuda re; memset(&re,0,sizeof(re));
            int r=BSGS_NO; int trozos=0;
            do { r=bsgs_giant_ck(ctx,&bc,pub,33,&run,nullptr,out,&re,100); trozos++; }
            while(r==BSGS_NO && re.valido && trozos<100000);
            uint64_t got=0; for(int i=24;i<32;i++) got=(got<<8)|out[i];
            if(r==BSGS_HALLADO && got==c.k) printf("  OK   reanudar k=%llu en %d trozos\n",(unsigned long long)c.k,trozos);
            else { printf("  MAL  reanudar k=%llu r=%d got=%llu\n",(unsigned long long)c.k,r,(unsigned long long)got); fallos++; }
            bsgs_ctx_free(&bc);
        }
        /* Un estado de OTRO objetivo no debe torcer el resultado: se descarta. */
        {
            uint8_t a32[32],b32[32]; be(1,a32); be(1ULL<<20,b32);
            BsgsCtx bc; std::atomic<bool> run{true};
            bsgs_build(ctx,a32,b32,22,&run,&bc);
            uint8_t pub1[33],pub2[33],out[32]={0};
            pub_de_k(ctx,300000,pub1); pub_de_k(ctx,654321,pub2);
            BsgsReanuda re; memset(&re,0,sizeof(re));
            bsgs_giant_ck(ctx,&bc,pub1,33,&run,nullptr,out,&re,50);   /* estado de la clave 300000 */
            int r=bsgs_giant_ck(ctx,&bc,pub2,33,&run,nullptr,out,&re,0); /* se busca 654321 con ese estado */
            uint64_t got=0; for(int i=24;i<32;i++) got=(got<<8)|out[i];
            if(r==BSGS_HALLADO && got==654321) printf("  OK   estado de otro objetivo descartado\n");
            else { printf("  MAL  estado ajeno r=%d got=%llu\n",r,(unsigned long long)got); fallos++; }
            bsgs_ctx_free(&bc);
        }
    }
    printf(fallos? "FALLOS %d\n" : "TODO CORRECTO\n", fallos);
    secp256k1_context_destroy(ctx);
    return fallos?1:0;
}
