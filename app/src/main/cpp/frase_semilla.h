#pragma once
/* Semilla BIP39 de la frase que guarda la app. Va aparte para poder probarla
 * en el escritorio contra los vectores publicados (tools/ec-harness/frase.cpp).
 */
#include <stdint.h>
#include <string.h>
#include "sha512.h"

/* La frase que guarda la app es "palabras[|passphrase][#cuenta]". La passphrase
 * es la palabra 25 de BIP39: sin ella (sin '|') la semilla es la de siempre. La
 * cuenta (0..99) va al final tras '#'; sin ella es la 0. Se limita a ASCII
 * imprimible en la app, así que no hace falta normalizarla NFKD. */
static size_t frase_sin_cuenta(const char *frase, size_t n, uint32_t *cuenta){
    uint32_t c=0;
    const char *h=nullptr;
    for(size_t i=n;i>0;i--) if(frase[i-1]=='#'){ h=frase+i-1; break; }
    if(h){
        size_t d=(size_t)((frase+n)-(h+1));
        bool ok=(d>=1 && d<=2);
        for(size_t j=0;ok && j<d;j++){
            char ch=h[1+j];
            if(ch<'0'||ch>'9') ok=false; else c=c*10+(uint32_t)(ch-'0');
        }
        if(ok && c<=99){ if(cuenta) *cuenta=c; return (size_t)(h-frase); }
    }
    if(cuenta) *cuenta=0;
    return n;
}

/* Cuenta de la frase (0 si no tiene). */
static uint32_t frase_cuenta(const char *frase, size_t n){
    uint32_t c=0; frase_sin_cuenta(frase,n,&c); return c;
}

static void semilla_de_frase(const char *frase, size_t n0, uint8_t out[64], uint32_t *cuenta=nullptr){
    size_t n=frase_sin_cuenta(frase,n0,cuenta);
    const char *bar=(const char*)memchr(frase,'|',n);
    if(!bar){ bip39_semilla(frase,n,"",0,out); return; }
    size_t wn=(size_t)(bar-frase);
    bip39_semilla(frase,wn,bar+1,n-wn-1,out);
}
