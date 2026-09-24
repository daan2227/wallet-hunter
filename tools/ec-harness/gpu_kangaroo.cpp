/* Kangaroo en la GPU, contra la verdad: con Vulkan por software (lavapipe) o
 * cualquier GPU del escritorio.
 *
 *   g++ -O2 -o gpu_kangaroo gpu_kangaroo.cpp -lvulkan -lpthread && ./gpu_kangaroo
 *
 * 1. Cada punto distinguido que devuelve la GPU tiene que ser de verdad el que
 *    dice su distancia: el manso en d*G, el salvaje en P''+d*G (salvo el
 *    signo, que la x no ve).
 * 2. Y resolver claves conocidas solo con la GPU, sin hilos de CPU.
 * No va en run.sh: la CI no tiene Vulkan. */
#include <stdio.h>
#include <math.h>
#include "../../app/src/main/cpp/kangaroo.h"
#include "../../app/src/main/cpp/gpu/gpu_kangaroo.h"
#include "../../app/src/main/cpp/gpu/kangaroo_spv.h"
static void be(uint8_t*b,unsigned long long v){memset(b,0,32);for(int i=0;i<8;i++)b[31-i]=(uint8_t)(v>>(8*i));}
static void pub_de(unsigned long long k,uint8_t*p){sc_t s;sc_set_u64(s,k);JP P;kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);fe_t x,y;kg_normalize(&P,x,y);p[0]=(y[0]&1)?3:2;for(int w=0;w<4;w++)for(int b2=0;b2<8;b2++)p[1+(3-w)*8+(7-b2)]=(uint8_t)(x[w]>>(b2*8));}
int main(){
    int fallos=0;
    kg_negacion=1;
    /* 1) Invariante de los distinguidos. */
    {
        int bits=40; unsigned long long a=1ULL<<(bits-1), k=a+0x123456789ULL;
        uint8_t pub[33],ini[32],fin[32]; pub_de(k,pub); be(ini,a); be(fin,(1ULL<<bits)-1);
        KangarooCtx c; kg_setup(&c,pub,ini,fin,6,20);
        std::string err; GpuKg *g=gpu_kg_crear(&c,KANGAROO_SPV,sizeof(KANGAROO_SPV),256,8,0xABCDEF,err);
        if(!g){ printf("MAL  no arranca la GPU: %s\n",err.c_str()); return 1; }
        printf("GPU: %s\n",g->nombre.c_str());
        /* Una tanda a mano, mirando los distinguidos antes de que se recojan. */
        int vistos=0, malos=0;
        for(int t=0;t<20 && vistos<200;t++){
            VkCommandBufferAllocateInfo cba{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; (void)cba;
            uint32_t *S=(uint32_t*)g->map[2];
            /* se deja correr una tanda normal pero antes de recoger se miran */
            uint32_t antes=S[0]; (void)antes;
            gpu_kg_tanda(g,&c,32);
            /* gpu_kg_tanda ya los ha recogido: se miran en la tabla */
            for(uint64_t i=0;i<=c.tabla.mask;i++){
                DP *sl=&c.tabla.slots[i]; if(!sl->usado) continue;
                JP P; kg_scalar_mul(&P,sl->dist,FIELD_GX,FIELD_GY);
                int inf=1; for(int z=0;z<4;z++) if(P.z[z]) inf=0;
                if(!sl->manso){ if(inf) continue; fe_t dx,dy; kg_normalize(&P,dx,dy); JP W2; jp_add_affine(&W2,&c.objetivo,dx,dy); P=W2; }
                fe_t x,y; kg_normalize(&P,x,y);
                vistos++; if(x[0]!=sl->kx[0]||x[1]!=sl->kx[1]) malos++;
            }
        }
        printf("%s  %d distinguidos de la GPU, %d que no estan donde dice su distancia\n",
               (vistos>0&&malos==0)?"OK ":"MAL",vistos,malos);
        fallos+=!(vistos>0&&malos==0);
        printf("     saltos de la GPU: %lld\n",c.saltos.load());
        gpu_kg_destruir(g); kg_free(&c);
    }
    /* 2) Resolver solo con la GPU. */
    int bl[]={28,32,36};
    for(int bi=0;bi<3;bi++){
        int bits=bl[bi]; int dbits=bits/4+2;
        unsigned long long a=1ULL<<(bits-1), k=a+((0x9E3779B97F4A7C15ULL*(bi+1))%(a-1));
        uint8_t pub[33],ini[32],fin[32]; pub_de(k,pub); be(ini,a); be(fin,(1ULL<<bits)-1);
        KangarooCtx c; kg_setup(&c,pub,ini,fin,dbits,20);
        std::string err; GpuKg *g=gpu_kg_crear(&c,KANGAROO_SPV,sizeof(KANGAROO_SPV),256,8,0x1234+bi,err);
        if(!g){ printf("MAL  %s\n",err.c_str()); return 1; }
        auto t0=std::chrono::steady_clock::now();
        std::thread vig([&]{ while(!c.encontrado.load()){ if(std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count()>300){ c.parar.store(1); break;} std::this_thread::sleep_for(std::chrono::milliseconds(50)); } });
        gpu_kg_correr(g,&c);
        c.parar.store(1); vig.join();
        sc_t kk; sc_set_u64(kk,k);
        int ok=c.encontrado.load() && sc_cmp(c.k,kk)==0;
        double raiz=sqrt((double)(a-1));
        printf("%s  %d bits, dbits %d: %s (%.2f x raiz(W), %.1f s)\n", ok?"OK ":"MAL", bits, dbits,
               ok?"clave correcta":"no la encuentra", c.saltos.load()/raiz,
               std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count());
        fallos+=!ok;
        gpu_kg_destruir(g); kg_free(&c);
    }
    printf("\n%s\n",fallos?"HAY FALLOS":"TODO CORRECTO");
    return fallos;
}
