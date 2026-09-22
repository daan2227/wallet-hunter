/* Cuanto cuesta de verdad este Kangaroo.
 *
 * El coste de Kangaroo se mide en unidades de raiz(W): "este motor tarda C
 * veces raiz(W) operaciones de grupo". C es LA cifra del algoritmo — el doble
 * de C es el doble de dias de movil encendido — y hasta ahora no estaba medida
 * en ningun sitio. Habia un numero apuntado de memoria de una tanda a mano
 * ("3,4-4,6"), que es lo mismo que no tener nada: no se puede saber si un
 * cambio lo mejora si el punto de partida no es reproducible.
 *
 * Esto lo convierte en medida. Resuelve muchos logaritmos discretos de tamano
 * pequeno —pequeno para que quepan en segundos, no por otra cosa— y divide las
 * operaciones gastadas entre raiz(W).
 *
 * LO IMPORTANTE NO ES LA MEDIA, ES EL REPARTO POR CUARTIL.
 *
 * La teoria dice que con el reparto de salida actual (mansos al azar por
 * [0,W), salvajes en P'+[0,W), o sea por [k,k+W)) los dos rebanos solo se
 * pisan en [k,W). Cuanto mas arriba este la clave dentro del rango, menos
 * terreno comun hay, y como ademas todos los saltos van hacia delante, un
 * salvaje que salga por encima de W no puede cruzarse jamas con el rastro de
 * un manso. Sale coste = 2/raiz(1-k/W) veces raiz(W), que promediado sobre k
 * uniforme da 4.
 *
 * O sea: si la teoria es correcta, el ultimo cuartil tiene que costar mas del
 * DOBLE que el primero. Si sale plano, la explicacion es otra y hay que
 * buscarla en otro sitio antes de tocar nada.
 *
 * Uso: ./constante [bits] [n_kang] [dbits] [tandas] [politica]
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <pthread.h>
#include "../../app/src/main/cpp/kangaroo.h"

static void be_from_u64(uint8_t *be,unsigned long long v){
    memset(be,0,32); for(int i=0;i<8;i++) be[31-i]=(uint8_t)(v>>(8*i));
}

static void pub_de(unsigned long long k,uint8_t *pub33){
    sc_t s; sc_set_u64(s,k);
    JP P; kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);
    fe_t x,y; kg_normalize(&P,x,y);
    pub33[0]=(y[0]&1)?0x03:0x02;
    for(int w=0;w<4;w++) for(int b=0;b<8;b++)
        pub33[1+(3-w)*8+(7-b)]=(uint8_t)(x[w]>>(b*8));
}

/* Generador propio: rand() cambia de una libreria a otra y estas cifras hay
   que poder compararlas entre maquinas. Va por hilo. */
static uint64_t sig(uint64_t *r){
    *r^=*r<<13; *r^=*r>>7; *r^=*r<<17; return *r;
}

typedef struct {
    double media, mediana, peor;
    double cuartil[4];
    double error;            /* error tipico de la media */
    int    fallos;
    int    tandas;
    double pegados;          /* canguros pegados a otro de su rebano, por tanda */
} Resultado;

/* Una tanda, aislada, para poder repartirlas entre hilos. Cada hilo lleva su
 * propio generador: si compartieran uno, el reparto dependeria del orden en que
 * los planifique el sistema y dos ejecuciones no darian lo mismo. */
typedef struct {
    int bits,n_kang,dbits,politica,shift,soltar_muertos;
    int desde,hasta,paso,tandas;
    uint64_t semilla;
    double *coste;           /* [tandas], 0 = no resuelta */
    long long *pegados;      /* [tandas] */
} Trabajo;

