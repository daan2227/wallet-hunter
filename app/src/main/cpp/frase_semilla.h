#pragma once
/* Semilla BIP39 de la frase que guarda la app. Va aparte para poder probarla
 * en el escritorio contra los vectores publicados (tools/ec-harness/frase.cpp).
 */
#include <stdint.h>
#include <string.h>
#include "sha512.h"

/* La frase que guarda la app es "palabras" o "palabras|passphrase". La passphrase
 * es la palabra 25 de BIP39: sin ella (sin '|') la semilla es la de siempre. Se
 * limita a ASCII imprimible en la app, así que no hace falta normalizarla NFKD. */
static void semilla_de_frase(const char *frase, size_t n, uint8_t out[64]){
    const char *bar=(const char*)memchr(frase,'|',n);
    if(!bar){ bip39_semilla(frase,n,"",0,out); return; }
    size_t wn=(size_t)(bar-frase);
    bip39_semilla(frase,wn,bar+1,n-wn-1,out);
}
