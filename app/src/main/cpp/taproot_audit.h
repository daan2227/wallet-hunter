#pragma once
/* Auditoría de claves Taproot propias: fuerza bruta con el tweak de BIP341.
 *
 * Por qué no sirve Kangaroo ni BSGS aquí: los dos resuelven Q = d·G cuando d
 * está en un rango acotado. En una salida Taproot lo que se ve es
 * Q = P + hash(P)·G con P = d·G (BIP341, sin árbol de scripts). Si se despeja,
 * Q = e·G con e = d + hash(P): aunque d esté en un rango pequeño, e queda
 * disperso por casi todo el cuerpo, porque hash(P) se comporta como un número
 * al azar. Ni Kangaroo ni BSGS tienen nada que aprovechar ahí. El único
 * camino es probar cada d candidata, aplicar el tweak y comparar.
 *
 * Es justo el mismo cálculo que ya hace taproot_tweak_pubkey() en
 * hunter_jni.cpp para derivar direcciones; aquí se repite en un bucle
 * contra una lista de direcciones/claves PROPIAS (no un dataset ajeno), y es
 * aparte del motor del Scanner para no tocar su estado.
 *
 * Se prueba en el escritorio con tools/ec-harness/taproot_audit.cpp.
 */
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <atomic>
#include <algorithm>
#include <secp256k1.h>
#include <openssl/sha.h>
#include "addr_encode.h"
#include "addr_decode.h"
#include "jac_batch.h"

