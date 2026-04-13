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
#include <thread>
#include <chrono>
#include <sstream>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <cmath>
#include <pthread.h>
#include <secp256k1.h>
#include <openssl/sha.h>
#include <openssl/hmac.h>
#include <openssl/evp.h>
#include <openssl/bn.h>
#include <openssl/ripemd.h>
#include "jac_batch.h"
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

static std::atomic<long>   g_count(0);
static std::atomic<long>   g_found(0);
static std::atomic<bool>   g_running(false);
static std::atomic<bool>   g_stop(false);
static std::atomic<double> g_wps(0.0);
static std::atomic<int>    g_cpu_limit(100);
static std::atomic<int>    g_batch_size(1); // minimo para debug
static std::atomic<int>    g_pbkdf2_iters(2048); /* 2048=standard, 1=fast */
static std::atomic<int>    g_nthreads(6);
static std::atomic<bool>   g_csv_loaded(false);
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
static void gen_mnemonic(char *out,size_t sz){
    uint8_t ent[16],h[32];
    FILE *r=fopen("/dev/urandom","rb");
    if(r){fread(ent,1,16,r);fclose(r);}
    SHA256(ent,16,h);uint8_t cs=h[0]>>4;uint32_t bits[132];int bi=0;
    for(int i=0;i<16;i++)for(int b=7;b>=0;b--)bits[bi++]=(ent[i]>>b)&1;
    for(int b=7;b>=4;b--)bits[bi++]=(cs>>b)&1;
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
static void derive_path(secp256k1_context *ctx,const uint8_t *s64,const char *path,HDKey *o){
    uint8_t seed[64];memcpy(seed,s64,64);derive_master(seed,o);
    const char *p=path;while(*p&&*p!='/')p++;
    while(*p=='/'){p++;uint32_t i=0;int h=0;while(*p>='0'&&*p<='9')i=i*10+(*p++-'0');if(*p=='\''){h=1;p++;}if(*p=='/')p++;HDKey c;derive_child(ctx,o,i+(h?0x80000000:0),&c);*o=c;}
}
static void pk_to_h160(secp256k1_context *ctx,const uint8_t *pk,uint8_t *out){uint8_t pub[33];get_pub33(ctx,pk,pub);uint8_t sha[32];SHA256(pub,33,sha);RIPEMD160(sha,32,out);}
static const char B58C[]="123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
static void b58enc(const uint8_t *pl,int plen,char *out,int osz){
    uint8_t buf[plen+4];memcpy(buf,pl,plen);uint8_t ck[32];SHA256_CTX sc;SHA256_Init(&sc);SHA256_Update(&sc,pl,plen);SHA256_Final(ck,&sc);SHA256_Init(&sc);SHA256_Update(&sc,ck,32);SHA256_Final(ck,&sc);memcpy(buf+plen,ck,4);
    BIGNUM *bn=BN_new(),*rem=BN_new(),*d58=BN_new();BN_CTX *bctx=BN_CTX_new();BN_bin2bn(buf,plen+4,bn);BN_set_word(d58,58);
    char tmp[64];int tlen=0;while(!BN_is_zero(bn)){BN_div(bn,rem,bn,d58,bctx);tmp[tlen++]=B58C[BN_get_word(rem)];}
    for(int i=0;i<plen+4&&buf[i]==0;i++)tmp[tlen++]='1';int ol=0;for(int i=tlen-1;i>=0&&ol<osz-1;i--)out[ol++]=tmp[i];out[ol]='\0';BN_free(bn);BN_free(rem);BN_free(d58);BN_CTX_free(bctx);
}
static void h160_to_addr(const uint8_t *h,char *a){uint8_t v[21];v[0]=0;memcpy(v+1,h,20);b58enc(v,21,a,MAX_ADDR);}
static void pk_to_wif(const uint8_t *k,char *w){uint8_t v[34];v[0]=0x80;memcpy(v+1,k,32);v[33]=1;b58enc(v,34,w,60);}
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
static const char *BECH32_CHARSET="qpzry9x8gf2tvdw0s3jn54khce6mua7l";
static uint32_t bech32_polymod(const uint8_t *v,int vlen){
    uint32_t c=1;
    for(int i=0;i<vlen;i++){
        uint8_t d=c>>25; c=((c&0x1ffffff)<<5)^v[i];
        if(d&1)c^=0x3b6a57b2; if(d&2)c^=0x26508e6d;
        if(d&4)c^=0x1ea119fa; if(d&8)c^=0x3d4233dd; if(d&16)c^=0x2a1462b3;
    }
    return c;
}

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

/* Bech32m encoding for P2TR (witness version 1, 32-byte program) */
static void xonly_to_p2tr(const uint8_t *xonly32, char *out) {
    /* bech32m: same as bech32 but checksum constant = 0x2bc830a3 */
    const char *CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    /* Convert 32 bytes to 5-bit groups */
    uint8_t d5[65]; int nd5=0;
    /* witness version 0x01 first */
    d5[nd5++]=1;
    int acc=0,bits=0;
    for(int i=0;i<32;i++){
        acc=(acc<<8)|xonly32[i]; bits+=8;
        while(bits>=5){bits-=5;d5[nd5++]=(acc>>bits)&31;}
    }
    if(bits>0) d5[nd5++]=(acc<<(5-bits))&31;
    /* Build HRP + data for checksum */
    const char *hrp="bc";
    int hrplen=2;
    /* polymod */
    uint32_t GEN[5]={0x3b6a57b2,0x26508e6d,0x1ea119fa,0x3d4233dd,0x2a1462b3};
    uint32_t chk=1;
    auto polymod_step=[&](uint8_t v){
        uint8_t b=(chk>>25)&0x1f;
        chk=((chk&0x1ffffff)<<5)^v;
        for(int i=0;i<5;i++) if((b>>i)&1) chk^=GEN[i];
    };
    for(int i=0;i<hrplen;i++) polymod_step(hrp[i]>>5);
    polymod_step(0);
    for(int i=0;i<hrplen;i++) polymod_step(hrp[i]&31);
    for(int i=0;i<nd5;i++) polymod_step(d5[i]);
    for(int i=0;i<6;i++) polymod_step(0);
    chk ^= 0x2bc830a3; /* bech32m constant */
    /* Write output */
    char *o=out;
    *o++='b';*o++='c';*o++='1';*o++='p'; /* bc1p prefix for version 1 */
    for(int i=1;i<nd5;i++) *o++=CHARSET[d5[i]]; /* skip version byte, already in prefix */
    for(int i=0;i<6;i++) *o++=CHARSET[(chk>>(5*(5-i)))&31];
    *o=0;
}

/* Decode bech32m bc1p address to x-only pubkey (32 bytes) */
static int p2tr_addr_to_xonly(const char *addr, uint8_t *xonly32) {
    if(addr[0]!='b'||addr[1]!='c'||addr[2]!='1'||addr[3]!='p') return 0;
    const char *CHARSET="qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    /* Find separator */
    const char *p=addr+4; /* skip bc1p */
    int nch=0; while(p[nch] && nch<100) nch++;
    nch-=6; /* remove checksum */
    if(nch<52) return 0;
    /* Decode 5-bit values (skip witness version in bc1p prefix) */
    std::vector<int> d5;
    for(int i=0;i<nch;i++){
        const char *c=strchr(CHARSET,tolower(p[i]));
        if(!c) return 0;
        d5.push_back((int)(c-CHARSET));
    }
    /* Convert 5-bit to 8-bit */
    int acc=0,bits=0; int idx=0;
    for(int v:d5){
        acc=(acc<<5)|v; bits+=5;
        while(bits>=8){bits-=8;if(idx<32)xonly32[idx++]=(acc>>bits)&0xff;}
    }
    return (idx==32)?1:0;
}

static void h160_to_bech32(const uint8_t *h160, char *out){
    /* Convert 20 bytes to 5-bit groups (32 values) */
    uint8_t d5[33]; /* witness version 0 + 32 data values */
    d5[0]=0; /* witness version */
    uint32_t acc=0; int bits=0; int idx=1;
    for(int i=0;i<20;i++){
        acc=(acc<<8)|h160[i]; bits+=8;
        while(bits>=5){ bits-=5; d5[idx++]=(acc>>bits)&31; }
    }
    if(bits>0) d5[idx++]=(acc<<(5-bits))&31;
    /* Checksum */
    const char *hrp="bc"; int hrplen=2;
    uint8_t enc[hrplen+1+idx+6+1];
    int p=0;
    for(int i=0;i<hrplen;i++) enc[p++]=(uint8_t)hrp[i]>>5;
    enc[p++]=0;
    for(int i=0;i<hrplen;i++) enc[p++]=(uint8_t)hrp[i]&31;
    for(int i=0;i<idx;i++) enc[p++]=d5[i];
    for(int i=0;i<6;i++) enc[p++]=0;
    uint32_t mod=bech32_polymod(enc,p)^1;
    char *o=out; for(int i=0;i<hrplen;i++) *o++=hrp[i]; *o++='1';
    for(int i=0;i<idx;i++) *o++=BECH32_CHARSET[d5[i]];
    for(int i=0;i<6;i++) *o++=BECH32_CHARSET[(mod>>(5*(5-i)))&31];
    *o='\0';
}
static void h160_to_p2sh(const uint8_t *h160, char *out){
    /* P2SH-P2WPKH: redeem = 0x0014 + h160, then hash160 of redeem */
    uint8_t redeem[22]; redeem[0]=0x00; redeem[1]=0x14; memcpy(redeem+2,h160,20);
    uint8_t sha[32],rh[20];
    SHA256(redeem,22,sha); RIPEMD160(sha,32,rh);
    uint8_t v[21]; v[0]=0x05; memcpy(v+1,rh,20);
    b58enc(v,21,out,MAX_ADDR);
}
/* Derive wallet: returns JSON string with 8 addresses */
static std::string derive_wallet_json(const char *mnemonic){
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    uint8_t seed[64];
    PKCS5_PBKDF2_HMAC(mnemonic,(int)strlen(mnemonic),(const uint8_t*)"mnemonic",8,2048,EVP_sha512(),64,seed);
    HDKey master; derive_master(seed,&master);
    std::string json="{";
    /* BIP44: m/44'/0'/0'/0/0-2 */
    HDKey h44,h44_0,h44_00,h44_ext;
    derive_child(ctx,&master,0x80000000u+44,&h44);
    derive_child(ctx,&h44,0x80000000u+0,&h44_0);
    derive_child(ctx,&h44_0,0x80000000u+0,&h44_00);
    derive_child(ctx,&h44_00,0,&h44_ext);
    for(int i=0;i<3;i++){
        HDKey leaf; derive_child(ctx,&h44_ext,i,&leaf);
        uint8_t h160[20]; pk_to_h160(ctx,leaf.key,h160);
        char addr[MAX_ADDR]={0}; h160_to_addr(h160,addr);
        char key[32]; snprintf(key,32,"\"p2pkh_%d\"",i);
        json+=key; json+=":\""; json+=addr; json+="\",";
    }
    /* BIP49: m/49'/0'/0'/0/0 */
    HDKey h49,h49_0,h49_00,h49_ext,h49_leaf;
    derive_child(ctx,&master,0x80000000u+49,&h49);
    derive_child(ctx,&h49,0x80000000u+0,&h49_0);
    derive_child(ctx,&h49_0,0x80000000u+0,&h49_00);
    derive_child(ctx,&h49_00,0,&h49_ext);
    derive_child(ctx,&h49_ext,0,&h49_leaf);
    {uint8_t h160[20]; pk_to_h160(ctx,h49_leaf.key,h160);
     char addr[MAX_ADDR]={0}; h160_to_p2sh(h160,addr);
     json+="\"p2sh_0\":\""; json+=addr; json+="\",";}
    /* BIP84: m/84'/0'/0'/0/0-1 */
    HDKey h84,h84_0,h84_00,h84_ext;
    derive_child(ctx,&master,0x80000000u+84,&h84);
    derive_child(ctx,&h84,0x80000000u+0,&h84_0);
    derive_child(ctx,&h84_0,0x80000000u+0,&h84_00);
    derive_child(ctx,&h84_00,0,&h84_ext);
    for(int i=0;i<2;i++){
        HDKey leaf; derive_child(ctx,&h84_ext,i,&leaf);
        uint8_t h160[20]; pk_to_h160(ctx,leaf.key,h160);
        char addr[MAX_ADDR]={0}; h160_to_bech32(h160,addr);
        char key[32]; snprintf(key,32,"\"p2wpkh_%d\"",i);
        json+=key; json+=":\""; json+=addr; json+="\",";
    }
    /* BIP86: m/86'/0'/0'/0/0-1 P2TR */
    HDKey h86,h86_0,h86_00,h86_ext;
    derive_child(ctx,&master,0x80000000u+86,&h86);
    derive_child(ctx,&h86,0x80000000u+0,&h86_0);
    derive_child(ctx,&h86_0,0x80000000u+0,&h86_00);
    derive_child(ctx,&h86_00,0,&h86_ext);
    for(int i=0;i<2;i++){
        HDKey leaf; derive_child(ctx,&h86_ext,i,&leaf);
        uint8_t pub33[33]; get_pub33(ctx,leaf.key,pub33);
        uint8_t xonly[32]; memcpy(xonly,pub33+1,32);
        uint8_t tweaked[32];
        if(taproot_tweak_pubkey(ctx,xonly,tweaked)){
            char addr[MAX_ADDR]={0}; xonly_to_p2tr(tweaked,addr);
            char key[32]; snprintf(key,32,"\"p2tr_%d\"",i);
            json+=key; json+=":""; json+=addr; json+="",";
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
    /* Encontrar byte mas alto */
    g_range_bits=0;
    for(int i=0;i<32;i++){
        if(diff[i]){
            g_range_bits=(32-i)*8;
            uint8_t b=diff[i];
            while(b>>=1) g_range_bits--;
            g_range_bits++;
            /* mascara para el byte superior del rango */
            int top_byte=32-(g_range_bits+7)/8;
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

static void gen_privkey_fast(uint8_t *out, XR128 *rng){
    /* Copiar start como base */
    memcpy(out, g_range_start, 32);
    /* Generar bytes aleatorios para los bits del rango */
    int range_bytes = (g_range_bits+7)/8;
    int top_idx     = 32 - range_bytes;
    /* Llenar con xorshift128+ */
    uint64_t r;
    for(int i=31; i>=top_idx; i-=8){
        r=xr_next(rng);
        for(int j=0;j<8&&(i-j)>=top_idx;j++)
            out[i-j]=(uint8_t)(r>>(j*8));
    }
    /* Aplicar mascara al byte superior para no salir del rango */
    out[top_idx] &= g_range_mask;
    /* Sumar start con carry */
    int carry=0;
    for(int i=31;i>=0;i--){
        int s=(int)out[i]+(int)g_range_start[i]+carry;
        out[i]=(uint8_t)(s&0xFF); carry=s>>8;
    }
    /* Si supera end, usar start (raro) */
    if(memcmp(out, g_range_end, 32)>0)
        memcpy(out, g_range_start, 32);
}

/* Guardar match */
static void save_match(const char *privhex, const char *addr, double btc, const char *wif, const char *extra){
    std::string outpath=std::string(g_csv_path);
    size_t sl=outpath.rfind('/');
    if(sl!=std::string::npos) outpath=outpath.substr(0,sl+1)+"coincidencias.txt";
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

static void *worker_bip39_fn(void *){
    set_thread_affinity(0);
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
            /* --- Shared subtree m/44'/0'/0' --- */
            HDKey h44,h44_0,h44_0_0;
            derive_child(ctx,&master,0x80000000u+44,&h44);
            derive_child(ctx,&h44,0x80000000u+0,&h44_0);
            derive_child(ctx,&h44_0,0x80000000u+0,&h44_0_0);
            /* m/44'/0'/0'/0/0 only */
            HDKey h44_ch0,h44_leaf;
            derive_child(ctx,&h44_0_0,0,&h44_ch0);
            derive_child(ctx,&h44_ch0,0,&h44_leaf);
            pk_to_h160(ctx,h44_leaf.key,h160); local_done++;
            {int64_t ix=bsearch_h160(h160);if(ix>=0){hits[nhits].idx=ix;strcpy(hits[nhits].mn,mn);memcpy(hits[nhits].pk,h44_leaf.key,PRIVKEY_BYTES);hits[nhits].pi=0;nhits++;}}
            /* BIP49 skipped — p2sh not in dataset */
            /* --- m/84'/0'/0'/0/0 --- */
            HDKey h84,h84_0,h84_00,h84_000,h84_leaf;
            derive_child(ctx,&master,0x80000000u+84,&h84);
            derive_child(ctx,&h84,0x80000000u+0,&h84_0);
            derive_child(ctx,&h84_0,0x80000000u+0,&h84_00);
            derive_child(ctx,&h84_00,0,&h84_000);
            derive_child(ctx,&h84_000,0,&h84_leaf);
            pk_to_h160(ctx,h84_leaf.key,h160); local_done++;
            {int64_t ix=bsearch_h160(h160);if(ix>=0){hits[nhits].idx=ix;strcpy(hits[nhits].mn,mn);memcpy(hits[nhits].pk,h84_leaf.key,PRIVKEY_BYTES);hits[nhits].pi=6;nhits++;}}
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
    uint8_t sha[32],h160[HASH160_BYTES];
    SHA256(pub33,33,sha); RIPEMD160(sha,32,h160);
    c->done++;
    if(c->done%500==0){char atmp[MAX_ADDR]={0};h160_to_addr(h160,atmp);add_addr(std::string(atmp));}
    int match=0; char sats_buf[24]="0"; char type_buf[12]="?";
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
   Logica del hunter_master.cpp:
   - Inicio aleatorio por thread
   - Incremento secuencial (+1) para aprovechar tweak_add
   - unordered lookup via bloom+bsearch
   - secp256k1_ec_pubkey_tweak_add para evitar mul escalar
   ========================================================= */

/* Hash table para lookup O(1) - complementa bloom+bsearch */
static const uint8_t G_SCALAR[32] = {
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1
}; /* scalar = 1, representa sumar G */

static void *worker_rawkey_fn(void *){
    set_thread_affinity(0);
    secp256k1_context *ctx = secp256k1_context_create(
        SECP256K1_CONTEXT_SIGN | SECP256K1_CONTEXT_VERIFY);
    if(!ctx) return nullptr;

    XR128 rng; xr_init(&rng);

    /* Inicio aleatorio para este thread - igual que hunter_master */
    uint8_t priv[32];
    uint64_t r0=xr_next(&rng),r1=xr_next(&rng),
             r2=xr_next(&rng),r3=xr_next(&rng);
    memcpy(priv,    &r0, 8); memcpy(priv+8,  &r1, 8);
    memcpy(priv+16, &r2, 8); memcpy(priv+24, &r3, 8);
    /* Asegurar clave válida */
    while(!secp256k1_ec_seckey_verify(ctx, priv)){
        for(int b=31;b>=0;b--){if(++priv[b])break;}
    }

    /* Crear pubkey inicial - 1 mul escalar */
    secp256k1_pubkey pubkey;
    secp256k1_ec_pubkey_create(ctx, &pubkey, priv);

    uint8_t pub33[33];
    uint8_t h160[HASH160_BYTES];

    while(!g_stop.load()){
        auto t0 = std::chrono::high_resolution_clock::now();
        int batch = g_batch_size.load();
        if(batch < 1) batch = 1;
        if(batch > 16000) batch = 16000;

        for(int i=0; i<batch && !g_stop.load(); i++){
            /* Incremento secuencial - como hunter_master */
            for(int b=31;b>=0;b--){if(++priv[b])break;}

            /* Sumar G al pubkey - MUCHO mas rapido que mul escalar */
            if(!secp256k1_ec_pubkey_tweak_add(ctx, &pubkey, G_SCALAR)){
                /* Overflow muy raro - regenerar */
                secp256k1_ec_pubkey_create(ctx, &pubkey, priv);
            }

            /* Serializar comprimida */
            size_t plen = 33;
            secp256k1_ec_pubkey_serialize(ctx, pub33, &plen, &pubkey,
                SECP256K1_EC_COMPRESSED);

            /* Hash160 */
            hash160_inline(pub33, h160);

            /* Address feed */
            if((i & 0x3FF) == 0){ /* cada 1024 */
                char atmp[MAX_ADDR]={0};
                h160_to_addr(h160, atmp);
                add_addr(std::string(atmp));
            }

            /* Checkpoint */
            if((i & 0x1FFF) == 0){ /* cada 8192 */
                std::lock_guard<std::mutex> lk(g_last_key_mutex);
                memcpy(g_last_key, priv, 32);
            }

            /* Lookup */
            int match = 0;
            if(g_has_target){
                if(memcmp(h160, g_target_h160, HASH160_BYTES)==0) match=1;
            } else if(g_csv_loaded.load()){
                if(bsearch_h160(h160)>=0) match=1;
            }

            if(match){
                g_found.fetch_add(1);
                char addr[MAX_ADDR]={0},wif[60]={0},pkhex[65]={0};
                h160_to_addr(h160,addr);
                pk_to_wif(priv,wif);
                for(int b=0;b<32;b++) sprintf(pkhex+b*2,"%02x",priv[b]);
                char extra[128];
                snprintf(extra,sizeof(extra),"RAW_PRIV:%s",pkhex);
                save_match(pkhex,addr,0.0,wif,extra);
                add_log(std::string("*** RAW KEY MATCH *** ADDR:")+addr+" PRIV:"+pkhex);
            }
        }

        g_count.fetch_add(batch);

        double work_ms = std::chrono::duration<double,std::milli>(
            std::chrono::high_resolution_clock::now()-t0).count();
        int cpu = g_cpu_limit.load();
        if(cpu<100){
            double sl=work_ms*(100.0-cpu)/cpu;
            if(sl>0.5) std::this_thread::sleep_for(
                std::chrono::milliseconds((int)sl));
        }
    }

    secp256k1_context_destroy(ctx);
    return nullptr;
}


static void *worker_puzzle_fn(void *){
    set_thread_affinity(0);
    secp256k1_context *ctx = secp256k1_context_create(
        SECP256K1_CONTEXT_SIGN | SECP256K1_CONTEXT_VERIFY);
    if(!ctx) return nullptr;

    const int MAX_SAFE = JAC_BATCH;
    JP *pts = (JP*)malloc(MAX_SAFE * sizeof(JP));
    if(!pts){ secp256k1_context_destroy(ctx); return nullptr; }

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
            /* Avanzar posición para el próximo thread */
            int cur_b = g_batch_size.load();
            for(int step = 0; step < cur_b; step++) {
                for(int b = 31; b >= 0; b--) { if(++g_seq_pos[b]) break; }
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
        jac_batch_hash160(pts, actual, puzzle_on_key, &pctx);

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

    free(pts);
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
    if(!g_csv_loaded.load()&&g_mode.load()!=1&&g_mode.load()!=2)return;
    g_nthreads.store(threads);g_cpu_limit.store(cpuLimit);
    g_stop.store(false);g_count.store(0);g_found.store(0);g_wps.store(0);
    g_last_count=0;g_last_wps_t=time(nullptr);
    g_start_time=time(nullptr);g_running.store(true);
    int n=threads>MAX_THREADS?MAX_THREADS:threads;
    void *(*fn)(void*) = (g_mode.load()==1) ? worker_puzzle_fn :
                          (g_mode.load()==2) ? worker_rawkey_fn : worker_bip39_fn;
    for(int i=0;i<n;i++) pthread_create(&g_workers[i],nullptr,fn,nullptr);
    g_active=n;
    const char *modeStr=(g_mode.load()==1)?"PUZZLE":(g_mode.load()==2)?"RAWKEY":"BIP39";
    add_log(std::string("Started | mode:")+modeStr+" | threads:"+std::to_string(n)+" | CPU:"+std::to_string(cpuLimit)+"%");
}

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_stopHunting(JNIEnv *,jobject){
    if(!g_running.load())return;
    g_stop.store(true);
    std::thread([]{
        for(int i=0;i<g_active;i++) pthread_join(g_workers[i],nullptr);
        g_running.store(false);g_active=0;
        add_log("Stopped | total:"+std::to_string(g_count.load())+" | matches:"+std::to_string(g_found.load()));
    }).detach();
}

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_setCpuLimit(JNIEnv *,jobject,jint v){g_cpu_limit.store(v);}

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_setPbkdf2Mode(JNIEnv *,jobject,jint fast){
    g_pbkdf2_iters.store(fast==1 ? PBKDF2_ITERS_FAST : PBKDF2_ITERS_STD);
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
static bool wif_decode(const char *wif, uint8_t *privkey) {
    // Base58 decode into fixed 256-byte buffer (big-endian result)
    uint8_t buf[64] = {0};
    size_t wlen = strlen(wif);
    for (size_t i = 0; i < wlen; i++) {
        const char *p = strchr(B58_ALPHA, wif[i]);
        if (!p) return false;
        int carry = (int)(p - B58_ALPHA);
        for (int j = 63; j >= 0; j--) {
            carry += 58 * buf[j];
            buf[j] = (uint8_t)(carry & 0xff);
            carry >>= 8;
        }
    }
    // The decoded payload is right-aligned in buf
    // WIF structure: 0x80 + privkey(32) + [0x01 if compressed] + checksum(4)
    // Total payload: 37 (uncompressed) or 38 (compressed)
    // privkey starts at byte index 1 from start of payload
    // Find start of payload in buf (skip leading zero bytes but keep version)
    int start = 0;
    for (int i = 0; i < 64 - 33; i++) {
        if (buf[i] == 0x80) { start = i; break; }
    }
    // privkey is right after version byte
    if (start + 33 > 64) return false;
    memcpy(privkey, buf + start + 1, 32);
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_deriveWallet(JNIEnv *env, jobject, jstring jmn){
    const char *mn=env->GetStringUTFChars(jmn,nullptr);
    std::string result=derive_wallet_json(mn);
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
static std::string sha256d(const std::string &data){
    uint8_t h1[32],h2[32];
    SHA256((const uint8_t*)data.data(),data.size(),h1);
    SHA256(h1,32,h2);
    return std::string((char*)h2,32);
}
/* Parse simple JSON string field */
static std::string json_str(const std::string &j,const char *key){
    std::string k=std::string("\"")+key+"\":\"";
    size_t p=j.find(k); if(p==std::string::npos)return "";
    p+=k.size(); size_t e=j.find('"',p); if(e==std::string::npos)return "";
    return j.substr(p,e-p);
}
static int64_t json_int(const std::string &j,const char *key){
    std::string k=std::string("\"")+key+"\":";
    size_t p=j.find(k); if(p==std::string::npos)return 0;
    p+=k.size(); return (int64_t)strtoll(j.c_str()+p,nullptr,10);
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

static std::string build_and_sign_tx(const std::string &req){
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    std::string mnemonic=json_str(req,"mnemonic");
    std::string path=json_str(req,"path");
    std::string to_addr=json_str(req,"to");
    int64_t send_sat=json_int(req,"amount");
    int64_t fee_sat=json_int(req,"fee");
    // tx type from path
    bool is_segwit = (path.find("84'")==std::string::npos)?false:true;
    bool is_p2sh   = (path.find("49'")==std::string::npos)?false:true;
    // Derive key
    uint8_t seed[64];
    PKCS5_PBKDF2_HMAC(mnemonic.c_str(),(int)mnemonic.size(),(const uint8_t*)"mnemonic",8,2048,EVP_sha512(),64,seed);
    HDKey hd; derive_path(ctx,seed,path.empty()?"m/44'/0'/0'/0/0":path.c_str(),&hd);
    uint8_t pub33[33]; get_pub33(ctx,hd.key,pub33);
    uint8_t h160[20]; pk_to_h160(ctx,hd.key,h160);
    // my scriptPubKey
    std::string spk_me;
    if(is_segwit){
        spk_me+='\x00'; spk_me+='\x14'; spk_me+=std::string((char*)h160,20);
    } else {
        spk_me+='\x76'; spk_me+='\xa9'; spk_me+='\x14';
        spk_me+=std::string((char*)h160,20); spk_me+='\x88'; spk_me+='\xac';
    }
    // parse UTXOs
    struct UTXO { std::string txid; uint32_t vout; int64_t amount; };
    std::vector<UTXO> utxos;
    size_t ap=req.find("\"utxos\":[");
    if(ap!=std::string::npos){
        ap+=9;
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
    bool has_change=(change>546);
    // Build outputs bytes (shared for sighash)
    std::string outs_bytes;
    outs_bytes+=uint64_le(send_sat); outs_bytes+=varint(to_spk.size()); outs_bytes+=to_spk;
    if(has_change){outs_bytes+=uint64_le(change);outs_bytes+=varint(spk_me.size());outs_bytes+=spk_me;}
    std::vector<std::string> sigs;
    if(is_segwit){
        // BIP143
        // hashPrevouts
        std::string all_prevouts;
        for(auto &u:utxos){all_prevouts+=reverse_bytes(hex_decode(u.txid));all_prevouts+=uint32_le(u.vout);}
        std::string hPrevouts=sha256d(all_prevouts);
        // hashSequence
        std::string all_seq;
        for(size_t i=0;i<utxos.size();i++) all_seq+=uint32_le(0xFFFFFFFF);
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
            pre+=uint32_le(0xFFFFFFFF);
            pre+=hOutputs;
            pre+=uint32_le(0); // locktime
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
        for(auto &u:utxos){
            tx+=reverse_bytes(hex_decode(u.txid));
            tx+=uint32_le(u.vout);
            tx+='\x00'; // empty scriptSig for segwit
            tx+=uint32_le(0xFFFFFFFF);
        }
        tx+=varint(has_change?2:1);
        tx+=outs_bytes;
        // witness for each input
        for(size_t i=0;i<utxos.size();i++){
            tx+='\x02'; // 2 witness items
            tx+=varint(sigs[i].size()); tx+=sigs[i];
            tx+=(char)33; tx+=std::string((char*)pub33,33);
        }
        tx+=uint32_le(0);
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
                pre+=uint32_le(0xFFFFFFFF);
            }
            pre+=varint(has_change?2:1);
            pre+=outs_bytes;
            pre+=uint32_le(0);
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
            tx+=uint32_le(0xFFFFFFFF);
        }
        tx+=varint(has_change?2:1);
        tx+=outs_bytes;
        tx+=uint32_le(0);
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

}