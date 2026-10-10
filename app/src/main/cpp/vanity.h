#pragma once
/* Generador de direcciones "vanity" (P2PKH, empiezan por 1) reusando el motor
 * rapido del escaner: grupos simetricos en afin con una sola inversion por lote
 * (escaner_raw.h), endomorfismo (6 candidatos por punto) y hash160 de cuatro en
 * cuatro (hash160x4.h). En vez de comparar contra un bloom, se comprueba si la
 * direccion base58 empieza por el prefijo pedido.
 *
 * Base58 sin heap: division larga del buffer de 25 bytes por 58. Para el
 * PREFILTRO se pone el checksum a cero (no afecta a los primeros caracteres,
 * que dependen de los bytes altos); solo cuando el prefijo cuadra se recalcula
 * con el checksum real y se vuelve a comprobar, asi no hay falsos positivos.
 *
 * Mismo resultado que h160_to_addr (addr_encode.h) para los candidatos validos. */
#include "escaner_raw.h"
#include "addr_encode.h"
#include <openssl/sha.h>
#include <atomic>
#include <thread>
#include <vector>
#include <mutex>
#include <string>
#include <random>
#include <cstdio>
#include <cstdlib>

/* Base58 de un buffer de 25 bytes (version+h160+checksum) sin asignar memoria.
 * Escribe la cadena terminada en '\0' en out (hasta ~35 chars). */
static inline void van_b58_25(const uint8_t in25[25], char *out){
    int zeros=0; while(zeros<25 && in25[zeros]==0) zeros++;
    uint8_t tmp[25]; memcpy(tmp,in25,25);
    char rev[48]; int rl=0; int start=0;
    while(start<25){
        int rem=0;
        for(int i=start;i<25;i++){ int acc=(rem<<8)|tmp[i]; tmp[i]=(uint8_t)(acc/58); rem=acc%58; }
        rev[rl++]=B58C[rem];
        while(start<25 && tmp[start]==0) start++;
    }
    for(int i=0;i<zeros;i++) rev[rl++]='1';
    int o=0; for(int i=rl-1;i>=0;i--) out[o++]=rev[i]; out[o]='\0';
}

/* Carga una x/y big-endian (32 bytes) en un fe_t (limbs little-endian). */
static inline void van_be32_to_fe(const uint8_t b[32], fe_t r){
    for(int w=0;w<4;w++){ uint64_t v=0; for(int i=0;i<8;i++) v=(v<<8)|b[(3-w)*8+i]; r[w]=v; }
}

/* ---- estado global del generador ---- */
static std::atomic<bool>     g_van_run{false};
static std::atomic<bool>     g_van_found{false};
static std::atomic<uint64_t> g_van_count{0};
static std::atomic<int>      g_van_vivos{0};
static std::mutex            g_van_mx;
static std::string           g_van_priv, g_van_addr;
static char                  g_van_target[72];   /* "1" + prefijo */
static int                   g_van_tlen=0;
static std::vector<std::thread> g_van_hilos;
static EscTabla              g_van_tabla;
static std::atomic<bool>     g_van_tabla_lista{false};
static int                   g_van_mode=0;   /* 0=P2PKH(1), 1=P2SH(3), 2=bech32(bc1q) */

struct VanCtx {
    secp256k1_context *ctx;
    const uint8_t     *kc;   /* escalar del centro actual (32 bytes big-endian) */
};

/* Visitante de cada hash160 producido por el grupo. Segun el modo, compara el
 * prefijo contra una direccion P2PKH (1), P2SH-P2WPKH (3) o bech32 (bc1q). */
static void van_visto(const uint8_t *h160, int j, int v, void *vp){
    if(g_van_found.load()) return;
    VanCtx *c=(VanCtx*)vp;
    char addr[110];
    if(g_van_mode==2){
        h160_to_bech32(h160,addr,false);                 /* bc1q… (determinista) */
        if(memcmp(addr,g_van_target,g_van_tlen)!=0) return;
    } else if(g_van_mode==1){
        h160_to_p2sh(h160,addr,false);                   /* 3… (base58check, exacto) */
        if(memcmp(addr,g_van_target,g_van_tlen)!=0) return;
    } else {
        /* P2PKH: prefiltro rapido con checksum a cero y confirmacion real. */
        uint8_t buf[25]; buf[0]=0x00; memcpy(buf+1,h160,20); memset(buf+21,0,4);
        char a2[40]; van_b58_25(buf,a2);
        if(memcmp(a2,g_van_target,g_van_tlen)!=0) return;
        uint8_t ck[32]; SHA256_CTX sc;
        SHA256_Init(&sc); SHA256_Update(&sc,buf,21); SHA256_Final(ck,&sc);
        SHA256_Init(&sc); SHA256_Update(&sc,ck,32); SHA256_Final(ck,&sc);
        memcpy(buf+21,ck,4); van_b58_25(buf,a2);
        if(memcmp(a2,g_van_target,g_van_tlen)!=0) return; /* falso positivo del prefiltro */
        memcpy(addr,a2,strlen(a2)+1);
    }
    /* Recuperar la clave privada del candidato (centro kc, desplazamiento j, variante v). */
    uint8_t priv[32];
    if(!esc_clave(c->ctx,c->kc,j,v,priv)) return;
    bool esperado=false;
    if(g_van_found.compare_exchange_strong(esperado,true)){
        char ph[65]; for(int i=0;i<32;i++){ sprintf(ph+i*2,"%02x",priv[i]); }
        std::lock_guard<std::mutex> lk(g_van_mx);
        g_van_priv=ph; g_van_addr=addr;
        g_van_run.store(false);
    }
}

