#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <jni.h>
#include <android/log.h>
#include <sched.h>
#include <sys/syscall.h>
#include <string>
#include <atomic>
#include <mutex>
#include <deque>
#include <vector>
#include <ctime>
#include <thread>
#include <chrono>
#include <sstream>
#include <iomanip>
#include <cstring>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <cmath>
#include <cctype>
#include <pthread.h>
#include <secp256k1.h>
#include <secp256k1_extrakeys.h>
#include <secp256k1_schnorrsig.h>
#include <secp256k1_recovery.h>
#include <openssl/aes.h>
#include <openssl/rand.h>
#include <openssl/sha.h>
#include <openssl/hmac.h>
#include <openssl/evp.h>
#include <openssl/bn.h>
#include <openssl/ripemd.h>
#include "jac_batch.h"
#include "kangaroo.h"
#include "fe_arm64.h"
#include "gpu/gpu_kangaroo.h"
#include "gpu/kangaroo_spv.h"
#include "coincidencias.h"
#include "bloom.h"

#include "sha256_ripemd160.h"
#include "sha512.h"
#include "escaner_raw.h"
#include "indice9.h"

#define TAG "HunterJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define LOG_FILE "/data/data/com.hunter.btc/files/puz_debug.txt"
#define FPLOG(fmt, ...) do {     FILE *_f = fopen(LOG_FILE, "a");     if(_f){ fprintf(_f, fmt "\n", ##__VA_ARGS__); fclose(_f); } } while(0)

#include <signal.h>

static void crash_handler(int sig) {
    LOGE("NATIVE CRASH: signal %d", sig);
    FILE *f = fopen("/data/data/com.hunter.btc/files/crash_log.txt", "a");
    if (f) { fprintf(f, "\nNATIVE CRASH: signal %d\n", sig); fclose(f); }
    signal(sig, SIG_DFL);
    raise(sig);
}
static void install_crash_handlers() {
    signal(SIGSEGV, crash_handler);
    signal(SIGBUS,  crash_handler);
    signal(SIGABRT, crash_handler);
    signal(SIGFPE,  crash_handler);
    signal(SIGILL,  crash_handler);
}

#define MAX_THREADS  16
#define LOCAL_BATCH   128
#define MAX_CSV_ROWS  120000000ULL
#define HASH160_BYTES 20
#define PRIVKEY_BYTES 32
#define MAX_LINE      256
#define MAX_ADDR      64
#include "addr_encode.h"   /* b58enc, h160_to_addr, bech32, p2sh, p2tr */
#include "vanity.h"        /* generador de direcciones vanity (motor del escaner) */
#include "bsgs.h"          /* baby-step/giant-step para rangos pequenos */
#define N_PATHS       9

/* Paths BIP44 para modo BIP39 */
static const char *PATHS[N_PATHS]={
    "m/44'/0'/0'/0/0","m/44'/0'/0'/0/1","m/44'/0'/0'/0/2","m/44'/0'/0'/0/3",
    "m/44'/0'/0'/0/4","m/49'/0'/0'/0/0","m/84'/0'/0'/0/0","m/44'/0'/0'/1/0",
    "m/86'/0'/0'/0/0"
};

/* =========================================================
   Estado global compartido
   ========================================================= */
static uint8_t  *g_h160   = nullptr;
static uint64_t  g_total  = 0;
static Bloom     g_bloom  = {nullptr,0,0};
/* P2TR: store 32-byte x-only pubkeys separately */
static uint8_t  *g_xonly  = nullptr;
static uint64_t  g_total_tr = 0;
static Bloom     g_bloom_tr = {nullptr,0,0};
/* El indice de 9 bytes (indice9.h), si lo que se cargo es eso. */
static Indice9   g_i9;
/* Que tipo de direccion dio la ultima coincidencia en este hilo (0 P2PKH,
   1 P2WPKH, 2 P2SH, 3 P2TR, -1 no se sabe: formato viejo). Para guardar el
   hallazgo con la direccion que de verdad esta en la lista. */
static thread_local int g_tipo_hallado=-1;
static char      g_csv_path[1024] = "";
/* Hallazgos que no se han podido entregar al baul (ver save_match). Se
   quedan en memoria, nunca en disco, hasta que la app los recoja con
   popPorGuardar(). */
static std::deque<std::string> g_por_guardar;
static std::mutex              g_por_guardar_mtx;

/* Para llamar a HunterEngine.alHallar() desde los hilos del motor. La clase
   se busca en JNI_OnLoad porque desde un hilo nativo FindClass usa el
   cargador del sistema, que no conoce las clases de la app. */
static JavaVM   *g_vm          = nullptr;
static jclass    g_engine_cls  = nullptr;
static jmethodID g_al_hallar   = nullptr;

static std::atomic<long>   g_count(0);
static std::atomic<long>   g_found(0);
static std::atomic<bool>   g_running(false);
static std::atomic<bool>   g_stop(false);
static std::atomic<double> g_wps(0.0);
static std::atomic<int>    g_cpu_limit(100);
static std::atomic<int>    g_batch_size(1); // minimo para debug
/* Rutas a derivar por mnemónico: bit0 = BIP44 (m/44'), bit1 = BIP84 (m/84').
   Derivar ambas duplica las derivaciones y los hash160 por candidato. PBKDF2
   domina el coste, así que el ahorro de usar sólo una es del 2-5%, pero si el
   dataset sólo contiene un tipo de dirección la otra mitad no sirve de nada. */
static std::atomic<int>    g_bip39_paths(3);
static std::atomic<int>    g_nthreads(6);
static std::atomic<bool>   g_csv_loaded(false);
/* Parada en curso. stopHunting() no espera a los workers —hacerlo bloquearía el
   hilo de UI—, así que entre pulsar STOP y que g_running pase a false hay una
   ventana. Sin marcarla, una segunda pulsación en esa ventana volvía a entrar y
   lanzaba un segundo joiner sobre unos pthread_t que el primero ya estaba
   uniendo: pthread_join dos veces sobre el mismo hilo es comportamiento
   indefinido. Y desde Kotlin la ventana se veía como "isRunning() sigue true",
   así que el botón de START ejecutaba la rama de STOP y no arrancaba nada. */
static std::atomic<bool>   g_stopping(false);
static std::atomic<bool>   g_loading(false);
static std::atomic<int>    g_mode(0); /* 0=BIP39 1=PUZZLE 2=RAWKEY */

static uint8_t g_range_start[32] = {0};
static uint8_t g_range_end[32]   = {0};
static std::atomic<int> g_sequential(0);  /* 0=random, 1=sequential */
static uint8_t g_seq_pos[32]     = {0};   /* posición actual en modo secuencial */
static std::mutex g_seq_mutex;
/* Secuencial: el bloque se recorre UNA vez, de principio a fin. Antes, al
   llegar al final volvia al principio y seguia para siempre, asi que nunca
   se sabia si el bloque estaba hecho. Ahora, cuando no queda nada que repartir
   y el ultimo lote en curso termina, el motor se para solo y avisa. */
static std::atomic<int> g_seq_en_curso(0);   /* lotes repartidos y aun sin acabar */
static std::atomic<int> g_seq_agotado(0);    /* ya no queda rango que repartir */
static std::atomic<int> g_rango_completo(0); /* recorrido entero: la UI lo marca */
static uint8_t g_last_key[32]     = {0};
static std::mutex g_last_key_mutex;
static uint8_t g_target_h160[20]  = {0};
static int     g_has_target       = 0;
/* El objetivo unico ya ha aparecido. Ver por que existe donde se pone. */
static std::atomic<int> g_objetivo_hallado{0};
/* Para el motor entero: avisa a los hilos, los recoge y baja g_running. Se
 * declara aqui porque se usa desde los buscadores, que van mas arriba que su
 * definicion. */
static void parar_motor();

static std::mutex               g_log_mutex;
static std::deque<std::string>  g_log;
static Coincidencias            g_matches;

static char   g_load_status[256] = "";
static time_t g_start_time = 0;
static long   g_last_count = 0;
static time_t g_last_wps_t = 0;

static void add_log(const std::string &msg){
    std::lock_guard<std::mutex> lk(g_log_mutex);
    g_log.push_front(msg);
    if(g_log.size()>200) g_log.pop_back();
    LOGI("%s", msg.c_str());
}

/* =========================================================
   Crypto helpers
   ========================================================= */
static void trim_str(char *s){
    if(!s||!s[0])return;
    if(s[0]=='"'){int n=(int)strlen(s);memmove(s,s+1,n);n=(int)strlen(s);if(n>0&&s[n-1]=='"')s[n-1]='\0';}
    int n=(int)strlen(s);
    while(n>0&&(s[n-1]==' '||s[n-1]=='\r'||s[n-1]=='\n'))s[--n]='\0';
    while(s[0]==' ')memmove(s,s+1,strlen(s));
}
static const char *B58A="123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
static int base58_to_h160(const char *addr,uint8_t *out){
    if(!addr||strlen(addr)<25||strlen(addr)>35)return 0;
    BIGNUM *n=BN_new(),*t=BN_new(),*b=BN_new();BN_CTX *ctx=BN_CTX_new();BN_zero(n);BN_set_word(b,58);
    for(int i=0;addr[i];i++){const char *p=strchr(B58A,addr[i]);if(!p){BN_free(n);BN_free(t);BN_free(b);BN_CTX_free(ctx);return 0;}BN_mul(n,n,b,ctx);BN_set_word(t,(unsigned long)(p-B58A));BN_add(n,n,t);}
    if(BN_num_bytes(n)>25){BN_free(n);BN_free(t);BN_free(b);BN_CTX_free(ctx);return 0;}
    uint8_t raw[25]={0};BN_bn2binpad(n,raw,25);BN_free(n);BN_free(t);BN_free(b);BN_CTX_free(ctx);
    uint8_t ck[32];SHA256_CTX sc;SHA256_Init(&sc);SHA256_Update(&sc,raw,21);SHA256_Final(ck,&sc);SHA256_Init(&sc);SHA256_Update(&sc,ck,32);SHA256_Final(ck,&sc);
    if(memcmp(ck,raw+21,4))return 0;if(raw[0]!=0x00&&raw[0]!=0x05)return 0;memcpy(out,raw+1,20);return 1;
}
static const char BC32[]="qpzry9x8gf2tvdw0s3jn54khce6mua7l";
static int b32val(char c){if(c>='A'&&c<='Z')c=(char)(c-'A'+'a');for(int i=0;i<32;i++)if(BC32[i]==c)return i;return -1;}
static int bech32_to_h160(const char *a,uint8_t *out){
    if((int)strlen(a)!=42)return 0;if(a[0]!='b'||a[1]!='c'||a[2]!='1'||a[3]!='q')return 0;
    uint8_t d[38];for(int i=0;i<38;i++){int v=b32val(a[4+i]);if(v<0)return 0;d[i]=(uint8_t)v;}
    uint32_t acc=0;int bits=0,idx=0;
    for(int i=0;i<32&&idx<20;i++){acc=(acc<<5)|d[i];bits+=5;if(bits>=8){bits-=8;out[idx++]=(uint8_t)((acc>>bits)&0xFF);}}
    return(idx==20)?1:0;
}
static int addr_to_h160(const char *a,uint8_t *out){
    if(!a||!a[0])return 0;
    if(a[0]=='b'&&a[1]=='c'&&a[2]=='1'&&a[3]=='q')return bech32_to_h160(a,out);
    if(a[0]=='b'&&a[1]=='c'&&a[2]=='1')return 0;
    return base58_to_h160(a,out);
}
typedef struct{uint8_t h[HASH160_BYTES];}LE;
static int cmp_le(const void *a,const void *b){return memcmp(((LE*)a)->h,((LE*)b)->h,HASH160_BYTES);}

static int64_t bsearch_xonly(const uint8_t *t){
    if(!bloom_check(&g_bloom_tr,t)) return -1;
    int64_t lo=0,hi=(int64_t)g_total_tr-1;
    while(lo<=hi){int64_t mid=(lo+hi)>>1;int c=memcmp(g_xonly+mid*32,t,32);if(!c)return mid;if(c<0)lo=mid+1;else hi=mid-1;}
    return -1;
}
static int64_t bsearch_h160(const uint8_t *t){
    if(g_i9.d){
        /* El mismo hash160 es a la vez la direccion 1... y la bc1q... */
        int64_t r=i9_buscar(&g_i9,0,t); if(r>=0){ g_tipo_hallado=0; return r; }
        r=i9_buscar(&g_i9,1,t);         if(r>=0){ g_tipo_hallado=1; return r; }
        return -1;
    }
    g_tipo_hallado=-1;
    if(!bloom_check(&g_bloom,t)) return -1; /* bloom filter: skip bsearch */
    int64_t lo=0,hi=(int64_t)g_total-1;
    while(lo<=hi){int64_t mid=(lo+hi)>>1;int c=memcmp(g_h160+mid*HASH160_BYTES,t,HASH160_BYTES);if(!c)return mid;if(c<0)lo=mid+1;else hi=mid-1;}
    return -1;
}
/* P2SH (hash del script) y P2TR (clave x-only de salida, 32 bytes). */
static int64_t buscar_p2sh(const uint8_t *script_h160){
    if(g_i9.d){ int64_t r=i9_buscar(&g_i9,2,script_h160); if(r>=0) g_tipo_hallado=2; return r; }
    int64_t r=bsearch_h160(script_h160); if(r>=0) g_tipo_hallado=2; return r;   /* el CSV guarda los 3... por su hash */
}
static int64_t buscar_p2tr(const uint8_t *xonly32){
    if(g_i9.d){ int64_t r=i9_buscar(&g_i9,3,xonly32); if(r>=0) g_tipo_hallado=3; return r; }
    if(!g_xonly) return -1;
    int64_t r=bsearch_xonly(xonly32); if(r>=0) g_tipo_hallado=3; return r;
}
static char g_sep=',';
static int split_line(char *line,char **f,int mx){
    int n=0;char *p=line;
    while(n<mx){f[n++]=p;while(*p&&*p!=g_sep&&*p!='\n'&&*p!='\r')p++;if(*p==g_sep){*p++='\0';}else{*p='\0';break;}}
    return n;
}
static const char *BIP39[]={
#include "bip39_words.h"
};
/* /dev/urandom se abría y cerraba en cada mnemónico: tres syscalls por
   candidato, en todos los hilos. Se mantiene abierto por hilo, con destructor
   para que arrancar y parar el scanner no acumule descriptores. */
struct UrandomHandle {
    FILE *f = nullptr;
    ~UrandomHandle(){ if(f) fclose(f); }
};
static thread_local UrandomHandle tl_ur;

static void gen_mnemonic(char *out,size_t sz){
    uint8_t ent[16],h[32];
    if(!tl_ur.f) tl_ur.f=fopen("/dev/urandom","rb");
    if(tl_ur.f){ if(fread(ent,1,16,tl_ur.f)!=16) memset(ent,0,16); }
    else memset(ent,0,16);
    SHA256(ent,16,h);uint32_t bits[132];int bi=0;
    for(int i=0;i<16;i++)for(int b=7;b>=0;b--)bits[bi++]=(ent[i]>>b)&1;
    /* Checksum BIP39: los 4 bits ALTOS de SHA256(entropía)[0].
       Antes se hacía cs=h[0]>>4 y luego se leían los bits 7..4 de cs, es decir
       se desplazaba dos veces: los cuatro bits escritos eran siempre 0000. Sólo
       1 de cada 16 mnemónicos generados era válido en BIP39, así que el 93,6%
       del PBKDF2 se gastaba en frases que ninguna wallet pudo producir. */
    for(int b=7;b>=4;b--)bits[bi++]=(h[0]>>b)&1;
    out[0]='\0';for(int w=0;w<12;w++){uint32_t idx=0;for(int b=0;b<11;b++)idx=(idx<<1)|bits[w*11+b];if(w>0)strncat(out," ",sz-strlen(out)-1);strncat(out,BIP39[idx%2048],sz-strlen(out)-1);}
}
typedef struct{uint8_t key[32];uint8_t chain[32];}HDKey;
static void derive_master(const uint8_t *s,HDKey *o){uint8_t I[64];hmac_sha512((const uint8_t*)"Bitcoin seed",12,s,64,I);memcpy(o->key,I,32);memcpy(o->chain,I+32,32);}
static void get_pub33(secp256k1_context *ctx,const uint8_t *pk,uint8_t *p33){secp256k1_pubkey pub;secp256k1_ec_pubkey_create(ctx,&pub,pk);size_t len=33;secp256k1_ec_pubkey_serialize(ctx,p33,&len,&pub,SECP256K1_EC_COMPRESSED);}
static void derive_child(secp256k1_context *ctx,const HDKey *par,uint32_t idx,HDKey *child){
    uint8_t data[37];uint8_t I[64];
    if(idx>=0x80000000){data[0]=0;memcpy(data+1,par->key,32);}else get_pub33(ctx,par->key,data);
    data[33]=(uint8_t)(idx>>24);data[34]=(uint8_t)(idx>>16);data[35]=(uint8_t)(idx>>8);data[36]=(uint8_t)idx;
    hmac_sha512(par->chain,32,data,37,I);
    memcpy(child->key,par->key,32);secp256k1_ec_seckey_tweak_add(ctx,child->key,I);memcpy(child->chain,I+32,32);
}
/*
 * Deriva una ruta BIP32 completa: m/84'/0'/0'/0/0 son CINCO niveles.
 *
 * Había un `if(*p=='/')p++;` al final del cuerpo que consumía el separador
 * ANTES de que la condición `while(*p=='/')` volviera a mirarlo. Tras el primer
 * segmento p quedaba apuntando al dígito siguiente, no a la barra, así que el
 * bucle salía: sólo se derivaba m/84'.
 *
 * O sea, build_and_sign_tx firmaba con la clave de CUENTA en lugar de con la
 * hoja. Esa clave no es dueña del UTXO, de modo que la firma no podía satisfacer
 * el script y la red rechazaba la transacción — cualquier transacción, de
 * cualquier tipo de dirección. Comprobado ejecutando el código: para
 * m/84'/0'/0'/0/0 sobre la seed de prueba usaba el hash160 de m/84'.
 *
 * El separador lo consume ya el `p++` de la cabecera del bucle.
 */
static void derive_path(secp256k1_context *ctx,const uint8_t *s64,const char *path,HDKey *o){
    uint8_t seed[64];memcpy(seed,s64,64);derive_master(seed,o);
    const char *p=path;while(*p&&*p!='/')p++;
    while(*p=='/'){
        p++;
        uint32_t i=0;int h=0;
        while(*p>='0'&&*p<='9') i=i*10+(uint32_t)(*p++-'0');
        if(*p=='\''){h=1;p++;}
        HDKey c;derive_child(ctx,o,i+(h?0x80000000u:0u),&c);*o=c;
    }
}
static void pk_to_h160(secp256k1_context *ctx,const uint8_t *pk,uint8_t *out){uint8_t pub[33];get_pub33(ctx,pk,pub);hash160_inline(pub,out);}

/* =========================================================
   Rango hex -> bytes
   ========================================================= */
static void hex_to_bytes32(const char *hex, uint8_t *out){
    memset(out,0,32);
    int hlen=(int)strlen(hex);
    for(int i=0;i<hlen&&i<64;i++){
        char c=hex[hlen-1-i];
        uint8_t v=(c>='0'&&c<='9')?c-'0':(c>='a'&&c<='f')?c-'a'+10:(c>='A'&&c<='F')?c-'A'+10:0;
        out[31-i/2]|=(i%2==0)?v:(v<<4);
    }
}



/* =========================================================
   Wallet address encoding helpers
   ========================================================= */

/* === TAPROOT (BIP86) === */
/* Tagged hash: SHA256(SHA256(tag) || SHA256(tag) || msg) */
/* Precomputed SHA256("TapTweak") */
static const uint8_t TAPLEAF_TAG_HASH[32] = {
    0xe8,0x0f,0xe1,0x63,0x9c,0x9c,0xa0,0x50,0xe3,0xaf,0x1b,0x39,0xc1,0x43,0xc6,0x34,
    0x12,0x75,0x15,0x51,0x58,0x19,0x24,0x06,0x13,0x10,0x00,0x00,0x00,0x00,0x00,0x00
};
static void tagged_hash(const char *tag, const uint8_t *msg, size_t mlen, uint8_t *out) {
    /* Use precomputed tag hash for TapTweak */
    uint8_t tag_hash[32];
    if(strcmp(tag,"TapTweak")==0){
        /* Precompute once */
        static uint8_t cached[32]={0}; static int done=0;
        if(!done){SHA256((const uint8_t*)"TapTweak",8,cached);done=1;}
        memcpy(tag_hash,cached,32);
    } else {
        SHA256((const uint8_t*)tag,strlen(tag),tag_hash);
    }
    SHA256_CTX ctx2;
    SHA256_Init(&ctx2);
    SHA256_Update(&ctx2, tag_hash, 32);
    SHA256_Update(&ctx2, tag_hash, 32);
    SHA256_Update(&ctx2, msg, mlen);
    SHA256_Final(out, &ctx2);
}

/* Tweak x-only pubkey for keypath spend (no script): output = internal + t*G
   Returns x-only output pubkey (32 bytes) */
static int taproot_tweak_pubkey(secp256k1_context *ctx,
                                 const uint8_t *internal_xonly, /* 32 bytes */
                                 uint8_t *output_xonly)          /* 32 bytes out */ {
    uint8_t tweak[32];
    tagged_hash("TapTweak", internal_xonly, 32, tweak);
    /* Parse x-only pubkey into full pubkey */
    uint8_t pub33[33]; pub33[0] = 0x02;
    memcpy(pub33+1, internal_xonly, 32);
    secp256k1_pubkey pub;
    if (!secp256k1_ec_pubkey_parse(ctx, &pub, pub33, 33)) return 0;
    /* Add tweak*G */
    if (!secp256k1_ec_pubkey_tweak_add(ctx, &pub, tweak)) return 0;
    /* Serialize and take x coordinate */
    uint8_t out33[33]; size_t len=33;
    secp256k1_ec_pubkey_serialize(ctx, out33, &len, &pub, SECP256K1_EC_COMPRESSED);
    memcpy(output_xonly, out33+1, 32);
    return 1;
}


/*
 * Decodifica una dirección bc1p (bech32m, witness v1) a su clave x-only.
 *
 * La versión anterior no comprobaba el checksum: troceaba los caracteres y
 * convertía de 5 a 8 bits sin más. Una dirección con una errata decodificaba
 * igual, a una clave DISTINTA, y mandar ahí es mandar a un sitio del que nadie
 * tiene la clave. El checksum de bech32m existe justo para eso, y además el
 * padding sobrante debe ser cero o la codificación no es canónica.
 */
static int p2tr_addr_to_xonly(const char *addr, uint8_t *xonly32) {
    if(!addr) return 0;
    size_t alen=strlen(addr);
    if(alen!=62) return 0;                         /* bc1 + 59 datos = 62 */
    if(tolower(addr[0])!='b'||tolower(addr[1])!='c'||addr[2]!='1') return 0;
    if(tolower(addr[3])!='p') return 0;            /* witness v1 -> 'p' */

    /* Mayúsculas y minúsculas no se pueden mezclar (BIP173). */
    int hasU=0,hasL=0;
    for(size_t i=0;i<alen;i++){ if(isupper((unsigned char)addr[i]))hasU=1; if(islower((unsigned char)addr[i]))hasL=1; }
    if(hasU&&hasL) return 0;

    const char *CHARSET="qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    /* hrp "bc" expandido + datos, tal y como manda bech32. */
    uint8_t v[128]; int n=0;
    v[n++]='b'>>5; v[n++]='c'>>5; v[n++]=0; v[n++]='b'&31; v[n++]='c'&31;
    int dstart=n;
    for(size_t i=3;i<alen;i++){
        const char *c=strchr(CHARSET,tolower((unsigned char)addr[i]));
        if(!c) return 0;
        v[n++]=(uint8_t)(c-CHARSET);
    }
    if(bech32_polymod(v,n)!=0x2bc830a3) return 0;  /* constante de bech32m */

    /* Datos sin la versión de testigo ni los 6 del checksum. */
    const uint8_t *d=v+dstart+1; int nd=n-dstart-1-6;
    if(nd!=52) return 0;                            /* 32 bytes en grupos de 5 bits */
    uint32_t acc=0; int bits=0, idx=0;
    for(int i=0;i<nd;i++){
        acc=(acc<<5)|d[i]; bits+=5;
        if(bits>=8){ bits-=8; xonly32[idx++]=(uint8_t)((acc>>bits)&0xff); }
    }
    if(idx!=32) return 0;
    if(bits>=5) return 0;                           /* relleno de más */
    if(acc&((1u<<bits)-1)) return 0;                /* relleno distinto de cero */
    return 1;
}

