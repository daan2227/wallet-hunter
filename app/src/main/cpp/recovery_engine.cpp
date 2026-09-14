#include <jni.h>
#include <string>
#include <vector>
#include <unordered_map>
#include <cstring>
#include <thread>
#include <mutex>
#include <atomic>
#include <android/log.h>
#include <openssl/sha.h>
#include <openssl/ripemd.h>
#include <openssl/hmac.h>
#include <openssl/evp.h>
#include <secp256k1.h>
#include "mnemonic.h"
#include "bip32.h"

#define LOG_TAG "RecoveryEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define MAX_ADDR 64
/* Índices de dirección a probar por candidato (m/purpose'/0'/0'/0/0..N).
   Antes sólo se probaba el 0, así que si la dirección conocida no era la
   primera de la wallet no había forma de encontrarla. Cada índice extra
   multiplica el coste por candidato, así que se mantiene bajo. */
#define MAX_ADDRESS_INDEX 4

// ── Base58 + address (auto-contenido) ────────────────────────────────────────
static const char B58C[]="123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

static void b58enc_local(const uint8_t* data,size_t len,char* out,size_t out_sz){
    uint8_t buf[64]={0};size_t blen=sizeof(buf);
    int zeros=0;while(zeros<(int)len&&data[zeros]==0)zeros++;
    for(size_t i=zeros;i<len;i++){
        int carry=data[i];
        for(int j=(int)blen-1;j>=0;j--){carry+=256*buf[j];buf[j]=carry%58;carry/=58;}
    }
    size_t start=0;while(start<blen&&buf[start]==0)start++;
    size_t out_len=zeros+(blen-start);
    if(out_len>=out_sz){out[0]=0;return;}
    for(int i=0;i<zeros;i++)out[i]='1';
    for(size_t i=start;i<blen;i++)out[zeros+(i-start)]=B58C[buf[i]];
    out[out_len]='\0';
}

static void privkey_to_addr(secp256k1_context* ctx,const uint8_t* pk,char* addr_out){
    secp256k1_pubkey pubkey;
    secp256k1_ec_pubkey_create(ctx,&pubkey,pk);
    uint8_t pub[33];size_t pub_len=33;
    secp256k1_ec_pubkey_serialize(ctx,pub,&pub_len,&pubkey,SECP256K1_EC_COMPRESSED);
    uint8_t sha[32],h160[20];
    SHA256(pub,33,sha);RIPEMD160(sha,32,h160);
    uint8_t payload[25];payload[0]=0x00;memcpy(payload+1,h160,20);
    uint8_t tmp[32],chk[32];SHA256(payload,21,tmp);SHA256(tmp,32,chk);
    memcpy(payload+21,chk,4);
    b58enc_local(payload,25,addr_out,MAX_ADDR);
}

/* ── Tipo de dirección objetivo ───────────────────────────────────────────────
   privkey_to_addr sólo generaba P2PKH (version 0x00), así que una wallet
   SegWit nunca producía coincidencia por correcta que fuese la seed. Aquí se
   detecta el tipo del objetivo y se deriva la ruta que le corresponde,
   comparando hash160 en binario en lugar de reconstruir la cadena Base58. */
enum AddrKind { AK_P2PKH=0, AK_P2SH=1, AK_P2WPKH=2, AK_UNKNOWN=99 };

static uint32_t purpose_for(AddrKind k){
    switch(k){
        case AK_P2SH:   return 49;   /* m/49' → P2SH-P2WPKH "3..." */
        case AK_P2WPKH: return 84;   /* m/84' → P2WPKH      "bc1q..." */
        default:        return 44;   /* m/44' → P2PKH       "1..." */
    }
}

static void hash160_of(const uint8_t* data,size_t len,uint8_t out[20]){
    uint8_t sha[32];
    SHA256(data,len,sha);
    RIPEMD160(sha,32,out);
}