namespace tr_audit {

/* ---- suma de 64 bits a un valor de 32 bytes big-endian, con acarreo ---- */
static inline void be32_add_u64(uint8_t *v, uint64_t add){
    for(int b=31; b>=0 && add; b--){
        uint64_t s=(uint64_t)v[b]+(add&0xFF);
        v[b]=(uint8_t)(s&0xFF); add=(add>>8)+(s>>8);
    }
}

static inline int hex2byte(char c){
    if(c>='0'&&c<='9') return c-'0';
    if(c>='a'&&c<='f') return c-'a'+10;
    if(c>='A'&&c<='F') return c-'A'+10;
    return -1;
}
static inline bool hex2bin(const char *h, size_t n, uint8_t *out, size_t outlen){
    if(n!=outlen*2) return false;
    for(size_t i=0;i<outlen;i++){
        int hi=hex2byte(h[i*2]), lo=hex2byte(h[i*2+1]);
        if(hi<0||lo<0) return false;
        out[i]=(uint8_t)((hi<<4)|lo);
    }
    return true;
}

/* El tweak de BIP341, sin árbol de scripts: t = TaggedHash("TapTweak", x), Q = P + t·G.
 *
 * SHA256("TapTweak") es siempre el mismo valor: se calculaba de nuevo en cada llamada, es decir
 * una vez por cada d candidata. La misma constante ya se guarda una sola vez en hunter_jni.cpp
 * (tagged_hash()); aquí, igual: una variable local estática con inicializador no trivial se
 * construye una sola vez la primera vez que un hilo cualquiera pasa por aquí, y el propio
 * lenguaje (desde C++11) garantiza que esa construcción es segura entre hilos sin mutex propio. */
static bool tweak(secp256k1_context *ctx, const uint8_t *internal_xonly, uint8_t *out_xonly){
    struct TagHash { uint8_t v[32]; TagHash(){ SHA256((const uint8_t*)"TapTweak",8,v); } };
    static const TagHash tag;
    uint8_t t[32];
    { SHA256_CTX c; SHA256_Init(&c); SHA256_Update(&c,tag.v,32); SHA256_Update(&c,tag.v,32);
      SHA256_Update(&c,internal_xonly,32); SHA256_Final(t,&c); }
    uint8_t p33[33]; p33[0]=0x02; memcpy(p33+1,internal_xonly,32);
    secp256k1_pubkey pub;
    if(!secp256k1_ec_pubkey_parse(ctx,&pub,p33,33)) return false;
    if(!secp256k1_ec_pubkey_tweak_add(ctx,&pub,t)) return false;
    uint8_t out33[33]; size_t len=33;
    secp256k1_ec_pubkey_serialize(ctx,out33,&len,&pub,SECP256K1_EC_COMPRESSED);
    memcpy(out_xonly,out33+1,32);
    return true;
}

/* Lista de objetivos: claves x-only de 32 bytes, ordenada, sin repetidos. */
struct Objetivos {
    uint8_t *d = nullptr;
    uint64_t n = 0;
    void liberar(){ free(d); d=nullptr; n=0; }
};

static int cmp32(const void *a, const void *b){ return memcmp(a,b,32); }

/** Lee un fichero con una entrada por línea: una dirección bc1p… o 64 caracteres hex (x-only).
 *  Cualquier otra línea se ignora (no es un error: así se puede pasar el mismo fichero que uno
 *  pegó a mano, con cabeceras o texto suelto). [cupo] limita cuántas se aceptan como mucho. */
static bool cargar(const char *ruta, Objetivos *out, uint64_t cupo, char *status, size_t statuslen){
    out->liberar();
    FILE *f=fopen(ruta,"r");
    if(!f){ snprintf(status,statuslen,"Error: could not open the file"); return false; }
    uint64_t cap=cupo>0?cupo:1; auto *tmp=(uint8_t*)malloc(cap*32);
    if(!tmp){ fclose(f); snprintf(status,statuslen,"Error: out of memory"); return false; }
    uint64_t n=0; char line[512];
    while(fgets(line,sizeof(line),f)){
        size_t len=strlen(line);
        while(len>0 && (line[len-1]=='\n'||line[len-1]=='\r'||line[len-1]==' ')) line[--len]=0;
        char *s=line; while(*s==' '||*s=='\t') s++;
        len=strlen(s);
        if(len==0) continue;
        uint8_t xonly[32];
        bool ok=false;
        if(len>=14 && (!strncmp(s,"bc1p",4) || !strncmp(s,"BC1P",4) ||
                       !strncmp(s,"tb1p",4) || !strncmp(s,"TB1P",4))){
            uint8_t prog[40]; int plen=0;
            int ver=ad_segwit(s,-1,prog,&plen);
            if(ver==1 && plen==32){ memcpy(xonly,prog,32); ok=true; }
        } else if(len==64){
            ok=hex2bin(s,len,xonly,32);
        }
        if(!ok) continue;
        if(n>=cap) break;   /* se llenó el cupo: se avisa al terminar, no es un error */
        memcpy(tmp+n*32,xonly,32); n++;
    }
    fclose(f);
    if(n==0){ free(tmp); snprintf(status,statuslen,"No Taproot address or x-only key found in that file"); return false; }
    qsort(tmp,n,32,cmp32);
    uint64_t m=1;
    for(uint64_t i=1;i<n;i++) if(memcmp(tmp+i*32,tmp+(m-1)*32,32)!=0){ memcpy(tmp+m*32,tmp+i*32,32); m++; }
    out->d=tmp; out->n=m;
    if(n>=cap) snprintf(status,statuslen,"Loaded %llu addresses (the file had more; raise the limit to load them all)",(unsigned long long)m);
    else snprintf(status,statuslen,"Loaded %llu address(es)",(unsigned long long)m);
    return true;
}

static int64_t buscar(const Objetivos *o, const uint8_t *xonly32){
    if(!o->d || !o->n) return -1;
    int64_t lo=0,hi=(int64_t)o->n-1;
    while(lo<=hi){ int64_t mid=(lo+hi)>>1; int c=memcmp(o->d+mid*32,xonly32,32);
        if(!c) return mid; if(c<0) lo=mid+1; else hi=mid-1; }
    return -1;
}

/** Un bloque de d consecutivas desde [start32 + desde, start32 + desde + cuenta). Para cada una que
 *  dé con un objetivo llama a [hallado](privhex, addr) — los 64 y 76 caracteres de siempre. Devuelve
 *  cuántas probó (para el progreso), que es siempre [cuenta] salvo que [parar] se ponga a true a mitad.
 */
template <typename F>
static uint64_t escanear_bloque(secp256k1_context *ctx, const Objetivos *o,
                                const uint8_t *start32, uint64_t desde, uint64_t cuenta,
                                const std::atomic<bool> &parar, F hallado){
    uint8_t priv[32]; memcpy(priv,start32,32); be32_add_u64(priv,desde);
    uint64_t hecho=0;
    for(uint64_t i=0;i<cuenta;i++, hecho++){
        if((i&1023)==0 && parar.load()) break;
        if(secp256k1_ec_seckey_verify(ctx,priv)){
            secp256k1_pubkey pub;
            if(secp256k1_ec_pubkey_create(ctx,&pub,priv)){
                uint8_t p33[33]; size_t l=33;
                secp256k1_ec_pubkey_serialize(ctx,p33,&l,&pub,SECP256K1_EC_COMPRESSED);
                uint8_t out_xonly[32];
                if(tweak(ctx,p33+1,out_xonly) && buscar(o,out_xonly)>=0){
                    char addr[128]={0}; xonly_to_p2tr(out_xonly,addr,false);
                    char privhex[65]; for(int b=0;b<32;b++) sprintf(privhex+b*2,"%02x",priv[b]); privhex[64]=0;
                    hallado(privhex,addr);
                }
            }
        }
        be32_add_u64(priv,1);
    }
    return hecho;
}

/* De los 32 bytes de un elemento del cuerpo (como los guarda jac_batch.h: 4 limbs de 64 bits,
 * x[3] el más significativo) a big-endian de toda la vida. Misma extracción, byte a byte, que ya
 * usa jac_batch_hash160() para construir la clave comprimida — aquí sin el byte de prefijo. */
static inline void fe_to_be32(uint8_t *out32, const fe_t x){
    for(int w=0;w<4;w++) for(int b=0;b<8;b++)
        out32[(3-w)*8+(7-b)]=(uint8_t)(x[w]>>(b*8));
}

#define TR_LOTE 256

/** Lo mismo que escanear_bloque(), pero sin pagar una multiplicación de curva completa
 * (secp256k1_ec_pubkey_create) por cada d: arranca en P0 = (start+desde)·G —una sola
 * multiplicación, hecha una vez por bloque entero, no una vez por candidata— y a partir de ahí
 * sólo SUMA +G (jp_add_G, de jac_batch.h), que sale mucho más barato que multiplicar. Los puntos
 * se normalizan por lotes con una sola inversión de Montgomery cada vez (3N multiplicaciones + 1
 * inversión en vez de N inversiones) en lugar de una por candidata.
 *
 * Es la misma idea que ya usa escaner_raw.h para las claves al azar (RawKey), pero en orden
 * estrictamente secuencial d, d+1, d+2... — ahí no sirve su variante con el endomorfismo de la
 * curva porque esa da seis claves DISTINTAS por punto (λd, −d, λ²d...), no la d exacta; el
 * reanudar aproximado de la auditoría depende de que el rango se recorra en ese orden exacto.
 *
 * Sólo hace falta la x de P: el tweak de BIP341 toma siempre la x con la y par de lift_x, así que
 * la y real de P no se usa ni se normaliza nunca.
 *
 * jp_add_affine no cubre el caso P == Q (ahí hace falta doblar, jp_dbl): con una d al azar de 256
 * bits eso sólo pasaría si cae justo en d=1 o d=orden_de_la_curva−1 dentro del bloque, algo que un
 * rango de hasta 2^40 candidatas prácticamente no puede tocar salvo que el propio start sea ese
 * valor exacto. Se detecta igual que en escaner_raw.h (el producto de las Z del lote sale cero) y
 * si pasa se cae a escanear_bloque(), que sí lo cubre uno a uno. */
template <typename F>
static uint64_t escanear_bloque_rapido(secp256k1_context *ctx, const Objetivos *o,
                                const uint8_t *start32, uint64_t desde, uint64_t cuenta,
                                const std::atomic<bool> &parar, F hallado){
    uint8_t priv0[32]; memcpy(priv0,start32,32); be32_add_u64(priv0,desde);
    secp256k1_pubkey pub0;
    if(cuenta==0 || !secp256k1_ec_seckey_verify(ctx,priv0) || !secp256k1_ec_pubkey_create(ctx,&pub0,priv0))
        return escanear_bloque(ctx,o,start32,desde,cuenta,parar,hallado);

    uint8_t pub0_65[65]; size_t l=65;
    secp256k1_ec_pubkey_serialize(ctx,pub0_65,&l,&pub0,SECP256K1_EC_UNCOMPRESSED);
    JP cur; jp_from_affine(&cur,pub0_65);

    JP lote[TR_LOTE]; fe_t pfx[TR_LOTE];
    uint64_t hecho=0;
    while(hecho<cuenta){
        if(parar.load()) break;
        uint64_t n=cuenta-hecho; if(n>(uint64_t)TR_LOTE) n=TR_LOTE;
        uint64_t base=hecho;   /* desplazamiento de lote[0] respecto a priv0 */

        memcpy(&lote[0],&cur,sizeof(JP));
        for(uint64_t i=1;i<n;i++) jp_add_G(&lote[i],&lote[i-1]);

        memcpy(pfx[0],lote[0].z,32);
        for(uint64_t i=1;i<n;i++) fe_mul(pfx[i],pfx[i-1],lote[i].z);
        bool pfx_zero=true;
        for(int j=0;j<4;j++) if(pfx[n-1][j]){ pfx_zero=false; break; }
        if(pfx_zero)   /* P == ±G en algún punto del lote: caso raro, lo cubre la vía de siempre */
            return hecho + escanear_bloque(ctx,o,start32,desde+hecho,cuenta-hecho,parar,hallado);

        fe_t inv; fe_inv(inv,pfx[n-1]);
        for(int64_t i=(int64_t)n-1;i>=0;i--){
            fe_t zi;
            if(i){ fe_mul(zi,inv,pfx[i-1]); fe_mul(inv,inv,lote[i].z); } else memcpy(zi,inv,32);
            fe_t z2,xaff; fe_sqr(z2,zi); fe_mul(xaff,lote[i].x,z2);
            uint8_t x32[32]; fe_to_be32(x32,xaff);
            uint8_t out_xonly[32];
            if(tweak(ctx,x32,out_xonly) && buscar(o,out_xonly)>=0){
                uint8_t privi[32]; memcpy(privi,priv0,32); be32_add_u64(privi,base+(uint64_t)i);
                char addr[128]={0}; xonly_to_p2tr(out_xonly,addr,false);
                char privhex[65]; for(int b=0;b<32;b++) sprintf(privhex+b*2,"%02x",privi[b]); privhex[64]=0;
                hallado(privhex,addr);
            }
        }
        jp_add_G(&cur,&lote[n-1]);
        hecho+=n;
    }
    return hecho;
}

} // namespace tr_audit