/* Derive wallet: returns JSON string with 8 addresses */
static std::string derive_wallet_json(const char *mnemonic, bool testnet=false){
    /* Coin type 0' en mainnet, 1' en testnet. Ver la nota en h160_to_addr. */
    const uint32_t COIN = 0x80000000u + (testnet?1u:0u);
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    uint8_t seed[64];
    bip39_semilla(mnemonic,strlen(mnemonic),"",0,seed);
    HDKey master; derive_master(seed,&master);
    std::string json="{";
    /* BIP44: m/44'/0'/0'/0/0-2 */
    HDKey h44,h44_0,h44_00,h44_ext;
    derive_child(ctx,&master,0x80000000u+44,&h44);
    derive_child(ctx,&h44,COIN,&h44_0);
    derive_child(ctx,&h44_0,0x80000000u+0,&h44_00);
    derive_child(ctx,&h44_00,0,&h44_ext);
    for(int i=0;i<3;i++){
        HDKey leaf; derive_child(ctx,&h44_ext,i,&leaf);
        uint8_t h160[20]; pk_to_h160(ctx,leaf.key,h160);
        char addr[MAX_ADDR]={0}; h160_to_addr(h160,addr,testnet);
        char key[32]; snprintf(key,32,"\"p2pkh_%d\"",i);
        json+=key; json+=":\""; json+=addr; json+="\",";
    }
    /* BIP49: m/49'/0'/0'/0/0 */
    HDKey h49,h49_0,h49_00,h49_ext,h49_leaf;
    derive_child(ctx,&master,0x80000000u+49,&h49);
    derive_child(ctx,&h49,COIN,&h49_0);
    derive_child(ctx,&h49_0,0x80000000u+0,&h49_00);
    derive_child(ctx,&h49_00,0,&h49_ext);
    derive_child(ctx,&h49_ext,0,&h49_leaf);
    {uint8_t h160[20]; pk_to_h160(ctx,h49_leaf.key,h160);
     char addr[MAX_ADDR]={0}; h160_to_p2sh(h160,addr,testnet);
     json+="\"p2sh_0\":\""; json+=addr; json+="\",";}
    /* BIP84: m/84'/0'/0'/0/0-1 */
    HDKey h84,h84_0,h84_00,h84_ext;
    derive_child(ctx,&master,0x80000000u+84,&h84);
    derive_child(ctx,&h84,COIN,&h84_0);
    derive_child(ctx,&h84_0,0x80000000u+0,&h84_00);
    derive_child(ctx,&h84_00,0,&h84_ext);
    for(int i=0;i<2;i++){
        HDKey leaf; derive_child(ctx,&h84_ext,i,&leaf);
        uint8_t h160[20]; pk_to_h160(ctx,leaf.key,h160);
        char addr[MAX_ADDR]={0}; h160_to_bech32(h160,addr,testnet);
        char key[32]; snprintf(key,32,"\"p2wpkh_%d\"",i);
        json+=key; json+=":\""; json+=addr; json+="\",";
    }
    /* BIP86: m/86'/0'/0'/0/0-1 P2TR */
    HDKey h86,h86_0,h86_00,h86_ext;
    derive_child(ctx,&master,0x80000000u+86,&h86);
    derive_child(ctx,&h86,COIN,&h86_0);
    derive_child(ctx,&h86_0,0x80000000u+0,&h86_00);
    derive_child(ctx,&h86_00,0,&h86_ext);
    for(int i=0;i<2;i++){
        HDKey leaf; derive_child(ctx,&h86_ext,i,&leaf);
        uint8_t pub33[33]; get_pub33(ctx,leaf.key,pub33);
        uint8_t xonly[32]; memcpy(xonly,pub33+1,32);
        uint8_t tweaked[32];
        if(taproot_tweak_pubkey(ctx,xonly,tweaked)){
            char addr[MAX_ADDR]={0}; xonly_to_p2tr(tweaked,addr,testnet);
            char key[32]; snprintf(key,32,"\"p2tr_%d\"",i);
            // En C++ los literales adyacentes se concatenan: ":"" es ":" y
            // "", es ",". Faltaban las comillas del valor, así que la entrada
            // p2tr salía como  "p2tr_0":bc1p...,  y el objeto entero dejaba de
            // ser JSON válido.
            json+=key; json+=":\""; json+=addr; json+="\",";
        }
    }
    /* Remove trailing comma */
    if(json.back()==',') json.pop_back();
    json+="}";
    secp256k1_context_destroy(ctx);
    return json;
}

/* === FAST RNG para puzzle mode ===
   Usa xorshift128+ por thread - sin malloc, sin syscall, sin BN
   Range en bytes: calcula bits activos y genera solo esos bits */

/* Calcula cuantos bytes/bits son el rango */
static int       g_range_bits  = 0;   /* bits activos del rango */
static uint8_t   g_range_mask  = 0;   /* mascara para el byte superior */
static uint8_t   g_range_diff[32] = {0}; /* end - start, para acotar el offset */

static void precompute_range(){
    /* Encontrar el bit mas alto del rango */
    /* end - start para saber el tamano */
    uint8_t diff[32];
    int borrow=0;
    for(int i=31;i>=0;i--){
        int d=(int)g_range_end[i]-(int)g_range_start[i]-borrow;
        if(d<0){d+=256;borrow=1;}else borrow=0;
        diff[i]=(uint8_t)d;
    }
    memcpy(g_range_diff,diff,32);
    /* Longitud en bits de diff.
       El cálculo anterior partía de (32-i)*8 y restaba un bit por cada
       desplazamiento de diff[i], lo que sobrestimaba o subestimaba según el
       byte: para diff[i]=0x01 daba 73 bits en vez de 65, y para 0xFF daba 66
       en vez de 72. La máscara derivada de ahí limitaba el byte superior a
       unos pocos bits, de modo que el generador sólo cubría una fracción del
       rango (1,6% en varios puzzles). */
    g_range_bits=0;
    for(int i=0;i<32;i++){
        if(diff[i]){
            g_range_bits=(31-i)*8;          /* bytes completos por debajo */
            uint8_t b=diff[i];
            while(b){ g_range_bits++; b>>=1; }
            int bits_in_top=g_range_bits%8;
            g_range_mask=(bits_in_top==0)?0xFF:((1<<bits_in_top)-1);
            break;
        }
    }
}

/* xorshift128+ - ultra rapido, un estado por thread */
struct XR128 { uint64_t s0,s1; };

static void xr_init(XR128 *x){
    /* seed con urandom una vez por thread */
    FILE *ur=fopen("/dev/urandom","rb");
    if(ur){fread(&x->s0,8,1,ur);fread(&x->s1,8,1,ur);fclose(ur);}
    if(!x->s0) x->s0=0xdeadbeefcafeULL;
    if(!x->s1) x->s1=0x123456789abcULL;
}

static uint64_t xr_next(XR128 *x){
    uint64_t s1=x->s0, s0=x->s1;
    x->s0=s0; s1^=s1<<23; s1^=s1>>17; s1^=s0; s1^=s0>>26;
    x->s1=s1; return s0+s1;
}

/* Genera una clave uniforme dentro de [g_range_start, g_range_end].
   Antes se copiaba g_range_start en `out` como base y luego se volvía a SUMAR
   entero: los bytes por encima del offset aleatorio acababan valiendo el doble
   del inicio del rango. Para muchos puzzles eso dejaba el 100% de las claves
   fuera del rango, y como el fallback era memcpy(out, g_range_start, 32), el
   worker probaba una y otra vez exactamente la misma clave. */
static void gen_privkey_fast(uint8_t *out, XR128 *rng){
    int range_bytes = (g_range_bits+7)/8;
    int top_idx     = 32 - range_bytes;
    if(top_idx < 0) top_idx = 0;

    /* Offset aleatorio en [0, 2^range_bits). La máscara deja el offset por
       encima de diff con probabilidad < 1/2, así que unos pocos reintentos
       bastan para que quede uniforme dentro de [0, diff]. */
    for(int attempt=0; attempt<8; attempt++){
        memset(out, 0, 32);
        for(int i=31; i>=top_idx; i-=8){
            uint64_t r=xr_next(rng);
            for(int j=0;j<8&&(i-j)>=top_idx;j++)
                out[i-j]=(uint8_t)(r>>(j*8));
        }
        out[top_idx] &= g_range_mask;
        if(memcmp(out, g_range_diff, 32) <= 0) break;   /* offset dentro de diff */
    }

    /* out = start + offset */
    int carry=0;
    for(int i=31;i>=0;i--){
        int s=(int)out[i]+(int)g_range_start[i]+carry;
        out[i]=(uint8_t)(s&0xFF); carry=s>>8;
    }
    /* Red de seguridad: acotar al final del rango, no al inicio, para no
       reintroducir la clave repetida. */
    if(memcmp(out, g_range_end, 32)>0)
        memcpy(out, g_range_end, 32);
}

/* Entrega una coincidencia al baul cifrado, en el momento.
 *
 * Antes se escribia en files/coincidencias.txt, en claro y con la clave
 * privada, y la app lo pasaba al baul la proxima vez que se abria. Entre medias
 * la clave estaba en un fichero legible por cualquiera con acceso al
 * almacenamiento de la app (root, una copia por adb, una herramienta forense).
 * El motor no puede cifrar —el Keystore es de la capa Java—, asi que ahora
 * llama a HunterEngine.alHallar(), que la cifra y la guarda antes de que el
 * hilo siga buscando.
 *
 * @return true si el baul la ha guardado. */
static bool entregar_al_baul(const std::string &linea){
    if(!g_vm || !g_engine_cls || !g_al_hallar) return false;
    JNIEnv *env=nullptr;
    bool adjuntado=false;
    jint st=g_vm->GetEnv((void**)&env,JNI_VERSION_1_6);
    if(st==JNI_EDETACHED){
        if(g_vm->AttachCurrentThread(&env,nullptr)!=JNI_OK) return false;
        adjuntado=true;
    }else if(st!=JNI_OK) return false;
    bool ok=false;
    jstring js=env->NewStringUTF(linea.c_str());
    if(js){
        ok=env->CallStaticBooleanMethod(g_engine_cls,g_al_hallar,js)==JNI_TRUE;
        if(env->ExceptionCheck()){ env->ExceptionClear(); ok=false; }
        env->DeleteLocalRef(js);
    }
    /* Un hilo nativo que termina adjuntado tumba el proceso: se suelta aqui
       mismo, que es quien lo adjunto. */
    if(adjuntado) g_vm->DetachCurrentThread();
    return ok;
}

/* Guardar match */
static void save_match(const char *privhex, const char *addr, double btc, const char *wif, const char *extra){
    /* Primero a la lista, que es quien sabe si ya estaba.
     *
     * Lo de mirar si se repite no es cosmetico: el escaneo secuencial vuelve a
     * empezar al terminar el rango, asi que con un rango pequeno encuentra la
     * MISMA clave una y otra vez. Sin este filtro, cada vuelta anadia una
     * entrada a la lista y una llamada al baul, y la app se quedaba sin
     * memoria en minutos. Ver coincidencias.h. */
    std::ostringstream full;full<<"MATCH|ADDR:"<<addr<<"|BTC:"<<btc<<"|WIF:"<<wif<<"|HEX:"<<privhex;
    if(!coinc_add(&g_matches,full.str())) return;   /* ya estaba */

    /* El formato es el que lee MatchVault.deLinea(). */
    char btcs[32]; snprintf(btcs,sizeof(btcs),"%.8f",btc);
    std::string linea=std::string(extra)+" ADDR:"+addr+" BTC:"+btcs+" WIF:"+wif;
    if(!entregar_al_baul(linea)){
        /* Si el baul no ha podido (Keystore sin desbloquear tras reiniciar,
           app aun sin conectar) se guarda en memoria y la app la recoge con
           popPorGuardar(). En memoria y no en disco: es justo lo que se quiere
           evitar. */
        std::lock_guard<std::mutex> lk(g_por_guardar_mtx);
        g_por_guardar.push_back(linea);
    }
    std::ostringstream oss;oss<<"MATCH! "<<addr<<" "<<btc<<" BTC";
    add_log(oss.str());
}

/* =========================================================
   Worker BIP39 (modo 0)
   ========================================================= */
typedef struct{int64_t idx;char mn[256];uint8_t pk[PRIVKEY_BYTES];int pi;int tipo;}Hit;

/* ── CPU Affinity ── */
static int  g_big_cores[8]  = {4,5,6,7,-1,-1,-1,-1};
static int  g_n_big_cores   = 4;
static bool g_use_affinity  = false;


static void set_thread_affinity(int thread_idx) {
    if (!g_use_affinity) return;
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    int core = g_big_cores[thread_idx % g_n_big_cores];
    if (core >= 0) CPU_SET(core, &cpuset);
    sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
}

static void *worker_bip39_fn(void *arg){
    /* El índice del hilo llega en el argumento: antes se pasaba 0 literal y
       set_thread_affinity fijaba TODOS los hilos al mismo núcleo. */
    set_thread_affinity((int)(intptr_t)arg);
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    char mn[256]; uint8_t seed[64],h160[HASH160_BYTES];
    Hit hits[LOCAL_BATCH*N_PATHS]; int nhits=0; long local_done=0;
    while(!g_stop.load()){
        auto t0=std::chrono::high_resolution_clock::now();
        nhits=0; local_done=0;
        /* De dos en dos: con las instrucciones SHA-512 las dos PBKDF2 van
           entrelazadas (ver sha512.h); sin ellas es lo mismo que una y otra. */
        /* De cuatro en cuatro: con instrucciones SHA-512 las PBKDF2 van
           entrelazadas (2 o 4, lo que mida mejor sha512.cpp); sin ellas, una
           detras de otra. */
        for(int bi=0;bi<LOCAL_BATCH&&!g_stop.load();bi+=4){
            char mn2[4][256]; uint8_t seeds[4][64];
            const uint8_t *pw[4]; size_t pl[4];
            for(int q=0;q<4;q++){ gen_mnemonic(mn2[q],sizeof(mn2[q])); pw[q]=(const uint8_t*)mn2[q]; pl[q]=strlen(mn2[q]); }
            pbkdf2_sha512_x4(pw,pl,(const uint8_t*)"mnemonic",8,2048,seeds);
          for(int q=0;q<4;q++){
            strcpy(mn,mn2[q]); memcpy(seed,seeds[q],64);
            HDKey master; derive_master(seed,&master);
            const int paths=g_bip39_paths.load();
            /* bit0 m/44' (1...), bit1 m/84' (bc1q), bit2 m/49' (3...),
               bit3 m/86' (bc1p). Cada una: la hoja .../0'/0'/0/0. */
            static const struct { int bit, purpose, pi; } RUTAS[]={{1,44,0},{2,84,6},{4,49,5},{8,86,8}};
            for(const auto &r: RUTAS){
                if(!(paths&r.bit)) continue;
                HDKey a1,a2,a3,a4,hoja;
                derive_child(ctx,&master,0x80000000u+r.purpose,&a1);
                derive_child(ctx,&a1,0x80000000u+0,&a2);
                derive_child(ctx,&a2,0x80000000u+0,&a3);
                derive_child(ctx,&a3,0,&a4);
                derive_child(ctx,&a4,0,&hoja);
                int64_t ix=-1;
                if(r.purpose==86){
                    uint8_t p33[33],salida[32]; get_pub33(ctx,hoja.key,p33);
                    if(taproot_tweak_pubkey(ctx,p33+1,salida)) ix=buscar_p2tr(salida);
                }else{
                    pk_to_h160(ctx,hoja.key,h160);
                    if(r.purpose==49){
                        uint8_t red[22]={0x00,0x14}; memcpy(red+2,h160,20);
                        uint8_t sh[32],scr[20]; SHA256(red,22,sh); RIPEMD160(sh,32,scr);
                        ix=buscar_p2sh(scr);
                    }else ix=bsearch_h160(h160);
                }
                local_done++;
                if(ix>=0){ hits[nhits].idx=ix; strcpy(hits[nhits].mn,mn); memcpy(hits[nhits].pk,hoja.key,PRIVKEY_BYTES);
                           hits[nhits].pi=r.pi; hits[nhits].tipo=g_tipo_hallado; nhits++; }
            }
          }
        }
        double work_ms=std::chrono::duration<double,std::milli>(std::chrono::high_resolution_clock::now()-t0).count();
        int cpu=g_cpu_limit.load();
        if(cpu<100){double sl=work_ms*(100.0-cpu)/cpu;if(sl>0.5)std::this_thread::sleep_for(std::chrono::milliseconds((int)sl));}
        g_count.fetch_add(local_done);
        for(int i=0;i<nhits;i++){
            g_found.fetch_add(1);
            char addr[MAX_ADDR]={0},wif[60]={0},pkhex[65]={0},sats[24]={0},type_[12]={0};
            {   /* La direccion que de verdad esta en la lista, segun la ruta
                   y el tipo que coincidio (antes siempre la 1..., tambien
                   para m/84', cuya direccion es la bc1q). */
                uint8_t h160b[20]; pk_to_h160(ctx,hits[i].pk,h160b);
                int pi=hits[i].pi, t=hits[i].tipo;
                if(pi==5) h160_to_p2sh(h160b,addr);
                else if(pi==8){ uint8_t p33[33],sal[32]; get_pub33(ctx,hits[i].pk,p33);
                                if(taproot_tweak_pubkey(ctx,p33+1,sal)) xonly_to_p2tr(sal,addr); }
                else if(t==1 || (t<0 && pi==6)) h160_to_bech32(h160b,addr);
                else h160_to_addr(h160b,addr);
                pk_to_wif(hits[i].pk,wif);
            }
            for(int b=0;b<32;b++) sprintf(pkhex+b*2,"%02x",hits[i].pk[b]);
            double btc=0.0; /* sats not available in .bin format — check via Electrum */
            char extra[512]; snprintf(extra,sizeof(extra),"SEED:%s PATH:%s PRIV:%s%s",hits[i].mn,PATHS[hits[i].pi],pkhex,
                                      g_i9.d?" CHECK:8-byte-index":"");
            save_match(pkhex,addr,btc,wif,extra);
        }
    }
    secp256k1_context_destroy(ctx); return nullptr;
}

/* Callback para jac_batch: hash160 + check match por cada clave del batch */
struct PuzzleBatchCtx {
    uint8_t priv_base[32]; /* privkey del punto[0] */
    long    done;
};
static PuzzleBatchCtx g_pbctx;

static void puzzle_h160(PuzzleBatchCtx *c, long idx, const uint8_t *h160){
    c->done++;
    int match=0;
    if(g_has_target){
        if(memcmp(h160,g_target_h160,HASH160_BYTES)==0) match=1;
    } else if(g_csv_loaded.load()){
        int64_t i=bsearch_h160(h160);
        if(i>=0){match=1;}  /* sats not in .bin */
    }
    /* Con objetivo unico, solo el PRIMER hilo que lo encuentra lo guarda.
       Varios hilos pueden estar en el mismo rango diminuto (los primeros
       puzzles) a la vez, y antes de que parar_motor() llegara a todos cada
       uno guardaba y avisaba: "WALLET FOUND (3 total)" con el puzzle #1. */
    if(match && g_has_target && g_objetivo_hallado.exchange(1)) match=0;
    if(match){
        g_found.fetch_add(1);
        /* Reconstruir privkey = base + idx */
        uint8_t privkey[32]; memcpy(privkey,c->priv_base,32);
        { uint64_t add=(uint64_t)idx;             /* base + idx, con acarreo */
          for(int b=31;b>=0&&add;b--){ uint64_t sm=(uint64_t)privkey[b]+(add&0xFF); privkey[b]=(uint8_t)sm; add=(add>>8)+(sm>>8); } }
        char addr[MAX_ADDR]={0},wif[60]={0},pkhex[65]={0};
        if(g_tipo_hallado==1) h160_to_bech32(h160,addr); else h160_to_addr(h160,addr);
        pk_to_wif(privkey,wif);
        for(int b=0;b<32;b++) sprintf(pkhex+b*2,"%02x",privkey[b]);
        double btc=0.0; /* check via Electrum */
        char extra[160]; snprintf(extra,sizeof(extra),"PRIV:%s%s",pkhex,g_i9.d?" CHECK:8-byte-index":"");
        save_match(pkhex,addr,btc,wif,extra);
        /* Sin la clave. Este log lo ensena la pantalla de Debug y su boton
           "Copy" lo manda al portapapeles: la clave privada del puzzle
           resuelto iba entera. La clave ya viaja por save_match al baul
           cifrado, que es el unico sitio donde tiene que estar. */
        add_log(std::string("*** PUZZLE SOLVED *** ADDR:")+addr+" (key saved to the vault)");
        /* SE ACABO.
         *
         * Con un objetivo unico —que es lo que hay en modo puzzle— encontrarlo
         * es el final del trabajo. Antes no paraba nadie: el escaneo secuencial
         * llega al final del rango, vuelve a empezar, y encuentra la MISMA clave
         * otra vez. Con el puzzle #1, que tiene una sola clave, eso son cientos
         * de miles de "PUZZLE SOLVED" por minuto y el movil calentando para
         * nada. Visto en pantalla: 409.494 coincidencias de la misma clave.
         *
         * Con un CSV cargado es al reves —hay muchas direcciones y encontrar una
         * no agota la lista— asi que solo se para cuando el objetivo es unico. */
        g_objetivo_hallado.store(1);
        parar_motor();
    }
}
/* Para esc_grupo: la clave es base + g*ESC_GRUPO + ESC_M + j. Lo que se sale
 * del lote (el final del rango) se ignora. */
struct PuzzleGrupoCtx { PuzzleBatchCtx *pb; long g, cuenta; };
static void puzzle_visto(const uint8_t *h160, int j, int, void *raw){
    PuzzleGrupoCtx *c=(PuzzleGrupoCtx*)raw;
    long idx=c->g*ESC_GRUPO+ESC_M+j;
    if(idx<0 || idx>=c->cuenta) return;
    puzzle_h160(c->pb,idx,h160);
}



/* =========================================================
   RAW KEY WORKER - modo 2
   Claves al azar: cada hilo parte de una al azar y recorre grupos de
   ESC_GRUPO claves de curva, seis claves publicas por punto (escaner_raw.h).
   ========================================================= */
static EscTabla g_esc_tabla;
static std::once_flag g_esc_tabla_hecha;

struct RawCtx {
    secp256k1_context *ctx;
    uint8_t kc[32];              /* clave del centro del grupo en curso */
};

static void raw_visto(const uint8_t *h160, int j, int v, void *raw){
    int match=0;
    if(g_has_target){
        if(memcmp(h160, g_target_h160, HASH160_BYTES)==0) match=1;
    } else if(g_csv_loaded.load()){
        if(bsearch_h160(h160)>=0) match=1;
    }
    if(match && g_has_target && g_objetivo_hallado.exchange(1)) match=0;  /* ver puzzle_h160 */
    if(!match) return;
    RawCtx *c=(RawCtx*)raw;
    uint8_t pk[32];
    if(!esc_clave(c->ctx,c->kc,j,v,pk)) return;
    g_found.fetch_add(1);
    char addr[MAX_ADDR]={0},wif[60]={0},pkhex[65]={0};
    if(g_tipo_hallado==1) h160_to_bech32(h160,addr); else h160_to_addr(h160,addr);
    pk_to_wif(pk,wif);
    for(int b=0;b<32;b++) sprintf(pkhex+b*2,"%02x",pk[b]);
    char extra[160]; snprintf(extra,sizeof(extra),"RAW:%s%s",pkhex,g_i9.d?" CHECK:8-byte-index":"");
    save_match(pkhex,addr,0.0,wif,extra);
    add_log(std::string("*** RAW MATCH *** ADDR:")+addr);
    /* Igual que en modo puzzle: con objetivo unico, encontrarlo es el final.
       Con CSV no, que hay muchas direcciones. */
    if(g_has_target){ g_objetivo_hallado.store(1); parar_motor(); }
}

static void *worker_rawkey_fn(void *arg){
    /* El índice del hilo llega en el argumento: antes se pasaba 0 literal y
       set_thread_affinity fijaba TODOS los hilos al mismo núcleo. */
    set_thread_affinity((int)(intptr_t)arg);
    secp256k1_context *ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    if(!ctx) return nullptr;
    std::call_once(g_esc_tabla_hecha,[]{ esc_tabla_crear(&g_esc_tabla); });
    fe_t *dx=(fe_t*)malloc((ESC_M+1)*sizeof(fe_t));
    fe_t *pfx=(fe_t*)malloc((ESC_M+1)*sizeof(fe_t));
    if(!dx||!pfx){ free(dx); free(pfx); secp256k1_context_destroy(ctx); return nullptr; }

    RawCtx rc; rc.ctx=ctx;
    uint8_t paso[32]={0}; paso[30]=(uint8_t)(ESC_GRUPO>>8); paso[31]=(uint8_t)ESC_GRUPO;
    fe_t cx,cy;
    auto nuevo_centro=[&]{
        XR128 rng; xr_init(&rng);
        do{
            for(int i=0;i<4;i++){ uint64_t r=xr_next(&rng); memcpy(rc.kc+8*i,&r,8); }
        }while(!secp256k1_ec_seckey_verify(ctx,rc.kc));
        secp256k1_pubkey pk; (void)secp256k1_ec_pubkey_create(ctx,&pk,rc.kc);
        uint8_t p65[65]; size_t l=65;
        secp256k1_ec_pubkey_serialize(ctx,p65,&l,&pk,SECP256K1_EC_UNCOMPRESSED);
        JP P; jp_from_affine(&P,p65); memcpy(cx,P.x,32); memcpy(cy,P.y,32);
    };
    nuevo_centro();

    while(!g_stop.load()){
        auto t0 = std::chrono::high_resolution_clock::now();
        /* Unos cuantos grupos por vuelta: cada uno son 6150 claves. */
        long hechas=0;
        for(int g=0; g<8 && !g_stop.load(); g++){
            if(!esc_grupo(&g_esc_tabla,cx,cy,dx,pfx,raw_visto,&rc)){ nuevo_centro(); continue; }
            hechas+=6L*ESC_GRUPO;
            if(!secp256k1_ec_seckey_tweak_add(ctx,rc.kc,paso)) nuevo_centro();
        }
        g_count.fetch_add(hechas);

        {std::lock_guard<std::mutex> lk(g_last_key_mutex);
         memcpy(g_last_key, rc.kc, 32);}

        double work_ms = std::chrono::duration<double,std::milli>(
            std::chrono::high_resolution_clock::now()-t0).count();
        int cpu = g_cpu_limit.load();
        if(cpu<100){
            double sl=work_ms*(100.0-cpu)/cpu;
            if(sl>0.5) std::this_thread::sleep_for(
                std::chrono::milliseconds((int)sl));
        }
    }

    free(dx); free(pfx);
    secp256k1_context_destroy(ctx);
    return nullptr;
}