/* hash160 esperado para un candidato, según el tipo de dirección objetivo. */
static void candidate_h160(secp256k1_context* ctx,const uint8_t* pk,
                           AddrKind kind,uint8_t out[20]){
    secp256k1_pubkey pubkey; memset(&pubkey,0,sizeof(pubkey));
    if(!secp256k1_ec_pubkey_create(ctx,&pubkey,pk)){ memset(out,0,20); return; }
    uint8_t pub[33]; size_t plen=33;
    secp256k1_ec_pubkey_serialize(ctx,pub,&plen,&pubkey,SECP256K1_EC_COMPRESSED);

    uint8_t h160[20];
    hash160_of(pub,33,h160);

    if(kind==AK_P2SH){
        /* P2SH-P2WPKH: el hash del script redentor 0x0014<h160> */
        uint8_t script[22]; script[0]=0x00; script[1]=0x14;
        memcpy(script+2,h160,20);
        hash160_of(script,22,out);
    }else{
        /* P2PKH y P2WPKH comparten hash160(pubkey comprimida) */
        memcpy(out,h160,20);
    }
}

/* Decodifica Bech32 (sólo v0, 20 bytes → P2WPKH) verificando el checksum. */
static bool bech32_program(const std::string& addr,uint8_t out[20]){
    static const char* CS="qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    std::string lower; lower.reserve(addr.size());
    for(char c:addr) lower += (char)tolower((unsigned char)c);
    size_t sep=lower.rfind('1');
    if(sep==std::string::npos||sep<1) return false;
    std::string hrp=lower.substr(0,sep);
    if(hrp!="bc"&&hrp!="tb") return false;

    std::vector<int> data;
    for(size_t i=sep+1;i<lower.size();i++){
        const char* p=strchr(CS,lower[i]);
        if(!p) return false;
        data.push_back((int)(p-CS));
    }
    if(data.size()<7) return false;

    /* polymod BCH sobre hrp expandido + datos */
    static const uint32_t GEN[5]={0x3b6a57b2u,0x26508e6du,0x1ea119fau,0x3d4233ddu,0x2a1462b3u};
    std::vector<int> values;
    for(char c:hrp) values.push_back((unsigned char)c>>5);
    values.push_back(0);
    for(char c:hrp) values.push_back((unsigned char)c&31);
    for(int v:data) values.push_back(v);
    uint32_t chk=1;
    for(int v:values){
        uint32_t b=chk>>25;
        chk=((chk&0x1ffffffu)<<5)^(uint32_t)v;
        for(int i=0;i<5;i++) if((b>>i)&1) chk^=GEN[i];
    }
    if(data[0]!=0||chk!=1u) return false;   /* sólo witness v0 (bech32) */

    /* 5 bits → 8 bits sobre el programa (sin la versión ni el checksum) */
    uint32_t acc=0; int bits=0; std::vector<uint8_t> prog;
    for(size_t i=1;i+6<data.size();i++){
        acc=(acc<<5)|(uint32_t)data[i]; bits+=5;
        while(bits>=8){ bits-=8; prog.push_back((uint8_t)((acc>>bits)&0xff)); }
    }
    if(prog.size()!=20) return false;
    memcpy(out,prog.data(),20);
    return true;
}

// ── Precalcular fingerprint de la dirección objetivo ─────────────────────────
// Convierte dirección Base58 → h160 (20 bytes)
// Luego usamos los primeros 4 bytes como fingerprint rápido
static bool addr_to_h160_local(const std::string& addr, uint8_t h160_out[20]){
    // Decodificar Base58Check
    static const char* B58="123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    uint8_t decoded[25]={0};
    for(char c:addr){
        const char* p=strchr(B58,c);
        if(!p)return false;
        int carry=(int)(p-B58);
        for(int i=24;i>=0;i--){carry+=58*decoded[i];decoded[i]=carry%256;carry/=256;}
    }
    /* Verificar el checksum: los 4 últimos bytes son el doble SHA-256 del
       resto. Sin esto una dirección mal tecleada pasaba como válida y la
       búsqueda entera se hacía contra un hash160 inexistente. */
    uint8_t t[32],c[32];
    SHA256(decoded,21,t); SHA256(t,32,c);
    if(memcmp(decoded+21,c,4)!=0) return false;

    memcpy(h160_out,decoded+1,20);
    return true;
}

