/* Cuanto miden los ciclos esteriles del mapa de negacion.
 *
 * Es el numero que dimensiona KG_VENTANA, la memoria con la que cada canguro
 * detecta que esta dando vueltas. Si la ventana se queda corta, el canguro se
 * queda atrapado PARA SIEMPRE: sigue saltando, sigue contando, y no vuelve a
 * producir un punto distinguido en su vida. Con la red de seguridad de
 * pasos_sin_dp ya no cuelga el motor, pero cada canguro atrapado son 20 veces
 * 2^dbits pasos tirados.
 *
 * Asi que hay que saber cuanto miden de verdad, y no suponerlo: se sueltan
 * muchos canguros SIN detector ninguno y se mira en que ciclo acaban.
 *
 * Lo que salio con njumps=15 (un rango de juguete) confundia: habia ciclos de
 * 82. A tamano real, con njumps=75, la cosa es otra — y es la que importa.
 *
 * Uso: ./ciclos [muestras] [tope] [bits]
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <map>
#include <vector>
#include <pthread.h>
#include "../../app/src/main/cpp/kangaroo.h"
static void be(uint8_t*b,unsigned long long v){memset(b,0,32);for(int i=0;i<8;i++)b[31-i]=(uint8_t)(v>>(8*i));}
static void pub_de(unsigned long long k,uint8_t*p){
    sc_t s; sc_set_u64(s,k); JP P; kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);
    fe_t x,y; kg_normalize(&P,x,y); p[0]=(y[0]&1)?3:2;
    for(int w=0;w<4;w++)for(int b2=0;b2<8;b2++)p[1+(3-w)*8+(7-b2)]=(uint8_t)(x[w]>>(b2*8));
}
/* Camina un manso SIN detector de ciclos y mide la longitud del ciclo en el que
   acaba y cuanto tarda en caer en el. Es lo que hay que saber para dimensionar
   el detector: si los ciclos son de 2 y de 4, un detector corto vale; si son de
   cientos, no hay detector que valga. */
int main(int argc,char**argv){
    setvbuf(stdout,NULL,_IONBF,0);
    kg_negacion=1;
    int muestras=argc>1?atoi(argv[1]):4000;
    int tope=argc>2?atoi(argv[2]):4000;
    int bits=argc>3?atoi(argv[3]):139;
    unsigned long long a=1ULL<<24,b=(1ULL<<25)-1,k=a+12345;
    uint8_t pub[33],i2[32],f2[32]; pub_de(k,pub);
    /* Rango grande solo para que njumps suba; la clave no se busca. */
    memset(i2,0,32); i2[31-bits/8]=(uint8_t)(1u<<(bits%8));
    memset(f2,0,32); for(int q=0;q<=bits;q++) f2[31-q/8]|=(uint8_t)(1u<<(q%8));
    (void)a;(void)b;
    /* El dbits que usaria la app para este rango (bits/4+4, hasta 28). Con
       Gaudry-Schost el tamano de los saltos sale del dbits —ver kg_setup—, y
       con un dbits de juguete los saltos son cortos y el camino se pisa a si
       mismo: salian ciclos de 180 que en el movil no existen. */
    int dapp=bits/4+4; if(dapp<6) dapp=6; if(dapp>28) dapp=28;
    KangarooCtx c; kg_setup(&c,pub,i2,f2,dapp,18);
    printf("bits=%d dbits=%d njumps=%d\n",bits,dapp,c.njumps);
    std::map<int,int> hist; int sin_ciclo=0; long long suma_cola=0, suma_len=0; int n=0;
    for(int s=0;s<muestras;s++){
        sc_t d; sc_set_u64(d,1000+s*7919);
        JP P; kg_scalar_mul(&P,d,FIELD_GX,FIELD_GY);
        fe_t X,Y; kg_normalize(&P,X,Y);
        int eps=1;
        if(Y[0]&1){ fe_t z;memset(z,0,32);fe_sub(Y,z,Y);eps=-1; }
        std::map<unsigned long long,int> cuando;
        int len=-1, cola=-1;
        for(int t=0;t<tope;t++){
            auto it=cuando.find(X[0]);
            if(it!=cuando.end()){ cola=it->second; len=t-it->second; break; }
            cuando[X[0]]=t;
            int h=(int)(X[0]%(uint64_t)c.njumps);
            fe_t den; fe_sub(den,c.jx[h],X);
            int z0=1;for(int j=0;j<4;j++)if(den[j])z0=0;
            if(z0) break;
            fe_t dinv; fe_inv(dinv,den);
            fe_t lam,t1,x3,y3;
            fe_sub(t1,c.jy[h],Y); fe_mul(lam,t1,dinv);
            fe_sqr(x3,lam); fe_sub(x3,x3,X); fe_sub(x3,x3,c.jx[h]);
            fe_sub(t1,X,x3); fe_mul(y3,lam,t1); fe_sub(y3,y3,Y);
            if(eps>0) sc_add_n(d,d,c.jlen[h]); else sc_sub_n(d,d,c.jlen[h]);
            if(y3[0]&1){ fe_t z;memset(z,0,32);fe_sub(y3,z,y3); eps=-eps; }
            memcpy(X,x3,32); memcpy(Y,y3,32);
        }
        if(len<0){ sin_ciclo++; continue; }
        hist[len]++; suma_cola+=cola; suma_len+=len; n++;
    }
    printf("de %d canguros, %d no ciclaron en %d pasos\n",muestras,sin_ciclo,tope);
    if(n){
        printf("cola media hasta caer en el ciclo: %lld pasos\n",suma_cola/n);
        printf("longitud media del ciclo: %lld\n",suma_len/n);
        int largos=0; for(auto&p:hist) if(p.first>4) largos+=p.second;
        printf("ciclos de mas de 4: %d de %d  (%.1f%%)\n",largos,n,100.0*largos/n);
        printf("reparto:"); for(auto&p:hist) printf("  %d:%d",p.first,p.second);
        printf("\n");
    }
    /* Lo que hay que sujetar: que la ventana llega. Si alguna vez aparece un
       ciclo mas largo que KG_VENTANA a tamano real, esto salta y hay que
       agrandarla (o repensar el escape) antes de que se note en el movil como
       "va mas lento y no se sabe por que". */
    int fallos=0;
    if(bits>=118){
        int mayor=0; for(auto&p:hist) if(p.first>mayor) mayor=p.first;
        int pasa = (mayor>0 && mayor<=KG_VENTANA);
        printf("  %s el ciclo mas largo mide %d y la ventana es %d\n",
               pasa?"OK ":"MAL",mayor,KG_VENTANA);
        if(!pasa) fallos++;
        if(sin_ciclo){ printf("  MAL  %d canguros no ciclaron: la medida no vale\n",
                              sin_ciclo); fallos++; }
    }
    printf("\n%s\n", fallos?"HAY FALLOS":"TODO CORRECTO");
    kg_free(&c); return fallos?1:0;
}