static void *worker_puzzle_fn(void *arg){
    /* El índice del hilo llega en el argumento: antes se pasaba 0 literal y
       set_thread_affinity fijaba TODOS los hilos al mismo núcleo. */
    set_thread_affinity((int)(intptr_t)arg);
    secp256k1_context *ctx = secp256k1_context_create(
        SECP256K1_CONTEXT_SIGN | SECP256K1_CONTEXT_VERIFY);
    if(!ctx) return nullptr;

    const int MAX_SAFE = JAC_BATCH;
    std::call_once(g_esc_tabla_hecha,[]{ esc_tabla_crear(&g_esc_tabla); });
    fe_t *dx=(fe_t*)malloc((ESC_M+1)*sizeof(fe_t));
    fe_t *pfxg=(fe_t*)malloc((ESC_M+1)*sizeof(fe_t));
    if(!dx||!pfxg){ free(dx); free(pfxg); secp256k1_context_destroy(ctx); return nullptr; }

    XR128 rng; xr_init(&rng);

    while(!g_stop.load()){
        auto t0 = std::chrono::high_resolution_clock::now();

        int cur_batch = g_batch_size.load();
        if(cur_batch < 1) cur_batch = 1;
        if(cur_batch > MAX_SAFE) cur_batch = MAX_SAFE;

        /* Generar clave base - aleatorio o secuencial */
        uint8_t privkey[32];
        const bool secuencial = g_sequential.load()!=0;
        if(secuencial) {
            /* Modo secuencial: tomar posición actual y avanzar batch */
            std::unique_lock<std::mutex> lk(g_seq_mutex);
            memcpy(privkey, g_seq_pos, 32);
            /* ¿Pasado el fin? Ya no hay nada que repartir. */
            if(g_seq_agotado.load() || memcmp(privkey, g_range_end, 32) > 0) {
                g_seq_agotado.store(1);
                bool ultimo=(g_seq_en_curso.load()==0);
                lk.unlock();
                if(ultimo){ g_rango_completo.store(1); add_log("Block done: range scanned end to end"); parar_motor(); }
                break;
            }
            g_seq_en_curso.fetch_add(1);
            /* Avanzar la posición para el siguiente hilo.
               Antes se releía g_batch_size aquí: si el usuario movía el slider
               entre las dos lecturas, el avance no coincidía con lo que este
               hilo iba a procesar y quedaban claves sin escanear o repetidas.
               Se usa cur_batch, el mismo valor con el que se construye el lote.
               Además el avance era un bucle de cur_batch incrementos de 32
               bytes CON EL MUTEX TOMADO — con lotes de 16000 eso serializaba a
               todos los hilos. Ahora es una suma con acarreo, O(32). */
            uint64_t add = (uint64_t)cur_batch;
            for(int b = 31; b >= 0 && add; b--){
                uint64_t sum = (uint64_t)g_seq_pos[b] + (add & 0xFF);
                g_seq_pos[b] = (uint8_t)(sum & 0xFF);
                add = (add >> 8) + (sum >> 8);
            }
        } else {
            gen_privkey_fast(privkey, &rng);
        }
        if(!secp256k1_ec_seckey_verify(ctx, privkey))
            memcpy(privkey, g_range_start, 32);

        {std::lock_guard<std::mutex> lk(g_last_key_mutex);
         memcpy(g_last_key, privkey, 32);}

        /* Cuantas claves de este lote caben antes del final del rango. */
        long cuenta=cur_batch;
        {
            sc_t fin,ini,dif; sc_from_be32(fin,g_range_end); sc_from_be32(ini,privkey);
            if(!sc_sub(dif,fin,ini)) cuenta=1;
            else if(!dif[1]&&!dif[2]&&!dif[3]&&dif[0]<(uint64_t)cur_batch) cuenta=(long)dif[0]+1;
        }

        /* Grupos de ESC_GRUPO claves con centro en base+ESC_M, base+ESC_M+
           ESC_GRUPO... (escaner_raw.h): una inversion por lotes para C+iG y
           C-iG a la vez, y hash160 de cuatro en cuatro. */
        PuzzleBatchCtx pctx;
        memcpy(pctx.priv_base, privkey, 32);
        pctx.done = 0;
        PuzzleGrupoCtx gc; gc.pb=&pctx; gc.cuenta=cuenta;
        long ngrupos=(cuenta+ESC_GRUPO-1)/ESC_GRUPO;
        fe_t cx,cy; bool hay_centro=false;
        for(long g=0; g<ngrupos && !g_stop.load(); g++){
            gc.g=g;
            if(!hay_centro){
                /* centro = base + g*ESC_GRUPO + ESC_M, con una multiplicacion */
                uint8_t kc[32]; memcpy(kc,privkey,32);
                uint64_t add=(uint64_t)(g*ESC_GRUPO+ESC_M);
                for(int b2=31;b2>=0&&add;b2--){ uint64_t sm=(uint64_t)kc[b2]+(add&0xFF); kc[b2]=(uint8_t)sm; add=(add>>8)+(sm>>8); }
                secp256k1_pubkey pk;
                if(!secp256k1_ec_pubkey_create(ctx,&pk,kc)) break;
                uint8_t p65[65]; size_t l=65;
                secp256k1_ec_pubkey_serialize(ctx,p65,&l,&pk,SECP256K1_EC_UNCOMPRESSED);
                JP P; jp_from_affine(&P,p65); memcpy(cx,P.x,32); memcpy(cy,P.y,32);
                hay_centro=true;
            }
            if(esc_grupo(&g_esc_tabla,cx,cy,dx,pfxg,puzzle_visto,&gc,0)) continue;
            /* Centro en +-iG: rangos diminutos (los primeros puzzles). Ese
               grupo, clave a clave. */
            for(long k=0;k<ESC_GRUPO;k++){
                long idx=g*ESC_GRUPO+k; if(idx>=cuenta) break;
                uint8_t kk[32]; memcpy(kk,privkey,32);
                uint64_t add=(uint64_t)idx;
                for(int b2=31;b2>=0&&add;b2--){ uint64_t sm=(uint64_t)kk[b2]+(add&0xFF); kk[b2]=(uint8_t)sm; add=(add>>8)+(sm>>8); }
                secp256k1_pubkey pk; if(!secp256k1_ec_pubkey_create(ctx,&pk,kk)) continue;
                uint8_t p33[33]; size_t l=33;
                secp256k1_ec_pubkey_serialize(ctx,p33,&l,&pk,SECP256K1_EC_COMPRESSED);
                uint8_t h[20]; hash160_inline(p33,h); puzzle_h160(&pctx,idx,h);
            }
            hay_centro=false;
        }
        long actual=cuenta;

        g_count.fetch_add(actual);
        if(secuencial){
            /* El ultimo lote en terminar, con el rango ya repartido, cierra. */
            int quedan=g_seq_en_curso.fetch_sub(1)-1;
            if(quedan==0 && g_seq_agotado.load() && !g_stop.load()){
                g_rango_completo.store(1);
                add_log("Block done: range scanned end to end");
                parar_motor();
            }
        }

        double work_ms = std::chrono::duration<double,std::milli>(
            std::chrono::high_resolution_clock::now() - t0).count();
        int cpu = g_cpu_limit.load();
        if(cpu < 100){
            double sl = work_ms * (100.0 - cpu) / cpu;
            if(sl > 0.5)
                std::this_thread::sleep_for(
                    std::chrono::milliseconds((int)sl));
        }
    }

    free(dx); free(pfxg);
    secp256k1_context_destroy(ctx);
    return nullptr;
}


static pthread_t g_workers[MAX_THREADS];
static int g_active=0;

/* =========================================================
   CSV loader
   ========================================================= */
static void i9_progreso(uint64_t h,uint64_t n){
    snprintf(g_load_status,sizeof(g_load_status),"Indexing %.0f%%",100.0*(double)h/(double)(n?n:1));
}

static void* load_bin_fn(void*) {
    g_loading.store(true); g_csv_loaded.store(false);
    snprintf(g_load_status,sizeof(g_load_status),"Loading .bin...");
    add_log(std::string("BIN path: ")+g_csv_path);
    /* Primero, el indice de 9 bytes (tipo + 8 bytes de hash, ordenado, sin
       cabecera). Si no lo es, el formato de siempre. */
    {
        Indice9 nuevo;
        if(i9_abrir(&nuevo,g_csv_path,i9_progreso)){
            if(g_h160){free(g_h160);g_h160=nullptr;} g_total=0;
            if(g_xonly){free(g_xonly);g_xonly=nullptr;} g_total_tr=0;
            if(g_bloom.bits) bloom_free(&g_bloom);
            if(g_bloom_tr.bits) bloom_free(&g_bloom_tr);
            i9_cerrar(&g_i9); g_i9=nuevo;
            g_total=g_i9.n;
            snprintf(g_load_status,sizeof(g_load_status),
                     "Ready: %.1fM | 1:%.1fM bc1q:%.1fM 3:%.1fM bc1p/wsh:%.1fM",
                     g_i9.n/1e6,g_i9.por_tipo[0]/1e6,g_i9.por_tipo[1]/1e6,g_i9.por_tipo[2]/1e6,g_i9.por_tipo[3]/1e6);
            add_log(std::string("9-byte index: ")+g_load_status+" | bloom "+
                    std::to_string(g_i9.nbloques*64/1024/1024)+"MB, file mapped (not copied)");
            g_csv_loaded.store(true); g_loading.store(false);
            return nullptr;
        }
    }
    i9_cerrar(&g_i9);
    int fd=open(g_csv_path,O_RDONLY);
    if(fd<0){snprintf(g_load_status,sizeof(g_load_status),"Error: cannot open .bin");g_loading.store(false);return nullptr;}
    struct stat st; fstat(fd,&st); size_t fsz=(size_t)st.st_size;
    if(fsz<8){snprintf(g_load_status,sizeof(g_load_status),"Error: .bin too small");close(fd);g_loading.store(false);return nullptr;}
    void* mapped=mmap(nullptr,fsz,PROT_READ,MAP_PRIVATE,fd,0); close(fd);
    if(mapped==MAP_FAILED){snprintf(g_load_status,sizeof(g_load_status),"Error: mmap failed");g_loading.store(false);return nullptr;}
    madvise(mapped,fsz,MADV_SEQUENTIAL);
    uint64_t n; memcpy(&n,mapped,8);
    add_log("BIN n="+std::to_string(n)+" fsz="+std::to_string(fsz));
    if(fsz<8+n*HASH160_BYTES){snprintf(g_load_status,sizeof(g_load_status),"Error: .bin truncated n=%llu",(unsigned long long)n);munmap(mapped,fsz);g_loading.store(false);return nullptr;}
    if(g_h160){free(g_h160);g_h160=nullptr;}g_total=0;
    if(g_xonly){free(g_xonly);g_xonly=nullptr;}g_total_tr=0;
    if(g_bloom.bits){bloom_free(&g_bloom);g_bloom.bits=nullptr;g_bloom.nbits=0;}
    if(g_bloom_tr.bits){bloom_free(&g_bloom_tr);g_bloom_tr.bits=nullptr;g_bloom_tr.nbits=0;}
    g_h160=(uint8_t*)malloc(n*HASH160_BYTES);
    if(!g_h160){snprintf(g_load_status,sizeof(g_load_status),"Error: OOM");munmap(mapped,fsz);g_loading.store(false);return nullptr;}
    memcpy(g_h160,(uint8_t*)mapped+8,n*HASH160_BYTES);
    munmap(mapped,fsz); g_total=n;
    snprintf(g_load_status,sizeof(g_load_status),"Building bloom...");
    g_bloom=bloom_create(g_total);
    for(uint64_t i=0;i<g_total;i++) bloom_set(&g_bloom,g_h160+i*HASH160_BYTES);
    add_log("Bloom: "+std::to_string(g_bloom.nbits/8/1024/1024)+"MB");
    snprintf(g_load_status,sizeof(g_load_status),"Ready: %.1fM | %.0fMB",(double)n/1e6,(double)(n*HASH160_BYTES)/1e6);
    g_csv_loaded.store(true); g_loading.store(false);
    add_log(std::string("BIN loaded: ")+g_load_status);
    return nullptr;
}


static void *load_fn(void *){
    g_loading.store(true);snprintf(g_load_status,sizeof(g_load_status),"Opening CSV...");
    add_log(std::string("PATH:")+g_csv_path);
    {size_t pl=strlen(g_csv_path);if(pl>4&&strcmp(g_csv_path+pl-4,".bin")==0){add_log("DETECTED BIN");return load_bin_fn(nullptr);}add_log("NOT BIN pl="+std::to_string(pl));}
    FILE *f=fopen(g_csv_path,"r");
    if(!f){snprintf(g_load_status,sizeof(g_load_status),"Error: could not open file");g_loading.store(false);return nullptr;}
    if(g_h160){free(g_h160);g_h160=nullptr;}g_total=0;g_csv_loaded.store(false);
    i9_cerrar(&g_i9);
    if(g_xonly){free(g_xonly);g_xonly=nullptr;}g_total_tr=0;
    if(g_bloom_tr.bits){bloom_free(&g_bloom_tr);}
    LE *tmp=(LE*)malloc(MAX_CSV_ROWS*sizeof(LE));
    if(!tmp){snprintf(g_load_status,sizeof(g_load_status),"Error: out of memory");fclose(f);g_loading.store(false);return nullptr;}
    char line[MAX_LINE],*fields[8];
    if(!fgets(line,sizeof(line),f)){fclose(f);free(tmp);g_loading.store(false);return nullptr;}
    if(strchr(line,';'))g_sep=';';else if(strchr(line,'\t'))g_sep='\t';else g_sep=',';
    int ca=0;{char hdr[MAX_LINE];strncpy(hdr,line,MAX_LINE-1);int n=split_line(hdr,fields,8);for(int i=0;i<n;i++){trim_str(fields[i]);if(!strcmp(fields[i],"address"))ca=i;}}
    uint64_t rows=0,ok=0,skip=0;
    while(fgets(line,sizeof(line),f)&&ok<MAX_CSV_ROWS){
        uint64_t off=(uint64_t)ftello(f)-(uint64_t)strlen(line);rows++;
        char tl[MAX_LINE];strncpy(tl,line,MAX_LINE-1);int n=split_line(tl,fields,8);if(n<=ca){skip++;continue;}
        char addr[MAX_ADDR];strncpy(addr,fields[ca],MAX_ADDR-1);trim_str(addr);if(!addr[0]){skip++;continue;}
        uint8_t h160[HASH160_BYTES];if(!addr_to_h160(addr,h160)){skip++;continue;}
        memcpy(tmp[ok].h,h160,HASH160_BYTES);ok++;
        if(rows%1000000==0)snprintf(g_load_status,sizeof(g_load_status),"Leyendo %.0fM... OK:%llu",(double)rows/1e6,(unsigned long long)ok);
    }
    fclose(f);
    snprintf(g_load_status,sizeof(g_load_status),"Ordenando %llu entradas...",(unsigned long long)ok);
    qsort(tmp,ok,sizeof(LE),cmp_le);
    g_h160=(uint8_t*)malloc(ok*HASH160_BYTES);
    if(!g_h160){snprintf(g_load_status,sizeof(g_load_status),"Error: out of memory");free(tmp);g_loading.store(false);return nullptr;}
    for(uint64_t i=0;i<ok;i++){memcpy(g_h160+i*HASH160_BYTES,tmp[i].h,HASH160_BYTES);}
    free(tmp);g_total=ok;
    snprintf(g_load_status,sizeof(g_load_status),"Listo: %.1fM dir | %.2f GB",(double)ok/1e6,ok*20.0/1e9);
    /* Sort p2tr xonly array */
    if(g_xonly && g_total_tr>1){
        qsort(g_xonly, g_total_tr, 32, [](const void *a,const void *b){return memcmp(a,b,32);});
    }
    /* Build bloom for p2tr */
    if(g_bloom_tr.bits){bloom_free(&g_bloom_tr);}
    if(g_xonly && g_total_tr>0){
        g_bloom_tr=bloom_create(g_total_tr);
        for(uint64_t bi=0;bi<g_total_tr;bi++) bloom_set(&g_bloom_tr,g_xonly+bi*32);
    }
    add_log("P2TR loaded: "+std::to_string(g_total_tr)+" taproot entries");
    /* Build bloom filter */
    if(g_bloom.bits){bloom_free(&g_bloom);}
    g_bloom=bloom_create(g_total);
    for(uint64_t bi=0;bi<g_total;bi++) bloom_set(&g_bloom,g_h160+bi*HASH160_BYTES);
    add_log("Bloom filter ready: "+std::to_string(g_bloom.nbits/8/1024/1024)+"MB for "+std::to_string(g_total)+" entries");
    g_csv_loaded.store(true);g_loading.store(false);
    add_log(std::string("CSV ready: ")+g_load_status);
    return nullptr;
}

/* =========================================================
   JNI exports
   ========================================================= */
extern "C" {

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_loadCsv(JNIEnv *env,jobject,jstring path){
    const char *p=env->GetStringUTFChars(path,nullptr);
    strncpy(g_csv_path,p,sizeof(g_csv_path)-1);
    env->ReleaseStringUTFChars(path,p);
    pthread_t t;pthread_create(&t,nullptr,load_fn,nullptr);pthread_detach(t);
}

/* Se llama al cargar la libreria, desde el hilo de System.loadLibrary y con el
   cargador de clases de la app: es el unico momento en que FindClass encuentra
   HunterEngine. */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *){
    g_vm=vm;
    JNIEnv *env=nullptr;
    if(vm->GetEnv((void**)&env,JNI_VERSION_1_6)!=JNI_OK) return JNI_VERSION_1_6;
    jclass c=env->FindClass("com/hunter/btc/HunterEngine");
    if(c){
        g_engine_cls=(jclass)env->NewGlobalRef(c);
        g_al_hallar=env->GetStaticMethodID(c,"alHallar","(Ljava/lang/String;)Z");
        env->DeleteLocalRef(c);
    }
    if(env->ExceptionCheck()){
        env->ExceptionClear();
        g_al_hallar=nullptr;
    }
    return JNI_VERSION_1_6;
}

/* Saca un hallazgo que no se pudo entregar al baul, o "" si no queda. */
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_popPorGuardar(JNIEnv *env,jobject){
    std::lock_guard<std::mutex> lk(g_por_guardar_mtx);
    if(g_por_guardar.empty()) return env->NewStringUTF("");
    std::string s=g_por_guardar.front(); g_por_guardar.pop_front();
    return env->NewStringUTF(s.c_str());
}

/* Devuelve uno que la app no pudo guardar, para que no se pierda. */
JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_devolverPorGuardar(JNIEnv *env,jobject,jstring l){
    const char *p=env->GetStringUTFChars(l,nullptr);
    if(!p) return;
    {
        std::lock_guard<std::mutex> lk(g_por_guardar_mtx);
        g_por_guardar.push_front(std::string(p));
    }
    env->ReleaseStringUTFChars(l,p);
}

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_setMode(JNIEnv *,jobject,jint mode){
    g_mode.store(mode);
}



JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_setRange(JNIEnv *env,jobject,jstring start,jstring end){
    const char *s=env->GetStringUTFChars(start,nullptr);
    const char *e=env->GetStringUTFChars(end,nullptr);
    hex_to_bytes32(s,g_range_start);
    hex_to_bytes32(e,g_range_end);
    env->ReleaseStringUTFChars(start,s);
    env->ReleaseStringUTFChars(end,e);
    precompute_range();
    /* El secuencial empieza SIEMPRE en el principio del rango nuevo. Antes la
       posicion solo se ponia al tocar el selector, asi que tras cambiar de
       bloque seguia donde se quedo el anterior (o en 0). */
    { std::lock_guard<std::mutex> lk(g_seq_mutex); memcpy(g_seq_pos,g_range_start,32); }
}

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_startHunting(JNIEnv *,jobject,jint threads,jint cpuLimit){
    install_crash_handlers();
    if(g_running.load())return;
    if(g_stopping.load())return;   /* workers de la sesión anterior aún vivos */
    if(!g_csv_loaded.load()&&g_mode.load()!=1&&g_mode.load()!=2)return;
    g_nthreads.store(threads);g_cpu_limit.store(cpuLimit);
    g_stop.store(false);g_count.store(0);g_found.store(0);g_wps.store(0);
    g_objetivo_hallado.store(0);
    g_seq_en_curso.store(0); g_seq_agotado.store(0); g_rango_completo.store(0);
    { std::lock_guard<std::mutex> lk(g_seq_mutex); memcpy(g_seq_pos,g_range_start,32); }
    g_last_count=0;g_last_wps_t=time(nullptr);
    g_start_time=time(nullptr);g_running.store(true);
    int n=threads>MAX_THREADS?MAX_THREADS:threads;
    void *(*fn)(void*) = (g_mode.load()==1) ? worker_puzzle_fn :
                          (g_mode.load()==2) ? worker_rawkey_fn : worker_bip39_fn;
    /* Se pasaba nullptr, así que ningún worker conocía su índice y todos
       acababan compitiendo por un único núcleo. */
    for(int i=0;i<n;i++) pthread_create(&g_workers[i],nullptr,fn,(void*)(intptr_t)i);
    g_active=n;
    const char *modeStr=(g_mode.load()==1)?"PUZZLE":(g_mode.load()==2)?"RAWKEY":"BIP39";
    add_log(std::string("Started | mode:")+modeStr+" | threads:"+std::to_string(n)+" | CPU:"+std::to_string(cpuLimit)+"%");
}

/* Parar de verdad.
 *
 * Poner g_stop a mano NO basta: los hilos salen del bucle, si, pero nadie los
 * recoge y g_running se queda en true. Desde fuera eso se ve como un motor que
 * ha dejado de trabajar y sigue diciendo que trabaja: el boton se queda en
 * rojo y el reloj corriendo. Paso de verdad al hacer que el motor se parara
 * solo al encontrar el objetivo.
 *
 * Esta es la secuencia entera, y la usan los dos sitios que paran: el boton y
 * el hallazgo del objetivo. */
static void parar_motor(){
    if(!g_running.load())return;
    /* Idempotente: si ya se está parando, no montar otro joiner. */
    bool expected=false;
    if(!g_stopping.compare_exchange_strong(expected,true))return;
    g_stop.store(true);
    std::thread([]{
        for(int i=0;i<g_active;i++) pthread_join(g_workers[i],nullptr);
        g_running.store(false);g_active=0;
        g_stopping.store(false);
        add_log("Stopped | total:"+std::to_string(g_count.load())+" | matches:"+std::to_string(g_found.load()));
    }).detach();
}

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_stopHunting(JNIEnv *,jobject){
    parar_motor();
}

/* ¿Hay una parada en curso? La UI lo usa para no aceptar pulsaciones mientras
   los workers todavía no han terminado. */
JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_isStopping(JNIEnv *,jobject){return (jboolean)g_stopping.load();}

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_setCpuLimit(JNIEnv *,jobject,jint v){g_cpu_limit.store(v);}

/* Máscara de rutas BIP39: bit0 = BIP44, bit1 = BIP84. Al menos una. */
JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_setBip39Paths(JNIEnv *,jobject,jint mask){
    g_bip39_paths.store((mask&3)==0 ? 3 : (mask&3));
}


JNIEXPORT jlong JNICALL
Java_com_hunter_btc_HunterEngine_getCsvCount(JNIEnv *,jobject){
    return (jlong)g_total;
}

JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_isCsvLoaded(JNIEnv *,jobject){return (jboolean)g_csv_loaded.load();}

JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_isLoading(JNIEnv *,jobject){return (jboolean)g_loading.load();}

JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_isRunning(JNIEnv *,jobject){return (jboolean)g_running.load();}

JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_getLoadStatus(JNIEnv *env,jobject){return env->NewStringUTF(g_load_status);}

JNIEXPORT jdouble JNICALL
Java_com_hunter_btc_HunterEngine_getWps(JNIEnv *,jobject){
    time_t now=time(nullptr);
    if(now!=g_last_wps_t&&g_last_wps_t>0){
        long cur=g_count.load();double el=difftime(now,g_last_wps_t);
        if(el>0)g_wps.store((cur-g_last_count)/el);
        g_last_count=cur;g_last_wps_t=now;
    } else if(g_last_wps_t==0) g_last_wps_t=now;
    return g_wps.load();
}

JNIEXPORT jlong JNICALL
Java_com_hunter_btc_HunterEngine_getCount(JNIEnv *,jobject){return (jlong)g_count.load();}

JNIEXPORT jlong JNICALL
Java_com_hunter_btc_HunterEngine_getFound(JNIEnv *,jobject){return (jlong)g_found.load();}

JNIEXPORT jlong JNICALL
Java_com_hunter_btc_HunterEngine_getElapsed(JNIEnv *,jobject){
    if(g_start_time==0)return 0;
    return (jlong)difftime(time(nullptr),g_start_time);
}

JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_popLog(JNIEnv *env,jobject){
    std::lock_guard<std::mutex> lk(g_log_mutex);
    if(g_log.empty())return env->NewStringUTF("");
    std::string s=g_log.front();g_log.pop_front();
    return env->NewStringUTF(s.c_str());
}

/* ¿Ha aparecido ya el objetivo unico?
 *
 * Sirve para distinguir "el motor se ha parado solo porque ha terminado" de
 * "el motor se ha parado y no se sabe por que", que es lo que mira el watchdog
 * para decidir si relanzar. Sin esto, encontrar la clave y que el watchdog la
 * vuelva a buscar es lo mismo desde fuera. */
JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_objetivoHallado(JNIEnv *,jobject){
    return (jboolean)(g_objetivo_hallado.load()!=0);
}

JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_getMatches(JNIEnv *env,jobject){
    std::string all=coinc_texto(&g_matches);
    return env->NewStringUTF(all.c_str());
}


static const char B58_ALPHA[] = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
/*
 * Decodifica un WIF validándolo entero.
 *
 * La versión anterior no comprobaba el checksum ni la longitud: buscaba el
 * primer byte 0x80 dentro del buffer y copiaba los 32 siguientes. Con eso,
 * cambiar un carácter en medio de la clave devolvía OTRA clave privada
 * distinta, sin decir nada —comprobado: un typo en el carácter 26 de un WIF
 * válido daba ...10311c1df79751f535b39874e818fb en lugar de la original—. Y si
 * no encontraba ningún 0x80 dejaba start=0 y devolvía true igualmente, así que
 * cualquier cadena Base58 "decodificaba" bien: "1"*52 daba una clave de ceros.
 *
 * Formato: 0x80 | privkey(32) | [0x01 si comprimida] | checksum(4)
 * El checksum son los 4 primeros bytes de SHA256(SHA256(resto)).
 */
static bool wif_decode(const char *wif, uint8_t *privkey) {
    size_t wlen = strlen(wif);
    if (wlen < 50 || wlen > 53) return false;

    uint8_t buf[64] = {0};
    for (size_t i = 0; i < wlen; i++) {
        const char *p = strchr(B58_ALPHA, wif[i]);
        if (!p) return false;
        int carry = (int)(p - B58_ALPHA);
        for (int j = 63; j >= 0; j--) {
            carry += 58 * buf[j];
            buf[j] = (uint8_t)(carry & 0xff);
            carry >>= 8;
        }
        if (carry) return false;          /* no cabe en 64 bytes: no es un WIF */
    }

    /* En Base58 cada '1' inicial es un byte 0x00 por delante del número. */
    size_t zeros = 0;
    while (zeros < wlen && wif[zeros] == '1') zeros++;
    size_t first = 0;
    while (first < 64 && buf[first] == 0) first++;

    size_t plen = zeros + (64 - first);
    if (plen != 37 && plen != 38) return false;

    uint8_t payload[38] = {0};
    memcpy(payload + zeros, buf + first, 64 - first);

    if (payload[0] != 0x80) return false;                 /* mainnet */
    if (plen == 38 && payload[33] != 0x01) return false;  /* marca de comprimida */

    uint8_t ck[32];
    SHA256_CTX sc;
    SHA256_Init(&sc); SHA256_Update(&sc, payload, plen - 4); SHA256_Final(ck, &sc);
    SHA256_Init(&sc); SHA256_Update(&sc, ck, 32);           SHA256_Final(ck, &sc);
    if (memcmp(ck, payload + plen - 4, 4) != 0) return false;

    memcpy(privkey, payload + 1, 32);
    return true;
}

JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_wifToAddr(JNIEnv *env, jobject, jstring jwif) {
    const char *wif = env->GetStringUTFChars(jwif, nullptr);
    if (!wif || strlen(wif) < 50) { env->ReleaseStringUTFChars(jwif, wif); return env->NewStringUTF(""); }
    uint8_t privkey[32] = {0};
    bool ok = wif_decode(wif, privkey);
    env->ReleaseStringUTFChars(jwif, wif);
    if (!ok) return env->NewStringUTF("");
    secp256k1_context *ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    /* Un WIF puede tener checksum correcto y aun así llevar un escalar fuera de
       rango (0, o >= n). get_pub33() ignora el fallo de pubkey_create y deja la
       clave pública a ceros, de donde saldría una dirección inventada. */
    if (!secp256k1_ec_seckey_verify(ctx, privkey)) {
        secp256k1_context_destroy(ctx);
        return env->NewStringUTF("");
    }
    uint8_t h160[20]; char addr[64] = {0};
    pk_to_h160(ctx, privkey, h160);
    h160_to_addr(h160, addr);
    secp256k1_context_destroy(ctx);
    return env->NewStringUTF(addr);
}

/* De una clave privada en hexadecimal, su WIF y su direccion.
 *
 * Kangaroo devuelve la clave y nada mas, y asi es como se guardaba en el baul:
 * con la direccion y el WIF vacios. La entrada quedaba ahi, pero sin nada que
 * la identificara ni nada con lo que gastar, o sea que parecia que no se habia
 * guardado. Encontrar una clave y que no se vea es de lo peor que puede hacer
 * este programa.
 *
 * @return "WIF|direccion", o "" si la clave no es valida.
 */
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_datosDeClave(JNIEnv *env, jobject, jstring jhex){
    const char *hex = env->GetStringUTFChars(jhex, nullptr);
    if(!hex || strlen(hex)!=64){ if(hex) env->ReleaseStringUTFChars(jhex,hex);
                                 return env->NewStringUTF(""); }
    uint8_t privkey[32]={0};
    int malo=0;
    for(int i=0;i<32;i++){
        int a=-1,b=-1;
        char c1=hex[i*2], c2=hex[i*2+1];
        if(c1>='0'&&c1<='9')a=c1-'0'; else if(c1>='a'&&c1<='f')a=c1-'a'+10;
        else if(c1>='A'&&c1<='F')a=c1-'A'+10;
        if(c2>='0'&&c2<='9')b=c2-'0'; else if(c2>='a'&&c2<='f')b=c2-'a'+10;
        else if(c2>='A'&&c2<='F')b=c2-'A'+10;
        if(a<0||b<0){ malo=1; break; }
        privkey[i]=(uint8_t)((a<<4)|b);
    }
    env->ReleaseStringUTFChars(jhex,hex);
    if(malo) return env->NewStringUTF("");
    secp256k1_context *ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    if(!secp256k1_ec_seckey_verify(ctx,privkey)){
        secp256k1_context_destroy(ctx);
        return env->NewStringUTF("");
    }
    uint8_t h160[20]; char addr[64]={0}, wif[60]={0};
    pk_to_h160(ctx,privkey,h160);
    h160_to_addr(h160,addr);
    pk_to_wif(privkey,wif);
    secp256k1_context_destroy(ctx);
    std::string r=std::string(wif)+"|"+addr;
    return env->NewStringUTF(r.c_str());
}



/* Todas las direcciones de una clave: privada (64 hex) o pública (02/03 + 64, o
 * 04 + 128). Devuelve líneas "etiqueta=direccion". Reutiliza los codificadores
 * de addr_encode.h y OpenSSL para el hash160 de la pública sin comprimir. */
static void h160_de(const uint8_t *pub, size_t len, uint8_t out20[20]){
    uint8_t sha[32]; SHA256(pub, len, sha); RIPEMD160(sha, 32, out20);
}
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_direccionesDe(JNIEnv *env,jobject,jstring jin){
    const char *in = env->GetStringUTFChars(jin, nullptr);
    std::string s = in ? in : "";
    if(in) env->ReleaseStringUTFChars(jin, in);
    // Quitar espacios y 0x, pasar a minúsculas.
    std::string hex; for(char c: s){ if(c=='\n'||c==' '||c=='\t'||c=='\r') continue; hex+=(char)tolower(c); }
    if(hex.rfind("0x",0)==0) hex=hex.substr(2);
    auto hexbytes=[&](const std::string &h, uint8_t *o, size_t n)->bool{
        if(h.size()!=n*2) return false;
        for(size_t i=0;i<n;i++){ int a=-1,b=-1; char c1=h[i*2],c2=h[i*2+1];
            if(c1>='0'&&c1<='9')a=c1-'0'; else if(c1>='a'&&c1<='f')a=c1-'a'+10;
            if(c2>='0'&&c2<='9')b=c2-'0'; else if(c2>='a'&&c2<='f')b=c2-'a'+10;
            if(a<0||b<0) return false; o[i]=(uint8_t)((a<<4)|b); }
        return true;
    };
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN|SECP256K1_CONTEXT_VERIFY);
    secp256k1_pubkey pub; bool ok=false;
    if(hex.size()==64){                       // privada hex
        uint8_t pk[32];
        if(hexbytes(hex,pk,32) && secp256k1_ec_seckey_verify(ctx,pk))
            ok = secp256k1_ec_pubkey_create(ctx,&pub,pk)!=0;
    } else if(hex.size()==66 && (hex.rfind("02",0)==0||hex.rfind("03",0)==0)){
        uint8_t p[33]; if(hexbytes(hex,p,33)) ok=secp256k1_ec_pubkey_parse(ctx,&pub,p,33)!=0;
    } else if(hex.size()==130 && hex.rfind("04",0)==0){
        uint8_t p[65]; if(hexbytes(hex,p,65)) ok=secp256k1_ec_pubkey_parse(ctx,&pub,p,65)!=0;
    }
    if(!ok){ secp256k1_context_destroy(ctx); return env->NewStringUTF(""); }
    uint8_t comp[33]; size_t lc=33; secp256k1_ec_pubkey_serialize(ctx,comp,&lc,&pub,SECP256K1_EC_COMPRESSED);
    uint8_t unc[65];  size_t lu=65; secp256k1_ec_pubkey_serialize(ctx,unc,&lu,&pub,SECP256K1_EC_UNCOMPRESSED);
    uint8_t hC[20],hU[20]; h160_de(comp,33,hC); h160_de(unc,65,hU);
    std::string r; char a[MAX_ADDR];
    a[0]=0; h160_to_addr(hC,a);   r+="P2PKH (compressed)="; r+=a; r+="\n";
    a[0]=0; h160_to_addr(hU,a);   r+="P2PKH (uncompressed)="; r+=a; r+="\n";
    a[0]=0; h160_to_p2sh(hC,a);   r+="P2SH-P2WPKH="; r+=a; r+="\n";
    a[0]=0; h160_to_bech32(hC,a); r+="P2WPKH (bech32)="; r+=a; r+="\n";
    uint8_t tw[32];
    if(taproot_tweak_pubkey(ctx,comp+1,tw)){ a[0]=0; xonly_to_p2tr(tw,a); r+="P2TR (taproot)="; r+=a; r+="\n"; }
    secp256k1_context_destroy(ctx);
    return env->NewStringUTF(r.c_str());
}

/* Pública comprimida (66 hex) de una privada hex, o "". */
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_pubDeHex(JNIEnv *env,jobject,jstring jhex){
    const char *hx=env->GetStringUTFChars(jhex,nullptr); std::string h=hx?hx:""; if(hx) env->ReleaseStringUTFChars(jhex,hx);
    if(h.size()!=64) return env->NewStringUTF("");
    uint8_t k[32]; for(int i=0;i<32;i++){ int a=-1,b=-1; char c1=tolower(h[i*2]),c2=tolower(h[i*2+1]);
        if(c1>='0'&&c1<='9')a=c1-'0'; else if(c1>='a'&&c1<='f')a=c1-'a'+10;
        if(c2>='0'&&c2<='9')b=c2-'0'; else if(c2>='a'&&c2<='f')b=c2-'a'+10;
        if(a<0||b<0) return env->NewStringUTF(""); k[i]=(uint8_t)((a<<4)|b); }
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    std::string out="";
    if(secp256k1_ec_seckey_verify(ctx,k)){ uint8_t p33[33]; get_pub33(ctx,k,p33);
        char hex[67]; for(int i=0;i<33;i++) snprintf(hex+i*2,3,"%02x",p33[i]); out=hex; }
    secp256k1_context_destroy(ctx);
    return env->NewStringUTF(out.c_str());
}

/* Firma un hash de 32 bytes (hex) con una privada hex → firma DER (hex, sin el
 * byte de sighash). Para firmar PSBT. */
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_firmarHash(JNIEnv *env,jobject,jstring jpriv,jstring jhash){
    const char *pv=env->GetStringUTFChars(jpriv,nullptr); std::string ps=pv?pv:""; if(pv) env->ReleaseStringUTFChars(jpriv,pv);
    const char *hs=env->GetStringUTFChars(jhash,nullptr); std::string hh=hs?hs:""; if(hs) env->ReleaseStringUTFChars(jhash,hs);
    if(ps.size()!=64||hh.size()!=64) return env->NewStringUTF("");
    uint8_t k[32],z[32];
    auto hx=[&](const std::string&s,uint8_t*o)->bool{ for(int i=0;i<32;i++){ int a=-1,b=-1; char c1=tolower(s[i*2]),c2=tolower(s[i*2+1]);
        if(c1>='0'&&c1<='9')a=c1-'0'; else if(c1>='a'&&c1<='f')a=c1-'a'+10; if(c2>='0'&&c2<='9')b=c2-'0'; else if(c2>='a'&&c2<='f')b=c2-'a'+10;
        if(a<0||b<0) return false; o[i]=(uint8_t)((a<<4)|b);} return true; };
    if(!hx(ps,k)||!hx(hh,z)) return env->NewStringUTF("");
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    std::string out="";
    secp256k1_ecdsa_signature sig;
    if(secp256k1_ec_seckey_verify(ctx,k) && secp256k1_ecdsa_sign(ctx,&sig,z,k,nullptr,nullptr)){
        uint8_t der[72]; size_t dl=72;
        if(secp256k1_ecdsa_signature_serialize_der(ctx,der,&dl,&sig)){
            char hex[160]; for(size_t i=0;i<dl;i++) snprintf(hex+i*2,3,"%02x",der[i]); out=hex; }
    }
    secp256k1_context_destroy(ctx);
    return env->NewStringUTF(out.c_str());
}

/* Deriva la privada de una ruta BIP32 concreta desde una frase semilla.
 * Devuelve la privada en 64 hex, o "" si la ruta no da una clave válida. */
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_deriveRuta(JNIEnv *env,jobject,jstring jm,jstring jp){
    const char *mn=env->GetStringUTFChars(jm,nullptr); std::string m=mn?mn:""; if(mn) env->ReleaseStringUTFChars(jm,mn);
    const char *pp=env->GetStringUTFChars(jp,nullptr); std::string path=pp?pp:""; if(pp) env->ReleaseStringUTFChars(jp,pp);
    if(m.empty()||path.empty()) return env->NewStringUTF("");
    uint8_t seed[64]; bip39_semilla(m.c_str(),m.size(),"",0,seed);
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    HDKey hd; memset(&hd,0,sizeof(hd));
    derive_path(ctx,seed,path.c_str(),&hd);
    std::string out="";
    if(secp256k1_ec_seckey_verify(ctx,hd.key)){
        char hx[65]; for(int i=0;i<32;i++) snprintf(hx+i*2,3,"%02x",hd.key[i]); out=hx;
    }
    secp256k1_context_destroy(ctx);
    return env->NewStringUTF(out.c_str());
}

/* ── BIP38: clave privada cifrada con contraseña (no multiplicada por EC) ──
 * scrypt estándar (EVP_PBE_scrypt) + AES-256-ECB, para que las "6P..." sean
 * compatibles con otras carteras. */
static void sha256d(const uint8_t *d, size_t n, uint8_t out32[32]){
    uint8_t h[32]; SHA256(d,n,h); SHA256(h,32,out32);
}
/* La dirección P2PKH (comprimida o no) de una pública ya serializada. */
static void pub_to_addr(const uint8_t *ser, size_t l, char *addr){
    uint8_t sha[32],h160[20]; SHA256(ser,l,sha); RIPEMD160(sha,32,h160); h160_to_addr(h160,addr);
}
static void bip38_addrhash(secp256k1_context *ctx, const uint8_t *priv, bool comp, uint8_t out4[4]){
    secp256k1_pubkey pub; secp256k1_ec_pubkey_create(ctx,&pub,priv);
    uint8_t ser[65]; size_t l=comp?33:65;
    secp256k1_ec_pubkey_serialize(ctx,ser,&l,&pub,comp?SECP256K1_EC_COMPRESSED:SECP256K1_EC_UNCOMPRESSED);
    uint8_t sha[32],h160[20]; SHA256(ser,l,sha); RIPEMD160(sha,32,h160);
    char addr[MAX_ADDR]={0}; h160_to_addr(h160,addr);
    uint8_t a1[32],a2[32]; SHA256((const uint8_t*)addr,strlen(addr),a1); SHA256(a1,32,a2);
    memcpy(out4,a2,4);
}
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_bip38Cifrar(JNIEnv *env,jobject,jstring jhex,jstring jpass,jboolean comp){
    const char *hx=env->GetStringUTFChars(jhex,nullptr); std::string h=hx?hx:""; if(hx) env->ReleaseStringUTFChars(jhex,hx);
    const char *pw=env->GetStringUTFChars(jpass,nullptr); std::string pass=pw?pw:""; if(pw) env->ReleaseStringUTFChars(jpass,pw);
    if(h.size()!=64) return env->NewStringUTF("");
    uint8_t priv[32];
    for(int i=0;i<32;i++){ int a=-1,b=-1; char c1=tolower(h[i*2]),c2=tolower(h[i*2+1]);
        if(c1>='0'&&c1<='9')a=c1-'0'; else if(c1>='a'&&c1<='f')a=c1-'a'+10;
        if(c2>='0'&&c2<='9')b=c2-'0'; else if(c2>='a'&&c2<='f')b=c2-'a'+10;
        if(a<0||b<0) return env->NewStringUTF(""); priv[i]=(uint8_t)((a<<4)|b); }
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    if(!secp256k1_ec_seckey_verify(ctx,priv)){ secp256k1_context_destroy(ctx); return env->NewStringUTF(""); }
    uint8_t ah[4]; bip38_addrhash(ctx,priv,comp,ah);
    uint8_t dk[64];
    if(EVP_PBE_scrypt(pass.c_str(),pass.size(),ah,4,16384,8,8,0,dk,64)!=1){ secp256k1_context_destroy(ctx); return env->NewStringUTF(""); }
    uint8_t x1[16],x2[16]; for(int i=0;i<16;i++){ x1[i]=priv[i]^dk[i]; x2[i]=priv[16+i]^dk[16+i]; }
    AES_KEY ak; AES_set_encrypt_key(dk+32,256,&ak);
    uint8_t e1[16],e2[16]; AES_encrypt(x1,e1,&ak); AES_encrypt(x2,e2,&ak);
    uint8_t rec[39]; rec[0]=0x01; rec[1]=0x42; rec[2]=(uint8_t)(0xC0|(comp?0x20:0));
    memcpy(rec+3,ah,4); memcpy(rec+7,e1,16); memcpy(rec+23,e2,16);
    char out[128]={0}; b58enc(rec,39,out,128);
    secp256k1_context_destroy(ctx);
    return env->NewStringUTF(out);
}
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_bip38Descifrar(JNIEnv *env,jobject,jstring jkey,jstring jpass){
    const char *ky=env->GetStringUTFChars(jkey,nullptr); std::string k=ky?ky:""; if(ky) env->ReleaseStringUTFChars(jkey,ky);
    const char *pw=env->GetStringUTFChars(jpass,nullptr); std::string pass=pw?pw:""; if(pw) env->ReleaseStringUTFChars(jpass,pw);
    // base58check decode → 43 bytes (39 payload + 4 checksum)
    BIGNUM *bn=BN_new(),*t=BN_new(),*b=BN_new(); BN_CTX *bc=BN_CTX_new(); BN_zero(bn); BN_set_word(b,58);
    bool okb=true; for(char ch: k){ const char *p=strchr(B58A,ch); if(!p){okb=false;break;} BN_mul(bn,bn,b,bc); BN_set_word(t,(unsigned long)(p-B58A)); BN_add(bn,bn,t); }
    std::string res="";
    if(okb && BN_num_bytes(bn)<=43){
        uint8_t raw[43]={0}; BN_bn2binpad(bn,raw,43);
        uint8_t h1[32],h2[32]; SHA256(raw,39,h1); SHA256(h1,32,h2);
        bool okck=(memcmp(h2,raw+39,4)==0 && raw[0]==0x01);
        if(okck && raw[1]==0x42){
            bool comp=(raw[2]&0x20)!=0; uint8_t ah[4]; memcpy(ah,raw+3,4);
            uint8_t dk[64];
            if(EVP_PBE_scrypt(pass.c_str(),pass.size(),ah,4,16384,8,8,0,dk,64)==1){
                AES_KEY ak; AES_set_decrypt_key(dk+32,256,&ak);
                uint8_t d1[16],d2[16]; AES_decrypt(raw+7,d1,&ak); AES_decrypt(raw+23,d2,&ak);
                uint8_t priv[32]; for(int i=0;i<16;i++){ priv[i]=d1[i]^dk[i]; priv[16+i]=d2[i]^dk[16+i]; }
                secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
                uint8_t ah2[4]; if(secp256k1_ec_seckey_verify(ctx,priv)){ bip38_addrhash(ctx,priv,comp,ah2);
                    if(memcmp(ah,ah2,4)==0){ char hx[65]; for(int i=0;i<32;i++) snprintf(hx+i*2,3,"%02x",priv[i]); res=std::string(hx)+(comp?"|1":"|0"); } }
                secp256k1_context_destroy(ctx);
            }
        } else if(okck && raw[1]==0x43){
            // EC-multiplied
            uint8_t flag=raw[2]; bool comp=(flag&0x20)!=0; bool hasLot=(flag&0x04)!=0;
            uint8_t ah[4]; memcpy(ah,raw+3,4);
            uint8_t ownerentropy[8]; memcpy(ownerentropy,raw+7,8);
            uint8_t enc1a[8]; memcpy(enc1a,raw+15,8);
            uint8_t enc2[16]; memcpy(enc2,raw+23,16);
            uint8_t passfactor[32]={0};
            if(hasLot){
                uint8_t prefactor[32];
                if(EVP_PBE_scrypt(pass.c_str(),pass.size(),ownerentropy,4,16384,8,8,0,prefactor,32)==1){
                    uint8_t buf[40]; memcpy(buf,prefactor,32); memcpy(buf+32,ownerentropy,8); sha256d(buf,40,passfactor);
                }
            } else EVP_PBE_scrypt(pass.c_str(),pass.size(),ownerentropy,8,16384,8,8,0,passfactor,32);
            secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN|SECP256K1_CONTEXT_VERIFY);
            secp256k1_pubkey pp;
            if(secp256k1_ec_seckey_verify(ctx,passfactor) && secp256k1_ec_pubkey_create(ctx,&pp,passfactor)){
                uint8_t passpoint[33]; size_t l=33; secp256k1_ec_pubkey_serialize(ctx,passpoint,&l,&pp,SECP256K1_EC_COMPRESSED);
                uint8_t salt[12]; memcpy(salt,ah,4); memcpy(salt+4,ownerentropy,8);
                uint8_t dk[64];
                if(EVP_PBE_scrypt((const char*)passpoint,33,salt,12,1024,1,1,0,dk,64)==1){
                    AES_KEY ak; AES_set_decrypt_key(dk+32,256,&ak);
                    uint8_t d2[16]; AES_decrypt(enc2,d2,&ak); for(int i=0;i<16;i++) d2[i]^=dk[16+i];
                    uint8_t enc1full[16]; memcpy(enc1full,enc1a,8); memcpy(enc1full+8,d2,8);
                    uint8_t d1[16]; AES_decrypt(enc1full,d1,&ak); for(int i=0;i<16;i++) d1[i]^=dk[i];
                    uint8_t seedb[24]; memcpy(seedb,d1,16); memcpy(seedb+16,d2+8,8);
                    uint8_t factorb[32]; sha256d(seedb,24,factorb);
                    uint8_t priv[32]; memcpy(priv,passfactor,32);
                    if(secp256k1_ec_seckey_tweak_mul(ctx,priv,factorb)){
                        uint8_t ah2[4]; bip38_addrhash(ctx,priv,comp,ah2);
                        if(memcmp(ah,ah2,4)==0){ char hx[65]; for(int i=0;i<32;i++) snprintf(hx+i*2,3,"%02x",priv[i]); res=std::string(hx)+(comp?"|1":"|0"); }
                    }
                }
            }
            secp256k1_context_destroy(ctx);
        }
    }
    BN_free(bn);BN_free(t);BN_free(b);BN_CTX_free(bc);
    return env->NewStringUTF(res.c_str());
}

