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
#include <openssl/sha.h>
#include <openssl/hmac.h>
#include <openssl/evp.h>
#include <openssl/bn.h>
#include <openssl/ripemd.h>
#include "jac_batch.h"
#include "kangaroo.h"
#include "bloom.h"

#include "sha256_ripemd160.h"

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

#define PBKDF2_ITERS_STD  2048
#define PBKDF2_ITERS_FAST 1
#define MAX_THREADS  16
#define LOCAL_BATCH   128
#define MAX_CSV_ROWS  120000000ULL
#define HASH160_BYTES 20
#define PRIVKEY_BYTES 32
#define MAX_LINE      256
#define MAX_ADDR      64
#include "addr_encode.h"   /* b58enc, h160_to_addr, bech32, p2sh, p2tr */
#define N_PATHS       8

/* Paths BIP44 para modo BIP39 */
static const char *PATHS[N_PATHS]={
    "m/44'/0'/0'/0/0","m/44'/0'/0'/0/1","m/44'/0'/0'/0/2","m/44'/0'/0'/0/3",
    "m/44'/0'/0'/0/4","m/49'/0'/0'/0/0","m/84'/0'/0'/0/0","m/44'/0'/0'/1/0"
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
static char      g_csv_path[1024] = "";
/* Directorio donde se guardan los matches. Lo fija la app con
   setMatchDir(filesDir) para que las claves privadas no acaben junto al CSV,
   que normalmente está en almacenamiento externo. */
static char      g_match_dir[1024] = "";

static std::atomic<long>   g_count(0);
static std::atomic<long>   g_found(0);
static std::atomic<bool>   g_running(false);
static std::atomic<bool>   g_stop(false);
static std::atomic<double> g_wps(0.0);
static std::atomic<int>    g_cpu_limit(100);
static std::atomic<int>    g_batch_size(1); // minimo para debug
static std::atomic<int>    g_pbkdf2_iters(2048); /* 2048=standard, 1=fast */
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
static uint8_t g_last_key[32]     = {0};
static std::mutex g_last_key_mutex;
static uint8_t g_target_h160[20]  = {0};
static int     g_has_target       = 0;

static std::mutex               g_log_mutex;
static std::deque<std::string>  g_log;
static std::mutex               g_match_mutex;
static std::vector<std::string> g_matches;
static std::mutex               g_addr_mutex;
static std::deque<std::string>  g_recent_addrs;

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
static void add_addr(const std::string &a){
    std::lock_guard<std::mutex> lk(g_addr_mutex);
    g_recent_addrs.push_front(a);
    if(g_recent_addrs.size()>50) g_recent_addrs.pop_back();
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
    if(!bloom_check(&g_bloom,t)) return -1; /* bloom filter: skip bsearch */
    int64_t lo=0,hi=(int64_t)g_total-1;
    while(lo<=hi){int64_t mid=(lo+hi)>>1;int c=memcmp(g_h160+mid*HASH160_BYTES,t,HASH160_BYTES);if(!c)return mid;if(c<0)lo=mid+1;else hi=mid-1;}
    return -1;
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
static void derive_master(const uint8_t *s,HDKey *o){uint8_t I[64];unsigned int l=64;HMAC(EVP_sha512(),"Bitcoin seed",12,s,64,I,&l);memcpy(o->key,I,32);memcpy(o->chain,I+32,32);}
static void get_pub33(secp256k1_context *ctx,const uint8_t *pk,uint8_t *p33){secp256k1_pubkey pub;secp256k1_ec_pubkey_create(ctx,&pub,pk);size_t len=33;secp256k1_ec_pubkey_serialize(ctx,p33,&len,&pub,SECP256K1_EC_COMPRESSED);}
static void derive_child(secp256k1_context *ctx,const HDKey *par,uint32_t idx,HDKey *child){
    uint8_t data[37];unsigned int l=64;uint8_t I[64];
    if(idx>=0x80000000){data[0]=0;memcpy(data+1,par->key,32);}else get_pub33(ctx,par->key,data);
    data[33]=(uint8_t)(idx>>24);data[34]=(uint8_t)(idx>>16);data[35]=(uint8_t)(idx>>8);data[36]=(uint8_t)idx;
    HMAC(EVP_sha512(),par->chain,32,data,37,I,&l);
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
static void pk_to_h160(secp256k1_context *ctx,const uint8_t *pk,uint8_t *out){uint8_t pub[33];get_pub33(ctx,pk,pub);uint8_t sha[32];SHA256(pub,33,sha);RIPEMD160(sha,32,out);}
static void read_row_by_h160(const uint8_t *h160,char *sats,char *type){
    strcpy(sats,"0");strcpy(type,"?");
    FILE *f=fopen(g_csv_path,"r");if(!f)return;
    char line[MAX_LINE],*fields[8];
    // skip header
    fgets(line,sizeof(line),f);
    while(fgets(line,sizeof(line),f)){
        char tmp[MAX_LINE];strncpy(tmp,line,MAX_LINE-1);
        int n=split_line(tmp,fields,8);if(n<1)continue;
        char addr[MAX_ADDR];strncpy(addr,fields[0],MAX_ADDR-1);trim_str(addr);
        uint8_t h[HASH160_BYTES];
        if(!addr_to_h160(addr,h))continue;
        if(memcmp(h,h160,HASH160_BYTES)==0){
            if(n>=2){strncpy(sats,fields[1],23);trim_str(sats);}
            if(n>=3){strncpy(type,fields[2],11);trim_str(type);}
            break;
        }
    }
    fclose(f);
}

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



/* PBKDF2-SHA512 optimizado: precalcula estado HMAC del password */
static void fast_pbkdf2_sha512(const char *pass, int plen, const uint8_t *salt, int slen, int iters, uint8_t *out) {
    /* Precalcular estado HMAC con el password - solo 2 SHA512 calls */
    HMAC_CTX *hctx = HMAC_CTX_new();
    HMAC_Init_ex(hctx, pass, plen, EVP_sha512(), nullptr);
    /* Salt + block counter para primer bloque */
    uint8_t saltblock[slen+4];
    memcpy(saltblock, salt, slen);
    saltblock[slen]=0; saltblock[slen+1]=0; saltblock[slen+2]=0; saltblock[slen+3]=1;
    /* U1 = HMAC(pass, salt||1) */
    uint8_t U[64], T[64];
    unsigned int ulen=64;
    HMAC_CTX *hctx2 = HMAC_CTX_new();
    HMAC_CTX_copy(hctx2, hctx);
    HMAC_Update(hctx2, saltblock, slen+4);
    HMAC_Final(hctx2, U, &ulen);
    HMAC_CTX_free(hctx2);
    memcpy(T, U, 64);
    /* U2..Un = HMAC(pass, U_prev) - reusar estado base del password */
    for (int i = 1; i < iters; i++) {
        HMAC_CTX *hctx3 = HMAC_CTX_new();
        HMAC_CTX_copy(hctx3, hctx);
        HMAC_Update(hctx3, U, 64);
        HMAC_Final(hctx3, U, &ulen);
        HMAC_CTX_free(hctx3);
        for (int j=0;j<64;j++) T[j]^=U[j];
    }
    HMAC_CTX_free(hctx);
    memcpy(out, T, 64);
}
static void pbkdf2_sha512_1iter(const char *pass, int plen, const uint8_t *salt, int slen, uint8_t *out) {
    fast_pbkdf2_sha512(pass, plen, salt, slen, 1, out);
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
    PKCS5_PBKDF2_HMAC(mnemonic,(int)strlen(mnemonic),(const uint8_t*)"mnemonic",8,2048,EVP_sha512(),64,seed);
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

/* Guardar match */
static void save_match(const char *privhex, const char *addr, double btc, const char *wif, const char *extra){
    /* El fichero lleva claves privadas en claro. Si la app ha fijado un
       directorio interno lo usamos; sólo si no, caemos junto al CSV (que suele
       estar en almacenamiento externo, legible por apps con
       MANAGE_EXTERNAL_STORAGE y por USB). */
    std::string outpath;
    if(g_match_dir[0]!='\0'){
        outpath=std::string(g_match_dir);
        if(outpath.back()!='/') outpath+='/';
        outpath+="coincidencias.txt";
    }else{
        outpath=std::string(g_csv_path);
        size_t sl=outpath.rfind('/');
        if(sl!=std::string::npos) outpath=outpath.substr(0,sl+1)+"coincidencias.txt";
    }
    FILE *fo=fopen(outpath.c_str(),"a");
    if(fo){fprintf(fo,"%s ADDR:%s BTC:%.8f WIF:%s\n",extra,addr,btc,wif);fclose(fo);}
    std::ostringstream oss;oss<<"MATCH! "<<addr<<" "<<btc<<" BTC";
    add_log(oss.str());
    std::ostringstream full;full<<"MATCH|ADDR:"<<addr<<"|BTC:"<<btc<<"|WIF:"<<wif<<"|HEX:"<<privhex;
    {std::lock_guard<std::mutex> lk(g_match_mutex);g_matches.push_back(full.str());}
}

/* =========================================================
   Worker BIP39 (modo 0)
   ========================================================= */
typedef struct{int64_t idx;char mn[256];uint8_t pk[PRIVKEY_BYTES];int pi;}Hit;

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
        for(int bi=0;bi<LOCAL_BATCH&&!g_stop.load();bi++){
            gen_mnemonic(mn,sizeof(mn));
            PKCS5_PBKDF2_HMAC(mn,(int)strlen(mn),(const uint8_t*)"mnemonic",8,g_pbkdf2_iters.load(),EVP_sha512(),64,seed);
            HDKey master; derive_master(seed,&master);
            const int paths=g_bip39_paths.load();
            /* --- m/44'/0'/0'/0/0 --- */
            if(paths&1){
                HDKey h44,h44_0,h44_0_0,h44_ch0,h44_leaf;
                derive_child(ctx,&master,0x80000000u+44,&h44);
                derive_child(ctx,&h44,0x80000000u+0,&h44_0);
                derive_child(ctx,&h44_0,0x80000000u+0,&h44_0_0);
                derive_child(ctx,&h44_0_0,0,&h44_ch0);
                derive_child(ctx,&h44_ch0,0,&h44_leaf);
                pk_to_h160(ctx,h44_leaf.key,h160); local_done++;
                {int64_t ix=bsearch_h160(h160);if(ix>=0){hits[nhits].idx=ix;strcpy(hits[nhits].mn,mn);memcpy(hits[nhits].pk,h44_leaf.key,PRIVKEY_BYTES);hits[nhits].pi=0;nhits++;}}
            }
            /* BIP49 skipped — p2sh not in dataset */
            /* --- m/84'/0'/0'/0/0 --- */
            if(paths&2){
                HDKey h84,h84_0,h84_00,h84_000,h84_leaf;
                derive_child(ctx,&master,0x80000000u+84,&h84);
                derive_child(ctx,&h84,0x80000000u+0,&h84_0);
                derive_child(ctx,&h84_0,0x80000000u+0,&h84_00);
                derive_child(ctx,&h84_00,0,&h84_000);
                derive_child(ctx,&h84_000,0,&h84_leaf);
                pk_to_h160(ctx,h84_leaf.key,h160); local_done++;
                {int64_t ix=bsearch_h160(h160);if(ix>=0){hits[nhits].idx=ix;strcpy(hits[nhits].mn,mn);memcpy(hits[nhits].pk,h84_leaf.key,PRIVKEY_BYTES);hits[nhits].pi=6;nhits++;}}
            }
            /* Feed visual: solo 1 vez por batch */
            if(bi==0){char at[MAX_ADDR]={0};h160_to_bech32(h160,at);add_addr(std::string(at));}
            /* BIP86 removed */
        }
        double work_ms=std::chrono::duration<double,std::milli>(std::chrono::high_resolution_clock::now()-t0).count();
        int cpu=g_cpu_limit.load();
        if(cpu<100){double sl=work_ms*(100.0-cpu)/cpu;if(sl>0.5)std::this_thread::sleep_for(std::chrono::milliseconds((int)sl));}
        g_count.fetch_add(local_done);
        for(int i=0;i<nhits;i++){
            g_found.fetch_add(1);
            char addr[MAX_ADDR]={0},wif[60]={0},pkhex[65]={0},sats[24]={0},type_[12]={0};
            uint8_t h160b[20]; pk_to_h160(ctx,hits[i].pk,h160b); h160_to_addr(h160b,addr); pk_to_wif(hits[i].pk,wif);
            for(int b=0;b<32;b++) sprintf(pkhex+b*2,"%02x",hits[i].pk[b]);
            double btc=0.0; /* sats not available in .bin format — check via Electrum */
            char extra[512]; snprintf(extra,sizeof(extra),"SEED:%s PATH:%s PRIV:%s",hits[i].mn,PATHS[hits[i].pi],pkhex);
            save_match(pkhex,addr,btc,wif,extra);
        }
    }
    secp256k1_context_destroy(ctx); return nullptr;
}

static void privkey_increment(uint8_t *k){
    for(int i=31;i>=0;i--){if(++k[i])break;}
}

/* Callback para jac_batch: hash160 + check match por cada clave del batch */
struct PuzzleBatchCtx {
    uint8_t priv_base[32]; /* privkey del punto[0] */
    long    done;
};
static PuzzleBatchCtx g_pbctx;

static void puzzle_on_key(int idx, const uint8_t *pub33, void *raw){
    PuzzleBatchCtx *c=(PuzzleBatchCtx*)raw;
    uint8_t h160[HASH160_BYTES];
    /* Llamaba a SHA256() y RIPEMD160() de OpenSSL, que montan y desmontan su
       contexto en cada llamada: para 33 bytes ese armazon pesa mas que el
       hash. El worker de clave directa ya usaba hash160_inline; el del puzzle
       se habia quedado atras. */
    hash160_inline(pub33,h160);
    c->done++;
    /* Muestra de direcciones para la UI. Estaba cada 500 claves, lo que a
       1M/s son 2000 codificaciones Base58 por segundo — cada una con doble
       SHA-256 — más 2000 tomas de g_addr_mutex desde todos los hilos. La UI
       sólo guarda las últimas 50 y se refresca cada 800 ms, así que una de
       cada 50 000 basta de sobra. */
    if(c->done%50000==0){char atmp[MAX_ADDR]={0};h160_to_addr(h160,atmp);add_addr(std::string(atmp));}
    int match=0;
    if(g_has_target){
        if(memcmp(h160,g_target_h160,HASH160_BYTES)==0) match=1;
    } else if(g_csv_loaded.load()){
        int64_t i=bsearch_h160(h160);
        if(i>=0){match=1;}  /* sats not in .bin */
    }
    if(match){
        g_found.fetch_add(1);
        /* Reconstruir privkey = base + idx */
        uint8_t privkey[32]; memcpy(privkey,c->priv_base,32);
        for(int k=0;k<idx;k++){for(int b=31;b>=0;b--){if(++privkey[b])break;}}
        char addr[MAX_ADDR]={0},wif[60]={0},pkhex[65]={0};
        h160_to_addr(h160,addr); pk_to_wif(privkey,wif);
        for(int b=0;b<32;b++) sprintf(pkhex+b*2,"%02x",privkey[b]);
        double btc=0.0; /* check via Electrum */
        char extra[128]; snprintf(extra,sizeof(extra),"PRIV:%s",pkhex);
        save_match(pkhex,addr,btc,wif,extra);
        add_log(std::string("*** PUZZLE SOLVED *** ADDR:")+addr+" PRIV:"+pkhex);
    }
}



/* =========================================================
   RAW KEY WORKER - modo 2
   Logica identica a hunter_master.cpp:
   - Inicio aleatorio por thread
   - Incremento secuencial +1
   - pubkey_create por cada key (igual que script Termux)
   - Lookup via bloom+bsearch
   ========================================================= */
static void *worker_rawkey_fn(void *arg){
    /* El índice del hilo llega en el argumento: antes se pasaba 0 literal y
       set_thread_affinity fijaba TODOS los hilos al mismo núcleo. */
    set_thread_affinity((int)(intptr_t)arg);
    secp256k1_context *ctx = secp256k1_context_create(
        SECP256K1_CONTEXT_SIGN | SECP256K1_CONTEXT_VERIFY);
    if(!ctx) return nullptr;

    const int MAX_SAFE = 512;
    JP *pts = (JP*)malloc(MAX_SAFE * sizeof(JP));
    if(!pts){ secp256k1_context_destroy(ctx); return nullptr; }
    /* Buffer de productos prefijo, reutilizado en todos los lotes. */
    fe_t *pfx = (fe_t*)malloc(MAX_SAFE * sizeof(fe_t));
    if(!pfx){ free(pts); secp256k1_context_destroy(ctx); return nullptr; }

    XR128 rng; xr_init(&rng);
    uint8_t priv[32];
    uint64_t r0=xr_next(&rng),r1=xr_next(&rng),
             r2=xr_next(&rng),r3=xr_next(&rng);
    memcpy(priv,    &r0, 8); memcpy(priv+8,  &r1, 8);
    memcpy(priv+16, &r2, 8); memcpy(priv+24, &r3, 8);
    while(!secp256k1_ec_seckey_verify(ctx, priv)){
        for(int b=31;b>=0;b--){if(++priv[b])break;}
    }

    while(!g_stop.load()){
        auto t0 = std::chrono::high_resolution_clock::now();

        int cur_batch = g_batch_size.load();
        if(cur_batch < 1) cur_batch = 1;
        if(cur_batch > MAX_SAFE) cur_batch = MAX_SAFE;

        /* 1 multiplicacion escalar para punto base */
        secp256k1_pubkey pubkey;
        if(!secp256k1_ec_pubkey_create(ctx, &pubkey, priv)){
            for(int b=31;b>=0;b--){if(++priv[b])break;}
            continue;
        }
        uint8_t pub65[65]; size_t plen=65;
        secp256k1_ec_pubkey_serialize(ctx, pub65, &plen, &pubkey,
            SECP256K1_EC_UNCOMPRESSED);
        jp_from_affine(&pts[0], pub65);

        bool z_ok=false;
        for(int j=0;j<4;j++) if(pts[0].z[j]){z_ok=true;break;}
        if(!z_ok){ for(int b=31;b>=0;b--){if(++priv[b])break;} continue; }

        uint8_t base[32]; memcpy(base, priv, 32);
        int actual=1;

        /* Batch de adiciones - cur_batch-1 sumas en vez de multiplicaciones */
        for(int i=1; i<cur_batch && !g_stop.load(); i++){
            for(int b=31;b>=0;b--){if(++priv[b])break;}
            jp_add_G(&pts[i], &pts[i-1]);
            bool zi=false;
            for(int j=0;j<4;j++) if(pts[i].z[j]){zi=true;break;}
            if(!zi) break;
            actual++;
        }

        /* Batch normalize + hash + lookup */
        struct RawCtx { 
            uint8_t base[32]; 
            int done;
        };
        RawCtx rctx; 
        memcpy(rctx.base, base, 32); 
        rctx.done=0;

        jac_batch_hash160(pts, actual, pfx, [](int idx, const uint8_t *pub33, void *raw){
            RawCtx *c = (RawCtx*)raw;
            uint8_t h160[HASH160_BYTES];
            hash160_inline(pub33, h160);
            c->done++;

            int match=0;
            if(g_has_target){
                if(memcmp(h160, g_target_h160, HASH160_BYTES)==0) match=1;
            } else if(g_csv_loaded.load()){
                if(bsearch_h160(h160)>=0) match=1;
            }

            if(match){
                g_found.fetch_add(1);
                uint8_t pk[32]; memcpy(pk, c->base, 32);
                for(int k=0;k<idx;k++){for(int b=31;b>=0;b--){if(++pk[b])break;}}
                char addr[MAX_ADDR]={0},wif[60]={0},pkhex[65]={0};
                h160_to_addr(h160,addr); pk_to_wif(pk,wif);
                for(int b=0;b<32;b++) sprintf(pkhex+b*2,"%02x",pk[b]);
                char extra[128]; snprintf(extra,sizeof(extra),"RAW:%s",pkhex);
                save_match(pkhex,addr,0.0,wif,extra);
                add_log(std::string("*** RAW MATCH *** ADDR:")+addr);
            }
        }, &rctx);

        g_count.fetch_add(actual);

        {std::lock_guard<std::mutex> lk(g_last_key_mutex);
         memcpy(g_last_key, priv, 32);}

        double work_ms = std::chrono::duration<double,std::milli>(
            std::chrono::high_resolution_clock::now()-t0).count();
        int cpu = g_cpu_limit.load();
        if(cpu<100){
            double sl=work_ms*(100.0-cpu)/cpu;
            if(sl>0.5) std::this_thread::sleep_for(
                std::chrono::milliseconds((int)sl));
        }
    }

    free(pts); free(pfx);
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
    JP *pts = (JP*)malloc(MAX_SAFE * sizeof(JP));
    if(!pts){ secp256k1_context_destroy(ctx); return nullptr; }
    /* Buffer de productos prefijo, reutilizado en todos los lotes. */
    fe_t *pfx = (fe_t*)malloc(MAX_SAFE * sizeof(fe_t));
    if(!pfx){ free(pts); secp256k1_context_destroy(ctx); return nullptr; }

    XR128 rng; xr_init(&rng);

    while(!g_stop.load()){
        auto t0 = std::chrono::high_resolution_clock::now();

        int cur_batch = g_batch_size.load();
        if(cur_batch < 1) cur_batch = 1;
        if(cur_batch > MAX_SAFE) cur_batch = MAX_SAFE;

        /* Generar clave base - aleatorio o secuencial */
        uint8_t privkey[32];
        if(g_sequential.load()) {
            /* Modo secuencial: tomar posición actual y avanzar batch */
            std::lock_guard<std::mutex> lk(g_seq_mutex);
            memcpy(privkey, g_seq_pos, 32);
            /* Verificar que no pasamos el fin */
            if(memcmp(privkey, g_range_end, 32) > 0) {
                /* Llegamos al fin, reiniciar desde inicio */
                memcpy(g_seq_pos, g_range_start, 32);
                memcpy(privkey, g_range_start, 32);
            }
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

        /* Crear pubkey base */
        secp256k1_pubkey pubkey;
        if(!secp256k1_ec_pubkey_create(ctx, &pubkey, privkey)) continue;

        uint8_t pub65[65]; size_t plen = 65;
        secp256k1_ec_pubkey_serialize(ctx, pub65, &plen, &pubkey,
            SECP256K1_EC_UNCOMPRESSED);
        jp_from_affine(&pts[0], pub65);

        /* Verificar Z valido */
        bool z_ok = false;
        for(int j=0;j<4;j++) if(pts[0].z[j]){z_ok=true;break;}
        if(!z_ok) continue;

        uint8_t cur[32];
        memcpy(cur, privkey, 32);
        int actual = 1;

        /* Batch de adiciones Jacobianas */
        for(int i = 1; i < cur_batch && !g_stop.load(); i++){
            for(int b = 31; b >= 0; b--){ if(++cur[b]) break; }
            if(memcmp(cur, g_range_end, 32) > 0) break;
            jp_add_G(&pts[i], &pts[i-1]);

            /* Verificar Z */
            bool zi_ok = false;
            for(int j=0;j<4;j++) if(pts[i].z[j]){zi_ok=true;break;}
            if(!zi_ok) break;
            actual++;
        }

        if(actual < 1) continue;

        PuzzleBatchCtx pctx;
        memcpy(pctx.priv_base, privkey, 32);
        pctx.done = 0;
        jac_batch_hash160(pts, actual, pfx, puzzle_on_key, &pctx);

        g_count.fetch_add(actual);

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

    free(pts); free(pfx);
    secp256k1_context_destroy(ctx);
    return nullptr;
}


static pthread_t g_workers[MAX_THREADS];
static int g_active=0;

/* =========================================================
   CSV loader
   ========================================================= */
static void* load_bin_fn(void*) {
    g_loading.store(true); g_csv_loaded.store(false);
    snprintf(g_load_status,sizeof(g_load_status),"Loading .bin...");
    add_log(std::string("BIN path: ")+g_csv_path);
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

/* Fija el directorio donde save_match() escribe coincidencias.txt. La app pasa
   filesDir (almacenamiento interno) para que las claves privadas no se escriban
   junto al CSV. */
JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_setMatchDir(JNIEnv *env,jobject,jstring dir){
    const char *p=env->GetStringUTFChars(dir,nullptr);
    if(p){ strncpy(g_match_dir,p,sizeof(g_match_dir)-1); g_match_dir[sizeof(g_match_dir)-1]='\0'; }
    env->ReleaseStringUTFChars(dir,p);
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
}

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_startHunting(JNIEnv *,jobject,jint threads,jint cpuLimit){
    install_crash_handlers();
    if(g_running.load())return;
    if(g_stopping.load())return;   /* workers de la sesión anterior aún vivos */
    if(!g_csv_loaded.load()&&g_mode.load()!=1&&g_mode.load()!=2)return;
    g_nthreads.store(threads);g_cpu_limit.store(cpuLimit);
    g_stop.store(false);g_count.store(0);g_found.store(0);g_wps.store(0);
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

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_stopHunting(JNIEnv *,jobject){
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

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_setPbkdf2Mode(JNIEnv *,jobject,jint fast){
    g_pbkdf2_iters.store(fast==1 ? PBKDF2_ITERS_FAST : PBKDF2_ITERS_STD);
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

JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_getMatches(JNIEnv *env,jobject){
    std::lock_guard<std::mutex> lk(g_match_mutex);
    std::string all;for(auto &m:g_matches)all+=m+"\n";
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

JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_popMatch(JNIEnv *env,jobject){
    std::lock_guard<std::mutex> lk(g_match_mutex);
    if(g_matches.empty())return env->NewStringUTF("");
    std::string s=g_matches.front();g_matches.erase(g_matches.begin());
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_popRecentAddr(JNIEnv *env,jobject){
    std::lock_guard<std::mutex> lk(g_addr_mutex);
    if(g_recent_addrs.empty())return env->NewStringUTF("");
    std::string s=g_recent_addrs.front();g_recent_addrs.pop_front();
    return env->NewStringUTF(s.c_str());
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
    PKCS5_PBKDF2_HMAC(mn,(int)strlen(mn),(const uint8_t*)"mnemonic",8,2048,EVP_sha512(),64,seed);
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
    // Derive key
    uint8_t seed[64];
    PKCS5_PBKDF2_HMAC(mnemonic.c_str(),(int)mnemonic.size(),(const uint8_t*)"mnemonic",8,2048,EVP_sha512(),64,seed);
    HDKey hd; derive_path(ctx,seed,path.empty()?"m/44'/0'/0'/0/0":path.c_str(),&hd);
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
    std::string change_path = json_str(req,"change_path");
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

JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_isSequential(JNIEnv *env, jobject){
    return (jboolean)(g_sequential.load() != 0);
}

JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_getSeqProgress(JNIEnv *env, jobject){
    /* Retorna hex de posición actual */
    char hex[65] = {0};
    uint8_t pos[32];
    {std::lock_guard<std::mutex> lk(g_seq_mutex);
     memcpy(pos, g_seq_pos, 32);}
    for(int i=0;i<32;i++) sprintf(hex+i*2,"%02x",pos[i]);
    /* Quitar ceros a la izquierda */
    int start = 0;
    while(start < 63 && hex[start] == '0') start++;
    return env->NewStringUTF(hex+start);
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
    int dbits=bits/4+4; if(dbits<6) dbits=6; if(dbits>28) dbits=28;
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
    int tbits=bits/2+4-dbits;
    if(tbits<14) tbits=14;
    if(tbits>tope_tabla_bits) tbits=tope_tabla_bits;

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
    /* Guardar ANTES de liberar: si no, parar tiraba a la basura todo el trabajo
       de la sesion y la siguiente empezaba de cero. */
    if(g_kg_ruta[0])
        dp_save(&g_kg.tabla,g_kg_ruta,g_kg_pub,g_kg_ini,g_kg_fin,g_kg_dbits,
                g_kg_ops_previas+(uint64_t)g_kg.saltos.load());
    kg_free(&g_kg);
    g_kg_vivo=false;
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

/* Cuantos hilos estan caminando. CERO con la tabla viva es el modo recolector:
 * el maestro de un cluster junta los puntos de los trabajadores sin buscar.
 *
 * Hace falta porque kangarooRunning() dice que si en los dos casos —y tiene que
 * decirlo, porque de eso depende que el maestro acepte los puntos que le
 * mandan—, asi que sin esto la pantalla ensenaria "buscando a 0 op/s". */
extern "C" JNIEXPORT jint JNICALL
Java_com_hunter_btc_HunterEngine_kangarooHilos(JNIEnv *, jobject){
    return g_kg_vivo ? (jint)g_kg_hilos.size() : 0;
}

/* Huecos de la tabla de distinguidos. */
extern "C" JNIEXPORT jlong JNICALL
Java_com_hunter_btc_HunterEngine_kangarooCapacidad(JNIEnv *, jobject){
    return g_kg_vivo ? (jlong)(g_kg.tabla.mask+1) : 0;
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

/* Los puntos que aun no se han mandado. Cada llamada devuelve solo lo nuevo.
 * @return byte[] para mandar tal cual, o null si no hay nada.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hunter_btc_HunterEngine_kangarooExport(JNIEnv *env, jobject, jint max_ent){
    std::lock_guard<std::mutex> lk(g_kg_mtx);
    if(!g_kg_vivo) return NULL;
    if(max_ent<1) max_ent=1024;
    if(max_ent>8192) max_ent=8192;
    size_t cap=kg_export_bytes((uint32_t)max_ent);
    std::vector<uint8_t> buf(cap);
    size_t n=kg_export(&g_kg.tabla,g_kg_pub,g_kg_ini,g_kg_fin,g_kg_dbits,
                       buf.data(),cap,(uint32_t)max_ent);
    if(n==0) return NULL;
    jbyteArray out=env->NewByteArray((jsize)n);
    if(!out) return NULL;
    env->SetByteArrayRegion(out,0,(jsize)n,(const jbyte*)buf.data());
    return out;
}

/* Mete puntos que llegan de otro aparato. La cabecera lleva puzzle, rango y
 * dbits: si no cuadran con los de aqui, se rechaza el bloque entero.
 * @return cuantos han entrado, o -1 si el bloque no valia.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_hunter_btc_HunterEngine_kangarooImport(JNIEnv *env, jobject, jbyteArray jdatos){
    std::lock_guard<std::mutex> lk(g_kg_mtx);
    if(!g_kg_vivo || !jdatos) return -1;
    jsize len=env->GetArrayLength(jdatos);
    if(len<=0) return -1;
    std::vector<uint8_t> buf((size_t)len);
    env->GetByteArrayRegion(jdatos,0,len,(jbyte*)buf.data());
    uint32_t metidas=0;
    if(!kg_import(&g_kg,g_kg_pub,g_kg_ini,g_kg_fin,g_kg_dbits,
                  buf.data(),(size_t)len,&metidas)) return -1;
    return (jint)metidas;
}

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

/* ---------- tsnet empotrado ----------
 *
 * El nodo de Tailscale DENTRO de la app, en vez de depender de que la app de
 * Tailscale este instalada y encendida enrutando el movil entero.
 *
 * Todo esto va entre #ifdef porque libtailscale.so es OPCIONAL: la produce
 * scripts/build-libtailscale.sh, que mete la cadena de Go y 14 MB, y nada de
 * eso puede ser motivo de que una app que funciona deje de construirse. Sin
 * ella el APK compila igual y estas funciones contestan que no hay tsnet.
 *
 * Por eso existen las dos versiones de cada una: Kotlin declara sus external
 * fun una sola vez y tienen que resolverse SIEMPRE, haya o no libreria. Un
 * external fun sin simbolo detras no falla al compilar, falla al llamarlo, con
 * un UnsatisfiedLinkError en medio de la busqueda.
 */
#ifdef TIENE_TAILSCALE
#include "tailscale.h"

/* El nodo. Uno solo: la app es un aparato del tailnet, no varios. */
static int g_ts = -1;
static std::mutex g_ts_mtx;

/* El ultimo error que dio tsnet, para poder ensenarlo en vez de un numero. */
static std::string ts_error(int sd){
    char buf[512]; buf[0]=0;
    if(tailscale_errmsg(sd,buf,sizeof(buf))==0 && buf[0]) return std::string(buf);
    return std::string("sin detalle");
}
#endif

/* @return true si esta compilacion lleva tsnet dentro. */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_TsNet_disponible(JNIEnv *, jobject){
#ifdef TIENE_TAILSCALE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

/* Levanta el nodo y espera a que este autenticado.
 *
 * @param jdir  carpeta privada de la app donde tsnet guarda su estado. Tiene
 *   que ser escribible y sobrevivir entre arranques: ahi vive la identidad del
 *   nodo, y perderla obliga a volver a autorizarlo.
 * @return "" si todo bien, o el motivo. Se devuelve el TEXTO y no un codigo
 *   porque el que lo lee es el usuario: "clave caducada" se entiende y "-3" no.
 *
 * BLOQUEA hasta que el nodo esta arriba. Nunca desde el hilo principal.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_TsNet_arrancar(JNIEnv *env, jobject, jstring jclave,
                                   jstring jnombre, jstring jdir){
#ifndef TIENE_TAILSCALE
    return env->NewStringUTF("Esta compilacion no lleva tsnet dentro");
#else
    std::lock_guard<std::mutex> lk(g_ts_mtx);
    if(g_ts >= 0) return env->NewStringUTF("");      /* ya estaba */

    int sd = tailscale_new();
    if(sd < 0) return env->NewStringUTF("No se pudo crear el nodo");

    const char *dir = jdir ? env->GetStringUTFChars(jdir,0) : NULL;
    if(dir){ tailscale_set_dir(sd,dir); env->ReleaseStringUTFChars(jdir,dir); }
    const char *nom = jnombre ? env->GetStringUTFChars(jnombre,0) : NULL;
    if(nom){ tailscale_set_hostname(sd,nom); env->ReleaseStringUTFChars(jnombre,nom); }
    const char *cla = jclave ? env->GetStringUTFChars(jclave,0) : NULL;
    if(cla){ tailscale_set_authkey(sd,cla); env->ReleaseStringUTFChars(jclave,cla); }

    /* up() y no start(): start deja el nodo corriendo pero sin esperar a que
       este autorizado, y entonces el primer dial falla por una razon que no
       tiene nada que ver con la red. */
    if(tailscale_up(sd) != 0){
        std::string e = ts_error(sd);
        tailscale_close(sd);
        return env->NewStringUTF(e.c_str());
    }
    g_ts = sd;
    return env->NewStringUTF("");
#endif
}

/* Las direcciones del nodo en el tailnet, separadas por coma. "" si no hay. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_TsNet_direcciones(JNIEnv *env, jobject){
#ifndef TIENE_TAILSCALE
    return env->NewStringUTF("");
#else
    std::lock_guard<std::mutex> lk(g_ts_mtx);
    if(g_ts < 0) return env->NewStringUTF("");
    char buf[256]; buf[0]=0;
    if(tailscale_getips(g_ts,buf,sizeof(buf)) != 0) return env->NewStringUTF("");
    return env->NewStringUTF(buf);
#endif
}

/* Para el nodo. Idempotente. */
extern "C" JNIEXPORT void JNICALL
Java_com_hunter_btc_TsNet_parar(JNIEnv *, jobject){
#ifdef TIENE_TAILSCALE
    std::lock_guard<std::mutex> lk(g_ts_mtx);
    if(g_ts >= 0){ tailscale_close(g_ts); g_ts = -1; }
#endif
}

}
