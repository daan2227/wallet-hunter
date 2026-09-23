/* Por que el mapa de negacion deja de funcionar al subir dbits.
 *
 * El mapa de negacion vale 1,38 veces mas velocidad y esta APAGADO
 * (kg_negacion=0) porque a partir de cierto dbits dejaba de resolver. La causa
 * no estaba identificada. Dos hipotesis ya descartadas, las dos midiendo:
 *
 *   - El seguimiento de eps. Se comprobo la invariante en x Y EN y a lo largo
 *     de 20.000 pasos: cero discrepancias.
 *   - kg_resolver. Instrumentado: se llamaba CERO veces, o sea que no es que
 *     cerrara mal una colision, es que no habia colision que cerrar.
 *
 * Y una tercera, que era mia y tambien hay que descartarla aqui: ciclos mas
 * largos que KG_VENTANA. El reparto de longitudes ya esta medido en `ciclos`
 * —maximo 6 con njumps=75— asi que una ventana de 16 deberia sobrar. Si esta
 * prueba dice que `rescatados` sube con dbits, la ventana no da abasto y la
 * hipotesis vuelve; si se queda en cero, queda descartada con numeros.
 *
 * QUE MIDE. Para cada dbits, con y sin negacion, sobre claves al azar de un
 * rango de verdad: cuantas veces resuelve, cuantas operaciones cuesta, cuantos
 * puntos distinguidos llega a guardar, cuantos canguros hay que rescatar por
 * llevar demasiado sin dar uno, y cuantos se quedan pegados.
 *
 * La diferencia entre "no resuelve porque el camino esta roto" y "no resuelve
 * porque no le ha dado tiempo" se lee en los distinguidos: un camino roto no
 * guarda ninguno por muchos saltos que de.
 *
 * Uso: ./negacion [bits] [canguros] [repeticiones] [presupuesto]
 *      presupuesto = cuantas veces raiz(W) se le deja gastar antes de rendirse.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <unistd.h>
#include <vector>
#include <algorithm>
#include "../../app/src/main/cpp/kangaroo.h"

static void be(uint8_t*b,unsigned long long v){
    memset(b,0,32); for(int i=0;i<8;i++) b[31-i]=(uint8_t)(v>>(8*i));
}
static void pub_de(unsigned long long k,uint8_t*p){
    sc_t s; sc_set_u64(s,k); JP P; kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);
    fe_t x,y; kg_normalize(&P,x,y); p[0]=(y[0]&1)?3:2;
    for(int w=0;w<4;w++)for(int b2=0;b2<8;b2++)p[1+(3-w)*8+(7-b2)]=(uint8_t)(x[w]>>(b2*8));
}
static uint64_t mezcla(uint64_t &s){
    s+=0x9E3779B97F4A7C15ULL; uint64_t z=s;
    z=(z^(z>>30))*0xBF58476D1CE4E5B9ULL;
    z=(z^(z>>27))*0x94D049BB133111EBULL;
    return z^(z>>31);
}

/* kg_run corre hasta encontrar o hasta que le pares. Una configuracion rota no
 * encuentra NUNCA, asi que sin esto la prueba se cuelga en vez de fallar. */
typedef struct { KangarooCtx *c; long long tope; } Vigia;
static void* vigilar(void*p){
    Vigia*v=(Vigia*)p;
    while(!v->c->encontrado.load() && !v->c->parar.load()){
        if(v->c->saltos.load() > v->tope){ v->c->parar.store(1); break; }
        usleep(1000);
    }
    return NULL;
}

typedef struct {
    int      resuelto;
    long long saltos;
    uint64_t dps;
    long long rescatados, pegados;
    long long escapes, escapes_repe;
} Res;

static Res una(int bits,int dbits,int neg,int n_kang,
               unsigned long long clave,uint64_t semilla,long long tope){
    Res r; memset(&r,0,sizeof r);
    uint8_t pub33[33]; pub_de(clave,pub33);
    uint8_t ini[32], fin[32];
    be(ini, 1ULL<<(bits-1));
    be(fin, (bits>=64)?~0ULL:((1ULL<<bits)-1));

    kg_negacion = neg;               /* lo lee kg_setup */
    KangarooCtx c;
    if(!kg_setup(&c,pub33,ini,fin,dbits,20)){ r.resuelto=-1; return r; }

    Vigia v; v.c=&c; v.tope=tope;
    pthread_t th; pthread_create(&th,NULL,vigilar,&v);
    kg_run(&c,n_kang,semilla);
    c.parar.store(1);
    pthread_join(th,NULL);

    sc_t k; sc_set_u64(k,clave);
    r.resuelto  = c.encontrado.load() && sc_cmp(c.k,k)==0;
    r.saltos    = c.saltos.load();
    r.dps       = c.tabla.guardados;
    r.rescatados= c.rescatados.load();
    r.pegados   = c.pegados.load();
    r.escapes   = c.escapes.load();
    r.escapes_repe = c.escapes_repe.load();
    kg_free(&c);
    return r;
}