/* Suma n (pequeno) a un escalar de 32 bytes big-endian. */
static inline void van_scalar_add(uint8_t k[32], uint32_t n){
    uint64_t carry=n;
    for(int i=31;i>=0 && carry;i--){ uint64_t s=(uint64_t)k[i]+(carry&0xff); k[i]=(uint8_t)s; carry=(carry>>8)+(s>>8); }
}

static void van_hilo(){
    g_van_vivos.fetch_add(1);
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    /* scratch del grupo (ESC_M+1 cada uno): en heap, son ~16 KB cada uno. */
    fe_t *dx=(fe_t*)malloc(sizeof(fe_t)*(ESC_M+1));
    fe_t *pfx=(fe_t*)malloc(sizeof(fe_t)*(ESC_M+1));
    uint8_t kc[32];
    std::random_device rd;
    auto nuevo_centro=[&](fe_t cx, fe_t cy)->bool{
        /* kc aleatorio valido, centro = kc*G en afin */
        for(;;){
            for(int i=0;i<32;i++) kc[i]=(uint8_t)(rd()&0xff);
            if(!secp256k1_ec_seckey_verify(ctx,kc)) continue;
            secp256k1_pubkey pk;
            if(!secp256k1_ec_pubkey_create(ctx,&pk,kc)) continue;
            uint8_t p65[65]; size_t l=65;
            secp256k1_ec_pubkey_serialize(ctx,p65,&l,&pk,SECP256K1_EC_UNCOMPRESSED);
            van_be32_to_fe(p65+1,cx); van_be32_to_fe(p65+33,cy);
            return true;
        }
    };
    if(dx&&pfx){
        fe_t cx,cy; nuevo_centro(cx,cy);
        VanCtx vc; vc.ctx=ctx; vc.kc=kc;
        while(g_van_run.load() && !g_van_found.load()){
            int ok=esc_grupo(&g_van_tabla,cx,cy,dx,pfx,van_visto,&vc,1);
            if(!ok){ nuevo_centro(cx,cy); continue; }   /* centro degenerado: re-aleatorizar */
            van_scalar_add(kc,(uint32_t)ESC_GRUPO);     /* el centro avanzo +ESC_GRUPO*G */
            g_van_count.fetch_add((uint64_t)ESC_GRUPO*6, std::memory_order_relaxed);
        }
    }
    free(dx); free(pfx);
    secp256k1_context_destroy(ctx);
    g_van_vivos.fetch_sub(1);
}

/* ---- API que llaman los JNI ---- */
static void vanity_parar(){
    g_van_run.store(false);
    for(auto &t:g_van_hilos) if(t.joinable()) t.join();
    g_van_hilos.clear();
}
static void vanity_arrancar(const char *prefijo, int hilos, int mode){
    vanity_parar();
    if(!g_van_tabla_lista.load()){ esc_tabla_crear(&g_van_tabla); g_van_tabla_lista.store(true); }
    g_van_mode = (mode<0||mode>2)?0:mode;
    const char *pre = (g_van_mode==1)?"3":(g_van_mode==2)?"bc1q":"1";
    int pl=0; while(pre[pl]){ g_van_target[pl]=pre[pl]; pl++; }
    int n=0; while(prefijo[n] && pl+n<70){ g_van_target[pl+n]=prefijo[n]; n++; }
    g_van_tlen=pl+n; g_van_target[g_van_tlen]='\0';
    g_van_count.store(0); g_van_found.store(false);
    { std::lock_guard<std::mutex> lk(g_van_mx); g_van_priv.clear(); g_van_addr.clear(); }
    if(hilos<1) hilos=1; if(hilos>32) hilos=32;
    g_van_run.store(true);
    for(int i=0;i<hilos;i++) g_van_hilos.emplace_back(van_hilo);
}
