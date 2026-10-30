#pragma once
/* Lo que la CPU le pasa al shader del minero y lo que lee de vuelta (mismo orden que los buffers std430 de
 * minero.comp). Sin Vulkan: lo usa también la prueba en C++ (tools/ec-harness/minero_gpu.cpp). Requiere minero.h. */

struct MinGpuParams { uint32_t mid[8], cola[4], obj[8], base, limite, iter, relleno; };
#define MIN_GPU_CAND 256
struct MinGpuRes { uint32_t minCand, mejorZ, mejorN, n, cand[MIN_GPU_CAND]; };

static inline void min_gpu_params(MinGpuParams *p, const uint8_t *cab, uint32_t desde, uint32_t cuantos, const uint32_t *obj, uint32_t iter){
    memcpy(p->mid,MIN_H0,32); sha256_block(p->mid,cab);       /* una vez por tanda: la de software basta */
    for(int i=0;i<3;i++) p->cola[i]=((uint32_t)cab[64+4*i]<<24)|((uint32_t)cab[65+4*i]<<16)|((uint32_t)cab[66+4*i]<<8)|cab[67+4*i];
    p->cola[3]=0;
    memcpy(p->obj,obj,32);
    p->base=desde; p->limite=cuantos; p->iter=iter; p->relleno=0;
}

static inline void min_gpu_res_iniciar(MinGpuRes *r){ r->minCand=0xffffffffu; r->mejorZ=0; r->mejorN=0; r->n=0; }

/* Como min_tanda: comprueba los candidatos de la GPU con el hash entero (en orden) y, si hay premio, cuenta
 * los hashes hasta él (incluido); si no, todos. Si hubo más candidatos de los que caben, el menor siempre está. */
static inline uint64_t min_gpu_leer(const MinGpuRes *r, const uint8_t *cab, uint32_t desde, uint32_t cuantos, const uint32_t *obj, MinResultado *mr){
    mr->mejor_ceros=(int)r->mejorZ; mr->mejor_nonce=desde+r->mejorN; mr->encontrado=-1;
    uint32_t c[MIN_GPU_CAND+1]; uint32_t n=r->n<MIN_GPU_CAND?r->n:MIN_GPU_CAND;
    for(uint32_t i=0;i<n;i++) c[i]=r->cand[i];
    if(r->minCand<cuantos) c[n++]=r->minCand;
    for(uint32_t i=1;i<n;i++){ uint32_t v=c[i]; uint32_t j=i; while(j>0 && c[j-1]>v){ c[j]=c[j-1]; j--; } c[j]=v; }
    for(uint32_t i=0;i<n;i++){
        if(c[i]>=cuantos || (i>0 && c[i]==c[i-1])) continue;
        MinResultado t; min_tanda<sha256_block>(cab,desde+c[i],1,obj,&t);
        if(t.mejor_ceros>mr->mejor_ceros || (t.mejor_ceros==mr->mejor_ceros && mr->mejor_ceros>=32)){ mr->mejor_ceros=t.mejor_ceros; mr->mejor_nonce=t.mejor_nonce; }
        if(t.encontrado>=0){ mr->encontrado=t.encontrado; return (uint64_t)c[i]+1; }
    }
    return cuantos;
}
