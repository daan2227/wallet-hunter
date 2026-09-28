/* El indice de direcciones de 9 bytes (indice9.h): se reconoce, no se
 * confunde con el formato viejo, encuentra lo que hay (con su tipo), no
 * encuentra lo que no hay, y el filtro de Bloom deja pasar poco. */
#include <stdio.h>
#include <stdlib.h>
#include <vector>
#include <algorithm>
#include <array>
#include "../../app/src/main/cpp/indice9.h"
static uint64_t s=0x9E3779B97F4A7C15ULL; static uint64_t rnd(){ s^=s<<13; s^=s>>7; s^=s<<17; return s; }
int main(){
    int fallos=0;
    const int N=400000;
    std::vector<std::array<uint8_t,9>> v(N);
    static const uint8_t TIPOS[5]={0,1,2,3,9};
    for(auto &r: v){ r[0]=TIPOS[rnd()%5]; uint64_t h=rnd(); memcpy(&r[1],&h,8); }
    std::sort(v.begin(),v.end());
    const char *ruta="/tmp/indice9_prueba.bin";
    FILE *f=fopen(ruta,"wb"); for(auto &r: v) fwrite(r.data(),1,9,f); fclose(f);
    Indice9 x;
    int ok=i9_abrir(&x,ruta,nullptr);
    printf("%s  se reconoce y se abre (%llu registros)\n",(ok&&x.n==(uint64_t)N)?"OK ":"MAL",(unsigned long long)x.n);
    fallos+=!(ok&&x.n==(uint64_t)N);
    long no=0, tipo_mal=0;
    for(int i=0;i<N;i+=7){
        int64_t r=i9_buscar(&x,v[i][0],&v[i][1]); if(r<0) no++;
        uint8_t otro=(uint8_t)(v[i][0]==0?1:0);
        if(i9_buscar(&x,otro,&v[i][1])>=0) tipo_mal++;   /* mismo hash, otro tipo: no esta */
    }
    printf("%s  todos los que estan se encuentran (%ld fallan)\n",no?"MAL":"OK ",no); fallos+=no!=0;
    printf("%s  el tipo cuenta: mismo hash con otro tipo no se encuentra (%ld)\n",tipo_mal?"MAL":"OK ",tipo_mal); fallos+=tipo_mal!=0;
    long fp=0, pasa=0; const long M=2000000;
    for(long i=0;i<M;i++){
        uint8_t h[8]; uint64_t r=rnd(); memcpy(h,&r,8);
        if(i9_bloom_ver(&x,i9_hash(0,h))) pasa++;
        if(i9_buscar(&x,0,h)>=0) fp++;
    }
    printf("%s  hashes al azar: %ld encontrados, el filtro deja pasar el %.3f%%\n",(fp==0&&pasa*100.0/M<0.5)?"OK ":"MAL",fp,pasa*100.0/M);
    fallos+=!(fp==0&&pasa*100.0/M<0.5);
    i9_cerrar(&x);
    /* Formato viejo: cuenta de 8 bytes + 20 por direccion, con un n que dé un
       tamano multiplo de 9 (el caso que podria confundirse). */
    {
        uint64_t n; size_t len=0;
        for(n=1;n<200;n++){ len=8+n*20; if(len%9==0) break; }
        std::vector<uint8_t> b(len,0); memcpy(b.data(),&n,8);
        f=fopen(ruta,"wb"); fwrite(b.data(),1,len,f); fclose(f);
        Indice9 y; int r=i9_abrir(&y,ruta,nullptr);
        printf("%s  un .bin del formato viejo (n=%llu, %zu bytes, multiplo de 9) no se toma por indice\n",r?"MAL":"OK ",(unsigned long long)n,len);
        fallos+=r!=0; i9_cerrar(&y);
    }
    remove(ruta);
    printf("\n%s\n",fallos?"HAY FALLOS":"TODO CORRECTO");
    return fallos;
}