/** Determina el tipo del objetivo y extrae su hash160 (o programa witness). */
static AddrKind parse_target(const std::string& addr,uint8_t h160_out[20]){
    if(addr.size()>3&&(addr.compare(0,3,"bc1")==0||addr.compare(0,3,"tb1")==0)){
        if(bech32_program(addr,h160_out)) return AK_P2WPKH;
        return AK_UNKNOWN;                       /* p2wsh/taproot no soportados */
    }
    if(!addr_to_h160_local(addr,h160_out)) return AK_UNKNOWN;
    /* El primer byte decodificado es la versión; lo recuperamos aparte. */
    static const char* B58="123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    uint8_t decoded[25]={0};
    for(char ch:addr){
        const char* p=strchr(B58,ch); if(!p) return AK_UNKNOWN;
        int carry=(int)(p-B58);
        for(int i=24;i>=0;i--){carry+=58*decoded[i];decoded[i]=carry%256;carry/=256;}
    }
    switch(decoded[0]){
        case 0x00: case 0x6f: return AK_P2PKH;
        case 0x05: case 0xc4: return AK_P2SH;
        default:             return AK_UNKNOWN;
    }
}

// ── Derivar h160 desde seed (sin BIP44 completo, solo master+1 nivel) ─────────
// Usado para filtro rápido: seed → master_key → h160 del master
// Si el master h160 no coincide con ningún patrón probable, skip
// NOTA: esto es una heurística — el filtro real compara h160 de m/44'/0'/0'/0/0
// Para máxima velocidad hacemos BIP44 completo solo cuando seed pasa pre-filtro

// Pre-filtro: comparar primeros 2 bytes del master_key con fingerprint
// ~1/65536 falsos positivos → solo 64 derivaciones BIP44 completas por 4M combos
static bool quick_filter(const uint8_t seed[64], const uint8_t fingerprint[2]){
    // master key = HMAC-SHA512("Bitcoin seed", seed)
    uint8_t I[64];
    unsigned int out_len=64;
    HMAC(EVP_sha512(),
         "Bitcoin seed",12,
         seed,64,
         I,&out_len);
    // Comparar primeros 2 bytes del master key con fingerprint
    return (I[0]==fingerprint[0] && I[1]==fingerprint[1]);
}

/**
 * Verifica el checksum BIP39 a partir de los índices de las palabras.
 *
 * mnemonic_to_seed() sólo aplica PBKDF2 a la frase: nunca comprueba el
 * checksum, así que cualquier combinación de palabras produce una seed. Sin
 * dirección objetivo eso hacía que se devolviera la primerísima combinación
 * probada como si fuese el resultado, y con objetivo obligaba a derivar
 * candidatos que BIP39 ya invalida (sólo 1 de cada 16 es válido cuando falta
 * una palabra de 12).
 *
 * Los últimos ENT/32 bits del mnemónico son los primeros bits de
 * SHA256(entropía).
 */
static bool bip39_checksum_ok(const std::vector<int>& idx){
    int nw = (int)idx.size();
    int total_bits = nw * 11;
    int cs_bits    = total_bits / 33;
    int ent_bits   = total_bits - cs_bits;
    if(cs_bits <= 0 || ent_bits % 8) return false;

    uint8_t ent[32] = {0};
    int bit = 0;
    for(int w = 0; w < nw; w++){
        for(int b = 10; b >= 0; b--){
            if(bit < ent_bits && ((idx[w] >> b) & 1))
                ent[bit / 8] |= (uint8_t)(1 << (7 - (bit % 8)));
            bit++;
        }
    }

    uint8_t h[32];
    SHA256(ent, ent_bits / 8, h);

    int got = 0;
    for(int i = 0; i < cs_bits; i++){
        int p = ent_bits + i;
        got = (got << 1) | ((idx[p / 11] >> (10 - (p % 11))) & 1);
    }
    return got == (h[0] >> (8 - cs_bits));
}