static void *hilo(void *p){
    Trabajo *w=(Trabajo*)p;
    unsigned long long W=1ULL<<w->bits;
    unsigned long long a=W, b=2ULL*W-1;      /* rango [W, 2W-1], ancho W-1 */
    double raizW=sqrt((double)W);
    for(int t=w->desde;t<w->hasta;t+=w->paso){
        /* Cada tanda con su semilla, sacada del indice: asi el resultado no
           depende de cuantos hilos haya ni de en que orden acaben. */
        uint64_t r=w->semilla ^ (0x9E3779B97F4A7C15ULL*(uint64_t)(t+1));
        if(!r) r=1;
        /* u = donde cae la clave dentro del rango, 0 abajo y 1 arriba. Se
           reparte a proposito por los cuatro cuartiles en vez de dejarlo al
           azar: con pocas tandas el azar deja cuartiles casi vacios y la
           comparacion que interesa se queda sin datos. */
        int cuart=t%4;
        double u=((double)cuart+(double)(sig(&r)>>11)/9007199254740992.0)/4.0;
        unsigned long long k=a+(unsigned long long)(u*(double)(W-1));
        if(k<a) k=a;
        if(k>b) k=b;

        uint8_t pub[33],ini[32],fin[32];
        pub_de(k,pub); be_from_u64(ini,a); be_from_u64(fin,b);
        KangarooCtx c;
        if(!kg_setup(&c,pub,ini,fin,w->dbits,18)) continue;
        /* politica < 0 = no tocar nada: se mide LO QUE TRAE kg_setup. Es la
           unica variante que sirve de red de seguridad, porque es la que corre
           en el movil. Si alguien cambia el valor por defecto y lo empeora,
           tiene que saltar aqui y no en una tabla de puntos dentro de un mes. */
        if(w->politica>=0){
            c.politica_salida=w->politica;
            c.salvaje_shift=w->shift;
        }
        c.soltar_muertos=w->soltar_muertos;
        kg_run(&c,w->n_kang,sig(&r)|1ULL);
        int ok = c.encontrado.load() && c.k[0]==k && !c.k[1] && !c.k[2] && !c.k[3];
        if(ok) w->coste[t]=(double)c.saltos.load()/raizW;
        w->pegados[t]=c.pegados.load();
        kg_free(&c);
    }
    return NULL;
}

static int cmp_d(const void *a,const void *b){
    double x=*(const double*)a, y=*(const double*)b;
    return x<y?-1:(x>y?1:0);
}

#define HILOS 4

/* Techo del coste con los valores por defecto del banco (28 bits, 64 canguros).
 *
 * Medido 2,40 +- 0,13, y sale identico desde una copia limpia en otra maquina:
 * esto cuenta operaciones, no segundos, asi que no depende de lo rapido que sea
 * el ordenador. El 2,9 deja cuatro veces el error de holgura.
 *
 * Lo que tiene que saltar es una vuelta a la politica de salida vieja, que en
 * esta misma configuracion da 4,16. */
#define TECHO 2.9

static Resultado medir(int bits,int n_kang,int dbits,int tandas,int politica,
                       int shift,int soltar_muertos,uint64_t semilla){
    Resultado r; memset(&r,0,sizeof(r));
    r.tandas=tandas;
    double *cs=(double*)calloc(tandas,sizeof(double));
    long long *pg=(long long*)calloc(tandas,sizeof(long long));

    Trabajo w[HILOS]; pthread_t th[HILOS];
    for(int j=0;j<HILOS;j++){
        w[j].bits=bits; w[j].n_kang=n_kang; w[j].dbits=dbits;
        w[j].politica=politica; w[j].shift=shift;
        w[j].soltar_muertos=soltar_muertos;
        w[j].desde=j; w[j].hasta=tandas; w[j].paso=HILOS; w[j].tandas=tandas;
        w[j].semilla=semilla; w[j].coste=cs; w[j].pegados=pg;
        pthread_create(&th[j],NULL,hilo,&w[j]);
    }
    for(int j=0;j<HILOS;j++) pthread_join(th[j],NULL);

    double suma_c[4]={0,0,0,0}; int n_c[4]={0,0,0,0};
    double suma=0, suma2=0, sp=0; int validas=0;
    for(int t=0;t<tandas;t++){
        sp+=(double)pg[t];
        if(cs[t]<=0){ r.fallos++; continue; }
        validas++; suma+=cs[t]; suma2+=cs[t]*cs[t];
        suma_c[t%4]+=cs[t]; n_c[t%4]++;
        if(cs[t]>r.peor) r.peor=cs[t];
    }
    /* Ordenar para la mediana. La media de esta distribucion tiene una cola
       larga —el caso malo es muy malo— asi que la mediana dice mas sobre el
       dia a dia y la media sobre el total de dias. Van las dos. */
    qsort(cs,tandas,sizeof(double),cmp_d);
    int prim=tandas-validas;                 /* los ceros quedan delante */
    r.media = validas? suma/validas : 0;
    r.mediana = validas? cs[prim+validas/2] : 0;
    r.pegados = tandas? sp/tandas : 0;
    /* Error de la media. Sin esto no se puede decidir nada: la diferencia entre
       dos variantes solo significa algo si es mayor que este numero. La primera
       tanda de esta prueba daba un barrido que subia y bajaba sin orden, y era
       ruido puro: no habia con que verlo. */
    if(validas>1){
        double var=(suma2-suma*suma/validas)/(validas-1);
        r.error = var>0? sqrt(var/validas) : 0;
    }
    for(int q=0;q<4;q++) r.cuartil[q]= n_c[q]? suma_c[q]/n_c[q] : 0;
    free(cs); free(pg);
    return r;
}

