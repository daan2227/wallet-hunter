/* La SHA-256 del shader del minero (gpu/minero_sha.inc), compilada como C++, contra el minero de CPU
 * (minero.h) y el bloque 1. También el main() del shader, invocación a invocación, para las cuentas de
 * índices, límite y "el menor que cumple". Aquí no hay Vulkan: en el teléfono, la app hace la misma prueba
 * del bloque 1 en la GPU antes de usarla.
 *
 *   g++ -O2 -o minero_gpu minero_gpu.cpp && ./minero_gpu */
#include <stdio.h>
#include <algorithm>
#include "../../app/src/main/cpp/minero.h"

typedef uint32_t uint;
#define ARR8_IN(n) const uint (&n)[8]
#define ARR8_OUT(n) uint (&n)[8]
#define ARR16_IN(n) const uint (&n)[16]
#define CLZ(x) ((uint)__builtin_clz(x))
static const uint KT[64]={
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
static const uint H0T[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
#include "../../app/src/main/cpp/gpu/minero_sha.inc"
#include "../../app/src/main/cpp/gpu/minero_gpu_params.h"

/* El main() del shader para una invocación (gid), con los atómicos hechos a mano. */
static void invocacion(const MinGpuParams &p, MinGpuRes &r, uint gid){
    uint idx0=gid*p.iter; uint mid[8], obj[8];
    for(int i=0;i<8;i++){ mid[i]=p.mid[i]; obj[i]=p.obj[i]; }
    uint mejorZ=0, mejorN=0;
    for(uint k=0;k<p.iter;k++){
        uint idx=idx0+k; if(idx>=p.limite) break;
        uint h[8]; minar_hash(mid,p.cola[0],p.cola[1],p.cola[2],p.base+idx,h);
        uint alta=bsw(h[7]); uint z=alta!=0u?CLZ(alta):ceros(h);
        if(z>mejorZ){ mejorZ=z; mejorN=idx; }
        if(alta<=obj[0] && cumple(h,obj)) r.encontrado=std::min(r.encontrado,idx);
    }
    uint antes=r.mejorZ; r.mejorZ=std::max(r.mejorZ,mejorZ); if(mejorZ>antes) r.mejorN=mejorN;
}

static void hexb(const char *h, uint8_t *o, int n){ for(int i=0;i<n;i++){ unsigned v; sscanf(h+2*i,"%2x",&v); o[i]=(uint8_t)v; } }

int main(){
    uint8_t cab[80];
    hexb("010000006fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677ba1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d00000000",cab,80);
    uint32_t obj[8]={0x00000000,0xffff0000,0,0,0,0,0,0};
    int f=0;
    /* 1) Hash a hash, la del shader == la de CPU, en 20000 nonces. */
    MinGpuParams p; min_gpu_params(&p,cab,0,0,obj,1);
    long malos=0;
    for(uint32_t n=2573384689u;n<2573404689u;n++){
        uint h[8]; minar_hash(p.mid,p.cola[0],p.cola[1],p.cola[2],n,h);
        uint32_t s[8]; memcpy(s,MIN_H0,32); sha256_block(s,cab);
        uint8_t c2[80]; memcpy(c2,cab,80); c2[76]=n; c2[77]=n>>8; c2[78]=n>>16; c2[79]=n>>24;
        uint8_t b[64]={0}; memcpy(b,c2+64,16); b[16]=0x80; b[62]=2; b[63]=0x80; sha256_block(s,b);
        uint8_t b2[64]={0}; for(int i=0;i<8;i++){ b2[4*i]=s[i]>>24; b2[4*i+1]=s[i]>>16; b2[4*i+2]=s[i]>>8; b2[4*i+3]=s[i]; }
        b2[32]=0x80; b2[62]=1; uint32_t t[8]; memcpy(t,MIN_H0,32); sha256_block(t,b2);
        if(memcmp(h,t,32)) malos++;
    }
    printf("%s  SHA-256 del shader == CPU en 20000 nonces (%ld distintos)\n", malos?"MAL":"OK ", malos); f|=malos!=0;

    /* 2) El main() del shader en una ventana alrededor del nonce del bloque 1, con límite que no es múltiplo. */
    uint32_t real=2573394689u, desde=real-3000;
    min_gpu_params(&p,cab,desde,6000,obj,16);
    MinGpuRes r; min_gpu_res_iniciar(&r);
    uint grupos=(6000+16*64-1)/(16*64);
    for(uint g=0; g<grupos*64; g++) invocacion(p,r,g);
    MinResultado mr; uint64_t hechos=min_gpu_leer(&r,desde,6000,&mr);
    printf("%s  ventana: nonce %lld (esperado %u), %llu hashes contados\n", (mr.encontrado==(int64_t)real && hechos==3001)?"OK ":"MAL",
           (long long)mr.encontrado, real, (unsigned long long)hechos);
    f|=!(mr.encontrado==(int64_t)real && hechos==3001 && mr.mejor_ceros==32 && mr.mejor_nonce==real);

    /* 3) Sin premio: el mejor de la GPU es el mismo que el de la CPU. */
    uint32_t cero[8]={0};
    min_gpu_params(&p,cab,12345,50000,cero,8); min_gpu_res_iniciar(&r);
    for(uint g=0; g<(50000+511)/512*64; g++) invocacion(p,r,g);
    hechos=min_gpu_leer(&r,12345,50000,&mr);
    MinResultado cpu; min_tanda<sha256_block>(cab,12345,50000,cero,&cpu);
    printf("%s  sin premio: %llu hashes, mejor %d ceros (CPU: %d)\n", (hechos==50000&&mr.encontrado==-1&&mr.mejor_ceros==cpu.mejor_ceros)?"OK ":"MAL",
           (unsigned long long)hechos, mr.mejor_ceros, cpu.mejor_ceros);
    f|=!(hechos==50000&&mr.encontrado==-1&&mr.mejor_ceros==cpu.mejor_ceros&&mr.mejor_nonce==cpu.mejor_nonce);
    return f;
}