static std::string jstr(JNIEnv* env,jstring js){
    if(!js)return"";
    const char* c=env->GetStringUTFChars(js,nullptr);
    std::string s(c);env->ReleaseStringUTFChars(js,c);return s;
}

// ── Estado compartido ─────────────────────────────────────────────────────────
static volatile bool      g_cancelled=false;
static std::atomic<bool>  g_found(false);
static std::mutex         g_result_mutex;
static std::string        g_result;
static std::atomic<long long> g_attempts(0);
static std::atomic<long long> g_filtered(0); // cuántos pasaron el filtro

// ── Worker ────────────────────────────────────────────────────────────────────
struct WorkerArgs {
    std::vector<std::string> slots;
    std::vector<std::string> wordlist;
    std::vector<int>         word_idx;   /* índice BIP39 de cada posición */
    std::vector<int>         missing_indices;
    std::string              target;
    bool                     has_target;
    uint8_t                  target_h160[20];  // h160 de la dirección objetivo
    int                      target_kind;      // AddrKind del objetivo
    int                      max_index;        // índices de dirección a probar (0..max_index)
    uint8_t                  fingerprint[2];   // primeros 2 bytes del privkey en m/44'/0'/0'/0/0
    long long                start_combo;
    long long                end_combo;
    int                      wl_size;
    int                      n_missing;
};

static void worker(WorkerArgs args){
    secp256k1_context* ctx=secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
    int n=args.n_missing,wl=args.wl_size;

    std::vector<int> counters(n,0);
    long long tmp=args.start_combo;
    for(int i=n-1;i>=0;i--){counters[i]=(int)(tmp%wl);tmp/=wl;}

    long long combo=args.start_combo;
    std::vector<std::string> slots=args.slots;
    std::vector<int> widx=args.word_idx;

    while(combo<args.end_combo&&!g_found.load()&&!g_cancelled){
        for(int i=0;i<n;i++)
            widx[args.missing_indices[i]]=counters[i];

        /* Descartar antes de derivar: el checksum cuesta un SHA256 de 16-32
           bytes, frente a PBKDF2 con 2048 iteraciones más la derivación BIP32
           y secp256k1 de cada candidato. */
        if(!bip39_checksum_ok(widx)){
            g_attempts.fetch_add(1);
            int carry0=1;
            for(int i=n-1;i>=0&&carry0;i--){
                counters[i]+=carry0;
                if(counters[i]>=wl){counters[i]=0;carry0=1;}else{carry0=0;}
            }
            combo++;
            continue;
        }

        for(int i=0;i<n;i++)
            slots[args.missing_indices[i]]=args.wordlist[counters[i]];

        std::string mnemonic;mnemonic.reserve(200);
        for(int i=0;i<(int)slots.size();i++){if(i>0)mnemonic+=' ';mnemonic+=slots[i];}

        uint8_t seed[64];
        if(mnemonic_to_seed(mnemonic,"",seed)){

            bool do_full_derivation=true;

            // Filtro deshabilitado - siempre derivación completa
            if(args.has_target) g_filtered.fetch_add(1);
            do_full_derivation=true;

            if(do_full_derivation){
                uint8_t privkey[32];
                if(args.has_target){
                    /* Sólo la ruta que corresponde al tipo del objetivo, y los
                       índices de dirección pedidos. Comparación binaria de
                       hash160: evita reconstruir la cadena Base58 por candidato. */
                    AddrKind kind=(AddrKind)args.target_kind;
                    uint32_t purpose=purpose_for(kind);
                    /* m/purpose'/0'/0'/0 se deriva una vez por seed en lugar de
                       una vez por índice: eran 25 niveles HMAC-SHA512 por
                       candidato en lugar de los 9 necesarios. */
                    Bip32Node chain;
                    if(bip_derive_chain(seed,purpose,chain))
                    for(int ai=0; ai<=args.max_index; ai++){
                        if(!bip_derive_from_chain(chain,(uint32_t)ai,privkey)) continue;
                        uint8_t h[20];
                        candidate_h160(ctx,privkey,kind,h);
                        if(memcmp(h,args.target_h160,20)==0){
                            std::lock_guard<std::mutex> lk(g_result_mutex);
                            g_result=mnemonic;g_found.store(true);
                            secp256k1_context_destroy(ctx);return;
                        }
                    }
                }else{
                    /* Sin objetivo devolvemos la primera frase que pasa el
                       checksum BIP39, con su dirección legacy de referencia. */
                    if(bip_derive_privkey(seed,44,0,privkey)){
                        char addr[MAX_ADDR];
                        privkey_to_addr(ctx,privkey,addr);
                        std::string res=mnemonic+"|ADDR:"+std::string(addr);
                        std::lock_guard<std::mutex> lk(g_result_mutex);
                        g_result=res;g_found.store(true);
                        secp256k1_context_destroy(ctx);return;
                    }
                }
            }
        }

        g_attempts.fetch_add(1);

        int carry=1;
        for(int i=n-1;i>=0&&carry;i--){
            counters[i]+=carry;
            if(counters[i]>=wl){counters[i]=0;carry=1;}else{carry=0;}
        }
        combo++;
    }
    secp256k1_context_destroy(ctx);
}

