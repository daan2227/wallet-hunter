#pragma once
/* Ruta BIP32 ("m/84'/0'/0'/0/5") -> índices, de forma ESTRICTA.
 *
 * La versión anterior se saltaba el primer trozo dando por hecho que era "m",
 * paraba en el primer carácter raro, no entendía 'h' y dejaba desbordar los
 * índices: "44'/0'/0'/0/0" derivaba m/0'/0'/0/0 y "m/84h/0h/0h/0/0" sólo m/84,
 * sin ningún error. Al firmar, eso es firmar con la clave de otra dirección.
 * Ahora cualquier cosa que no sea exactamente una ruta válida se rechaza.
 * Se prueba en tools/ec-harness/rutas.cpp. */
#include <stdint.h>

/* @return número de niveles (0..max), o -1 si la ruta no es válida. */
static int ruta_bip32_parse(const char *p, uint32_t *idx, int max){
    if(!p || p[0]!='m') return -1;
    p++;
    int n=0;
    while(*p){
        if(*p!='/') return -1;
        p++;
        if(*p<'0'||*p>'9') return -1;
        uint64_t v=0;
        while(*p>='0'&&*p<='9'){ v=v*10+(uint64_t)(*p-'0'); if(v>=0x80000000ull) return -1; p++; }
        int dura=0;
        if(*p=='\''||*p=='h'||*p=='H'){ dura=1; p++; }
        if(n>=max) return -1;
        idx[n++]=(uint32_t)v | (dura?0x80000000u:0u);
    }
    return n;
}
