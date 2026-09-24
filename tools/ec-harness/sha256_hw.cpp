/* SHA-256 con las instrucciones ARMv8 contra la de software. Solo ARM64:
 *
 *   aarch64-linux-gnu-g++ -O2 -march=armv8-a+crypto -static -o sha256_hw sha256_hw.cpp
 *   qemu-aarch64 -cpu max ./sha256_hw
 */
#include <stdio.h>
#include "../../app/src/main/cpp/sha256_ripemd160.h"
int main(){
#ifndef SHA256_HW
    printf("sin instrucciones SHA-256: nada que probar\n"); return 0;
#else
    /* Vector conocido: la clave publica de 1*G. Su hash160 es el de la
       direccion 1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH. */
    const uint8_t g33[33]={0x02,0x79,0xBE,0x66,0x7E,0xF9,0xDC,0xBB,0xAC,0x55,0xA0,0x62,0x95,0xCE,0x87,0x0B,0x07,0x02,0x9B,0xFC,0xDB,0x2D,0xCE,0x28,0xD9,0x59,0xF2,0x81,0x5B,0x16,0xF8,0x17,0x98};
    const uint8_t h160_esp[20]={0x75,0x1e,0x76,0xe8,0x19,0x91,0x96,0xd4,0x54,0x94,0x1c,0x45,0xd1,0xb3,0xa3,0x23,0xf1,0x43,0x3b,0xd6};
    uint8_t h[20]; hash160_inline(g33,h);
    int f=memcmp(h,h160_esp,20)!=0;
    printf("%s  hash160(1*G) con SHA-256 del procesador\n", f?"MAL":"OK ");
    uint64_t s=0x9E3779B97F4A7C15ULL; long mal=0;
    for(long it=0;it<2000000;it++){
        uint8_t in[33],a[32],b[32];
        for(int i=0;i<33;i++){ s^=s<<13; s^=s>>7; s^=s<<17; in[i]=(uint8_t)s; }
        sha256_33(in,a); sha256_33_sw(in,b);
        if(memcmp(a,b,32)) mal++;
    }
    printf("%s  SHA-256 procesador == software en 2.000.000 entradas (%ld distintas)\n", mal?"MAL":"OK ", mal);
    return f||mal;
#endif
}
