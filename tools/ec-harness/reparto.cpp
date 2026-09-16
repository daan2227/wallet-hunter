/* ¿Dónde se va el tiempo en cada modo? Sin esto, "optimizar" es adivinar. */
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <chrono>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include "../../app/src/main/cpp/sha256_ripemd160.h"

static volatile uint64_t sink=0;
template<class F> double mide(int n, F f){
    auto t0=std::chrono::high_resolution_clock::now();
    for(int i=0;i<n;i++) f(i);
    auto t1=std::chrono::high_resolution_clock::now();
    return std::chrono::duration<double>(t1-t0).count()/n;
}
int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    uint8_t pub[33]; for(int i=0;i<33;i++) pub[i]=i;
    uint8_t h[20], seed[64];

    /* 1) hash160 de una clave pública: lo que se ahorraría con un dataset de
          claves públicas. */
    double t_h = mide(300000,[&](int i){ pub[1]=i; hash160_inline(pub,h); sink^=h[0]; });

    /* 2) PBKDF2-HMAC-SHA512 con 2048 vueltas: lo que exige BIP39 para pasar de
          las palabras a la semilla. No es opcional, lo dice la norma. */
    const char *mn="abandon abandon abandon abandon abandon abandon "
                   "abandon abandon abandon abandon abandon about";
    double t_p = mide(300,[&](int i){
        PKCS5_PBKDF2_HMAC(mn,strlen(mn),(const unsigned char*)"mnemonic",8,
                          2048,EVP_sha512(),64,seed);
        sink^=seed[0];
    });

    /* 3) Una derivación BIP32: HMAC-SHA512 por nivel. m/84'/0'/0'/0/0 son cinco. */
    double t_d = mide(20000,[&](int i){
        uint8_t out[64]; unsigned int len=64;
        uint8_t key[32]={0}; key[0]=(uint8_t)i;
        for(int n=0;n<5;n++)
            HMAC(EVP_sha512(),key,32,(const unsigned char*)seed,64,out,&len);
        sink^=out[0];
    });

    printf("Por candidato:\n");
    printf("  hash160                 %9.3f us\n", t_h*1e6);
    printf("  derivación BIP32 (x5)   %9.3f us\n", t_d*1e6);
    printf("  PBKDF2 2048 (BIP39)     %9.3f us\n", t_p*1e6);
    printf("\nEn modo BIP39 el hash160 es el %.3f %% del coste\n",
           100.0*t_h/(t_h+t_d+t_p));
    printf("Quitarlo daría un %.4fx\n", (t_h+t_d+t_p)/(t_d+t_p));
    return (int)(sink&0);
}