static void pinta(const char *etiq,Resultado r){
    printf("  %-24s %5.2f +- %.2f   mediana %5.2f  peor %6.2f   "
           "cuartiles %5.2f %5.2f %5.2f %5.2f   pegados %.1f%s\n",
           etiq,r.media,r.error,r.mediana,r.peor,
           r.cuartil[0],r.cuartil[1],r.cuartil[2],r.cuartil[3],r.pegados,
           r.fallos?"  *** CON FALLOS ***":"");
}

int main(int argc,char **argv){
    setvbuf(stdout,NULL,_IONBF,0);
    int bits   = argc>1? atoi(argv[1]) : 30;
    int n_kang = argc>2? atoi(argv[2]) : 64;
    int dbits  = argc>3? atoi(argv[3]) : 5;
    int tandas = argc>4? atoi(argv[4]) : 48;
    int barrido= argc>5? atoi(argv[5]) : 0;   /* 1 = probar todos los shift */
    const uint64_t SEM=0x5EED1234ULL;   /* la misma para todas: se comparan */

    printf("Coste en unidades de raiz(W). Intervalo de %d bits, %d canguros,\n"
           "dbits %d, %d tandas repartidas por cuartil de la posicion de k.\n"
           "Todas las variantes con la MISMA semilla, o no serian comparables.\n\n",
           bits,n_kang,dbits,tandas);

    int fallos=0;
    char etiq[64];

    /* Lo que corre en el movil, tal cual sale de kg_setup. */
    Resultado hoy=medir(bits,n_kang,dbits,tandas,-1,0,1,SEM);
    pinta("LA DE AHORA (kg_setup)",hoy);
    if(hoy.fallos) fallos++;
    if(hoy.media>TECHO){
        printf("      *** %.2f pasa del techo de %.2f. El motor ha empeorado. ***\n",
               hoy.media,(double)TECHO);
        fallos++;
    }
    printf("\n");

    Resultado base=medir(bits,n_kang,dbits,tandas,0,0,1,SEM);
    pinta("politica 0 (ancha)",base);
    if(base.fallos) fallos++;
    {
        /* La comprobacion que de verdad importa no es la media, es la FORMA. Si
           el coste sube con la posicion de la clave, el problema es el solape
           de los dos rebanos y no otra cosa. */
        double q1=base.cuartil[0], q4=base.cuartil[3];
        printf("      ultimo cuartil / primero = %.2f  ->  %s\n", q1>0? q4/q1:0.0,
               (q1>0 && q4/q1>1.6)
                 ? "sube con k: es el solape de los rebanos"
                 : "plano: la perdida NO viene del solape");
    }

    Resultado juntos=medir(bits,n_kang,dbits,tandas,1,0,1,SEM);
    pinta("politica 1 (juntos)",juntos);
    if(juntos.fallos) fallos++;

    printf("\n");
    Resultado mejor=base; int mejor_shift=-1;
    int shifts[]={0,1,2,3,4,6};
    int n_sh = barrido? (int)(sizeof(shifts)/sizeof(shifts[0])) : 3;
    for(int i=0;i<n_sh;i++){
        Resultado r=medir(bits,n_kang,dbits,tandas,2,shifts[i],1,SEM);
        snprintf(etiq,sizeof(etiq),"politica 2 shift %d",shifts[i]);
        pinta(etiq,r);
        if(r.fallos) fallos++;
        if(!r.fallos && r.media>0 && r.media<mejor.media){ mejor=r; mejor_shift=shifts[i]; }
    }

    /* Saltos al azar frente a potencias de dos. La tabla de potencias de dos
       tiene la mitad de los saltos astronomicamente mas pequenos que la media,
       que no es lo que supone el analisis. Se mide con la mejor politica de
       salida para no mezclar dos cambios. */
    printf("\n");
    kg_politica_saltos=1;
    Resultado az=medir(bits,n_kang,dbits,tandas,
                       mejor_shift<0?0:2, mejor_shift<0?0:mejor_shift, 1, SEM);
    kg_politica_saltos=0;
    pinta("la mejor, saltos al azar",az);
    if(az.fallos) fallos++;

    /* Cuanto vale volver a soltar al canguro pegado. Se apaga y se vuelve a
       medir: un arreglo cuyo numero no se mueve al quitarlo no esta arreglando
       nada. */
    printf("\n");
    Resultado sin=medir(bits,n_kang,dbits,tandas,
                        mejor_shift<0?0:2, mejor_shift<0?0:mejor_shift, 0, SEM);
    pinta("la mejor, SIN soltar muertos",sin);
    if(sin.fallos) fallos++;

    printf("\nResumen\n");
    printf("  politica 0 (la de antes)       %5.2f +- %.2f  raiz(W)\n",
           base.media,base.error);
    if(mejor_shift>=0){
        /* Solo cuenta como mejora si la diferencia se sale de los errores de
           las dos medidas. Si no, es la misma medida dos veces. */
        double e=sqrt(base.error*base.error+mejor.error*mejor.error);
        double dif=base.media-mejor.media;
        printf("  politica 2 shift %-2d (la nueva) %5.2f +- %.2f  raiz(W)  ->  %.2f veces\n",
               mejor_shift, mejor.media, mejor.error, base.media/mejor.media);
        printf("  diferencia %.2f, margen %.2f  ->  %s\n", dif, 2*e,
               dif>2*e ? "de verdad" : "DENTRO DEL RUIDO: no decide nada");
        printf("  reparto por cuartil            %.2f %.2f %.2f %.2f  (%.2f de punta a punta)\n",
               mejor.cuartil[0],mejor.cuartil[1],mejor.cuartil[2],mejor.cuartil[3],
               mejor.cuartil[0]>0? mejor.cuartil[3]/mejor.cuartil[0] : 0.0);
    }else{
        printf("  ninguna variante mejora la politica 0\n");
    }
    if(az.media>0 && mejor.media>0){
        double e=sqrt(az.error*az.error+mejor.error*mejor.error);
        double dif=mejor.media-az.media;
        printf("  saltos al azar                 %.2f (%.2f veces)  ->  %s\n",
               az.media, mejor.media/az.media,
               (dif>2*e||-dif>2*e) ? "diferencia de verdad" : "dentro del ruido");
    }
    if(sin.media>0 && mejor.media>0){
        double e=sqrt(sin.error*sin.error+mejor.error*mejor.error);
        printf("  soltar a los muertos           %.2f veces (margen %.2f)%s\n",
               sin.media/mejor.media, 2*e/mejor.media,
               mejor.pegados<1.0? "   apenas ocurre, ver abajo":"");
        if(mejor.pegados<1.0)
            printf("    A este tamano un canguro se pega a otro de su rebano %.2f veces\n"
                   "    por tanda, asi que el arreglo casi no tiene ocasion de actuar y\n"
                   "    su efecto NO se puede medir aqui. Se queda por correccion, no\n"
                   "    por velocidad, y el contador 'pegados' es lo que hay que mirar.\n",
                   mejor.pegados);
    }

    printf("\n%s\n", fallos?"HAY FALLOS":"TODO CORRECTO");
    return fallos?1:0;
}
