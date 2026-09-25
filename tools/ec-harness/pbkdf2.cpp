/* SHA-512, HMAC y PBKDF2 propios (sha512.cpp, sha512_hw.cpp) contra OpenSSL,
 * en todos los modos que tenga el procesador, y lo que tarda cada uno.
 *
 *   g++ -O2 -o pbkdf2 pbkdf2.cpp ../../app/src/main/cpp/sha512.cpp -lcrypto
 * En ARM64, con las instrucciones (qemu-aarch64 -cpu max si no hay ARM):
 *   aarch64-linux-gnu-g++ -O2 -march=armv8.2-a+sha3 -c -o hw.o ../../app/src/main/cpp/sha512_hw.cpp
 *   aarch64-linux-gnu-g++ -O2 -static -DSIN_OPENSSL -o pbkdf2_arm pbkdf2.cpp ../../app/src/main/cpp/sha512.cpp hw.o
 *   qemu-aarch64 -cpu max ./pbkdf2_arm          (con las instrucciones)
 *   qemu-aarch64 -cpu cortex-a72 ./pbkdf2_arm   (sin ellas: tiene que elegir software) */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <chrono>
#include "../../app/src/main/cpp/sha512.h"
#ifndef SIN_OPENSSL
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/sha.h>
#endif

static uint64_t s=0x9E3779B97F4A7C15ULL;
static uint8_t azar(){ s^=s<<13; s^=s>>7; s^=s<<17; return (uint8_t)s; }

/* Vector de BIP39 (Trezor): "abandon ... about" con pass "TREZOR". */
static const char *FRASE="abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const char *SEMILLA="c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04";

static int hexeq(const uint8_t *b,const char *h){
    for(int i=0;i<64;i++){ unsigned v; sscanf(h+2*i,"%2x",&v); if(b[i]!=v) return 0; } return 1;
}

/* Sin OpenSSL (ARM cruzado): cada modo contra el software, que en el
   escritorio ya se ha comparado con OpenSSL. */
static uint8_t REF[200][64];
static void casos(int guardar,long *mal){
    s=12345;
    for(int it=0;it<200;it++){
        uint8_t k[300],m[300],a[64],b[64];
        size_t kn=1+azar()%(it%7==0?290:140), mn=azar()%200;
        for(size_t i=0;i<kn;i++) k[i]=azar();
        for(size_t i=0;i<mn;i++) m[i]=azar();
        uint32_t v=1+it%37;
        if(it&1) pbkdf2_sha512(k,kn,m,mn,v,a);
        else pbkdf2_sha512_x2(k,kn,m,mn/2+1,m,mn,v,a,b);
        if(!(it&1)){ uint8_t h[64]; hmac_sha512(k,kn,m,mn,h); for(int i=0;i<64;i++) a[i]^=b[i]^h[i]; }
        if(guardar) memcpy(REF[it],a,64); else if(memcmp(REF[it],a,64)) (*mal)++;
    }
}

int main(){
    int fallos=0;
    sha512_fijar_modo(0); casos(1,NULL);
    int modos[3]={0,1,2}; int nm=sha512_tiene_hw()?3:1;
    printf("instrucciones SHA-512: %s\n", sha512_tiene_hw()?"si":"no");
    for(int mi=0;mi<nm;mi++){
        sha512_fijar_modo(modos[mi]);
        const char *nom=sha512_nombre_modo(sha512_modo());
        uint8_t o[64],o2[64];
        bip39_semilla(FRASE,strlen(FRASE),"TREZOR",6,o);
        int ok=hexeq(o,SEMILLA);
        bip39_semilla_x2(FRASE,strlen(FRASE),FRASE,strlen(FRASE),"TREZOR",6,o,o2);
        ok&=hexeq(o,SEMILLA)&&hexeq(o2,SEMILLA);
        printf("%s  [%s] vector BIP39 de Trezor\n", ok?"OK ":"MAL", nom); fallos+=!ok;
        if(mi>0){ long mal=0; casos(0,&mal);
            printf("%s  [%s] 200 PBKDF2/HMAC iguales que en software (%ld distintas)\n", mal?"MAL":"OK ", nom, mal);
            fallos+=mal!=0; }
#ifndef SIN_OPENSSL
        long mal=0;
        for(int it=0;it<3000;it++){
            uint8_t k[300],m[400],a[64],b[64],c[64],d[64];
            size_t kn=azar()%(it%7==0?300:140), mn=azar()%(it%5==0?400:200);
            for(size_t i=0;i<kn;i++) k[i]=azar();
        for(size_t i=0;i<mn;i++) m[i]=azar();
            sha512(m,mn,a); SHA512(m,mn,b); if(memcmp(a,b,64)) mal++;
            hmac_sha512(k,kn,m,mn,a); unsigned l=64; HMAC(EVP_sha512(),k,(int)kn,m,mn,b,&l); if(memcmp(a,b,64)) mal++;
            if(it%10==0){
                uint32_t v=1+(it/10)%40; size_t kn2=1+azar()%150;
                pbkdf2_sha512(k,kn2,m,mn%160,v,a);
                PKCS5_PBKDF2_HMAC((const char*)k,(int)kn2,m,(int)(mn%160),(int)v,EVP_sha512(),64,b);
                if(memcmp(a,b,64)) mal++;
                pbkdf2_sha512_x2(k,kn2,m,mn%100+1,m,mn%160,v,c,d);
                PKCS5_PBKDF2_HMAC((const char*)m,(int)(mn%100+1),m,(int)(mn%160),(int)v,EVP_sha512(),64,a);
                if(memcmp(c,b,64)||memcmp(d,a,64)) mal++;
            }
        }
        printf("%s  [%s] SHA-512, HMAC y PBKDF2 == OpenSSL en 3000 entradas (%ld distintas)\n", mal?"MAL":"OK ", nom, mal);
        fallos+=mal!=0;
#endif
        /* Velocidad: frases por segundo en un hilo. */
        uint8_t oa[64],ob[64]; int n=0; auto t0=std::chrono::steady_clock::now(); double seg;
        do{ bip39_semilla_x2(FRASE,strlen(FRASE),FRASE,strlen(FRASE),"",0,oa,ob); n+=2;
            seg=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count(); }while(seg<0.5);
        printf("     [%s] %.0f semillas/s (1 hilo)\n", nom, n/seg);
    }
#ifndef SIN_OPENSSL
    { uint8_t o[64]; int n=0; auto t0=std::chrono::steady_clock::now(); double seg;
      do{ PKCS5_PBKDF2_HMAC(FRASE,(int)strlen(FRASE),(const uint8_t*)"mnemonic",8,2048,EVP_sha512(),64,o); n++;
          seg=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count(); }while(seg<0.5);
      printf("     [OpenSSL] %.0f semillas/s (1 hilo)\n", n/seg); }
#endif
    sha512_fijar_modo(-1);
    printf("modo elegido solo: %s\n", sha512_nombre_modo(sha512_modo()));
    return fallos;
}
