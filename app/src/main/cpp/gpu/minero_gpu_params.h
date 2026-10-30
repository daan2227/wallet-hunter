#pragma once
/* Lo que la CPU le pasa al shader del minero y lo que lee de vuelta (mismo orden que los buffers std430 de
 * minero.comp). Sin Vulkan: lo usa también la prueba en C++ (tools/ec-harness/minero_gpu.cpp). Requiere minero.h. */

struct MinGpuParams { uint32_t mid[8], cola[4], obj[8], base, limite, iter, relleno; };
struct MinGpuRes { uint32_t encontrado, mejorZ, mejorN, relleno; };

static inline void min_gpu_params(MinGpuParams *p, const uint8_t *cab, uint32_t desde, uint32_t cuantos, const uint32_t *obj, uint32_t iter){
    memcpy(p->mid,MIN_H0,32); sha256_block(p->mid,cab);       /* una vez por tanda: la de software basta */
    for(int i=0;i<3;i++) p->cola[i]=((uint32_t)cab[64+4*i]<<24)|((uint32_t)cab[65+4*i]<<16)|((uint32_t)cab[66+4*i]<<8)|cab[67+4*i];
    p->cola[3]=0;
    memcpy(p->obj,obj,32);
    p->base=desde; p->limite=cuantos; p->iter=iter; p->relleno=0;
}

static inline void min_gpu_res_iniciar(MinGpuRes *r){ r->encontrado=0xffffffffu; r->mejorZ=0; r->mejorN=0; r->relleno=0; }

/* Como min_tanda: si hay premio, cuenta los hashes hasta él (incluido); si no, todos. */
static inline uint64_t min_gpu_leer(const MinGpuRes *r, uint32_t desde, uint32_t cuantos, MinResultado *mr){
    mr->mejor_ceros=(int)r->mejorZ; mr->mejor_nonce=desde+r->mejorN;
    if(r->encontrado<cuantos){ mr->encontrado=(int64_t)(desde+r->encontrado); return (uint64_t)r->encontrado+1; }
    mr->encontrado=-1; return cuantos;
}