/* BIP38 EC-multiplied, paso 1 (dueño): código intermedio "passphrase…" a
 * partir de la contraseña. lot/seq opcionales (lot<0 = sin ellos). */
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_bip38Intermediate(JNIEnv *env,jobject,jstring jpass,jint jlot,jint jseq){
    const char *pw=env->GetStringUTFChars(jpass,nullptr); std::string pass=pw?pw:""; if(pw) env->ReleaseStringUTFChars(jpass,pw);
    bool useLot=(jlot>=0);
    uint8_t ownerentropy[8]={0}; uint8_t passfactor[32]={0};
    if(!useLot){
        uint8_t salt[8]; RAND_bytes(salt,8); memcpy(ownerentropy,salt,8);
        if(EVP_PBE_scrypt(pass.c_str(),pass.size(),salt,8,16384,8,8,0,passfactor,32)!=1) return env->NewStringUTF("");
    } else {
        uint8_t salt[4]; RAND_bytes(salt,4);
        uint32_t ls=(uint32_t)jlot*4096u+(uint32_t)jseq;
        memcpy(ownerentropy,salt,4);
        ownerentropy[4]=(uint8_t)((ls>>24)&0xff); ownerentropy[5]=(uint8_t)((ls>>16)&0xff);
        ownerentropy[6]=(uint8_t)((ls>>8)&0xff);  ownerentropy[7]=(uint8_t)(ls&0xff);
        uint8_t prefactor[32];
        if(EVP_PBE_scrypt(pass.c_str(),pass.size(),salt,4,16384,8,8,0,prefactor,32)!=1) return env->NewStringUTF("");
        uint8_t buf[40]; memcpy(buf,prefactor,32); memcpy(buf+32,ownerentropy,8); sha256d(buf,40,passfactor);
    }
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    std::string res="";
    secp256k1_pubkey pp;
    if(secp256k1_ec_seckey_verify(ctx,passfactor) && secp256k1_ec_pubkey_create(ctx,&pp,passfactor)){
        uint8_t passpoint[33]; size_t l=33; secp256k1_ec_pubkey_serialize(ctx,passpoint,&l,&pp,SECP256K1_EC_COMPRESSED);
        uint8_t rec[49]={0x2C,0xE9,0xB3,0xE1,0xFF,0x39,0xE2,(uint8_t)(useLot?0x51:0x53)};
        memcpy(rec+8,ownerentropy,8); memcpy(rec+16,passpoint,33);
        char out[128]={0}; b58enc(rec,49,out,128); res=out;
    }
    secp256k1_context_destroy(ctx);
    return env->NewStringUTF(res.c_str());
}

/* BIP38 EC-multiplied, paso 2 (tercero): del código intermedio genera una
 * clave cifrada "6P…" NUEVA y su dirección, sin conocer la contraseña.
 * Devuelve "6P…|direccion", o "". */
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_bip38GenerarCifrada(JNIEnv *env,jobject,jstring jinter,jboolean comp){
    const char *ic=env->GetStringUTFChars(jinter,nullptr); std::string code=ic?ic:""; if(ic) env->ReleaseStringUTFChars(jinter,ic);
    // base58check decode → 53 bytes (49 + 4 checksum)
    BIGNUM *bn=BN_new(),*t=BN_new(),*b=BN_new(); BN_CTX *bc=BN_CTX_new(); BN_zero(bn); BN_set_word(b,58);
    bool okb=true; for(char ch: code){ const char *p=strchr(B58A,ch); if(!p){okb=false;break;} BN_mul(bn,bn,b,bc); BN_set_word(t,(unsigned long)(p-B58A)); BN_add(bn,bn,t); }
    std::string res="";
    if(okb && BN_num_bytes(bn)<=53){
        uint8_t rec[53]={0}; BN_bn2binpad(bn,rec,53);
        uint8_t h1[32],h2[32]; SHA256(rec,49,h1); SHA256(h1,32,h2);
        bool magicOk = rec[0]==0x2C&&rec[1]==0xE9&&rec[2]==0xB3&&rec[3]==0xE1&&rec[4]==0xFF&&rec[5]==0x39&&rec[6]==0xE2&&(rec[7]==0x51||rec[7]==0x53);
        if(memcmp(h2,rec+49,4)==0 && magicOk){
            bool hasLot=(rec[7]==0x51);
            uint8_t ownerentropy[8]; memcpy(ownerentropy,rec+8,8);
            uint8_t passpoint[33]; memcpy(passpoint,rec+16,33);
            secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN|SECP256K1_CONTEXT_VERIFY);
            secp256k1_pubkey pp;
            if(secp256k1_ec_pubkey_parse(ctx,&pp,passpoint,33)){
                uint8_t seedb[24],factorb[32]; secp256k1_pubkey gen; bool ok=false;
                for(int tries=0;tries<8 && !ok;tries++){
                    RAND_bytes(seedb,24); sha256d(seedb,24,factorb);
                    gen=pp; if(secp256k1_ec_pubkey_tweak_mul(ctx,&gen,factorb)) ok=true;
                }
                if(ok){
                    uint8_t pub[65]; size_t l=comp?33:65;
                    secp256k1_ec_pubkey_serialize(ctx,pub,&l,&gen,comp?SECP256K1_EC_COMPRESSED:SECP256K1_EC_UNCOMPRESSED);
                    char address[MAX_ADDR]={0}; pub_to_addr(pub,l,address);
                    uint8_t ah32[32]; sha256d((const uint8_t*)address,strlen(address),ah32);
                    uint8_t ah[4]; memcpy(ah,ah32,4);
                    uint8_t salt[12]; memcpy(salt,ah,4); memcpy(salt+4,ownerentropy,8);
                    uint8_t dk[64];
                    if(EVP_PBE_scrypt((const char*)passpoint,33,salt,12,1024,1,1,0,dk,64)==1){
                        AES_KEY ak; AES_set_encrypt_key(dk+32,256,&ak);
                        uint8_t block1[16],enc1[16]; for(int i=0;i<16;i++) block1[i]=seedb[i]^dk[i]; AES_encrypt(block1,enc1,&ak);
                        uint8_t block2[16],enc2[16];
                        for(int i=0;i<8;i++) block2[i]=enc1[8+i]^dk[16+i];
                        for(int i=0;i<8;i++) block2[8+i]=seedb[16+i]^dk[24+i];
                        AES_encrypt(block2,enc2,&ak);
                        uint8_t out39[39]; out39[0]=0x01; out39[1]=0x43;
                        out39[2]=(uint8_t)((comp?0x20:0)|(hasLot?0x04:0));
                        memcpy(out39+3,ah,4); memcpy(out39+7,ownerentropy,8);
                        memcpy(out39+15,enc1,8); memcpy(out39+23,enc2,16);
                        char six[128]={0}; b58enc(out39,39,six,128);
                        res=std::string(six)+"|"+address;
                    }
                }
            }
            secp256k1_context_destroy(ctx);
        }
    }
    BN_free(bn);BN_free(t);BN_free(b);BN_CTX_free(bc);
    return env->NewStringUTF(res.c_str());
}

/* De una privada hex (64) a sus dos WIF: comprimida | sin comprimir. */
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_wifDeHex(JNIEnv *env,jobject,jstring jhex){
    const char *hex=env->GetStringUTFChars(jhex,nullptr);
    std::string h=hex?hex:""; if(hex) env->ReleaseStringUTFChars(jhex,hex);
    if(h.rfind("0x",0)==0) h=h.substr(2);
    if(h.size()!=64) return env->NewStringUTF("");
    uint8_t k[32];
    for(int i=0;i<32;i++){ int a=-1,b=-1; char c1=tolower(h[i*2]),c2=tolower(h[i*2+1]);
        if(c1>='0'&&c1<='9')a=c1-'0'; else if(c1>='a'&&c1<='f')a=c1-'a'+10;
        if(c2>='0'&&c2<='9')b=c2-'0'; else if(c2>='a'&&c2<='f')b=c2-'a'+10;
        if(a<0||b<0) return env->NewStringUTF(""); k[i]=(uint8_t)((a<<4)|b); }
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    bool okk=secp256k1_ec_seckey_verify(ctx,k)!=0; secp256k1_context_destroy(ctx);
    if(!okk) return env->NewStringUTF("");
    char wc[60]={0}; pk_to_wif(k,wc);                       // comprimida (sufijo 0x01)
    uint8_t v[33]; v[0]=0x80; memcpy(v+1,k,32); char wu[60]={0}; b58enc(v,33,wu,60); // sin comprimir
    std::string r=std::string(wc)+"|"+wu;
    return env->NewStringUTF(r.c_str());
}

/* De un WIF a su privada en hex (64), o "" si el WIF no vale. */
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_hexDeWif(JNIEnv *env,jobject,jstring jwif){
    const char *w=env->GetStringUTFChars(jwif,nullptr);
    std::string s=w?w:""; if(w) env->ReleaseStringUTFChars(jwif,w);
    uint8_t k[32];
    if(!wif_decode(s.c_str(),k)) return env->NewStringUTF("");
    char out[65]; for(int i=0;i<32;i++) snprintf(out+i*2,3,"%02x",k[i]);
    return env->NewStringUTF(out);
}

/* Recuperación por nonce reutilizado. Dadas dos firmas (misma r) con sus s y
 * sus hashes de mensaje z, despeja la privada:
 *   k = (z1 - z2) / (s1 - s2)   mod n
 *   d = (s1·k - z1) / r         mod n
 * Devuelve la privada en 64 hex, o "" si no se puede (p. ej. s1==s2). Toda la
 * aritmética va mod n (orden de secp256k1) con BIGNUM de OpenSSL. */
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_recuperarNonce(JNIEnv *env,jobject,
        jstring jr,jstring js1,jstring jz1,jstring js2,jstring jz2){
    auto bn=[&](jstring js)->BIGNUM*{ const char*c=env->GetStringUTFChars(js,nullptr);
        BIGNUM*b=nullptr; if(c) BN_hex2bn(&b,c); if(c) env->ReleaseStringUTFChars(js,c); return b; };
    BIGNUM *n=nullptr; BN_hex2bn(&n,"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141");
    BIGNUM *r=bn(jr),*s1=bn(js1),*z1=bn(jz1),*s2=bn(js2),*z2=bn(jz2);
    std::string res="";
    BN_CTX *c=BN_CTX_new();
    if(n&&r&&s1&&z1&&s2&&z2&&c){
        BIGNUM *ds=BN_new(),*inv=BN_new(),*dz=BN_new(),*k=BN_new();
        BIGNUM *sk=BN_new(),*num=BN_new(),*rinv=BN_new(),*d=BN_new();
        BN_mod_sub(ds,s1,s2,n,c);
        if(!BN_is_zero(ds) && BN_mod_inverse(inv,ds,n,c) && BN_mod_inverse(rinv,r,n,c)){
            BN_mod_sub(dz,z1,z2,n,c);
            BN_mod_mul(k,dz,inv,n,c);
            BN_mod_mul(sk,s1,k,n,c);
            BN_mod_sub(num,sk,z1,n,c);
            BN_mod_mul(d,num,rinv,n,c);
            if(!BN_is_zero(d)){
                uint8_t raw[32]={0}; int nb=BN_num_bytes(d);
                if(nb<=32){ BN_bn2bin(d,raw+(32-nb));
                    char out[65]; for(int i=0;i<32;i++) snprintf(out+i*2,3,"%02x",raw[i]); res=out; }
            }
        }
        BN_free(ds);BN_free(inv);BN_free(dz);BN_free(k);BN_free(sk);BN_free(num);BN_free(rinv);BN_free(d);
    }
    if(n)BN_free(n); if(r)BN_free(r); if(s1)BN_free(s1); if(z1)BN_free(z1);
    if(s2)BN_free(s2); if(z2)BN_free(z2); if(c)BN_CTX_free(c);
    return env->NewStringUTF(res.c_str());
}

/* Hash de un mensaje al estilo Bitcoin: dSHA256( 0x18 "Bitcoin Signed
 * Message:\n" + varint(len) + msg ). */
static void msg_hash(const std::string &msg, uint8_t out32[32]){
    std::string pre; pre += (char)0x18; pre += "Bitcoin Signed Message:\n";
    size_t n=msg.size();
    if(n<0xfd) pre+=(char)n;
    else if(n<=0xffff){ pre+=(char)0xfd; pre+=(char)(n&0xff); pre+=(char)((n>>8)&0xff); }
    else { pre+=(char)0xfe; for(int i=0;i<4;i++) pre+=(char)((n>>(8*i))&0xff); }
    pre+=msg;
    uint8_t h1[32]; SHA256((const uint8_t*)pre.data(),pre.size(),h1); SHA256(h1,32,out32);
}

/* Firma un mensaje con una privada hex (64). Devuelve la firma compacta
 * recuperable en 65 bytes hex (cabecera 27+recid+4 comprimida, + r + s). */
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_firmarMensaje(JNIEnv *env,jobject,jstring jhex,jstring jmsg){
    const char *hx=env->GetStringUTFChars(jhex,nullptr); std::string h=hx?hx:""; if(hx) env->ReleaseStringUTFChars(jhex,hx);
    const char *mg=env->GetStringUTFChars(jmsg,nullptr); std::string m=mg?mg:""; if(mg) env->ReleaseStringUTFChars(jmsg,mg);
    if(h.size()!=64) return env->NewStringUTF("");
    uint8_t k[32];
    for(int i=0;i<32;i++){ int a=-1,b=-1; char c1=tolower(h[i*2]),c2=tolower(h[i*2+1]);
        if(c1>='0'&&c1<='9')a=c1-'0'; else if(c1>='a'&&c1<='f')a=c1-'a'+10;
        if(c2>='0'&&c2<='9')b=c2-'0'; else if(c2>='a'&&c2<='f')b=c2-'a'+10;
        if(a<0||b<0) return env->NewStringUTF(""); k[i]=(uint8_t)((a<<4)|b); }
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    uint8_t hash[32]; msg_hash(m,hash);
    secp256k1_ecdsa_recoverable_signature rsig;
    std::string out="";
    if(secp256k1_ec_seckey_verify(ctx,k) &&
       secp256k1_ecdsa_sign_recoverable(ctx,&rsig,hash,k,nullptr,nullptr)){
        uint8_t comp[64]; int recid=0;
        secp256k1_ecdsa_recoverable_signature_serialize_compact(ctx,comp,&recid,&rsig);
        uint8_t full[65]; full[0]=(uint8_t)(27+recid+4);  // +4: clave comprimida
        memcpy(full+1,comp,64);
        char hex[131]; for(int i=0;i<65;i++) snprintf(hex+i*2,3,"%02x",full[i]); out=hex;
    }
    secp256k1_context_destroy(ctx);
    return env->NewStringUTF(out.c_str());
}

/* Verifica una firma (65 bytes hex) sobre un mensaje: recupera la pública y
 * devuelve su dirección P2PKH (comprimida o no según la cabecera), o "". Quien
 * llama compara esa dirección con la que dice ser el firmante. */
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_verificarMensaje(JNIEnv *env,jobject,jstring jmsg,jstring jsig){
    const char *mg=env->GetStringUTFChars(jmsg,nullptr); std::string m=mg?mg:""; if(mg) env->ReleaseStringUTFChars(jmsg,mg);
    const char *sg=env->GetStringUTFChars(jsig,nullptr); std::string s=sg?sg:""; if(sg) env->ReleaseStringUTFChars(jsig,sg);
    if(s.size()!=130) return env->NewStringUTF("");
    uint8_t full[65];
    for(int i=0;i<65;i++){ int a=-1,b=-1; char c1=tolower(s[i*2]),c2=tolower(s[i*2+1]);
        if(c1>='0'&&c1<='9')a=c1-'0'; else if(c1>='a'&&c1<='f')a=c1-'a'+10;
        if(c2>='0'&&c2<='9')b=c2-'0'; else if(c2>='a'&&c2<='f')b=c2-'a'+10;
        if(a<0||b<0) return env->NewStringUTF(""); full[i]=(uint8_t)((a<<4)|b); }
    int hdr=full[0]; if(hdr<27||hdr>34) return env->NewStringUTF("");
    int recid=(hdr-27)&3; bool comp=((hdr-27)&4)!=0;
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_VERIFY);
    uint8_t hash[32]; msg_hash(m,hash);
    secp256k1_ecdsa_recoverable_signature rsig; secp256k1_pubkey pub;
    std::string out="";
    if(secp256k1_ecdsa_recoverable_signature_parse_compact(ctx,&rsig,full+1,recid) &&
       secp256k1_ecdsa_recover(ctx,&pub,&rsig,hash)){
        uint8_t ser[65]; size_t len=comp?33:65;
        secp256k1_ec_pubkey_serialize(ctx,ser,&len,&pub,comp?SECP256K1_EC_COMPRESSED:SECP256K1_EC_UNCOMPRESSED);
        uint8_t sha[32],h160[20]; SHA256(ser,len,sha); RIPEMD160(sha,32,h160);
        char addr[MAX_ADDR]={0}; h160_to_addr(h160,addr); out=addr;
    }
    secp256k1_context_destroy(ctx);
    return env->NewStringUTF(out.c_str());
}

/* Derivación pública no endurecida (CKDpub): hijo i de un nodo (pub, chain). */
static bool ckd_pub(secp256k1_context *ctx, const secp256k1_pubkey *par,
                    const uint8_t parChain[32], uint32_t i,
                    secp256k1_pubkey *out, uint8_t outChain[32]){
    uint8_t comp[33]; size_t l=33;
    secp256k1_ec_pubkey_serialize(ctx,comp,&l,par,SECP256K1_EC_COMPRESSED);
    uint8_t data[37]; memcpy(data,comp,33);
    data[33]=(uint8_t)((i>>24)&0xff); data[34]=(uint8_t)((i>>16)&0xff);
    data[35]=(uint8_t)((i>>8)&0xff);  data[36]=(uint8_t)(i&0xff);
    uint8_t I[64]; hmac_sha512(parChain,32,data,37,I);
    *out=*par;
    if(!secp256k1_ec_pubkey_tweak_add(ctx,out,I)) return false;   // + IL·G
    memcpy(outChain,I+32,32);
    return true;
}

/* Primeras [n] direcciones de recepción (m/0/i) de un xpub/ypub/zpub. El tipo
 * de dirección sale de la versión del extended key. Líneas "i=direccion". */
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_xpubDirecciones(JNIEnv *env,jobject,jstring jx,jint jn){
    const char *xp=env->GetStringUTFChars(jx,nullptr); std::string x=xp?xp:""; if(xp) env->ReleaseStringUTFChars(jx,xp);
    int n=jn; if(n<1)n=1; if(n>50)n=50;
    // base58 → bytes
    BIGNUM *bn=BN_new(),*t=BN_new(),*b=BN_new(); BN_CTX *bc=BN_CTX_new(); BN_zero(bn); BN_set_word(b,58);
    bool okb=true;
    for(char ch: x){ const char *p=strchr(B58A,ch); if(!p){okb=false;break;} BN_mul(bn,bn,b,bc); BN_set_word(t,(unsigned long)(p-B58A)); BN_add(bn,bn,t); }
    std::string res="";
    if(okb && BN_num_bytes(bn)==82){
        uint8_t raw[82]; BN_bn2binpad(bn,raw,82);
        uint8_t h1[32],h2[32]; SHA256(raw,78,h1); SHA256(h1,32,h2);
        if(memcmp(h2,raw+78,4)==0){
            uint32_t ver=((uint32_t)raw[0]<<24)|((uint32_t)raw[1]<<16)|((uint32_t)raw[2]<<8)|raw[3];
            int tipo = (ver==0x049d7cb2u)?1 : (ver==0x04b24746u)?2 : 0;  // ypub / zpub / xpub
            const uint8_t *chain=raw+13; const uint8_t *key=raw+45;
            secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_VERIFY);
            secp256k1_pubkey acct;
            if(secp256k1_ec_pubkey_parse(ctx,&acct,key,33)){
                secp256k1_pubkey ext; uint8_t extChain[32];
                if(ckd_pub(ctx,&acct,chain,0,&ext,extChain)){   // cadena externa /0
                    for(int i=0;i<n;i++){
                        secp256k1_pubkey child; uint8_t cch[32];
                        if(!ckd_pub(ctx,&ext,extChain,(uint32_t)i,&child,cch)) break;
                        uint8_t c33[33]; size_t cl=33; secp256k1_ec_pubkey_serialize(ctx,c33,&cl,&child,SECP256K1_EC_COMPRESSED);
                        uint8_t sha[32],h160[20]; SHA256(c33,33,sha); RIPEMD160(sha,32,h160);
                        char a[MAX_ADDR]={0};
                        if(tipo==2) h160_to_bech32(h160,a); else if(tipo==1) h160_to_p2sh(h160,a); else h160_to_addr(h160,a);
                        char pre[8]; snprintf(pre,8,"%d=",i); res+=pre; res+=a; res+="\n";
                    }
                }
            }
            secp256k1_context_destroy(ctx);
        }
    }
    BN_free(bn);BN_free(t);BN_free(b);BN_CTX_free(bc);
    return env->NewStringUTF(res.c_str());
}

/* Claves PÚBLICAS hijas de un xpub (cadenas /0/i y /1/i), comprimidas. Para
 * auditar un monedero sin esperar a que las direcciones gasten. Líneas
 * "c/i=pubhex". */
JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_xpubPubkeys(JNIEnv *env,jobject,jstring jx,jint jn){
    const char *xp=env->GetStringUTFChars(jx,nullptr); std::string x=xp?xp:""; if(xp) env->ReleaseStringUTFChars(jx,xp);
    int n=jn; if(n<1)n=1; if(n>500)n=500;
    BIGNUM *bn=BN_new(),*t=BN_new(),*b=BN_new(); BN_CTX *bc=BN_CTX_new(); BN_zero(bn); BN_set_word(b,58);
    bool okb=true; for(char ch: x){ const char *p=strchr(B58A,ch); if(!p){okb=false;break;} BN_mul(bn,bn,b,bc); BN_set_word(t,(unsigned long)(p-B58A)); BN_add(bn,bn,t); }
    std::string res="";
    if(okb && BN_num_bytes(bn)==82){
        uint8_t raw[82]; BN_bn2binpad(bn,raw,82);
        uint8_t h1[32],h2[32]; SHA256(raw,78,h1); SHA256(h1,32,h2);
        if(memcmp(h2,raw+78,4)==0){
            const uint8_t *chain=raw+13; const uint8_t *key=raw+45;
            secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_VERIFY);
            secp256k1_pubkey acct;
            if(secp256k1_ec_pubkey_parse(ctx,&acct,key,33)){
                for(int c=0;c<2;c++){   // 0 = recepción, 1 = cambio
                    secp256k1_pubkey node; uint8_t nch[32];
                    if(!ckd_pub(ctx,&acct,chain,(uint32_t)c,&node,nch)) continue;
                    for(int i=0;i<n;i++){
                        secp256k1_pubkey child; uint8_t cch[32];
                        if(!ckd_pub(ctx,&node,nch,(uint32_t)i,&child,cch)) break;
                        uint8_t c33[33]; size_t cl=33; secp256k1_ec_pubkey_serialize(ctx,c33,&cl,&child,SECP256K1_EC_COMPRESSED);
                        char pre[12]; snprintf(pre,12,"%d/%d=",c,i); res+=pre;
                        for(int k=0;k<33;k++){ char hh[3]; snprintf(hh,3,"%02x",c33[k]); res+=hh; }
                        res+="\n";
                    }
                }
            }
            secp256k1_context_destroy(ctx);
        }
    }
    BN_free(bn);BN_free(t);BN_free(b);BN_CTX_free(bc);
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_setTarget(JNIEnv *env,jobject,jstring addr){
    const char *a=env->GetStringUTFChars(addr,nullptr);
    if(a&&a[0]){
        uint8_t h160[20]={0};
        if(addr_to_h160(a,h160)){
            memcpy(g_target_h160,h160,20);
            g_has_target=1;
            add_log(std::string("Target: ")+a);
        } else {
            g_has_target=0;
            add_log("ERROR: invalid address");
        }
    } else {
        g_has_target=0;
    }
    env->ReleaseStringUTFChars(addr,a);
}

JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_hasTarget(JNIEnv *,jobject){
    return (jboolean)(g_has_target==1);
}

/*
 * Deriva un tramo de direcciones de una rama cualquiera.
 *
 * derive_wallet_json() sólo emitía los índices 0..2 de la rama de recepción, así
 * que la app no podía ni buscar fondos más allá del índice 0 al restaurar una
 * seed, ni elegir una dirección de cambio sin estrenar. Esto expone la
 * derivación completa: propósito (44/49/84/86), rama (0 recepción, 1 cambio),
 * índice inicial y cuántas.
 *
 * Devuelve [{"i":n,"addr":"..."},...]; "[]" si los parámetros no valen.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_deriveAddresses(JNIEnv *env, jobject, jstring jmn,
                                                 jint purpose, jint change,
                                                 jint from, jint count,
                                                 jboolean jtestnet){
    const bool testnet = (jtestnet==JNI_TRUE);
    if(count<=0||count>200||from<0||change<0||change>1) return env->NewStringUTF("[]");
    if(purpose!=44&&purpose!=49&&purpose!=84&&purpose!=86) return env->NewStringUTF("[]");
    const char *mn=env->GetStringUTFChars(jmn,nullptr);
    if(!mn||!mn[0]){ if(mn)env->ReleaseStringUTFChars(jmn,mn); return env->NewStringUTF("[]"); }

    uint8_t seed[64];
    bip39_semilla(mn,strlen(mn),"",0,seed);
    env->ReleaseStringUTFChars(jmn,mn);

    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    HDKey master; { uint8_t s2[64]; memcpy(s2,seed,64); derive_master(s2,&master); }
    HDKey a,b,c,branch;
    derive_child(ctx,&master,0x80000000u+(uint32_t)purpose,&a);
    /* Coin type: 0' en mainnet, 1' en testnet (SLIP-44). No es cosmetico: son
       ramas distintas del arbol, con claves distintas. Derivar en 0' y luego
       pintar la direccion con el prefijo de testnet daria una direccion que no
       corresponde a ninguna cartera de pruebas de ningun otro programa. */
    derive_child(ctx,&a,0x80000000u+(testnet?1u:0u),&b);
    derive_child(ctx,&b,0x80000000u+0,&c);
    derive_child(ctx,&c,(uint32_t)change,&branch);

    std::string json="[";
    for(int i=0;i<count;i++){
        HDKey leaf; derive_child(ctx,&branch,(uint32_t)(from+i),&leaf);
        char addr[MAX_ADDR]={0};
        if(purpose==86){
            secp256k1_keypair kp; secp256k1_xonly_pubkey xo; int par=0;
            uint8_t internal[32], tweaked[32];
            if(!secp256k1_keypair_create(ctx,&kp,leaf.key) ||
               !secp256k1_keypair_xonly_pub(ctx,&xo,&par,&kp)) continue;
            secp256k1_xonly_pubkey_serialize(ctx,internal,&xo);
            if(!taproot_tweak_pubkey(ctx,internal,tweaked)) continue;
            xonly_to_p2tr(tweaked,addr,testnet);
        } else {
            uint8_t h160[20]; pk_to_h160(ctx,leaf.key,h160);
            if(purpose==44)      h160_to_addr(h160,addr,testnet);
            else if(purpose==49) h160_to_p2sh(h160,addr,testnet);
            else                 h160_to_bech32(h160,addr,testnet);
        }
        if(!addr[0]) continue;
        if(json.size()>1) json+=",";
        json+="{\"i\":"; json+=std::to_string(from+i);
        json+=",\"addr\":\""; json+=addr; json+="\"}";
    }
    json+="]";
    secp256k1_context_destroy(ctx);
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_deriveWallet(JNIEnv *env, jobject, jstring jmn,
                                              jboolean jtestnet){
    const char *mn=env->GetStringUTFChars(jmn,nullptr);
    std::string result=derive_wallet_json(mn,jtestnet==JNI_TRUE);
    env->ReleaseStringUTFChars(jmn,mn);
    return env->NewStringUTF(result.c_str());
}