// ── Calcular fingerprint desde dirección objetivo ─────────────────────────────
// Para el filtro necesitamos los primeros 2 bytes del master key que genera esa addr
// Como no sabemos el mnemonic, usamos h160 de la dirección como referencia alternativa:
// Filtramos por: SHA256(seed)[0:2] == SHA256(target_h160)[0:2]
// ~1/65536 ratio de falsos positivos
static void compute_fingerprint(const uint8_t target_h160[20], uint8_t fp_out[2]){
    uint8_t hash[32];
    SHA256(target_h160,20,hash);
    fp_out[0]=hash[0];fp_out[1]=hash[1];
}

static bool seed_passes_filter(const uint8_t seed[64], const uint8_t fp[2]){
    uint8_t hash[32];
    SHA256(seed,64,hash);
    return (hash[0]==fp[0]&&hash[1]==fp[1]);
}

// ── JNI ───────────────────────────────────────────────────────────────────────
extern "C"{

JNIEXPORT jstring JNICALL
Java_com_hunter_btc_recovery_RecoveryEngine_bruteForceSeeds(
    JNIEnv* env,jobject thiz,
    jobjectArray slots_arr,jobjectArray wordlist_arr,
    jintArray missing_arr,jstring target_j)
{
    g_cancelled=false;g_found.store(false);
    g_attempts.store(0);g_filtered.store(0);g_result="";

    int sc=env->GetArrayLength(slots_arr);
    std::vector<std::string> slots(sc);
    for(int i=0;i<sc;i++){auto js=(jstring)env->GetObjectArrayElement(slots_arr,i);slots[i]=jstr(env,js);env->DeleteLocalRef(js);}

    int wc=env->GetArrayLength(wordlist_arr);
    std::vector<std::string> wordlist(wc);
    for(int i=0;i<wc;i++){auto js=(jstring)env->GetObjectArrayElement(wordlist_arr,i);wordlist[i]=jstr(env,js);env->DeleteLocalRef(js);}

    int mc=env->GetArrayLength(missing_arr);
    jint* raw=env->GetIntArrayElements(missing_arr,nullptr);
    std::vector<int> missing(raw,raw+mc);
    env->ReleaseIntArrayElements(missing_arr,raw,JNI_ABORT);

    std::string target=jstr(env,target_j);
    bool has_target=!target.empty();

    /* Índice BIP39 de cada posición conocida, resuelto una sola vez: en el
       bucle los huecos se rellenan con el contador, que ya ES el índice. */
    std::unordered_map<std::string,int> wpos;
    wpos.reserve(wc*2);
    for(int i=0;i<wc;i++) wpos[wordlist[i]]=i;
    std::vector<int> word_idx(sc,0);
    for(int i=0;i<sc;i++){
        auto it=wpos.find(slots[i]);
        word_idx[i]=(it==wpos.end())?0:it->second;
    }

    long long total=1;for(int i=0;i<mc;i++)total*=wc;

    // Preparar fingerprint si hay target
    uint8_t target_h160[20]={0};
    uint8_t fingerprint[2]={0};
    AddrKind target_kind=AK_P2PKH;
    if(has_target){
        target_kind=parse_target(target,target_h160);
        if(target_kind==AK_UNKNOWN){
            /* Antes se seguía buscando con un hash160 basura, de modo que la
               recuperación no podía terminar nunca en coincidencia. */
            LOGI("Dirección objetivo no soportada o con checksum incorrecto");
            return env->NewStringUTF("ERROR:INVALID_TARGET");
        }
        compute_fingerprint(target_h160,fingerprint);
        LOGI("Objetivo tipo=%d purpose=m/%u'",(int)target_kind,purpose_for(target_kind));
    }

    /* Estaba fijo en 6, sin relación con el dispositivo: desaprovechaba los
       móviles de 8 núcleos y sobresuscribía los de 4. Se deja uno libre para
       la UI y el sistema. */
    int hw=(int)std::thread::hardware_concurrency();
    if(hw<1) hw=4;
    int n_threads=hw-1;
    if(n_threads<1) n_threads=1;
    if(n_threads>16) n_threads=16;
    if(total<n_threads)n_threads=(int)total;
    LOGI("Recovery: %d hilos (%d núcleos detectados)",n_threads,hw);
    long long chunk=total/n_threads;

    std::vector<std::thread> threads;
    for(int t=0;t<n_threads;t++){
        WorkerArgs args;
        args.slots=slots;args.wordlist=wordlist;
        args.word_idx=word_idx;
        args.missing_indices=missing;
        args.target=target;args.has_target=has_target;
        memcpy(args.target_h160,target_h160,20);
        memcpy(args.fingerprint,fingerprint,2);
        args.target_kind=(int)target_kind;
        args.max_index=MAX_ADDRESS_INDEX;
        args.wl_size=wc;args.n_missing=mc;
        args.start_combo=t*chunk;
        args.end_combo=(t==n_threads-1)?total:(t+1)*chunk;
        threads.emplace_back(worker,args);
    }

    jclass cls=env->GetObjectClass(thiz);
    jmethodID on_progress=env->GetMethodID(cls,"onProgress","(JJLjava/lang/String;)V");

    while(!g_found.load()&&!g_cancelled){
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
        long long att=g_attempts.load();
        if(on_progress){
            // Mostrar también cuántos pasaron el filtro
            std::string status=std::to_string(att);
            if(has_target){
                long long filt=g_filtered.load();
                status+=" (filtro: "+std::to_string(filt)+" full)";
            }
            jstring jw=env->NewStringUTF(status.c_str());
            env->CallVoidMethod(thiz,on_progress,(jlong)att,(jlong)total,jw);
            env->DeleteLocalRef(jw);
        }
        if(att>=total)break;
    }

    for(auto& t:threads)if(t.joinable())t.join();

    LOGI("Recovery done: %lld intentos, %lld full derivations",
         g_attempts.load(),g_filtered.load());

    if(g_result.empty())return nullptr;
    return env->NewStringUTF(g_result.c_str());
}

JNIEXPORT void JNICALL
Java_com_hunter_btc_recovery_RecoveryEngine_cancelRecovery(JNIEnv* env,jobject thiz){
    g_cancelled=true;
}

}// extern "C"
