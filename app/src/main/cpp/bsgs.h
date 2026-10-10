#pragma once
/* Baby-step / Giant-step para el logaritmo discreto en un intervalo PEQUENO y
 * conocido: dado P = k*G con k en [a,b], encontrar k de forma DETERMINISTA.
 *
 * Idea: con m ~ raiz(W) (W = b-a+1):
 *   - Baby:  tabla { x(j*G) -> j } para j en [1, m).
 *   - Giant: Q_i = P - (a + i*m)*G, para i en [0, ceil(W/m)). Si Q_i es el
 *            infinito -> k = a + i*m. Si x(Q_i) esta en la tabla con indice j,
 *            el punto es j*G o -j*G, asi que k = a + i*m +/- j. Se COMPRUEBA
 *            k*G == P con secp256k1 antes de darlo por bueno: un fallo de la
 *            tabla (o una colision de x) hace que NO encuentre, nunca que
 *            devuelva una clave falsa.
 *
 * La curva (jac_batch.h) hace las sumas; secp256k1 hace a*G, m*G, el parseo del
 * objetivo y la comprobacion final. Pensado para rangos W <= 2^63 (los grandes
 * son para Kangaroo). Probado en tools/ec-harness/bsgs.cpp contra claves cuyo
 * logaritmo se conoce. */
#include "jac_batch.h"
#include <secp256k1.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <atomic>

/* Resultado del intento. */
enum { BSGS_HALLADO=1, BSGS_NO=0, BSGS_RANGO_GRANDE=-1, BSGS_ERROR=-2 };

/* Tabla hash abierta: low64(x(jG)) -> j. */
struct BsgsTabla { uint64_t *key; uint32_t *val; uint64_t mask; };

static inline uint64_t bsgs_mix(uint64_t v){ v^=v>>33; v*=0xff51afd7ed558ccdULL; v^=v>>33; return v; }

/* x afin (low 64 bits) de un punto Jacobiano. */
static inline uint64_t bsgs_xlow(const JP *P){
    fe_t zi,z2,x; fe_inv(zi,(uint64_t*)P->z); fe_sqr(z2,zi); fe_mul(x,(uint64_t*)P->x,z2);
    return x[0];
}
static inline void bsgs_xaff(const JP *P, fe_t xout){
    fe_t zi,z2; fe_inv(zi,(uint64_t*)P->z); fe_sqr(z2,zi); fe_mul(xout,(uint64_t*)P->x,z2);
}

/* k (32 bytes big-endian) = base(32 be) + off (con signo). Devuelve 0 si k<=0. */
static inline int bsgs_k_mas(const uint8_t base[32], uint64_t off, uint8_t out[32]){
    memcpy(out,base,32);
    uint64_t carry=off;
    for(int i=31;i>=0 && carry;i--){ uint64_t s=(uint64_t)out[i]+(carry&0xff); out[i]=(uint8_t)s; carry=(carry>>8)+(s>>8); }
    return 1;
}
static inline int bsgs_k_menos(const uint8_t base[32], uint64_t off, uint8_t out[32]){
    memcpy(out,base,32);
    uint64_t borrow=off;
    for(int i=31;i>=0 && borrow;i--){ uint64_t d=(uint64_t)out[i]-(borrow&0xff); out[i]=(uint8_t)d; borrow=(borrow>>8)+((d>>63)&1); }
    if(borrow) return 0;   /* se fue por debajo de 0 */
    int cero=1; for(int i=0;i<32;i++) if(out[i]){cero=0;break;} return !cero;
}

/* ¿k*G == objetivo? (comprobacion final, con secp256k1). */
static int bsgs_verifica(secp256k1_context *ctx, const uint8_t k[32], const uint8_t *obj33){
    if(!secp256k1_ec_seckey_verify(ctx,k)) return 0;
    secp256k1_pubkey pk; if(!secp256k1_ec_pubkey_create(ctx,&pk,k)) return 0;
    uint8_t p33[33]; size_t l=33; secp256k1_ec_pubkey_serialize(ctx,p33,&l,&pk,SECP256K1_EC_COMPRESSED);
    return memcmp(p33,obj33,33)==0;
}

/* Construye Q0 = objetivo - a*G (afin, en pub65 de salida). a en 32 be.
 * Devuelve 0 si Q0 es el infinito (es decir k==a). */
static int bsgs_menos_aG(secp256k1_context *ctx, const secp256k1_pubkey *obj,
                         const uint8_t a[32], uint8_t q65[65], int *es_inf){
    *es_inf=0;
    int a_cero=1; for(int i=0;i<32;i++) if(a[i]){a_cero=0;break;}
    if(a_cero){ size_t l=65; secp256k1_ec_pubkey_serialize(ctx,q65,&l,obj,SECP256K1_EC_UNCOMPRESSED); return 1; }
    secp256k1_pubkey aG; if(!secp256k1_ec_pubkey_create(ctx,&aG,a)) return 0;
    if(!secp256k1_ec_pubkey_negate(ctx,&aG)) return 0;
    const secp256k1_pubkey *ins[2]={obj,&aG}; secp256k1_pubkey q;
    if(!secp256k1_ec_pubkey_combine(ctx,&q,ins,2)){ *es_inf=1; return 1; }  /* suma = infinito -> k==a */
    size_t l=65; secp256k1_ec_pubkey_serialize(ctx,q65,&l,&q,SECP256K1_EC_UNCOMPRESSED); return 1;
}

