#pragma once
/* Codificacion de direcciones de Bitcoin.
 *
 * Estaba todo suelto dentro de hunter_jni.cpp, que no se puede compilar fuera
 * de Android porque arrastra JNI y secp256k1. Eso significaba que el
 * codificador —la pieza que decide como se escribe una direccion— era la unica
 * parte del motor sin una sola prueba.
 *
 * Aqui solo hacen falta SHA256, RIPEMD160 y BIGNUM de OpenSSL, asi que se
 * compila en el escritorio y tiene su banco en tools/ec-harness/direcciones.cpp.
 *
 * Todas las funciones llevan `testnet` con valor por defecto false: el escaner
 * es siempre de mainnet y sus llamadas no cambian; solo la cartera lo pasa.
 */
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <openssl/sha.h>
#include <openssl/ripemd.h>
#include <openssl/bn.h>

#ifndef MAX_ADDR
#define MAX_ADDR 64
#endif

static const char B58C[]="123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
static void b58enc(const uint8_t *pl,int plen,char *out,int osz){
    uint8_t buf[plen+4];memcpy(buf,pl,plen);uint8_t ck[32];SHA256_CTX sc;SHA256_Init(&sc);SHA256_Update(&sc,pl,plen);SHA256_Final(ck,&sc);SHA256_Init(&sc);SHA256_Update(&sc,ck,32);SHA256_Final(ck,&sc);memcpy(buf+plen,ck,4);
    BIGNUM *bn=BN_new(),*rem=BN_new(),*d58=BN_new();BN_CTX *bctx=BN_CTX_new();BN_bin2bn(buf,plen+4,bn);BN_set_word(d58,58);
    char tmp[64];int tlen=0;while(!BN_is_zero(bn)){BN_div(bn,rem,bn,d58,bctx);tmp[tlen++]=B58C[BN_get_word(rem)];}
    for(int i=0;i<plen+4&&buf[i]==0;i++)tmp[tlen++]='1';int ol=0;for(int i=tlen-1;i>=0&&ol<osz-1;i--)out[ol++]=tmp[i];out[ol]='\0';BN_free(bn);BN_free(rem);BN_free(d58);BN_CTX_free(bctx);
}
/* Byte de version de la red. El escaner siempre es de mainnet, asi que va por
 * defecto; solo la derivacion de la cartera pasa testnet=true.
 *
 * ESTO ESTABA CLAVADO A MAINNET. El interruptor "Toggle Testnet" de la cartera
 * cambiaba a que explorador se preguntaba, pero las direcciones se seguian
 * derivando en mainnet. Preguntar por una direccion de mainnet en la cadena de
 * pruebas siempre responde "no usada", asi que la busqueda de hueco daba
 * siempre indice 0 y CADA ENVIO REUTILIZABA LA MISMA DIRECCION DE CAMBIO. */
static void h160_to_addr(const uint8_t *h,char *a,bool testnet=false){
    uint8_t v[21]; v[0]=testnet?0x6f:0x00; memcpy(v+1,h,20); b58enc(v,21,a,MAX_ADDR);
}
static void pk_to_wif(const uint8_t *k,char *w,bool testnet=false){
    uint8_t v[34]; v[0]=testnet?0xef:0x80; memcpy(v+1,k,32); v[33]=1; b58enc(v,34,w,60);
}

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

/* Bech32m encoding for P2TR (witness version 1, 32-byte program) */
static void xonly_to_p2tr(const uint8_t *xonly32, char *out, bool testnet=false) {
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
    const char *hrp=testnet?"tb":"bc";
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
    *o++=hrp[0];*o++=hrp[1];*o++='1';*o++='p'; /* bc1p / tb1p, version 1 */
    for(int i=1;i<nd5;i++) *o++=CHARSET[d5[i]]; /* skip version byte, already in prefix */
    for(int i=0;i<6;i++) *o++=CHARSET[(chk>>(5*(5-i)))&31];
    *o=0;
}

static void h160_to_bech32(const uint8_t *h160, char *out, bool testnet=false){
    /* Convert 20 bytes to 5-bit groups (32 values) */
    uint8_t d5[33]; /* witness version 0 + 32 data values */
    d5[0]=0; /* witness version */
    uint32_t acc=0; int bits=0; int idx=1;
    for(int i=0;i<20;i++){
        acc=(acc<<8)|h160[i]; bits+=8;
        while(bits>=5){ bits-=5; d5[idx++]=(acc>>bits)&31; }
    }
    if(bits>0) d5[idx++]=(acc<<(5-bits))&31;
    /* Checksum. En testnet el prefijo humano es "tb", no "bc"; los dos miden
       dos caracteres, asi que el tamano del buffer no cambia. */
    const char *hrp=testnet?"tb":"bc"; int hrplen=2;
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
static void h160_to_p2sh(const uint8_t *h160, char *out, bool testnet=false){
    /* P2SH-P2WPKH: redeem = 0x0014 + h160, then hash160 of redeem */
    uint8_t redeem[22]; redeem[0]=0x00; redeem[1]=0x14; memcpy(redeem+2,h160,20);
    uint8_t sha[32],rh[20];
    SHA256(redeem,22,sha); RIPEMD160(sha,32,rh);
    uint8_t v[21]; v[0]=testnet?0xc4:0x05; memcpy(v+1,rh,20);
    b58enc(v,21,out,MAX_ADDR);
}
