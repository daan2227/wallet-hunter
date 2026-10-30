/* El minero en una GPU de verdad (o en lavapipe): el bloque 1 y el mejor hash contra la CPU.
 *
 *   g++ -O2 -o minero_gpu_vk minero_gpu_vk.cpp -lvulkan && ./minero_gpu_vk
 *   (sin GPU: apt install mesa-vulkan-drivers y VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json)
 *
 * No va en run.sh: la CI no tiene Vulkan. En el teléfono la app hace la misma prueba antes de usar la GPU. */
#include <stdio.h>
#include <chrono>
#include "../../app/src/main/cpp/minero.h"
#include "../../app/src/main/cpp/gpu/minero_spv.h"
#include "../../app/src/main/cpp/gpu/minero_gpu.h"

static void hexb(const char *h, uint8_t *o, int n){ for(int i=0;i<n;i++){ unsigned v; sscanf(h+2*i,"%2x",&v); o[i]=(uint8_t)v; } }

int main(){
    std::string err; GpuMin *g=gpu_min_crear(MINERO_SPV,sizeof(MINERO_SPV),err);
    if(!g){ printf("--   sin GPU aquí (%s): nada que probar\n",err.c_str()); return 0; }
    printf("GPU: %s\n",g->nombre.c_str());
    uint8_t cab[80];
    hexb("010000006fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677ba1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d00000000",cab,80);
    uint32_t obj[8]={0x00000000,0xffff0000,0,0,0,0,0,0}, cero[8]={0};
    int f=0; MinResultado r, c;
    int64_t h=gpu_min_tanda(g,cab,2573394689u-3000,6000,obj,&r);
    printf("%s  bloque 1: nonce %lld, %lld hashes\n",(r.encontrado==2573394689LL&&h==3001)?"OK ":"MAL",(long long)r.encontrado,(long long)h);
    f|=!(r.encontrado==2573394689LL&&h==3001);
    h=gpu_min_tanda(g,cab,12345,200000,cero,&r); min_tanda<sha256_block>(cab,12345,200000,cero,&c);
    printf("%s  sin premio: %lld hashes, mejor %d ceros (CPU %d)\n",(h==200000&&r.encontrado==-1&&r.mejor_ceros==c.mejor_ceros)?"OK ":"MAL",(long long)h,r.mejor_ceros,c.mejor_ceros);
    f|=!(h==200000&&r.encontrado==-1&&r.mejor_ceros==c.mejor_ceros);
    auto t0=std::chrono::steady_clock::now(); uint64_t tot=0;
    /* Tandas pequeñas: en lavapipe (la CPU haciendo de GPU) una grande pasaría del límite de 10 s. */
    for(int i=0;i<4;i++){ h=gpu_min_tanda(g,cab,(uint32_t)i<<20,1u<<20,cero,&r); if(h<0){ printf("MAL  la tanda %d falló\n",i); f=1; break; } tot+=(uint64_t)h; }
    double s=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
    printf("     %.1f MH/s\n",tot/s/1e6);
    gpu_min_destruir(g);
    return f;
}
