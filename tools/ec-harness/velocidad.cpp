/* Cuantos saltos por segundo da el motor.
 *
 * POR QUE EXISTE. `constante` mide OPERACIONES por clave, no segundos, y eso fue
 * a proposito: asi la cifra no depende de lo rapido que sea el ordenador y se
 * puede comparar entre maquinas. Pero deja un punto ciego enorme, y se cayo en
 * el: el mapa de negacion bajo el coste a 1,38 veces menos operaciones... y de
 * paso hundio la velocidad. En el movil se vio como 618 K/s donde antes habia
 * 10 M/s. Menos operaciones, pero mucho mas lentas cada una: en neto, peor.
 *
 * Lo que de verdad importa es el producto de las dos cosas:
 *
 *     tiempo hasta la clave  =  (operaciones por clave) / (operaciones por segundo)
 *
 * asi que las dos hay que medirlas, y esta es la segunda.
 *
 * Uso: ./velocidad [bits] [n_kang] [segundos]
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <time.h>
#include "../../app/src/main/cpp/kangaroo.h"

static void pot2_be(uint8_t *be,int bits){
    memset(be,0,32); be[31-bits/8]=(uint8_t)(1u<<(bits%8));
}
static void unos_be(uint8_t *be,int bits){
    memset(be,0,32); for(int i=0;i<=bits;i++) be[31-i/8]|=(uint8_t)(1u<<(i%8));
}

typedef struct { KangarooCtx *c; int n_kang; uint64_t sem; } Arg;
static void *anda(void *p){ Arg *a=(Arg*)p; kg_run(a->c,a->n_kang,a->sem); return NULL; }

static double ahora(void){
    struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t);
    return (double)t.tv_sec + (double)t.tv_nsec/1e9;
}

/* Deja andar el motor unos segundos y devuelve saltos por segundo. */
static double mide(int bits,int n_kang,int dbits,double segundos,
                   int negacion,long long *rescatados){
    kg_negacion=negacion;
    uint8_t ini[32],fin[32],pub[33];
    pot2_be(ini,bits); unos_be(fin,bits);
    {
        sc_t s; sc_from_be32(s,ini); sc_add_u64(s,12345);
        JP P; kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);
        fe_t x,y; kg_normalize(&P,x,y);
        pub[0]=(y[0]&1)?0x03:0x02;
        for(int w=0;w<4;w++) for(int b=0;b<8;b++)
            pub[1+(3-w)*8+(7-b)]=(uint8_t)(x[w]>>(b*8));
    }
    KangarooCtx c;
    if(!kg_setup(&c,pub,ini,fin,dbits,16)) return 0;
    pthread_t h; Arg a={&c,n_kang,0xC0FFEE};
    double t0=ahora();
    pthread_create(&h,NULL,anda,&a);
    /* Dormir en trozos cortos para que el arranque no se coma la medida. */
    while(ahora()-t0 < segundos){
        struct timespec ts={0,20*1000*1000}; nanosleep(&ts,NULL);
        if(c.encontrado.load()) break;
    }
    double t1=ahora();
    c.parar.store(1); pthread_join(h,NULL);
    long long s=c.saltos.load();
    if(rescatados) *rescatados=c.rescatados.load();
    kg_free(&c);
    return (t1>t0)? (double)s/(t1-t0) : 0;
}

int main(int argc,char **argv){
    setvbuf(stdout,NULL,_IONBF,0);
    int bits   = argc>1? atoi(argv[1]) : 139;
    int n_kang = argc>2? atoi(argv[2]) : 512;
    double seg = argc>3? atof(argv[3]) : 2.0;
    /* dbits alto, como en el movil: aqui no se busca nada, solo se cuenta. */
    int dbits  = 28;

    printf("Velocidad del bucle. Intervalo de %d bits, %d canguros, un hilo.\n\n",
           bits,n_kang);

    long long r0=0,r1=0;
    double sin_neg = mide(bits,n_kang,dbits,seg,0,&r0);
    double con_neg = mide(bits,n_kang,dbits,seg,1,&r1);
    kg_negacion=1;

    printf("  sin mapa de negacion   %8.2f M saltos/s\n", sin_neg/1e6);
    printf("  con mapa de negacion   %8.2f M saltos/s   (rescatados %lld)\n",
           con_neg/1e6, r1);
    if(sin_neg<=0 || con_neg<=0){ printf("\nno se pudo medir\n"); return 1; }

    double f=sin_neg/con_neg;
    printf("\n  la negacion cuesta %.2f veces por salto\n", f);
    /* Lo que de verdad decide: hacer menos operaciones por clave sirve de algo
       solo si cada operacion no se encarece mas que eso. El 1,38 lo mide
       constante. */
    printf("  y ahorra 1,38 veces en numero de saltos\n");
    printf("  tiempo hasta la clave: %.2f veces el de antes  (menos es mejor)\n",
           f/1.38);

    /* La comprobacion es una RAZON, no una velocidad: los segundos dependen de
       la maquina y la razon no. Lo que no puede pasar es que un salto se
       encarezca mas de lo que se ahorra en numero de saltos; si eso ocurre, el
       motor va mas lento aunque la constante haya bajado, que es exactamente
       como se puede ir un cambio a peor sin que ninguna otra prueba lo note.
       El margen es amplio porque una maquina cargada mide con mucho ruido. */
    int fallos=0;
    if(f > 1.38){
        printf("  *** cada salto cuesta %.2f y solo se ahorra 1,38: EMPEORA ***\n",f);
        fallos++;
    }else{
        printf("  OK   el encarecimiento por salto se queda por debajo del ahorro\n");
    }
    printf("\n%s\n", fallos?"HAY FALLOS":"TODO CORRECTO");
    return fallos?1:0;
}