/* =========================================================
   Build + Sign raw Bitcoin transaction
   Input JSON: {"mnemonic":"...","path":"m/44'/0'/0'/0/0",
                "utxos":[{"txid":"...","vout":0,"amount":1000}],
                "to":"1Addr...","amount":900,"fee":100}
   Returns: hex-encoded signed raw tx
   ========================================================= */
static std::string uint64_le(uint64_t v){
    char buf[16];
    for(int i=0;i<8;i++) buf[i]=(char)((v>>(i*8))&0xFF);
    return std::string(buf,8);
}
static std::string uint32_le(uint32_t v){
    char buf[4];
    for(int i=0;i<4;i++) buf[i]=(char)((v>>(i*8))&0xFF);
    return std::string(buf,4);
}
static std::string varint(uint64_t v){
    char buf[9]; int n=0;
    if(v<0xFD){buf[n++]=(char)v;}
    else if(v<=0xFFFF){buf[n++]=0xFD;buf[n++]=v&0xFF;buf[n++]=(v>>8)&0xFF;}
    else{buf[n++]=0xFE;for(int i=0;i<4;i++)buf[n++]=(v>>(i*8))&0xFF;}
    return std::string(buf,n);
}
static std::string hex_decode(const std::string &hex){
    std::string r; r.resize(hex.size()/2);
    for(size_t i=0;i<r.size();i++){
        int hi=hex[i*2],lo=hex[i*2+1];
        hi=(hi>='a')?hi-'a'+10:(hi>='A')?hi-'A'+10:hi-'0';
        lo=(lo>='a')?lo-'a'+10:(lo>='A')?lo-'A'+10:lo-'0';
        r[i]=(char)((hi<<4)|lo);
    }
    return r;
}
static std::string to_hex(const uint8_t *d,int n){
    static const char *h="0123456789abcdef";
    std::string r; r.resize(n*2);
    for(int i=0;i<n;i++){r[i*2]=h[d[i]>>4];r[i*2+1]=h[d[i]&0xF];}
    return r;
}
static std::string reverse_bytes(const std::string &s){
    std::string r(s.rbegin(),s.rend()); return r;
}
/* BIP341 usa SHA256 SIMPLE para sha_prevouts/amounts/scriptpubkeys/sequences
   y sha_outputs, al contrario que BIP143, que lo hace doble. */
static std::string sha256(const std::string &data){
    uint8_t h[32];
    SHA256((const uint8_t*)data.data(),data.size(),h);
    return std::string((char*)h,32);
}
static std::string sha256d(const std::string &data){
    uint8_t h1[32],h2[32];
    SHA256((const uint8_t*)data.data(),data.size(),h1);
    SHA256(h1,32,h2);
    return std::string((char*)h2,32);
}
/* Parse simple JSON string field */
/*
 * Posición del valor de una clave, buscándola SÓLO en el nivel superior.
 *
 * json_str/json_int hacían un find() de "clave": sobre toda la cadena, así que
 * encontraban la primera aparición estuviera donde estuviera. La petición de
 * firma lleva "utxos":[{...,"amount":N}] ANTES que el "amount" de nivel
 * superior — org.json conserva el orden de inserción y Kotlin pone los utxos
 * primero—, de modo que json_int(req,"amount") devolvía el valor del PRIMER
 * UTXO en lugar del importe a enviar. Cada envío intentaba mandar la entrada
 * entera, el cambio salía negativo y la transacción nunca era válida.
 *
 * De paso tolera espacios tras los dos puntos: antes "clave": con un espacio no
 * se encontraba, lo que ataba el formato exacto del emisor.
 */
static size_t json_find_key(const std::string &j,const char *key){
    const std::string k=std::string("\"")+key+"\"";
    int depth=0; bool instr=false;
    for(size_t i=0;i<j.size();i++){
        char c=j[i];
        if(instr){
            if(c=='\\'){ i++; continue; }
            if(c=='"') instr=false;
            continue;
        }
        if(c=='"'){
            if(depth==1 && j.compare(i,k.size(),k)==0){
                size_t p=i+k.size();
                while(p<j.size()&&(j[p]==' '||j[p]=='\t')) p++;
                if(p<j.size()&&j[p]==':'){
                    p++;
                    while(p<j.size()&&(j[p]==' '||j[p]=='\t')) p++;
                    return p;
                }
            }
            instr=true; continue;
        }
        if(c=='{'||c=='[') depth++;
        else if(c=='}'||c==']') depth--;
    }
    return std::string::npos;
}

static std::string json_str(const std::string &j,const char *key){
    size_t p=json_find_key(j,key);
    if(p==std::string::npos||p>=j.size()||j[p]!='"') return "";
    p++;
    std::string out;
    while(p<j.size()&&j[p]!='"'){
        if(j[p]=='\\'&&p+1<j.size()){ out+=j[p+1]; p+=2; }
        else out+=j[p++];
    }
    return out;
}
static int64_t json_int(const std::string &j,const char *key){
    size_t p=json_find_key(j,key);
    if(p==std::string::npos) return 0;
    return (int64_t)strtoll(j.c_str()+p,nullptr,10);
}

static std::string addr_to_spk(const char *addr) {
    std::string spk;
    // bech32 p2wpkh: bc1q... use existing bech32_to_h160
    if (addr[0]=='b'&&addr[1]=='c'&&addr[2]=='1'&&addr[3]=='q') {
        uint8_t h[20];
        if (bech32_to_h160(addr, h) != 1) return "";
        spk += '\x00'; spk += '\x14';
        spk += std::string((char*)h, 20);
        return spk;
    }
    /* bech32m p2tr: bc1p... -> OP_1 <32 bytes>. Antes caía en el return ""
       de abajo, así que enviar a una Taproot daba "bad_to_address". */
    if (addr[0]=='b'&&addr[1]=='c'&&addr[2]=='1'&&tolower(addr[3])=='p') {
        uint8_t x[32];
        if (!p2tr_addr_to_xonly(addr, x)) return "";
        spk += '\x51'; spk += '\x20';
        spk += std::string((char*)x, 32);
        return spk;
    }
    // base58 decode
    const char *B58A="123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    uint8_t buf[25]={0};
    for(int i=0;addr[i];i++){
        const char *p=strchr(B58A,addr[i]); if(!p) return "";
        int carry=(int)(p-B58A);
        for(int j=24;j>=0;j--){carry+=58*buf[j];buf[j]=carry&0xff;carry>>=8;}
    }
    uint8_t ver=buf[0];
    uint8_t *h=buf+1;
    if(ver==0x00||ver==0x6f) { // P2PKH mainnet/testnet
        spk+='\x76'; spk+='\xa9'; spk+='\x14';
        spk+=std::string((char*)h,20);
        spk+='\x88'; spk+='\xac';
    } else if(ver==0x05||ver==0xc4) { // P2SH mainnet/testnet
        spk+='\xa9'; spk+='\x14';
        spk+=std::string((char*)h,20);
        spk+='\x87';
    }
    return spk;
}

/* nSequence de las entradas.
 *
 * Iba a 0xFFFFFFFF, que es el valor "final" y desactiva Replace-By-Fee: si
 * mandabas con una comisión baja y la transacción se quedaba atascada, no había
 * forma de subirla, sólo esperar. Con 0xFFFFFFFD la transacción se señala como
 * reemplazable (BIP125) y además deja activo nLockTime, que 0xFFFFFFFE sí
 * permitiría pero 0xFFFFFFFF no.
 *
 * Tiene que ser el MISMO valor en el preimagen de firma y en la transacción
 * serializada; si divergen, la firma no vale.
 */
static const uint32_t TX_SEQUENCE = 0xFFFFFFFD;