int main(int argc,char**argv){
    setvbuf(stdout,NULL,_IONBF,0);
    int bits  = argc>1?atoi(argv[1]):40;
    int nk    = argc>2?atoi(argv[2]):64;
    int reps  = argc>3?atoi(argv[3]):5;
    int presu = argc>4?atoi(argv[4]):80;

    /* raiz(W) con W = 2^(bits-1): el rango va de 2^(bits-1) a 2^bits. */
    double raizW = 1.0; for(int i=0;i<(bits-1);i++) raizW*=1.4142135623730951;
    long long tope = (long long)(raizW*presu);

    printf("  rango de %d bits, %d canguros, %d claves por celda\n", bits, nk, reps);
    printf("  raiz(W) ~ %.0f, presupuesto %d*raiz(W) = %lld saltos\n\n",
           raizW, presu, tope);
    printf("  %-6s %-4s  %-7s %-11s %-9s %-9s %-9s %s\n",
           "dbits","neg","resuelve","saltos/raizW","dps","escapes","rescatados","%repe");

    int dlista[] = {6,8,10,12,13,14,16,18};
    int nd = (int)(sizeof dlista/sizeof dlista[0]);
    /* La formula de la app, para senalar la celda que de verdad importa. */
    int dapp = bits/4+4; if(dapp<6) dapp=6; if(dapp>28) dapp=28;

    int roto_con=0, roto_sin=0;
    for(int di=0;di<nd;di++){
        int dbits=dlista[di];
        if(dbits>=bits-2) continue;
        for(int neg=0;neg<=1;neg++){
            int res=0; double suma=0; uint64_t dps=0; long long resc=0,peg=0;
            long long esc=0, repe=0;
            uint64_t s=0xBEEF1234ULL + (uint64_t)dbits*7919ULL;
            for(int r=0;r<reps;r++){
                unsigned long long clave =
                    (1ULL<<(bits-1)) | (mezcla(s) & ((1ULL<<(bits-1))-1));
                Res x = una(bits,dbits,neg,nk,clave,0xC0FFEEULL+r,tope);
                if(x.resuelto==-1){ printf("  kg_setup rechaza dbits %d\n",dbits); break; }
                res += x.resuelto?1:0;
                suma += (double)x.saltos/raizW;
                dps  += x.dps; resc += x.rescatados; peg += x.pegados;
                esc += x.escapes; repe += x.escapes_repe;
            }
            printf("  %-6d %-4s  %d/%-5d %-11.2f %-9llu %-9lld %-9lld %-5.1f%s\n",
                   dbits, neg?"si":"no", res, reps, suma/reps,
                   (unsigned long long)(dps/reps), esc/reps, resc/reps,
                   esc? 100.0*(double)repe/(double)esc : 0.0,
                   dbits==dapp?"  <-- el de la app":"");
            if(res<reps){ if(neg) roto_con++; else roto_sin++; }
        }
    }

    /* ---- Vida de un rebano ----
     *
     * Un rebano sano guarda puntos distinguidos a ritmo constante: el doble de
     * pasos, el doble de puntos. Si el ritmo se cae segun avanza, es que los
     * canguros se van muriendo por el camino — y entonces el problema no es de
     * presupuesto, es que el camino tiene trampas. Se usa un rango grande para
     * que no lo resuelva y no pare antes de tiempo. */
    {
        int vb=44, vd=8, vnk=16;
        double vraiz=1.0; for(int i=0;i<(vb-1);i++) vraiz*=1.4142135623730951;
        printf("\n  Vida del rebano: %d bits, dbits %d, %d canguros.\n", vb, vd, vnk);
        printf("  Un rebano sano dobla los puntos al doblar los pasos.\n\n");
        printf("  %-4s %-12s %-10s %-10s %-8s %s\n",
               "neg","pasos","dps","esperados","rinde","repe%");
        for(int neg=0;neg<=1;neg++){
            for(int mult=2; mult<=32; mult*=2){
                long long t=(long long)(vraiz*mult);
                Res x = una(vb,vd,neg,vnk,(1ULL<<(vb-1))|0x5EEDC0DEULL,0xABCDEF,t);
                double esp=(double)x.saltos/(double)(1<<vd);
                printf("  %-4s %-12lld %-10llu %-10.0f %-8.2f %.1f\n",
                       neg?"si":"no", x.saltos, (unsigned long long)x.dps, esp,
                       esp?x.dps/esp:0.0,
                       x.escapes?100.0*(double)x.escapes_repe/(double)x.escapes:0.0);
            }
        }
    }

    printf("\n  celdas que no resuelven siempre: %d con negacion, %d sin.\n",
           roto_con, roto_sin);
    printf("\n  Como leerlo:\n");
    printf("    dps a cero con muchos saltos = el camino no llega a ningun\n");
    printf("      punto distinguido: los canguros estan atrapados.\n");
    printf("    dps altos y sin resolver     = el camino esta sano y lo que\n");
    printf("      falta es presupuesto, o los rebanos no se cruzan.\n");
    printf("    rescatados alto              = la ventana de ciclos no da\n");
    printf("      abasto y la red de seguridad esta tapando el agujero.\n");
    return 0;
}
