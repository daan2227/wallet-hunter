#include <jni.h>
#include <android/log.h>
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

#define TAG "HunterJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define PBKDF2_ITERS  1
#define MAX_THREADS   8
#define LOCAL_BATCH   20
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
static char      g_csv_path[1024] = "";

static std::atomic<long>   g_count(0);
static std::atomic<long>   g_found(0);
static std::atomic<bool>   g_running(false);
static std::atomic<bool>   g_stop(false);
static std::atomic<double> g_wps(0.0);
static std::atomic<int>    g_cpu_limit(80);
static std::atomic<int>    g_nthreads(4);
static std::atomic<bool>   g_csv_loaded(false);
static std::atomic<bool>   g_loading(false);
static std::atomic<int>    g_mode(0); /* 0=BIP39 1=PUZZLE */

static uint8_t g_range_start[32] = {0};
static uint8_t g_range_end[32]   = {0};
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
static int64_t bsearch_h160(const uint8_t *t){
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

/* Genera clave privada aleatoria en [start, end] */
static int gen_privkey_range(uint8_t *out){
    BIGNUM *bn_s=BN_new(),*bn_e=BN_new(),*bn_r=BN_new(),*bn_rnd=BN_new();
    BN_CTX *ctx=BN_CTX_new();
    BN_bin2bn(g_range_start,32,bn_s);
    BN_bin2bn(g_range_end,  32,bn_e);
    BN_sub(bn_r,bn_e,bn_s);BN_add_word(bn_r,1);
    uint8_t rnd[32];
    FILE *ur=fopen("/dev/urandom","rb");
    if(ur){fread(rnd,1,32,ur);fclose(ur);}
    BN_bin2bn(rnd,32,bn_rnd);
    BN_mod(bn_rnd,bn_rnd,bn_r,ctx);
    BN_add(bn_rnd,bn_rnd,bn_s);
    memset(out,0,32);BN_bn2binpad(bn_rnd,out,32);
    BN_free(bn_s);BN_free(bn_e);BN_free(bn_r);BN_free(bn_rnd);BN_CTX_free(ctx);
    return 1;
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
    {std::lock_guard<std::mutex> lk(g_match_mutex);g_matches.push_back(oss.str());}
}

/* =========================================================
   Worker BIP39 (modo 0)
   ========================================================= */
typedef struct{int64_t idx;char mn[256];uint8_t pk[PRIVKEY_BYTES];int pi;}Hit;

static void *worker_bip39_fn(void *){
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    char mn[256];uint8_t seed[64],h160[HASH160_BYTES];
    Hit hits[LOCAL_BATCH*N_PATHS];int nhits=0;long local_done=0;
    while(!g_stop.load()){
        auto t0=std::chrono::high_resolution_clock::now();
        nhits=0;local_done=0;
        for(int bi=0;bi<LOCAL_BATCH&&!g_stop.load();bi++){
            gen_mnemonic(mn,sizeof(mn));
            PKCS5_PBKDF2_HMAC(mn,(int)strlen(mn),(const uint8_t*)"mnemonic",8,PBKDF2_ITERS,EVP_sha512(),64,seed);
            for(int pi=0;pi<N_PATHS&&!g_stop.load();pi++){
                HDKey hd;derive_path(ctx,seed,PATHS[pi],&hd);
                pk_to_h160(ctx,hd.key,h160);local_done++;
                {char atmp[MAX_ADDR]={0};h160_to_addr(h160,atmp);add_addr(std::string(atmp));}
                int64_t idx=bsearch_h160(h160);
                if(idx>=0){hits[nhits].idx=idx;strcpy(hits[nhits].mn,mn);memcpy(hits[nhits].pk,hd.key,PRIVKEY_BYTES);hits[nhits].pi=pi;nhits++;}
            }
        }
        double work_ms=std::chrono::duration<double,std::milli>(std::chrono::high_resolution_clock::now()-t0).count();
        int cpu=g_cpu_limit.load();
        if(cpu<100){double sl=work_ms*(100.0-cpu)/cpu;if(sl>0.5)std::this_thread::sleep_for(std::chrono::milliseconds((int)sl));}
        g_count.fetch_add(local_done);
        for(int i=0;i<nhits;i++){
            g_found.fetch_add(1);
            char addr[MAX_ADDR]={0},wif[60]={0},pkhex[65]={0},sats[24]={0},type_[12]={0};
            uint8_t h160b[20];pk_to_h160(ctx,hits[i].pk,h160b);h160_to_addr(h160b,addr);pk_to_wif(hits[i].pk,wif);
            for(int b=0;b<32;b++)sprintf(pkhex+b*2,"%02x",hits[i].pk[b]);
            uint8_t h160b2[20];pk_to_h160(ctx,hits[i].pk,h160b2);read_row_by_h160(h160b2,sats,type_);
            uint64_t satval=(uint64_t)strtoull(sats,NULL,10);double btc=satval/1e8;
            char extra[512];snprintf(extra,sizeof(extra),"SEED:%s PATH:%s PRIV:%s",hits[i].mn,PATHS[hits[i].pi],pkhex);
            save_match(pkhex,addr,btc,wif,extra);
        }
    }
    secp256k1_context_destroy(ctx);return nullptr;
}

/* =========================================================
   Worker PUZZLE (modo 1) - rango de clave privada
   ========================================================= */
static void *worker_puzzle_fn(void *){
    secp256k1_context *ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    uint8_t privkey[32],h160[HASH160_BYTES];
    long local_done=0;
    while(!g_stop.load()){
        auto t0=std::chrono::high_resolution_clock::now();
        local_done=0;
        for(int bi=0;bi<LOCAL_BATCH&&!g_stop.load();bi++){
            gen_privkey_range(privkey);
            if(!secp256k1_ec_seckey_verify(ctx,privkey)) continue;
            pk_to_h160(ctx,privkey,h160);
            local_done++;
            {char atmp[MAX_ADDR]={0};h160_to_addr(h160,atmp);add_addr(std::string(atmp));}
            int match=0;
            char sats_buf[24]="0"; char type_buf[12]="?";
            if(g_has_target){
                /* Comparar contra direccion objetivo */
                if(memcmp(h160,g_target_h160,HASH160_BYTES)==0) match=1;
            } else if(g_csv_loaded.load()){
                /* Fallback: buscar en CSV */
                int64_t idx=bsearch_h160(h160);
                if(idx>=0){match=1;read_row(idx,sats_buf,type_buf);}
            }
            if(match){
                g_found.fetch_add(1);
                char addr[MAX_ADDR]={0},wif[60]={0},pkhex[65]={0};
                h160_to_addr(h160,addr);pk_to_wif(privkey,wif);
                for(int b=0;b<32;b++)sprintf(pkhex+b*2,"%02x",privkey[b]);
                uint64_t satval=(uint64_t)strtoull(sats_buf,NULL,10);
                double btc=g_has_target?0.0:satval/1e8;
                char extra[128];snprintf(extra,sizeof(extra),"PRIV:%s",pkhex);
                save_match(pkhex,addr,btc,wif,extra);
                add_log(std::string("*** PUZZLE SOLVED *** ADDR:")+addr+" PRIV:"+pkhex);
            }
        }
        double work_ms=std::chrono::duration<double,std::milli>(std::chrono::high_resolution_clock::now()-t0).count();
        int cpu=g_cpu_limit.load();
        if(cpu<100){double sl=work_ms*(100.0-cpu)/cpu;if(sl>0.5)std::this_thread::sleep_for(std::chrono::milliseconds((int)sl));}
        g_count.fetch_add(local_done);
    }
    secp256k1_context_destroy(ctx);return nullptr;
}

static pthread_t g_workers[MAX_THREADS];
static int g_active=0;

/* =========================================================
   CSV loader
   ========================================================= */
static void *load_fn(void *){
    g_loading.store(true);snprintf(g_load_status,sizeof(g_load_status),"Opening CSV...");
    FILE *f=fopen(g_csv_path,"r");
    if(!f){snprintf(g_load_status,sizeof(g_load_status),"Error: could not open file");g_loading.store(false);return nullptr;}
    if(g_h160){free(g_h160);g_h160=nullptr;}g_total=0;g_csv_loaded.store(false);
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
}

JNIEXPORT void JNICALL
Java_com_hunter_btc_HunterEngine_startHunting(JNIEnv *,jobject,jint threads,jint cpuLimit){
    if(g_running.load())return;
    if(!g_csv_loaded.load()&&g_mode.load()!=1)return;
    g_nthreads.store(threads);g_cpu_limit.store(cpuLimit);
    g_stop.store(false);g_count.store(0);g_found.store(0);g_wps.store(0);
    g_last_count=0;g_last_wps_t=time(nullptr);
    g_start_time=time(nullptr);g_running.store(true);
    int n=threads>MAX_THREADS?MAX_THREADS:threads;
    void *(*fn)(void*) = (g_mode.load()==1) ? worker_puzzle_fn : worker_bip39_fn;
    for(int i=0;i<n;i++) pthread_create(&g_workers[i],nullptr,fn,nullptr);
    g_active=n;
    const char *modeStr=(g_mode.load()==1)?"PUZZLE":"BIP39";
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

} /* extern C */
