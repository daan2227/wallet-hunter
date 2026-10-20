#pragma once
/* Dirección de DESTINO -> scriptPubKey, de forma estricta.
 *
 * Esto decide a dónde va el dinero, así que no adivina nada:
 *   - base58check: exactamente 25 bytes, '1' iniciales = bytes cero iniciales,
 *     checksum doble-SHA256 obligatorio y versión conocida.
 *   - bech32/bech32m (BIP173/BIP350): hrp bc/tb, sin mezclar mayúsculas y
 *     minúsculas (se acepta todo en mayúsculas, como dice el BIP), checksum de
 *     la variante que toca a la versión de testigo, padding a cero y longitud de
 *     programa válida (v0: 20 o 32 bytes).
 * Antes el motor decodificaba base58 sin checksum ni límite de longitud, y un
 * "BC1Q..." en mayúsculas caía por ahí: una dirección con una errata podía
 * convertirse en un script de una clave que no tiene nadie.
 *
 * Requiere bech32_polymod (addr_encode.h) y SHA256 (OpenSSL). Se prueba en
 * tools/ec-harness/destinos.cpp con los vectores de los BIP. */
#include <string>
#include <string.h>
#include <ctype.h>
#include <stdint.h>
#include <openssl/sha.h>
#include <openssl/bn.h>

/* red: 0 = mainnet, 1 = testnet, -1 = cualquiera. */
static int ad_b58check25(const char *a, uint8_t out25[25]){
    static const char *A="123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    size_t n=strlen(a); if(n<26||n>35) return 0;
    BIGNUM *bn=BN_new(), *t=BN_new(); BN_CTX *cx=BN_CTX_new(); int ok=1;
    BN_zero(bn);
    for(size_t i=0;i<n && ok;i++){
        const char *p=strchr(A,a[i]); if(!p||!*p){ ok=0; break; }
        BN_mul_word(bn,58); BN_add_word(bn,(BN_ULONG)(p-A));
    }
    if(ok && BN_num_bytes(bn)>25) ok=0;
    if(ok) BN_bn2binpad(bn,out25,25);
    BN_free(bn); BN_free(t); BN_CTX_free(cx);
    if(!ok) return 0;
    size_t unos=0; while(unos<n && a[unos]=='1') unos++;
    size_t ceros=0; while(ceros<25 && out25[ceros]==0) ceros++;
    if(unos!=ceros) return 0;            /* codificación no canónica */
    uint8_t h[32]; SHA256(out25,21,h); SHA256(h,32,h);
    return memcmp(h,out25+21,4)==0;
}

/* Programa de testigo. Devuelve la versión (0..16) o -1. */
static int ad_segwit(const char *a, int red, uint8_t prog[40], int *plen){
    size_t n=strlen(a); if(n<14||n>90) return -1;
    int hasU=0,hasL=0;
    for(size_t i=0;i<n;i++){ unsigned char c=(unsigned char)a[i]; if(c<33||c>126) return -1;
        if(isupper(c)) hasU=1; if(islower(c)) hasL=1; }
    if(hasU&&hasL) return -1;
    char s[91]; for(size_t i=0;i<n;i++) s[i]=(char)tolower((unsigned char)a[i]); s[n]=0;
    const char *sep=strrchr(s,'1'); if(!sep) return -1;
    size_t hl=(size_t)(sep-s);
    int esTest;
    if(hl==2 && !strncmp(s,"bc",2)) esTest=0; else if(hl==2 && !strncmp(s,"tb",2)) esTest=1; else return -1;
    if(red>=0 && red!=esTest) return -1;
    size_t dl=n-hl-1; if(dl<6+1) return -1;
    static const char *CS="qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    uint8_t v[200]; int k=0;
    for(size_t i=0;i<hl;i++) v[k++]=(uint8_t)(s[i]>>5);
    v[k++]=0;
    for(size_t i=0;i<hl;i++) v[k++]=(uint8_t)(s[i]&31);
    int dstart=k;
    for(size_t i=0;i<dl;i++){ const char *p=strchr(CS,sep[1+i]); if(!p) return -1; v[k++]=(uint8_t)(p-CS); }
    uint32_t pm=bech32_polymod(v,k);
    int ver=v[dstart]; if(ver>16) return -1;
    if(ver==0 ? pm!=1u : pm!=0x2bc830a3u) return -1;   /* v0 bech32, v1+ bech32m */
    /* 5 -> 8 bits, padding <= 4 bits y a cero */
    const uint8_t *d=v+dstart+1; int nd=k-dstart-1-6;
    uint32_t acc=0; int bits=0; int o=0;
    for(int i=0;i<nd;i++){ acc=(acc<<5)|d[i]; bits+=5;
        while(bits>=8){ bits-=8; if(o>=40) return -1; prog[o++]=(uint8_t)((acc>>bits)&0xff); } }
    if(bits>=5 || ((acc<<(8-bits))&0xff)) return -1;
    if(o<2||o>40) return -1;
    if(ver==0 && o!=20 && o!=32) return -1;
    /* Taproot (v1) es siempre la clave x de 32 bytes: otro largo no se puede gastar. */
    if(ver==1 && o!=32) return -1;
    *plen=o; return ver;
}

/* scriptPubKey de la dirección, o "" si no es válida (o no es de la red). */
static std::string addr_destino_spk(const char *a, int red=-1){
    std::string spk; if(!a||!*a) return spk;
    uint8_t prog[40]; int plen=0;
    int ver=ad_segwit(a,red,prog,&plen);
    if(ver>=0){
        spk+=(char)(ver==0?0x00:(0x50+ver));
        spk+=(char)plen; spk.append((const char*)prog,plen);
        return spk;
    }
    uint8_t r[25];
    if(!ad_b58check25(a,r)) return spk;
    uint8_t vb=r[0];
    bool main=(vb==0x00||vb==0x05), test=(vb==0x6f||vb==0xc4);
    if(!(main||test)) return spk;
    if(red==0 && !main) return spk;
    if(red==1 && !test) return spk;
    if(vb==0x00||vb==0x6f){ spk+="\x76\xa9\x14"; spk.append((const char*)r+1,20); spk+="\x88\xac"; }
    else { spk+="\xa9\x14"; spk.append((const char*)r+1,20); spk+='\x87'; }
    return spk;
}