static std::string build_and_sign_tx(const std::string &req){
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    std::string mnemonic=json_str(req,"mnemonic");
    std::string path=json_str(req,"path");
    std::string to_addr=json_str(req,"to");
    int64_t send_sat=json_int(req,"amount");
    int64_t fee_sat=json_int(req,"fee");
    /* nLockTime. Iba fijo a 0 en los seis sitios donde aparece —tres preimágenes
       de firma y tres serializaciones—, lo que permite reminar el último bloque
       e incluir esta transacción (fee sniping). Con la altura actual sólo vale a
       partir del siguiente. Tiene que ser IDÉNTICO en la firma y en la
       transacción; por eso es una sola variable.
       Si Kotlin no pudo consultar la altura manda 0 y se mantiene lo de antes:
       inventar un número dejaría la transacción sin validez hasta alcanzarlo. */
    int64_t lt=json_int(req,"locktime");
    uint32_t locktime=(lt>0&&lt<500000000)?(uint32_t)lt:0u;
    /* Tres formas de gastar. Antes sólo existía is_segwit —"la ruta contiene
       84'"— y un is_p2sh que no se usaba en ningún sitio, así que m/49' caía en
       la rama heredada y se firmaba como P2PKH. La firma no satisface el script
       P2SH real, así que la red rechazaba la transacción sin más explicación.

       P2SH-P2WPKH (BIP49) firma con el MISMO sighash BIP143 que P2WPKH —el
       scriptCode es el mismo 76a914<h160>88ac—; lo único que cambia es el
       scriptPubKey del cambio y que el input lleva el redeemScript en su
       scriptSig. Verificado contra el vector P2SH-P2WPKH de BIP143. */
    enum SpendType { SP_LEGACY, SP_P2SH_P2WPKH, SP_P2WPKH, SP_P2TR };
    SpendType stype = (path.find("86'")!=std::string::npos) ? SP_P2TR
                    : (path.find("84'")!=std::string::npos) ? SP_P2WPKH
                    : (path.find("49'")!=std::string::npos) ? SP_P2SH_P2WPKH
                    : SP_LEGACY;
    bool is_segwit = (stype==SP_P2SH_P2WPKH || stype==SP_P2WPKH);
    /* La clave: de la seed y la ruta, o de un WIF.
     *
     * Con una cartera WIF —las claves sueltas, los hallazgos pasados a la
     * cartera— no había forma de firmar: esto sólo sabía derivar de una seed,
     * y Kotlin paraba antes con "Unsupported address type". Así que los
     * hallazgos se podían ver pero no gastar desde la app.
     *
     * Sólo WIF comprimido (K/L): la dirección que enseña la app para un WIF
     * es la de la clave pública comprimida, P2PKH, y es la que se gasta aquí.
     * Uno sin comprimir (5...) tendría los fondos en OTRA dirección. */
    std::string wif=json_str(req,"wif");
    uint8_t seed[64]={0};
    HDKey hd; memset(&hd,0,sizeof(hd));
    if(!wif.empty()){
        if(wif[0]!='K'&&wif[0]!='L'){secp256k1_context_destroy(ctx);return "ERROR:uncompressed_wif";}
        if(!wif_decode(wif.c_str(),hd.key)||!secp256k1_ec_seckey_verify(ctx,hd.key)){
            secp256k1_context_destroy(ctx);return "ERROR:bad_wif";
        }
        stype=SP_LEGACY; is_segwit=false;
    }else{
        bip39_semilla(mnemonic.c_str(),mnemonic.size(),"",0,seed);
        derive_path(ctx,seed,path.empty()?"m/44'/0'/0'/0/0":path.c_str(),&hd);
    }
    uint8_t pub33[33]; get_pub33(ctx,hd.key,pub33);
    uint8_t h160[20]; pk_to_h160(ctx,hd.key,h160);
    /* redeemScript de BIP49: OP_0 <h160>, 22 bytes. La dirección 3... es
       base58(0x05 | hash160(redeemScript)). */
    std::string redeem;
    redeem+='\x00'; redeem+='\x14'; redeem+=std::string((char*)h160,20);
    uint8_t redeem_h160[20];
    { uint8_t sh[32]; SHA256((const uint8_t*)redeem.data(),redeem.size(),sh);
      RIPEMD160(sh,32,redeem_h160); }

    /* Taproot BIP86: la clave que va en el script es la INTERNA ajustada con
       t = tagged_hash("TapTweak", xonly(P)), sin árbol de scripts. El par de
       claves se ajusta con la misma t, así que keypair_xonly_tweak_add resuelve
       de paso la paridad —que es donde es fácil equivocarse—. */
    secp256k1_keypair tr_kp;
    uint8_t tr_xonly[32] = {0};
    bool tr_ok = false;
    if(stype==SP_P2TR){
        secp256k1_xonly_pubkey xo; int par=0;
        if(secp256k1_keypair_create(ctx,&tr_kp,hd.key) &&
           secp256k1_keypair_xonly_pub(ctx,&xo,&par,&tr_kp)){
            uint8_t internal[32];
            secp256k1_xonly_pubkey_serialize(ctx,internal,&xo);
            uint8_t tweak[32];
            tagged_hash("TapTweak",internal,32,tweak);
            if(secp256k1_keypair_xonly_tweak_add(ctx,&tr_kp,tweak) &&
               secp256k1_keypair_xonly_pub(ctx,&xo,&par,&tr_kp)){
                secp256k1_xonly_pubkey_serialize(ctx,tr_xonly,&xo);
                tr_ok = true;
            }
        }
        if(!tr_ok){secp256k1_context_destroy(ctx);return "ERROR:taproot_key";}
    }

    /* scriptPubKey de la ENTRADA que se gasta. Ojo: en la firma heredada esto
       hace también de scriptCode del input, así que no puede ser la dirección
       de cambio — por eso spk_change va aparte. */
    std::string spk_me;
    if(stype==SP_P2TR){
        spk_me+='\x51'; spk_me+='\x20'; spk_me+=std::string((char*)tr_xonly,32);
    } else if(stype==SP_P2WPKH){
        spk_me+='\x00'; spk_me+='\x14'; spk_me+=std::string((char*)h160,20);
    } else if(stype==SP_P2SH_P2WPKH){
        spk_me+='\xa9'; spk_me+='\x14';
        spk_me+=std::string((char*)redeem_h160,20); spk_me+='\x87';
    } else {
        spk_me+='\x76'; spk_me+='\xa9'; spk_me+='\x14';
        spk_me+=std::string((char*)h160,20); spk_me+='\x88'; spk_me+='\xac';
    }
    // parse UTXOs
    struct UTXO { std::string txid; uint32_t vout; int64_t amount; };
    std::vector<UTXO> utxos;
    size_t ap=json_find_key(req,"utxos");
    if(ap!=std::string::npos&&ap<req.size()&&req[ap]=='['){
        ap+=1;
        while(ap<req.size()&&req[ap]!=']'){
            size_t ob=req.find('{',ap); if(ob==std::string::npos)break;
            size_t cb=req.find('}',ob); if(cb==std::string::npos)break;
            std::string u=req.substr(ob,cb-ob+1);
            UTXO ut; ut.txid=json_str(u,"txid"); ut.vout=(uint32_t)json_int(u,"vout"); ut.amount=json_int(u,"amount");
            utxos.push_back(ut); ap=cb+1;
        }
    }
    if(utxos.empty()||to_addr.empty()||send_sat<=0){secp256k1_context_destroy(ctx);return "ERROR:invalid_params";}
    std::string to_spk=addr_to_spk(to_addr.c_str());
    if(to_spk.empty()){secp256k1_context_destroy(ctx);return "ERROR:bad_to_address";}
    int64_t total_in=0; for(auto &u:utxos)total_in+=u.amount;
    int64_t change=total_in-send_sat-fee_sat;
    /* Sin esto, si las entradas no cubren importe+comisión, has_change quedaba
       en false y se firmaba una transacción que gasta más de lo que entra: la
       red la rechaza y no hay forma de saber por qué. Kotlin ya lo comprueba,
       pero esto firma dinero y no debe fiarse de quien le llame. */
    if(change<0){secp256k1_context_destroy(ctx);return "ERROR:insufficient_funds";}
    bool has_change=(change>546);

    /* Dirección de cambio.
     *
     * El cambio volvía a la MISMA dirección de la que se gastaba. Eso deja en la
     * cadena la constancia de que esas monedas siguen siendo tuyas y va
     * encadenando tu historial: cualquiera que mire una transacción sabe cuál de
     * las dos salidas es el cambio. Las carteras usan la rama .../1/k para esto.
     *
     * Si la petición trae "change_path" se deriva esa clave y el cambio va allí.
     * Sin ella se mantiene el comportamiento anterior, para no romper a quien
     * llame sin el campo. */
    std::string spk_change = spk_me;
    /* Con WIF no hay seed de la que derivar el cambio: vuelve a la misma
       dirección. */
    std::string change_path = wif.empty() ? json_str(req,"change_path") : std::string();
    if(has_change && !change_path.empty()){
        HDKey ch; derive_path(ctx,seed,change_path.c_str(),&ch);
        if(stype==SP_P2TR){
            secp256k1_keypair kp; secp256k1_xonly_pubkey xo; int par=0;
            uint8_t internal[32], tw[32];
            if(secp256k1_keypair_create(ctx,&kp,ch.key) &&
               secp256k1_keypair_xonly_pub(ctx,&xo,&par,&kp)){
                secp256k1_xonly_pubkey_serialize(ctx,internal,&xo);
                if(taproot_tweak_pubkey(ctx,internal,tw)){
                    spk_change.clear();
                    spk_change+='\x51'; spk_change+='\x20';
                    spk_change+=std::string((char*)tw,32);
                }
            }
        } else {
            uint8_t ch160[20]; pk_to_h160(ctx,ch.key,ch160);
            spk_change.clear();
            if(stype==SP_P2WPKH){
                spk_change+='\x00'; spk_change+='\x14';
                spk_change+=std::string((char*)ch160,20);
            } else if(stype==SP_P2SH_P2WPKH){
                std::string rd; rd+='\x00'; rd+='\x14'; rd+=std::string((char*)ch160,20);
                uint8_t sh[32], rh[20];
                SHA256((const uint8_t*)rd.data(),rd.size(),sh); RIPEMD160(sh,32,rh);
                spk_change+='\xa9'; spk_change+='\x14';
                spk_change+=std::string((char*)rh,20); spk_change+='\x87';
            } else {
                spk_change+='\x76'; spk_change+='\xa9'; spk_change+='\x14';
                spk_change+=std::string((char*)ch160,20);
                spk_change+='\x88'; spk_change+='\xac';
            }
        }
        /* Si algo falló arriba, spk_change sigue siendo spk_me: se pierde
           privacidad pero el dinero vuelve a una dirección propia. */
    }

    // Build outputs bytes (shared for sighash)
    std::string outs_bytes;
    outs_bytes+=uint64_le(send_sat); outs_bytes+=varint(to_spk.size()); outs_bytes+=to_spk;
    if(has_change){outs_bytes+=uint64_le(change);outs_bytes+=varint(spk_change.size());outs_bytes+=spk_change;}
    std::vector<std::string> sigs;
    if(stype==SP_P2TR){
        /* BIP341, gasto por clave con SIGHASH_DEFAULT (0x00) y sin annex.
           Todas las entradas son de la misma dirección, así que comparten
           scriptPubKey. Verificado contra los vectores de BIP341: los cinco
           hashes intermedios y los siete sigHash de keyPathSpending. */
        std::string prevouts, amounts, spks, seqs;
        for(auto &u:utxos){
            prevouts+=reverse_bytes(hex_decode(u.txid));
            prevouts+=uint32_le(u.vout);
            amounts+=uint64_le(u.amount);
            spks+=varint(spk_me.size()); spks+=spk_me;
            seqs+=uint32_le(TX_SEQUENCE);
        }
        std::string sha_prevouts=sha256(prevouts), sha_amounts=sha256(amounts);
        std::string sha_spks=sha256(spks), sha_seqs=sha256(seqs);
        std::string sha_outs=sha256(outs_bytes);

        for(size_t ii=0;ii<utxos.size();ii++){
            std::string m;
            m+='\x00';                 /* epoch */
            m+='\x00';                 /* hash_type = SIGHASH_DEFAULT */
            m+=uint32_le(1);           /* nVersion */
            m+=uint32_le(locktime);           /* nLockTime */
            m+=sha_prevouts; m+=sha_amounts; m+=sha_spks; m+=sha_seqs;
            m+=sha_outs;
            m+='\x00';                 /* spend_type: clave, sin annex */
            m+=uint32_le((uint32_t)ii);/* input_index */

            uint8_t sighash[32];
            tagged_hash("TapSighash",(const uint8_t*)m.data(),m.size(),sighash);

            uint8_t sig64[64];
            if(!secp256k1_schnorrsig_sign32(ctx,sig64,sighash,&tr_kp,nullptr)){
                secp256k1_context_destroy(ctx);return "ERROR:schnorr_sign";
            }
            /* Con SIGHASH_DEFAULT la firma son 64 bytes pelados: NO se le añade
               el byte de tipo, al revés que en ECDSA. */
            sigs.push_back(std::string((char*)sig64,64));
        }

        std::string tx;
        tx+=uint32_le(1);
        tx+='\x00'; tx+='\x01';        /* marker + flag */
        tx+=varint(utxos.size());
        for(auto &u:utxos){
            tx+=reverse_bytes(hex_decode(u.txid));
            tx+=uint32_le(u.vout);
            tx+='\x00';                /* scriptSig vacío */
            tx+=uint32_le(TX_SEQUENCE);
        }
        tx+=varint(has_change?2:1);
        tx+=outs_bytes;
        for(size_t i=0;i<utxos.size();i++){
            tx+='\x01';                /* un solo elemento de testigo */
            tx+=varint(sigs[i].size()); tx+=sigs[i];
        }
        tx+=uint32_le(locktime);
        secp256k1_context_destroy(ctx);
        return to_hex((const uint8_t*)tx.data(),(int)tx.size());
    }
    if(is_segwit){
        // BIP143
        // hashPrevouts
        std::string all_prevouts;
        for(auto &u:utxos){all_prevouts+=reverse_bytes(hex_decode(u.txid));all_prevouts+=uint32_le(u.vout);}
        std::string hPrevouts=sha256d(all_prevouts);
        // hashSequence
        std::string all_seq;
        for(size_t i=0;i<utxos.size();i++) all_seq+=uint32_le(TX_SEQUENCE);
        std::string hSequence=sha256d(all_seq);
        // hashOutputs
        std::string hOutputs=sha256d(outs_bytes);
        for(size_t ii=0;ii<utxos.size();ii++){
            // scriptCode for P2WPKH
            std::string scriptCode;
            scriptCode+='\x76'; scriptCode+='\xa9'; scriptCode+='\x14';
            scriptCode+=std::string((char*)h160,20);
            scriptCode+='\x88'; scriptCode+='\xac';
            std::string pre;
            pre+=uint32_le(1); // version
            pre+=hPrevouts;
            pre+=hSequence;
            pre+=reverse_bytes(hex_decode(utxos[ii].txid));
            pre+=uint32_le(utxos[ii].vout);
            pre+=varint(scriptCode.size()); pre+=scriptCode;
            pre+=uint64_le(utxos[ii].amount);
            pre+=uint32_le(TX_SEQUENCE);
            pre+=hOutputs;
            pre+=uint32_le(locktime); // locktime
            pre+=uint32_le(1); // SIGHASH_ALL
            std::string hash=sha256d(pre);
            secp256k1_ecdsa_signature sig;
            secp256k1_ecdsa_sign(ctx,&sig,(const uint8_t*)hash.data(),hd.key,nullptr,nullptr);
            secp256k1_ecdsa_signature_normalize(ctx,&sig,&sig);
            uint8_t der[72]; size_t dlen=72;
            secp256k1_ecdsa_signature_serialize_der(ctx,der,&dlen,&sig);
            std::string sigder; sigder+=std::string((char*)der,dlen); sigder+='\x01';
            sigs.push_back(sigder);
        }
        // Segwit tx: version + marker + flag + inputs + outputs + witness + locktime
        std::string tx;
        tx+=uint32_le(1);
        tx+='\x00'; tx+='\x01'; // marker + flag
        tx+=varint(utxos.size());
        /* P2WPKH nativo va con scriptSig vacío. P2SH-P2WPKH lleva ahí un único
           empujón del redeemScript (0x16 seguido de sus 22 bytes): eso es lo que
           satisface el script P2SH y hace que el nodo trate el input como
           SegWit. Sin esto la transacción no es gastable. */
        std::string ssig;
        if(stype==SP_P2SH_P2WPKH){ ssig+=(char)redeem.size(); ssig+=redeem; }
        for(auto &u:utxos){
            tx+=reverse_bytes(hex_decode(u.txid));
            tx+=uint32_le(u.vout);
            tx+=varint(ssig.size()); tx+=ssig;
            tx+=uint32_le(TX_SEQUENCE);
        }
        tx+=varint(has_change?2:1);
        tx+=outs_bytes;
        // witness for each input
        for(size_t i=0;i<utxos.size();i++){
            tx+='\x02'; // 2 witness items
            tx+=varint(sigs[i].size()); tx+=sigs[i];
            tx+=(char)33; tx+=std::string((char*)pub33,33);
        }
        tx+=uint32_le(locktime);
        secp256k1_context_destroy(ctx);
        return to_hex((const uint8_t*)tx.data(),(int)tx.size());
    } else {
        // Legacy P2PKH
        for(size_t ii=0;ii<utxos.size();ii++){
            std::string pre;
            pre+=uint32_le(1);
            pre+=varint(utxos.size());
            for(size_t j=0;j<utxos.size();j++){
                pre+=reverse_bytes(hex_decode(utxos[j].txid));
                pre+=uint32_le(utxos[j].vout);
                if(j==ii){pre+=varint(spk_me.size());pre+=spk_me;}
                else{pre+='\x00';}
                pre+=uint32_le(TX_SEQUENCE);
            }
            pre+=varint(has_change?2:1);
            pre+=outs_bytes;
            pre+=uint32_le(locktime);
            pre+=uint32_le(1);
            std::string hash=sha256d(pre);
            secp256k1_ecdsa_signature sig;
            secp256k1_ecdsa_sign(ctx,&sig,(const uint8_t*)hash.data(),hd.key,nullptr,nullptr);
            secp256k1_ecdsa_signature_normalize(ctx,&sig,&sig);
            uint8_t der[72]; size_t dlen=72;
            secp256k1_ecdsa_signature_serialize_der(ctx,der,&dlen,&sig);
            std::string sigscript;
            sigscript+=(char)(dlen+1); sigscript+=std::string((char*)der,dlen); sigscript+='\x01';
            sigscript+=(char)33; sigscript+=std::string((char*)pub33,33);
            sigs.push_back(sigscript);
        }
        std::string tx;
        tx+=uint32_le(1);
        tx+=varint(utxos.size());
        for(size_t i=0;i<utxos.size();i++){
            tx+=reverse_bytes(hex_decode(utxos[i].txid));
            tx+=uint32_le(utxos[i].vout);
            tx+=varint(sigs[i].size()); tx+=sigs[i];
            tx+=uint32_le(TX_SEQUENCE);
        }
        tx+=varint(has_change?2:1);
        tx+=outs_bytes;
        tx+=uint32_le(locktime);
        secp256k1_context_destroy(ctx);
        return to_hex((const uint8_t*)tx.data(),(int)tx.size());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_buildAndSignTx(JNIEnv *env,jobject,jstring jreq){
    const char *req=env->GetStringUTFChars(jreq,nullptr);
    std::string result=build_and_sign_tx(std::string(req));
    env->ReleaseStringUTFChars(jreq,req);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_getLastKey(JNIEnv *env,jobject){
    std::lock_guard<std::mutex> lk(g_last_key_mutex);
    char hex[65];
    for(int i=0;i<32;i++) sprintf(hex+i*2,"%02x",g_last_key[i]);
    hex[64]=0;
    return env->NewStringUTF(hex);
}

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_setBigCores(JNIEnv *env, jobject, jintArray cores, jboolean enable){
    g_use_affinity = enable;
    if (!enable) return;
    jint *c = env->GetIntArrayElements(cores, nullptr);
    int n = env->GetArrayLength(cores);
    g_n_big_cores = 0;
    for(int i=0;i<n&&i<8;i++){
        g_big_cores[i]=c[i];
        if(c[i]>=0) g_n_big_cores++;
    }
    env->ReleaseIntArrayElements(cores, c, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_setBatchSize(JNIEnv *env, jobject, jint size){
    g_batch_size.store(size);
}

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_setSequential(JNIEnv *env, jobject, jboolean seq){
    g_sequential.store(seq ? 1 : 0);
    if(seq) {
        std::lock_guard<std::mutex> lk(g_seq_mutex);
        memcpy(g_seq_pos, g_range_start, 32);
    }
}



/* ¿Se paro el motor porque el secuencial recorrio el rango entero? */
JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_rangoCompleto(JNIEnv *, jobject){
    return (jboolean)(g_rango_completo.load()!=0);
}

JNIEXPORT jint JNICALL
Java_com_hunter_btc_HunterEngine_getBatchSize(JNIEnv *env, jobject){
    return g_batch_size.load();
}


/* ===================== KANGAROO ===================== */
/*
 * Verificado en tools/ec-harness/kang.cpp: resuelve logaritmos discretos de los
 * que ya se sabe la respuesta, en intervalos de 20 a 36 bits, con una tanda de
 * 30 claves al azar y con cuatro hilos sobre la misma tabla.
 */
static KangarooCtx g_kg;
static bool        g_kg_vivo=false;
/* Lo que hace falta para volver a guardar sin que Kotlin lo repita. */
static char        g_kg_ruta[1024]={0};
static uint8_t     g_kg_pub[33], g_kg_ini[32], g_kg_fin[32];
static int         g_kg_dbits=0;
/* Operaciones de sesiones anteriores, para que el contador no se reinicie. */
static uint64_t    g_kg_ops_previas=0;
static std::vector<pthread_t> g_kg_hilos;
static std::mutex  g_kg_mtx;

struct KgArg { int n_kang; uint64_t semilla; };
static std::vector<KgArg> g_kg_args;

static void *kg_thread(void *p){
    KgArg *a=(KgArg*)p;
    kg_run(&g_kg,a->n_kang,a->semilla);
    return NULL;
}

/* ---------- La GPU como un trabajador mas ----------
 *
 * Si el usuario la activa, un hilo mas prepara la GPU (Vulkan) y la hace
 * saltar sobre la MISMA tabla que los hilos de CPU: ver gpu/gpu_kangaroo.h.
 * Se prepara dentro del hilo porque soltar los 65.536 canguros de la GPU son
 * otras tantas multiplicaciones escalares, un par de segundos en un movil, y
 * kangarooStart no debe tardar eso. */
static std::atomic<int> g_usar_gpu(0);
static pthread_t   g_gpu_hilo;
static bool        g_gpu_hilo_vivo=false;
static std::mutex  g_gpu_estado_mtx;
static std::string g_gpu_estado="";
static std::atomic<long long> g_gpu_saltos(0);

static void gpu_estado(const std::string &e){
    std::lock_guard<std::mutex> lk(g_gpu_estado_mtx); g_gpu_estado=e;
}
static void *gpu_thread(void *){
    g_gpu_saltos.store(0);
    gpu_estado("starting");
    std::string err;
    GpuKg *g=gpu_kg_crear(&g_kg,KANGAROO_SPV,sizeof(KANGAROO_SPV),1024,64,kg_semilla(0x6770),err);
    if(!g){ gpu_estado(err=="stopped before starting" ? "stopped (the search ended before the GPU started)"
                                                         : "error: "+err); return NULL; }
    gpu_estado("running on "+g->nombre);
    /* Tandas de ~60 ms: Android corta el trabajo de GPU que tarda segundos, y
       asi parar responde enseguida. El freno de CPU del usuario vale tambien
       para la GPU (duerme en proporcion). */
    uint32_t pasos=4;
    while(!g_kg.parar.load() && !g_kg.encontrado.load()){
        double seg=gpu_kg_tanda(g,&g_kg,pasos);
        g_gpu_saltos.store(g->saltos);
        if(seg<0.03 && pasos<4096) pasos*=2;
        else if(seg>0.12 && pasos>1) pasos/=2;
        int cpu=g_kg.cpu_limite.load();
        if(cpu>0 && cpu<100){
            double dormir=seg*(100.0-cpu)/cpu;
            if(dormir>0.0005) std::this_thread::sleep_for(std::chrono::microseconds((long long)(dormir*1e6)));
        }
    }
    gpu_kg_destruir(g);
    gpu_estado("stopped");
    return NULL;
}

static int hex2bin(const char *h,uint8_t *out,int max){
    int n=0;
    while(h[0]&&h[1]&&n<max){
        auto v=[](char c)->int{
            if(c>='0'&&c<='9')return c-'0';
            if(c>='a'&&c<='f')return c-'a'+10;
            if(c>='A'&&c<='F')return c-'A'+10;
            return -1; };
        int hi=v(h[0]),lo=v(h[1]);
        if(hi<0||lo<0) return -1;
        out[n++]=(uint8_t)((hi<<4)|lo); h+=2;
    }
    return (*h)?-1:n;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_kangarooStart(
        JNIEnv *env, jobject, jstring jpub, jstring jini, jstring jfin,
        jint hilos, jint por_hilo, jstring jruta, jint tope_tabla_bits){
    std::lock_guard<std::mutex> lk(g_kg_mtx);
    if(g_kg_vivo) return JNI_FALSE;

    const char *cp=env->GetStringUTFChars(jpub,0);
    const char *ci=env->GetStringUTFChars(jini,0);
    const char *cf=env->GetStringUTFChars(jfin,0);
    uint8_t pub[33],ini[32],fin[32];
    memset(ini,0,32); memset(fin,0,32);
    int okp=(hex2bin(cp,pub,33)==33);
    bool oki=kg_hex_a_be32(ci,ini)!=0, okf=kg_hex_a_be32(cf,fin)!=0;
    env->ReleaseStringUTFChars(jpub,cp);
    env->ReleaseStringUTFChars(jini,ci);
    env->ReleaseStringUTFChars(jfin,cf);
    if(!okp||!oki||!okf) return JNI_FALSE;

    /* Tamanos: los distinguidos se eligen para que la tabla no se llene. Con
       2^d puntos por distinguido y ~2^(bits/2+1) operaciones totales, guardar
       del orden de 2^(bits/2+1-d) entradas. d = bits/4 + 4 deja la tabla en
       unos pocos MB hasta bits=80. */
    int bits=0;
    for(int i=0;i<32;i++){
        int idx=i; uint8_t v=fin[idx];
        if(v){ bits=(31-idx)*8; for(int b=7;b>=0;b--) if(v&(1<<b)){ bits+=b+1; break; } break; }
    }
    if(bits<8) return JNI_FALSE;
    /* Lo que cuenta para el coste es el ANCHO del rango, no lo alto que este:
       un trozo de 2^47 claves del #50, o uno pequeno del #135, se trataban
       como el rango entero (50 o 135 bits), con un umbral de distinguidos y
       una tabla de ese tamano. En un puzzle el ancho es 2^(N-1): con el +1 sale
       N, lo mismo que antes, asi que los puzzles enteros no cambian. */
    {
        sc_t a,b,w; sc_from_be32(a,ini); sc_from_be32(b,fin);
        if(!sc_sub(w,b,a)) return JNI_FALSE;          /* From > To */
        int bw=sc_bits(w)+1;
        if(bw<bits) bits=bw<8?8:bw;
    }
    /* Cada cuantas operaciones se apunta un punto distinguido.
     *
     * bits/4+4 es la proporcion sensata, pero con rangos enormes se dispara: en
     * el puzzle #140 (139 bits) daba 38, o sea un punto cada 2^38 operaciones —
     * a 6 M/s, uno cada DOCE HORAS Y MEDIA. Durante ese rato el contador de
     * puntos se queda en cero y el fichero de guardado esta vacio, asi que
     * cerrar la app tiraba todo el trabajo de la sesion.
     *
     * Con el tope en 28 sale uno cada 45 segundos y la tabla de 2^20 tarda
     * un ano y medio en llenarse. Bajar el umbral no hace la busqueda peor:
     * guardar mas puntos solo mejora la deteccion de colisiones, lo unico que
     * cuesta es memoria. */
    int dbits=bits/4+4;
    /* Y con muchos canguros, bits/4+4 es DEMASIADO en rangos medianos.
     *
     * Cada canguro lleva siempre un tramo sin apuntar: el que ha andado desde
     * su ultimo distinguido, de media 2^dbits saltos. Con N canguros eso son
     * unas N*2^dbits operaciones que la tabla todavia no ve, y la colision no
     * se detecta hasta que alguno llega a su siguiente distinguido. Con los
     * 7 hilos x 2048 canguros de la app (N ~ 2^14) y el #61 (dbits 19):
     * 2^14 * 2^19 = 2^33 operaciones "en vuelo", SIETE veces raiz(W). Se vio:
     * el #60 costo 2,5 raices de W, y el #61 iba por 1,8 veces lo esperado
     * sin encontrarla. El banco de pruebas medía 1,4 porque usa pocos canguros.
     *
     * Se limita para que N*2^dbits no pase de raiz(W)/16 con N = 2^14. N es
     * fijo y no el de este aparato a proposito: en un cluster el maestro y
     * los trabajadores tienen que usar el mismo dbits (la cabecera de cada
     * bloque lo comprueba) aunque cada uno lance distintos canguros. Solo
     * cambia algo por debajo de ~88 bits; los puzzles grandes (#135...) siguen
     * con el tope de 28. */
    { int lim=bits/2-4-14; if(dbits>lim) dbits=lim; }
    if(dbits<6) dbits=6; if(dbits>28) dbits=28;
    /* Se esperan del orden de 2*raiz(W)/2^dbits distinguidos hasta dar con la
       clave. La tabla se dimensiona con holgura por encima de eso: si se llena,
       se dejan de guardar y la busqueda se degrada sin avisar. Antes salia
       apenas 1,4 veces lo esperado, que es demasiado justo. */
    /* Cuantos huecos tiene la tabla.
     *
     * El techo estaba fijo en 2^20 —un millon de puntos, 59 MB— y ese es el
     * limite de verdad de una busqueda larga, mucho antes que ningun otro: al
     * 90 % lleno dp_insert deja de guardar, y a partir de ahi la busqueda sigue
     * corriendo, gastando bateria y ensenando sus megasaltos por segundo, pero
     * ya no acumula nada nuevo. O sea que deja de avanzar sin avisar.
     *
     * Y es peor cuantos mas aparatos haya, que es lo contrario de lo que uno
     * espera: en el puzzle #140 se llena en 229 dias con un movil, 93 con dos y
     * 20 con diez, porque todos meten en la misma tabla.
     *
     * Ahora el techo lo pone quien llama, sacado de la RAM del aparato (ver
     * HunterEngine.topeTablaBits). En un movil de 8 GB son 2^22, que cuadruplica
     * el margen. Se sigue cogiendo el MENOR entre eso y lo que pida el rango:
     * para un puzzle de 40 bits reservar 235 MB seria tirar memoria. */
    if(tope_tabla_bits<14) tope_tabla_bits=14;
    if(tope_tabla_bits>24) tope_tabla_bits=24;
    /* Huecos para 4*raiz(W)/2^dbits puntos: al 90 % de lleno aguanta una
       busqueda de ~3,6 raices de W, y la media son 1,4. Con +4 (16 veces)
       el #50 reservaba 3,7 millones de huecos —unos 200 MB— para los ~250.000
       puntos que necesita. */
    int tbits=bits/2+2-dbits;
    if(tbits<14) tbits=14;
    if(tbits>tope_tabla_bits){
        /* La tabla no da para tantos puntos: subir dbits hasta que quepan,
           antes que dejar que se llene. */
        tbits=tope_tabla_bits;
        int minimo=bits/2+2-tope_tabla_bits;
        if(dbits<minimo) dbits=minimo>28?28:minimo;
    }

    if(!kg_setup(&g_kg,pub,ini,fin,dbits,tbits)) return JNI_FALSE;

    /* Recuperar el trabajo de sesiones anteriores, si lo hay y es del mismo
       puzzle. La cabecera del fichero lo comprueba. */
    memset(g_kg_ruta,0,sizeof(g_kg_ruta));
    if(jruta){
        const char *cr=env->GetStringUTFChars(jruta,0);
        if(cr){ strncpy(g_kg_ruta,cr,sizeof(g_kg_ruta)-1);
                env->ReleaseStringUTFChars(jruta,cr); }
    }
    memcpy(g_kg_pub,pub,33); memcpy(g_kg_ini,ini,32); memcpy(g_kg_fin,fin,32);
    g_kg_dbits=dbits; g_kg_ops_previas=0;
    if(g_kg_ruta[0]){
        uint64_t ops_ant=0;
        uint64_t n=dp_load(&g_kg.tabla,g_kg_ruta,pub,ini,fin,dbits,&ops_ant);
        if(n) g_kg_ops_previas=ops_ant;
    }

    /* La potencia y el tamano de lote los elige el usuario y hasta ahora
       Kangaroo los ignoraba: los hilos iban fijos y el lote a 512. */
    g_kg.cpu_limite.store(g_cpu_limit.load());
    /* CERO hilos es valido y significa "recolector": la tabla queda viva y
     * kangarooImport() mete en ella los puntos que lleguen de otros aparatos,
     * pero aqui no camina ningun canguro y no se gasta CPU ni bateria.
     *
     * Hace falta para que el maestro de un cluster pueda coordinar sin buscar.
     * La alternativa —que el maestro no tenga tabla— parece lo mismo y no lo
     * es: sin tabla donde juntarlos, kangarooImport() rechaza los puntos, cada
     * movil se queda buscando por su cuenta y el cluster pierde justo lo que lo
     * hacia valer. N aparatos con la tabla compartida van N veces mas rapido;
     * sin compartirla, raiz(N). Con dos moviles eso es 2x contra 1,41x.
     *
     * La colision se detecta igual, porque kg_import() llama a kg_resolver()
     * con cada punto que entra: el maestro puede encontrar la clave sin haber
     * dado un solo salto, juntando las dos mitades de dos moviles distintos.
     * Eso no es teoria: es lo que comprueban las pruebas 5 y 6 de
     * tools/ec-harness/reparte.cpp, "un master que solo escucha". */
    if(hilos<0) hilos=0; if(hilos>16) hilos=16;
    if(por_hilo<16) por_hilo=16; if(por_hilo>4096) por_hilo=4096;
    g_kg_args.assign(hilos,KgArg{});
    g_kg_hilos.assign(hilos,pthread_t{});
    for(int i=0;i<hilos;i++){
        g_kg_args[i].n_kang=por_hilo;
        /* Entropia de verdad por hilo. Con la version anterior —el numero de
           hilo revuelto con time(NULL), que va en segundos— dos moviles que
           arrancaran en el mismo segundo soltaban sus canguros en los mismos
           sitios y duplicaban todo su trabajo sin que nada lo dijera. Ver
           kg_semilla() en kangaroo.h. */
        g_kg_args[i].semilla=kg_semilla((uint64_t)(i+1));
        pthread_create(&g_kg_hilos[i],NULL,kg_thread,&g_kg_args[i]);
    }
    /* La GPU, si esta activada y el telefono busca (con 0 hilos es solo
       recolector de un cluster y no busca). Solo con el motor de negacion,
       que es el que hay en la GPU. */
    g_gpu_hilo_vivo=false;
    /* ¿Le da tiempo a la GPU a aportar algo? Sus 65.536 canguros tienen que
     * andar unos 2^dbits saltos cada uno antes de dar su primer distinguido.
     * Si eso pasa de una vigesima del trabajo esperado (1,4 raices de W), la
     * clave casi siempre aparece antes de que la GPU cuente, y mientras tanto
     * le quita potencia a la CPU (comparten limite de consumo y temperatura).
     * Medido en el A56 con el #60: 14,96 M/s con GPU frente a 16,93 sin ella.
     * En esos rangos no se usa. */
    bool gpu_sirve=true;
    if(g_usar_gpu.load() && hilos>0 && g_kg.negacion){
        double arranque=65536.0*ldexp(1.0,g_kg.dbits);
        double trabajo=1.4*sqrt(ldexp(1.0,g_kg.bits>0?g_kg.bits:1));
        if(arranque>trabajo/20.0) gpu_sirve=false;
    }
    if(g_usar_gpu.load() && hilos>0 && g_kg.negacion && !gpu_sirve){
        gpu_estado("not used: range too small for the GPU to catch up (it only helps from about #100 on)");
    }else
    if(g_usar_gpu.load() && hilos>0 && g_kg.negacion){
        if(pthread_create(&g_gpu_hilo,NULL,gpu_thread,NULL)==0) g_gpu_hilo_vivo=true;
    }else gpu_estado(g_usar_gpu.load()?"not used (collector mode)":"off");
    g_kg_vivo=true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_kangarooStop(JNIEnv *, jobject){
    std::lock_guard<std::mutex> lk(g_kg_mtx);
    if(!g_kg_vivo) return;
    g_kg.parar.store(1);
    for(auto &t:g_kg_hilos) pthread_join(t,NULL);
    g_kg_hilos.clear();
    if(g_gpu_hilo_vivo){ pthread_join(g_gpu_hilo,NULL); g_gpu_hilo_vivo=false; }
    /* Guardar ANTES de liberar: si no, parar tiraba a la basura todo el trabajo
       de la sesion y la siguiente empezaba de cero. */
    if(g_kg_ruta[0])
        dp_save(&g_kg.tabla,g_kg_ruta,g_kg_pub,g_kg_ini,g_kg_fin,g_kg_dbits,
                g_kg_ops_previas+(uint64_t)g_kg.saltos.load());
    kg_free(&g_kg);
    g_kg_vivo=false;
}

/* Kangaroo en la GPU, medido: saltos por segundo del bucle de verdad sobre
 * un rango como el del #140, con varios repartos (invocaciones x canguros por
 * invocacion). Con el motor parado. Sirve para saber donde se pierde: la
 * prueba de multiplicaciones promete ~10 veces mas de lo que dio en el A56. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_benchGpuKangaroo(JNIEnv *env, jobject){
    std::ostringstream o;
    uint8_t pub[33],ini[32],fin[32];
    sc_t k; sc_set_u64(k,987654321ULL);
    JP P; kg_scalar_mul(&P,k,FIELD_GX,FIELD_GY); fe_t x,y; kg_normalize(&P,x,y);
    pub[0]=(y[0]&1)?3:2;
    for(int w=0;w<4;w++) for(int bb=0;bb<8;bb++) pub[1+(3-w)*8+(7-bb)]=(uint8_t)(x[w]>>(bb*8));
    memset(ini,0,32); ini[31-139/8]=(uint8_t)(1u<<(139%8));
    memset(fin,0,32); for(int q2=0;q2<=139;q2++) fin[31-q2/8]|=(uint8_t)(1u<<(q2%8));
    int neg_antes=kg_negacion; kg_negacion=1;
    struct Rep{ uint32_t inv, kpi; } reps[]={{1024,16},{2048,16},{4096,16},{2048,32},{4096,32},{1024,64},{2048,64}};
    std::string nombre;
    for(auto r:reps){
        KangarooCtx *kc=new KangarooCtx();
        if(!kg_setup(kc,pub,ini,fin,28,18)){ delete kc; continue; }
        std::string err;
        auto tc=std::chrono::steady_clock::now();
        GpuKg *g=gpu_kg_crear(kc,KANGAROO_SPV,sizeof(KANGAROO_SPV),r.inv,r.kpi,0x5EED,err);
        double crear=std::chrono::duration<double>(std::chrono::steady_clock::now()-tc).count();
        if(!g){ o<<"GPU: "<<err<<"\n"; kg_free(kc); delete kc; break; }
        uint32_t pasos=4;
        for(int w=0;w<6;w++){ double seg=gpu_kg_tanda(g,kc,pasos); if(seg<0.03&&pasos<4096) pasos*=2; }
        long long s0=g->saltos; auto t0=std::chrono::steady_clock::now(); double gpu_seg=0;
        while(std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count()<2.0){
            double seg=gpu_kg_tanda(g,kc,pasos); gpu_seg+=seg;
            if(seg<0.03&&pasos<4096) pasos*=2; else if(seg>0.12&&pasos>1) pasos/=2;
        }
        double tot=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
        long long n=g->saltos-s0;
        o<<r.inv<<" x "<<r.kpi<<": "<<std::fixed<<std::setprecision(2)<<n/tot/1e6<<" M jumps/s"
         <<"  (busy "<<(int)(100*gpu_seg/tot)<<"%, "<<pasos<<" steps, start "<<std::setprecision(1)<<crear<<" s)\n";
        nombre=g->nombre;
        gpu_kg_destruir(g); kg_free(kc); delete kc;
    }
    kg_negacion=neg_antes;
    std::string texto=nombre.empty()?o.str():nombre+"\n"+o.str();
    return env->NewStringUTF(texto.c_str());
}

/* Usar o no la GPU en la proxima busqueda de Kangaroo. */
extern "C" JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_setUsarGpu(JNIEnv *, jobject, jboolean v){ g_usar_gpu.store(v?1:0); }

/* Saltos hechos por la GPU en esta busqueda (ya incluidos en el total). */
extern "C" JNIEXPORT jlong JNICALL
Java_com_hunter_btc_HunterEngine_gpuSaltos(JNIEnv *, jobject){ return (jlong)g_gpu_saltos.load(); }

/* Que esta haciendo la GPU: "off", "starting", "running on X", "error: ..." */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_gpuEstado(JNIEnv *env, jobject){
    std::lock_guard<std::mutex> lk(g_gpu_estado_mtx);
    return env->NewStringUTF(g_gpu_estado.c_str());
}

/* Guardado periodico. Android puede matar la app sin avisar, y entonces no hay
 * ocasion de guardar al parar: quien llame a esto cada pocos minutos limita lo
 * que se puede perder a esos pocos minutos. */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_kangarooSave(JNIEnv *, jobject){
    std::lock_guard<std::mutex> lk(g_kg_mtx);
    if(!g_kg_vivo || !g_kg_ruta[0]) return JNI_FALSE;
    return dp_save(&g_kg.tabla,g_kg_ruta,g_kg_pub,g_kg_ini,g_kg_fin,g_kg_dbits,
                   g_kg_ops_previas+(uint64_t)g_kg.saltos.load()) ? JNI_TRUE : JNI_FALSE;
}

/* Distinguidos en la tabla: es la medida real del trabajo acumulado, y lo que
 * sobrevive a un reinicio. */
/* Cambiar la potencia con la busqueda en marcha, sin reiniciarla. */
extern "C" JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_kangarooSetCpu(JNIEnv *, jobject, jint pct){
    if(pct<1) pct=1; if(pct>100) pct=100;
    if(g_kg_vivo) g_kg.cpu_limite.store(pct);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hunter_btc_HunterEngine_kangarooPoints(JNIEnv *, jobject){
    return g_kg_vivo ? (jlong)g_kg.tabla.guardados : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hunter_btc_HunterEngine_kangarooOps(JNIEnv *, jobject){
    /* Incluye lo de sesiones anteriores: el contador mide el trabajo total
       hecho contra este puzzle, no el de este arranque. */
    return g_kg_vivo ? (jlong)(g_kg_ops_previas+(uint64_t)g_kg.saltos.load()) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_kangarooRunning(JNIEnv *, jobject){
    return (g_kg_vivo && !g_kg.encontrado.load()) ? JNI_TRUE : JNI_FALSE;
}

/* ---------- Generador vanity ---------- */
extern "C" JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_vanityStart(JNIEnv *env, jobject, jstring pref, jint hilos, jint mode){
    const char *p=env->GetStringUTFChars(pref,nullptr);
    vanity_arrancar(p?p:"", (int)hilos, (int)mode);
    if(p) env->ReleaseStringUTFChars(pref,p);
}
extern "C" JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_vanityStop(JNIEnv *, jobject){ vanity_parar(); }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_vanityRunning(JNIEnv *, jobject){
    return (g_van_run.load() || g_van_vivos.load()>0) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_hunter_btc_HunterEngine_vanityCount(JNIEnv *, jobject){
    return (jlong)g_van_count.load();
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_vanityResult(JNIEnv *env, jobject){
    std::string r;
    if(g_van_found.load()){
        std::lock_guard<std::mutex> lk(g_van_mx);
        if(!g_van_priv.empty()) r=g_van_priv+"|"+g_van_addr;
    }
    return env->NewStringUTF(r.c_str());
}

/* ---------- BSGS (rango pequeno conocido) ---------- */
static std::atomic<bool>     g_bsgs_run{false};
static std::atomic<bool>     g_bsgs_done{false};
static std::atomic<uint64_t> g_bsgs_count{0};
static std::atomic<int>      g_bsgs_ret{0};
static std::mutex            g_bsgs_mx;
static std::string           g_bsgs_priv;
static std::thread           g_bsgs_th;

/* hex -> bytes; devuelve la longitud en bytes, o -1 si es invalido. */
static int bsgs_hex(const char *h, uint8_t *out, int maxb){
    int n=0; while(h[n]) n++; if(n&1 || n/2>maxb) return -1;
    for(int i=0;i<n/2;i++){
        auto nib=[&](char c)->int{ if(c>='0'&&c<='9')return c-'0'; c|=32; if(c>='a'&&c<='f')return c-'a'+10; return -1; };
        int hi=nib(h[i*2]), lo=nib(h[i*2+1]); if(hi<0||lo<0) return -1;
        out[i]=(uint8_t)((hi<<4)|lo);
    }
    return n/2;
}
/* hex de hasta 64 chars -> 32 bytes big-endian (alineado a la derecha). */
static int bsgs_hex_be32(const char *h, uint8_t out[32]){
    char buf[65]; int n=0; while(h[n]) n++; if(n>64) return -1;
    int pad=64-n; for(int i=0;i<pad;i++) buf[i]='0'; for(int i=0;i<n;i++) buf[pad+i]=h[i]; buf[64]=0;
    return bsgs_hex(buf,out,32);
}

static void bsgs_stop_join(){
    g_bsgs_run.store(false);
    if(g_bsgs_th.joinable()) g_bsgs_th.join();
}

extern "C" JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_bsgsStart(JNIEnv *env, jobject, jstring pubS, jstring iniS, jstring finS, jint capBits){
    bsgs_stop_join();
    const char *ps=env->GetStringUTFChars(pubS,nullptr);
    const char *is=env->GetStringUTFChars(iniS,nullptr);
    const char *fs=env->GetStringUTFChars(finS,nullptr);
    uint8_t pub[65]; int publen=bsgs_hex(ps?ps:"",pub,65);
    uint8_t a[32],b[32]; int ra=bsgs_hex_be32(is?is:"",a), rb=bsgs_hex_be32(fs?fs:"",b);
    if(ps) env->ReleaseStringUTFChars(pubS,ps);
    if(is) env->ReleaseStringUTFChars(iniS,is);
    if(fs) env->ReleaseStringUTFChars(finS,fs);
    g_bsgs_count.store(0); g_bsgs_done.store(false); g_bsgs_ret.store(0);
    { std::lock_guard<std::mutex> lk(g_bsgs_mx); g_bsgs_priv.clear(); }
    if((publen!=33 && publen!=65) || ra!=32 || rb!=32){
        g_bsgs_ret.store(BSGS_ERROR); g_bsgs_done.store(true); return;
    }
    int cap=(int)capBits;
    uint8_t pubc[65]; memcpy(pubc,pub,publen);
    uint8_t ac[32],bc[32]; memcpy(ac,a,32); memcpy(bc,b,32);
    g_bsgs_run.store(true);
    g_bsgs_th=std::thread([pubc,publen,ac,bc,cap](){
        secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
        uint8_t out[32]={0};
        int r=bsgs_solve(ctx,pubc,(size_t)publen,ac,bc,cap,&g_bsgs_run,&g_bsgs_count,out);
        if(r==BSGS_HALLADO){
            char ph[65]; for(int i=0;i<32;i++) sprintf(ph+i*2,"%02x",out[i]);
            std::lock_guard<std::mutex> lk(g_bsgs_mx); g_bsgs_priv=ph;
        }
        g_bsgs_ret.store(r); g_bsgs_done.store(true);
        secp256k1_context_destroy(ctx);
    });
}
extern "C" JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_bsgsStop(JNIEnv *, jobject){ bsgs_stop_join(); }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_bsgsRunning(JNIEnv *, jobject){
    return (g_bsgs_run.load() && !g_bsgs_done.load()) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_hunter_btc_HunterEngine_bsgsCount(JNIEnv *, jobject){ return (jlong)g_bsgs_count.load(); }

/* "" aun corriendo; "privhex" hallado; "NOT_FOUND"/"RANGE_TOO_BIG"/"ERROR". */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_bsgsResult(JNIEnv *env, jobject){
    std::string r;
    if(g_bsgs_done.load()){
        int rc=g_bsgs_ret.load();
        if(rc==BSGS_HALLADO){ std::lock_guard<std::mutex> lk(g_bsgs_mx); r=g_bsgs_priv; }
        else if(rc==BSGS_NO) r="NOT_FOUND";
        else if(rc==BSGS_RANGO_GRANDE) r="RANGE_TOO_BIG";
        else r="ERROR";
    }
    return env->NewStringUTF(r.c_str());
}

/* ---------- BSGS multi-objetivo (tabla baby compartida) ---------- */
static std::atomic<bool>     g_bm_run{false};
static std::atomic<bool>     g_bm_done{false};
static std::atomic<uint64_t> g_bm_count{0};     /* pasos giant totales */
static std::atomic<int>      g_bm_total{0};      /* pubkeys a auditar */
static std::atomic<int>      g_bm_hechas{0};     /* pubkeys ya procesadas */
static std::atomic<int>      g_bm_status{0};     /* 0 run/done, -1 rango, -2 error */
static std::mutex            g_bm_mx;
static std::string           g_bm_res;           /* "pubhex|privhex\n" por hallazgo */
static std::thread           g_bm_th;

static void bm_stop_join(){ g_bm_run.store(false); if(g_bm_th.joinable()) g_bm_th.join(); }

extern "C" JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_bsgsMultiStart(JNIEnv *env, jobject, jstring pubsS, jstring iniS, jstring finS, jint capBits){
    bm_stop_join();
    const char *ps=env->GetStringUTFChars(pubsS,nullptr);
    const char *is=env->GetStringUTFChars(iniS,nullptr);
    const char *fs=env->GetStringUTFChars(finS,nullptr);
    std::string pubs = ps?ps:"";
    uint8_t a[32],b[32]; int ra=bsgs_hex_be32(is?is:"",a), rb=bsgs_hex_be32(fs?fs:"",b);
    if(ps) env->ReleaseStringUTFChars(pubsS,ps);
    if(is) env->ReleaseStringUTFChars(iniS,is);
    if(fs) env->ReleaseStringUTFChars(finS,fs);
    g_bm_count.store(0); g_bm_total.store(0); g_bm_hechas.store(0); g_bm_done.store(false); g_bm_status.store(0);
    { std::lock_guard<std::mutex> lk(g_bm_mx); g_bm_res.clear(); }
    if(ra!=32||rb!=32){ g_bm_status.store(BSGS_ERROR); g_bm_done.store(true); return; }
    int cap=(int)capBits;
    uint8_t ac[32],bc[32]; memcpy(ac,a,32); memcpy(bc,b,32);
    g_bm_run.store(true);
    g_bm_th=std::thread([pubs,ac,bc,cap](){
        /* trocear las pubkeys por lineas */
        std::vector<std::string> lst; { std::string cur; for(char c:pubs){ if(c=='\n'||c=='\r'){ if(!cur.empty()){lst.push_back(cur); cur.clear();} } else cur+=c; } if(!cur.empty()) lst.push_back(cur); }
        g_bm_total.store((int)lst.size());
        secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
        BsgsCtx bc_ctx; int rbld=bsgs_build(ctx,ac,bc,cap,&g_bm_run,&bc_ctx);
        if(rbld!=BSGS_HALLADO){ g_bm_status.store(rbld==BSGS_RANGO_GRANDE?BSGS_RANGO_GRANDE:BSGS_ERROR); g_bm_done.store(true); secp256k1_context_destroy(ctx); return; }
        for(size_t i=0;i<lst.size() && g_bm_run.load();i++){
            uint8_t pub[65]; int pl=bsgs_hex(lst[i].c_str(),pub,65);
            if(pl==33||pl==65){
                uint8_t out[32]={0};
                int r=bsgs_giant(ctx,&bc_ctx,pub,(size_t)pl,&g_bm_run,&g_bm_count,out);
                if(r==BSGS_HALLADO){
                    char ph[65]; for(int q=0;q<32;q++) sprintf(ph+q*2,"%02x",out[q]);
                    std::lock_guard<std::mutex> lk(g_bm_mx); g_bm_res += lst[i]; g_bm_res += "|"; g_bm_res += ph; g_bm_res += "\n";
                }
            }
            g_bm_hechas.fetch_add(1);
        }
        bsgs_ctx_free(&bc_ctx);
        g_bm_done.store(true);
        secp256k1_context_destroy(ctx);
    });
}
extern "C" JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_bsgsMultiStop(JNIEnv *, jobject){ bm_stop_join(); }
extern "C" JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_bsgsMultiRunning(JNIEnv *, jobject){ return (g_bm_run.load() && !g_bm_done.load())?JNI_TRUE:JNI_FALSE; }

/* "<status>\t<hechas>\t<total>\t<giant>\n" + lineas "pubhex|privhex". status:
 * running / done / range_too_big / error. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_bsgsMultiInfo(JNIEnv *env, jobject){
    const char *st = !g_bm_done.load() ? "running"
        : (g_bm_status.load()==BSGS_RANGO_GRANDE ? "range_too_big"
        : (g_bm_status.load()==BSGS_ERROR ? "error" : "done"));
    char head[96];
    snprintf(head,sizeof(head),"%s\t%d\t%d\t%llu\n", st, g_bm_hechas.load(), g_bm_total.load(), (unsigned long long)g_bm_count.load());
    std::string out=head;
    { std::lock_guard<std::mutex> lk(g_bm_mx); out+=g_bm_res; }
    return env->NewStringUTF(out.c_str());
}



/* ---------- Prueba de rendimiento del motor (pantalla Debug) ----------
 *
 * Mide en el PROPIO movil lo que en el banco de escritorio no se puede: la
 * multiplicacion de cuerpo en C frente a la de ensamblador ARM64, y los
 * saltos por segundo del bucle de Kangaroo en un hilo. Es lo que decide si el
 * ensamblador (fe_arm64.h) merece entrar en el motor.
 *
 * Las multiplicaciones van en cuatro cadenas independientes (r = r*b con b al
 * azar): con una sola cadena se mide la latencia, no el ritmo, y con valores
 * que se vuelven triviales se mide nada. */
static double bench_mul(int variante,int n,uint64_t *sal){
    fe_t r[4],b[4];
    uint64_t s=0x9E3779B97F4A7C15ULL;
    for(int k=0;k<4;k++) for(int j=0;j<4;j++){ s^=s<<13; s^=s>>7; s^=s<<17; r[k][j]=s>>1; s^=s<<13; s^=s>>7; s^=s<<17; b[k][j]=s>>1; }
    auto t0=std::chrono::steady_clock::now();
    for(int i=0;i<n;i++) for(int k=0;k<4;k++){
#ifdef FE_ARM64
        if(variante==1){ fe_mul_asm(r[k],r[k],b[k]); continue; }
#endif
        if(variante==2) fe_sqr(r[k],r[k]);
        else if(variante==3) fe_sqr_c(r[k],r[k]);
        else fe_mul_c(r[k],r[k],b[k]);
    }
    double ns=std::chrono::duration<double,std::nano>(std::chrono::steady_clock::now()-t0).count()/(4.0*n);
    *sal^=r[0][0]^r[1][1]^r[2][2]^r[3][3];
    return ns;
}
struct BenchKg { KangarooCtx *c; };
static void *bench_kg_hilo(void *p){ kg_run(((BenchKg*)p)->c,512,0xC0FFEE1234ULL); return NULL; }

extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_benchCampo(JNIEnv *env,jobject){
    std::ostringstream o;
    uint64_t sal=0;
#ifdef FE_ARM64
    /* Primero, que de lo mismo. Un ensamblador rapido y equivocado es peor que
       nada: firmaria y buscaria con numeros que no son. */
    long mal=0;
    uint64_t s=0x1234567887654321ULL;
    for(int it=0;it<200000;it++){
        fe_t a,b,r1,r2;
        for(int j=0;j<4;j++){ s^=s<<13; s^=s>>7; s^=s<<17; a[j]=s; s^=s<<13; s^=s>>7; s^=s<<17; b[j]=s; }
        if(it%5==0){ a[3]=~0ULL; b[2]=~0ULL; }
        fe_mul_c(r1,a,b); fe_mul_asm(r2,a,b);
        if(memcmp(r1,r2,32)) mal++;
        fe_sqr_c(r1,a); fe_sqr(r2,a);
        if(memcmp(r1,r2,32)) mal++;
    }
    o<<"ARM64 asm vs C: "<<(mal?"DIFFERENT RESULTS ":"same results ")<<"("<<mal<<" of 400000)\n";
#else
    o<<"Not ARM64: no assembly to compare\n";
#endif
    bench_mul(0,200000,&sal);                     /* calentar la CPU */
    double c=bench_mul(0,3000000,&sal);
    o<<"fe_mul C:    "<<std::fixed<<std::setprecision(1)<<c<<" ns\n";
#ifdef FE_ARM64
    double a=bench_mul(1,3000000,&sal);
    o<<"fe_mul asm:  "<<a<<" ns  ("<<std::setprecision(2)<<c/a<<"x)\n"<<std::setprecision(1);
#endif
    double qc=bench_mul(3,3000000,&sal);
    o<<"fe_sqr C:    "<<qc<<" ns\n";
#ifdef FE_ARM64
    double qa=bench_mul(2,3000000,&sal);
    o<<"fe_sqr asm:  "<<qa<<" ns  ("<<std::setprecision(2)<<qc/qa<<"x)\n"<<std::setprecision(1);
#endif

    /* hash160 (SHA-256 + RIPEMD-160 de una clave publica): casi todo el
       tiempo del escaner de claves. El de antes (todo software, RIPEMD con
       bucle), el de ahora (SHA-256 del procesador, RIPEMD desenrollado) y
       cuatro a la vez (hash160x4.h). */
    {
        uint8_t in[4][33], h[20], h4[4][20];
        for(int k=0;k<4;k++){ in[k][0]=0x02; for(int i=1;i<33;i++) in[k][i]=(uint8_t)(i*37+k); }
        const uint8_t *pp[4]={in[0],in[1],in[2],in[3]};
        const int N=400000;
        auto t0=std::chrono::steady_clock::now();
        for(int i=0;i<N;i++){ uint8_t sh[32]; sha256_33_sw(in[0],sh); ripemd160_32_ref(sh,h); in[0][5]^=h[0]; }
        double antes=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
        o<<"hash160 before:  "<<std::setprecision(2)<<N/antes/1e6<<" M/s\n";
        t0=std::chrono::steady_clock::now();
        for(int i=0;i<N;i++){ hash160_inline(in[0],h); in[0][5]^=h[0]; }
        double uno=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
        o<<"hash160 now:     "<<N/uno/1e6<<" M/s  ("<<antes/uno<<"x)\n";
        t0=std::chrono::steady_clock::now();
        for(int i=0;i<N/4;i++){ hash160_x4(pp,h4); in[0][5]^=h4[0][0]; }
        double cuatro=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
        o<<"hash160 x4:      "<<N/cuatro/1e6<<" M/s  ("<<antes/cuatro<<"x)\n";
        sal^=h[0]^h4[1][0];
    }
    /* El escaner de claves entero (escaner_raw.h), un hilo, sin lista: curva,
       seis claves por punto y hash160 de cuatro en cuatro. */
    {
        std::call_once(g_esc_tabla_hecha,[]{ esc_tabla_crear(&g_esc_tabla); });
        static fe_t dx[ESC_M+1],pfx[ESC_M+1];
        /* Centro en 1026*G: lejos de todos los +-iG de la tabla. */
        fe_t cx,cy;
        { JP S; memcpy(S.x,g_esc_tabla.sx,32); memcpy(S.y,g_esc_tabla.sy,32); memset(S.z,0,32); S.z[0]=1;
          JP S1; jp_add_G(&S1,&S); kg_normalize(&S1,cx,cy); }
        long cuenta=0; int grupos=0;
        auto t0=std::chrono::steady_clock::now(); double seg;
        do{
            esc_grupo(&g_esc_tabla,cx,cy,dx,pfx,[](const uint8_t *h,int,int,void *c){ (*(long*)c)+=h[0]; },&cuenta);
            grupos++;
            seg=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
        }while(seg<1.0);
        o<<"RawKey scanner: "<<std::setprecision(2)<<(double)grupos*ESC_GRUPO*6/seg/1e6<<" M keys/s (1 thread)\n"<<std::setprecision(1);
        sal^=(uint64_t)cuenta;
    }
    /* PBKDF2-HMAC-SHA512 de 2048 vueltas: lo que cuesta cada seed del
       escaner BIP39 y de la recuperacion. OpenSSL frente a lo propio
       (sha512.cpp), en cada forma que tenga este procesador. */
    {
        const char *mn="abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        const size_t mnl=strlen(mn);
        uint8_t seed[64],seed2[64];
        auto medir=[&](int modo)->double{
            int n=0; auto t0=std::chrono::steady_clock::now(); double seg;
            do{
                if(modo<0){ PKCS5_PBKDF2_HMAC(mn,(int)mnl,(const uint8_t*)"mnemonic",8,2048,EVP_sha512(),64,seed); n++; }
                else if(modo==4){
                    const char *f4[4]={mn,mn,mn,mn}; size_t l4[4]={mnl,mnl,mnl,mnl}; uint8_t o4[4][64];
                    bip39_semilla_x4(f4,l4,"",0,o4); seed[0]^=o4[3][0]; n+=4;
                }
                else{ bip39_semilla_x2(mn,mnl,mn,mnl,"",0,seed,seed2); n+=2; }
                seg=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
            }while(seg<0.6);
            sal^=seed[0]; return n/seg;
        };
        o<<"SHA-512 instructions: "<<(sha512_tiene_hw()?"yes":"no")<<"\n";
        double base=medir(-1);
        o<<"BIP39 seeds OpenSSL:  "<<std::setprecision(0)<<base<<" /s (1 thread)\n";
        for(int m=0;m<=4;m++){
            if(!sha512_modo_disponible(m)) continue;
            sha512_fijar_modo(m);
            double v=medir(m);
            o<<"BIP39 seeds "<<sha512_nombre_modo(m)<<": "<<std::setprecision(0)<<v<<" /s ("
             <<std::setprecision(2)<<v/base<<"x)\n";
        }
        sha512_fijar_modo(-1);
        o<<"In use: "<<sha512_nombre_modo(sha512_modo())<<"\n"<<std::setprecision(1);
    }

    /* Saltos por segundo del bucle de Kangaroo, un hilo, dos segundos, en un
       rango como el del #140. */
    {
        uint8_t pub[33],ini[32],fin[32];
        sc_t k; sc_set_u64(k,123456789ULL);
        JP P; kg_scalar_mul(&P,k,FIELD_GX,FIELD_GY); fe_t x,y; kg_normalize(&P,x,y);
        pub[0]=(y[0]&1)?3:2;
        for(int w=0;w<4;w++) for(int bb=0;bb<8;bb++) pub[1+(3-w)*8+(7-bb)]=(uint8_t)(x[w]>>(bb*8));
        memset(ini,0,32); ini[31-139/8]=(uint8_t)(1u<<(139%8));
        memset(fin,0,32); for(int q2=0;q2<=139;q2++) fin[31-q2/8]|=(uint8_t)(1u<<(q2%8));
        KangarooCtx *kc=new KangarooCtx();
        if(kg_setup(kc,pub,ini,fin,20,18)){
            BenchKg arg{kc}; pthread_t th; pthread_create(&th,NULL,bench_kg_hilo,&arg);
            std::this_thread::sleep_for(std::chrono::milliseconds(300));
            long long s0=kc->saltos.load(); auto t0=std::chrono::steady_clock::now();
            std::this_thread::sleep_for(std::chrono::milliseconds(2000));
            long long s1=kc->saltos.load();
            double seg=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
            kc->parar.store(1); pthread_join(th,NULL);
            o<<"Kangaroo, 1 thread: "<<std::setprecision(2)<<(s1-s0)/seg/1e6<<" M jumps/s\n";
            kg_free(kc);
        }
        delete kc;
    }
    if(sal==42) o<<" ";   /* que el compilador no se salte el trabajo */
    return env->NewStringUTF(o.str().c_str());
}

/* Cuantos caben antes de que dp_insert deje de guardar. Es el 90 % de la
 * capacidad, y esta aqui —y no calculado en Kotlin— para que salga del mismo
 * sitio que la condicion de dp_insert y no puedan separarse. */
extern "C" JNIEXPORT jlong JNICALL
Java_com_hunter_btc_HunterEngine_kangarooTope(JNIEnv *, jobject){
    if(!g_kg_vivo) return 0;
    return (jlong)(((g_kg.tabla.mask+1)*9)/10);
}

/* ---------- Reparto por red ----------
 *
 * Repartir Kangaroo NO es partir el rango: partirlo lo empeora, porque el coste
 * es raiz(W) y hay que recorrer varios trozos sin saber en cual esta la clave.
 * Lo que se reparte es la TABLA de puntos distinguidos. Todos los aparatos
 * caminan el intervalo entero y mandan sus puntos al master, que los junta en
 * una sola tabla: ahi aparece la colision aunque las dos mitades vengan de
 * moviles distintos. El razonamiento largo esta en kangaroo.h.
 *
 * Lo que viaja son pares (punto, distancia). Dos de rebanos distintos que
 * coincidan dan la clave: es material de clave y no hay forma de evitarlo.
 */



/* La clave publica con la que se arranco, para que el master pueda decirle a
 * cada trabajador en que puzzle tiene que ponerse. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_kangarooPub(JNIEnv *env, jobject){
    if(!g_kg_vivo) return env->NewStringUTF("");
    char hex[67];
    for(int i=0;i<33;i++) sprintf(hex+i*2,"%02x",g_kg_pub[i]);
    hex[66]=0;
    return env->NewStringUTF(hex);
}

/* @return la clave privada en hex de 64 caracteres, o "" si aun no esta. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_kangarooResult(JNIEnv *env, jobject){
    if(!g_kg_vivo || !g_kg.encontrado.load()) return env->NewStringUTF("");
    uint8_t be[32]; sc_to_be32(g_kg.k,be);
    char hex[65];
    for(int i=0;i<32;i++) sprintf(hex+i*2,"%02x",be[i]);
    hex[64]=0;
    return env->NewStringUTF(hex);
}

}