/* Intento BSGS. a32/b32: [a,b] inclusivo, big-endian. cap_bits acota la tabla
 * (m <= 2^cap_bits). run: bandera de parada (puede ser null). progress: claves
 * tanteadas (puede ser null). out32: la clave si BSGS_HALLADO. */
static int bsgs_solve(secp256k1_context *ctx,
                      const uint8_t *pub, size_t publen,
                      const uint8_t a32[32], const uint8_t b32[32],
                      int cap_bits,
                      std::atomic<bool> *run, std::atomic<uint64_t> *progress,
                      uint8_t out32[32]){
    secp256k1_pubkey obj;
    if(!secp256k1_ec_pubkey_parse(ctx,&obj,pub,publen)) return BSGS_ERROR;
    uint8_t obj33[33]; size_t l33=33; secp256k1_ec_pubkey_serialize(ctx,obj33,&l33,&obj,SECP256K1_EC_COMPRESSED);

    /* W = b - a + 1, exigiendo que quepa en 64 bits (rangos grandes -> Kangaroo). */
    for(int i=0;i<24;i++) if(a32[i]||b32[i]) return BSGS_RANGO_GRANDE;   /* > 2^64 de a o b */
    uint64_t a64=0,b64=0; for(int i=24;i<32;i++){ a64=(a64<<8)|a32[i]; b64=(b64<<8)|b32[i]; }
    if(b64<a64) return BSGS_ERROR;
    if(b64-a64 > (1ULL<<62)) return BSGS_RANGO_GRANDE;
    uint64_t W = b64 - a64 + 1;

    /* m = min(ceil(raiz(W)), 2^cap). M = ceil(W/m). */
    uint64_t m = (uint64_t)ceil(sqrt((double)W)); if(m<1) m=1;
    uint64_t cap = (cap_bits>0 && cap_bits<40) ? (1ULL<<cap_bits) : (1ULL<<22);
    if(m>cap) m=cap;
    uint64_t M = (W + m - 1) / m;
    if(M > (1ULL<<40)) return BSGS_RANGO_GRANDE;   /* demasiados pasos giant */

    /* Tabla hash: tam potencia de dos, factor de carga ~0.6. */
    uint64_t cells=4; while(cells < m*5/3) cells<<=1;
    BsgsTabla t; t.mask=cells-1;
    t.key=(uint64_t*)calloc(cells,sizeof(uint64_t));
    t.val=(uint32_t*)malloc(cells*sizeof(uint32_t));
    if(!t.key||!t.val){ free(t.key); free(t.val); return BSGS_ERROR; }
    for(uint64_t i=0;i<cells;i++) t.val[i]=0xFFFFFFFFu;

    /* ---- Baby steps: B_j = j*G, j en [1,m) ---- */
    const int CH=1024;
    JP *buf=(JP*)malloc(sizeof(JP)*CH);
    fe_t *pf=(fe_t*)malloc(sizeof(fe_t)*CH);
    if(!buf||!pf){ free(buf);free(pf);free(t.key);free(t.val); return BSGS_ERROR; }
    JP cur; memcpy(cur.x,FIELD_GX,32); memcpy(cur.y,FIELD_GY,32); memset(cur.z,0,32); cur.z[0]=1; /* 1*G */
    auto insertar=[&](uint64_t xl, uint32_t j){
        uint64_t h=bsgs_mix(xl)&t.mask;
        while(t.val[h]!=0xFFFFFFFFu){ if(t.key[h]==xl) return; h=(h+1)&t.mask; }
        t.key[h]=xl; t.val[h]=j;
    };
    uint64_t j=1;
    while(j<m){
        if(run && !run->load()) { free(buf);free(pf);free(t.key);free(t.val); return BSGS_NO; }
        int n=0;
        for(; j<m && n<CH; j++,n++){
            buf[n]=cur;
            /* siguiente: 2G con doblado, el resto +G */
            if(j==1) jp_dbl(&cur,&cur); else jp_add_G(&cur,&cur);
        }
        /* normalizar el lote (una inversion) y sacar x afin low64 */
        bool bad=false; for(int i=0;i<n;i++){ bool z=true; for(int w=0;w<4;w++) if(buf[i].z[w]){z=false;break;} if(z){bad=true;break;} }
        if(bad){ for(int i=0;i<n;i++) insertar(bsgs_xlow(&buf[i]), (uint32_t)(j-n+i)); continue; }
        memcpy(pf[0],buf[0].z,32);
        for(int i=1;i<n;i++) fe_mul(pf[i],pf[i-1],buf[i].z);
        fe_t inv; fe_inv(inv,pf[n-1]);
        for(int i=n-1;i>=0;i--){
            fe_t zi,z2,x;
            if(i){ fe_mul(zi,inv,pf[i-1]); fe_mul(inv,inv,buf[i].z); } else memcpy(zi,inv,32);
            fe_sqr(z2,zi); fe_mul(x,buf[i].x,z2);
            insertar(x[0], (uint32_t)(j-n+i));
        }
    }
    free(buf); free(pf);

    /* ---- Giant steps ---- */
    /* S = m*G (afin), y -S para restar. */
    uint8_t m32[32]={0}; { uint64_t v=m; for(int i=0;i<8;i++) m32[31-i]=(uint8_t)(v>>(8*i)); }
    secp256k1_pubkey Spk; if(!secp256k1_ec_pubkey_create(ctx,&Spk,m32)){ free(t.key);free(t.val); return BSGS_ERROR; }
    uint8_t s65[65]; size_t ls=65; secp256k1_ec_pubkey_serialize(ctx,s65,&ls,&Spk,SECP256K1_EC_UNCOMPRESSED);
    JP SJ; jp_from_affine(&SJ,s65);
    fe_t Sx,Sy,negSy; memcpy(Sx,SJ.x,32); memcpy(Sy,SJ.y,32);
    static const fe_t CERO={0,0,0,0}; fe_sub(negSy,CERO,Sy);

    /* Q0 = obj - a*G */
    int inf=0; uint8_t q65[65];
    if(!bsgs_menos_aG(ctx,&obj,a32,q65,&inf)){ free(t.key);free(t.val); return BSGS_ERROR; }
    int ret=BSGS_NO;
    if(inf){ if(bsgs_verifica(ctx,a32,obj33)){ memcpy(out32,a32,32); ret=BSGS_HALLADO; } }
    if(ret!=BSGS_HALLADO){
        JP Q; jp_from_affine(&Q,q65);
        for(uint64_t i=0;i<M;i++){
            if(run && !run->load()){ ret=BSGS_NO; break; }
            /* ¿Q es el infinito? -> k = a + i*m */
            bool zz=true; for(int w=0;w<4;w++) if(Q.z[w]){zz=false;break;}
            if(zz){ uint8_t k[32]; if(bsgs_k_mas(a32,i*m,k) && bsgs_verifica(ctx,k,obj33)){ memcpy(out32,k,32); ret=BSGS_HALLADO; break; } }
            else {
                fe_t xa; bsgs_xaff(&Q,xa);
                uint64_t h=bsgs_mix(xa[0])&t.mask;
                while(t.val[h]!=0xFFFFFFFFu){
                    if(t.key[h]==xa[0]){
                        uint32_t jj=t.val[h];
                        uint8_t k[32];
                        if(bsgs_k_mas(a32, i*m + jj, k) && bsgs_verifica(ctx,k,obj33)){ memcpy(out32,k,32); ret=BSGS_HALLADO; break; }
                        /* k = a + i*m - jj (simetria de negacion; puede caer por debajo de a) */
                        if(i*m>=jj){ if(bsgs_k_mas(a32, i*m - jj, k) && bsgs_verifica(ctx,k,obj33)){ memcpy(out32,k,32); ret=BSGS_HALLADO; break; } }
                        else { if(bsgs_k_menos(a32, jj - i*m, k) && bsgs_verifica(ctx,k,obj33)){ memcpy(out32,k,32); ret=BSGS_HALLADO; break; } }
                    }
                    h=(h+1)&t.mask;
                }
                if(ret==BSGS_HALLADO) break;
            }
            /* Q -= m*G. Caso degenerado (x(Q)==Sx): recomponer desde secp. */
            fe_t xcur; bsgs_xaff(&Q,xcur);
            if(memcmp(xcur,Sx,32)==0){
                uint8_t off[32]={0}; uint64_t v=(i+1)*m; for(int q=0;q<8;q++) off[31-q]=(uint8_t)(v>>(8*q));
                uint8_t k[32]; bsgs_k_mas(a32,(i+1)*m,k);
                uint8_t q2[65]; int inf2=0;
                /* Q_{i+1} = obj - (a+(i+1)m)G */
                if(bsgs_menos_aG(ctx,&obj,k,q2,&inf2) && !inf2) jp_from_affine(&Q,q2);
                else { memset(Q.z,0,32); }  /* infinito: el bucle lo coge arriba la proxima */
            } else {
                jp_add_affine(&Q,&Q,Sx,negSy);
            }
            if(progress) progress->fetch_add(m, std::memory_order_relaxed);
        }
    }
    free(t.key); free(t.val);
    return ret;
}
