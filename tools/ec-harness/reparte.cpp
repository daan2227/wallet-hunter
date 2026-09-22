/* Kangaroo repartido entre aparatos.
 *
 * Lo que hay que demostrar no es que el codigo no reviente, es que DOS
 * aparatos juntos resuelven algo que ninguno de los dos tenia entero. Si eso no
 * pasa, el modo red no sirve para nada y encima lo parece: cada movil seguiria
 * buscando por su cuenta y el usuario creeria que esta repartiendo trabajo.
 *
 * Las pruebas, de la mas concluyente a la mas realista:
 *
 *  1. Colision cruzada a mano. Se fabrican dos puntos distinguidos que encajan
 *     y se meten en un contexto que NO ha dado ni un salto. Si sale la clave,
 *     es imposible que venga de otro sitio que no sea el intercambio.
 *  2. Rechazos: otro puzzle, otro rango, otro dbits, mensaje cortado, mensaje
 *     que miente en el numero de entradas, basura.
 *  3. Solo lo nuevo: exportar dos veces seguidas no puede repetir entradas.
 *  4. Un solo trabajador: resuelve el, y el master NO puede verlo por la tabla.
 *     Es un limite real del diseno y esta escrito aqui para que no cambie sin
 *     que nadie se entere.
 *  5 y 6. Varios trabajadores caminando de verdad contra un master que no da
 *     ni un salto: ahi la colision solo puede salir de juntar las tablas.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include "../../app/src/main/cpp/kangaroo.h"

static int fallos=0;
#define OK(cond,et) do{ if(cond) printf("  OK   %s\n",et); \
                        else { printf("MAL  %s\n",et); fallos++; } }while(0)

static void be_u64(uint8_t *be,unsigned long long v){
    memset(be,0,32); for(int i=0;i<8;i++) be[31-i]=(uint8_t)(v>>(8*i));
}
/* Clave publica comprimida de k*G */
static void pub_de(unsigned long long k,uint8_t *pub33){
    sc_t s; sc_set_u64(s,k);
    JP P; kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);
    fe_t x,y; kg_normalize(&P,x,y);
    pub33[0]=(y[0]&1)?0x03:0x02;
    for(int w=0;w<4;w++) for(int b=0;b<8;b++)
        pub33[1+(3-w)*8+(7-b)]=(uint8_t)(x[w]>>(b*8));
}
/* x afin de k*G, los dos limbos bajos, que es la clave de la tabla. */
static void x_de(unsigned long long k,uint64_t *kx){
    sc_t s; sc_set_u64(s,k);
    JP P; kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);
    fe_t x,y; kg_normalize(&P,x,y);
    kx[0]=x[0]; kx[1]=x[1];
}

/* Fabrica un mensaje de red con las entradas que se le den. */
static size_t blob_a_mano(uint8_t *buf,const uint8_t *pub,const uint8_t *ini,
                          const uint8_t *fin,int dbits,
                          const uint64_t (*kx)[2],const unsigned long long *dist,
                          const int *manso,uint32_t n){
    memset(buf,0,KG_NET_CAB);
    /* Se usan los mismos ayudantes que el exportador para no escribir el
       formato dos veces y que se separen. */
    uint8_t *p=buf+KG_NET_CAB;
    for(uint32_t i=0;i<n;i++,p+=KG_NET_ENT){
        sc_t d; sc_set_u64(d,dist[i]);
        for(int w=0;w<2;w++)
            for(int b=0;b<8;b++) p[w*8+b]=(uint8_t)(kx[i][w]>>(8*b));
        for(int w=0;w<4;w++)
            for(int b=0;b<8;b++) p[16+w*8+b]=(uint8_t)(d[w]>>(8*b));
        p[48]=(uint8_t)manso[i];
    }
    for(int i=0;i<4;i++) buf[i]=(uint8_t)(KG_NET_MAGIC>>(8*i));
    for(int i=0;i<4;i++) buf[4+i]=(uint8_t)(KG_NET_VER>>(8*i));
    memcpy(buf+8,pub,33); memcpy(buf+41,ini,32); memcpy(buf+73,fin,32);
    for(int i=0;i<4;i++) buf[105+i]=(uint8_t)(((uint32_t)dbits)>>(8*i));
    for(int i=0;i<4;i++) buf[109+i]=(uint8_t)(n>>(8*i));
    return KG_NET_CAB+(size_t)n*KG_NET_ENT;
}

/* ---- 1. Colision cruzada fabricada a mano ---- */
static void prueba_colision_cruzada(){
    printf("\n1. Colision que solo existe al juntar los dos aparatos:\n");

    /* Intervalo de 24 bits, clave conocida. */
    unsigned long long a=1ULL<<23, b=(1ULL<<24)-1;
    unsigned long long kp=12345;              /* clave relativa al inicio */
    unsigned long long k=a+kp;                /* la clave de verdad */
    unsigned long long e=777;                 /* distancia del salvaje */
    unsigned long long d=kp+e;                /* la del manso */

    /* El manso esta en d*G. El salvaje en objetivo + e*G = (kp+e)*G = d*G.
       Mismo punto: eso es la colision. */
    uint8_t pub[33]; pub_de(k,pub);
    uint8_t ini[32],fin[32]; be_u64(ini,a); be_u64(fin,b);

    KangarooCtx c;
    if(!kg_setup(&c,pub,ini,fin,8,14)){ printf("MAL  setup\n"); fallos++; return; }

    uint64_t kx[1][2]; x_de(d,kx[0]);
    uint8_t buf[KG_NET_CAB+KG_NET_ENT*4];

    /* Primero llega el manso, de un aparato. Todavia no puede pasar nada. */
    unsigned long long d1[1]={d}; int m1[1]={1};
    size_t n1=blob_a_mano(buf,pub,ini,fin,8,(const uint64_t(*)[2])kx,d1,m1,1);
    uint32_t metidas=0;
    OK(kg_import(&c,pub,ini,fin,8,buf,n1,&metidas)==1 && metidas==1,
       "entra el punto del aparato A");
    OK(c.encontrado.load()==0, "con uno solo no se resuelve nada");

    /* Ahora llega el salvaje, de OTRO aparato. */
    unsigned long long d2[1]={e}; int m2[1]={0};
    size_t n2=blob_a_mano(buf,pub,ini,fin,8,(const uint64_t(*)[2])kx,d2,m2,1);
    OK(kg_import(&c,pub,ini,fin,8,buf,n2,&metidas)==1, "entra el punto del aparato B");
    OK(c.encontrado.load()==1, "al juntarlos SI sale la clave");

    if(c.encontrado.load()){
        unsigned long long hallada=c.k[0];
        int solo_bajo = (c.k[1]|c.k[2]|c.k[3])==0;
        if(solo_bajo && hallada==k) printf("  OK   la clave es la correcta (%llu)\n",k);
        else { printf("MAL  clave equivocada: %llu, esperada %llu\n",hallada,k); fallos++; }
    }
    /* Este contexto no ha dado ni un salto: todo lo que sabe llego por red. */
    OK(c.saltos.load()==0, "el contexto no ha dado ni un salto por su cuenta");
    kg_free(&c);
}

/* ---- 2. Lo que tiene que rechazar ---- */
static void prueba_rechazos(){
    printf("\n2. Mensajes que hay que rechazar:\n");
    unsigned long long a=1ULL<<23, b=(1ULL<<24)-1, k=a+999;
    uint8_t pub[33]; pub_de(k,pub);
    uint8_t ini[32],fin[32]; be_u64(ini,a); be_u64(fin,b);
    uint8_t otro_pub[33]; pub_de(k+1,otro_pub);
    uint8_t otro_fin[32]; be_u64(otro_fin,b-1);

    KangarooCtx c;
    if(!kg_setup(&c,pub,ini,fin,8,14)){ printf("MAL  setup\n"); fallos++; return; }

    uint64_t kx[1][2]; x_de(4242,kx[0]);
    unsigned long long dd[1]={4242}; int mm[1]={1};
    uint8_t buf[KG_NET_CAB+KG_NET_ENT*4];
    size_t n=blob_a_mano(buf,pub,ini,fin,8,(const uint64_t(*)[2])kx,dd,mm,1);
    uint32_t metidas;

    /* Otro puzzle: mezclarlo daria colisiones que no significan nada. */
    uint8_t malo[sizeof(buf)]; memcpy(malo,buf,n);
    memcpy(malo+8,otro_pub,33);
    OK(kg_import(&c,pub,ini,fin,8,malo,n,&metidas)==0, "rechaza otra clave publica");

    memcpy(malo,buf,n); memcpy(malo+73,otro_fin,32);
    OK(kg_import(&c,pub,ini,fin,8,malo,n,&metidas)==0, "rechaza otro rango");

    OK(kg_import(&c,pub,ini,fin,9,buf,n,&metidas)==0, "rechaza otro dbits");

    memcpy(malo,buf,n); malo[0]^=0xFF;
    OK(kg_import(&c,pub,ini,fin,8,malo,n,&metidas)==0, "rechaza magic roto");

    OK(kg_import(&c,pub,ini,fin,8,buf,KG_NET_CAB-1,&metidas)==0,
       "rechaza mensaje mas corto que la cabecera");

    /* El caso feo: la cabecera dice 1000 entradas y solo vienen los bytes de
       una. Sin comprobarlo, el bucle leeria 1000*49 bytes fuera del buffer. */
    memcpy(malo,buf,n);
    malo[109]=0xE8; malo[110]=0x03; malo[111]=0; malo[112]=0;   /* n = 1000 */
    OK(kg_import(&c,pub,ini,fin,8,malo,n,&metidas)==0,
       "rechaza cabecera que miente en el numero de entradas");

    /* Cortado a la mitad de una entrada. */
    OK(kg_import(&c,pub,ini,fin,8,buf,n-10,&metidas)==0,
       "rechaza mensaje cortado a mitad de entrada");

    OK(kg_import(&c,pub,ini,fin,8,NULL,0,&metidas)==0, "rechaza mensaje vacio");

    /* Nada de lo anterior puede haber dejado rastro. */
    OK(c.tabla.guardados==0, "ninguno de los rechazados ha tocado la tabla");
    OK(c.encontrado.load()==0, "ninguno de los rechazados ha inventado una clave");

    /* El bueno, para confirmar que el rechazo no es "rechaza todo". */
    OK(kg_import(&c,pub,ini,fin,8,buf,n,&metidas)==1 && metidas==1,
       "el mensaje bueno si entra");
    kg_free(&c);
}

/* ---- 3. Cada envio lleva solo lo nuevo ---- */
static void prueba_solo_lo_nuevo(){
    printf("\n3. Cada envio lleva solo lo que no se ha mandado:\n");
    unsigned long long a=1ULL<<19, b=(1ULL<<20)-1, k=a+54321;
    uint8_t pub[33]; pub_de(k,pub);
    uint8_t ini[32],fin[32]; be_u64(ini,a); be_u64(fin,b);

    KangarooCtx c;
    if(!kg_setup(&c,pub,ini,fin,6,14)){ printf("MAL  setup\n"); fallos++; return; }
    kg_run(&c,64,0xABCDEF);       /* para solo al encontrarla */

    static uint8_t buf[1<<20];
    size_t n1=kg_export(&c.tabla,pub,ini,fin,6,buf,sizeof(buf),0);
    OK(n1>KG_NET_CAB, "el primer envio lleva puntos");
    uint32_t ent1=(uint32_t)((n1-KG_NET_CAB)/KG_NET_ENT);

    size_t n2=kg_export(&c.tabla,pub,ini,fin,6,buf,sizeof(buf),0);
    OK(n2==0, "el segundo envio seguido va vacio");

    printf("       (%u puntos en el primer envio, %zu bytes)\n",ent1,n1);

    /* Y el tope por envio se respeta. */
    KangarooCtx c2;
    kg_setup(&c2,pub,ini,fin,6,14);
    kg_run(&c2,64,0x123456);
    size_t n3=kg_export(&c2.tabla,pub,ini,fin,6,buf,sizeof(buf),5);
    uint32_t ent3=(uint32_t)((n3-KG_NET_CAB)/KG_NET_ENT);
    OK(n3==0 || ent3<=5, "se respeta el tope de entradas por envio");
    kg_free(&c); kg_free(&c2);
}

/* ---- 4 y 5. Aparatos de verdad caminando ---- */
typedef struct { KangarooCtx *c; int n_kang; uint64_t sem; } Arg;
static void *anda(void *p){
    Arg *a=(Arg*)p; kg_run(a->c,a->n_kang,a->sem); return NULL;
}

/* Master que NO camina: solo recibe. Todo lo que resuelva viene de fuera. */
static void monta(KangarooCtx *c,unsigned long long a,unsigned long long b,
                  unsigned long long k,uint8_t *pub,uint8_t *ini,uint8_t *fin,
                  int db,int tb){
    pub_de(k,pub); be_u64(ini,a); be_u64(fin,b);
    if(!kg_setup(c,pub,ini,fin,db,tb)){ printf("MAL  setup\n"); fallos++; }
}

/* ---- 4. Un solo trabajador: resuelve el solo, y eso el master NO lo ve ----
 *
 * Es un limite de verdad y conviene tenerlo escrito aqui para que no cambie sin
 * que nadie se entere.
 *
 * dp_insert, cuando encuentra que el punto que llega ya estaba y es del otro
 * rebano, avisa de la colision y SALE SIN GUARDAR el que acaba de llegar. O sea,
 * de la pareja que cierra el problema, en la tabla solo queda una mitad. El
 * trabajador resuelve con lo que tiene en la mano, pero por mucho que mande su
 * tabla entera, el master nunca recibe la otra mitad y no puede repetir la
 * cuenta.
 *
 * Por eso el reparto necesita DOS caminos, no uno:
 *   - la tabla, que resuelve las colisiones ENTRE aparatos (prueba 5);
 *   - un aviso de clave, para el que la encuentra el solo.
 *
 * Si algun dia esta prueba empieza a decir que el master si resuelve, es que
 * dp_insert ha cambiado y hay que revisar todo esto.
 */
static void prueba_un_trabajador(){
    printf("\n4. Un solo trabajador (resuelve el, no el master):\n");
    unsigned long long a=1ULL<<27, b=(1ULL<<28)-1, k=a+123456789ULL%((1ULL<<27));
    const int DB=7, TB=16;
    uint8_t pub[33],ini[32],fin[32];
    KangarooCtx master,trab;
    monta(&master,a,b,k,pub,ini,fin,DB,TB);
    monta(&trab,  a,b,k,pub,ini,fin,DB,TB);

    pthread_t h; Arg ar={&trab,128,0x1000};
    pthread_create(&h,NULL,anda,&ar);

    static uint8_t buf[1<<22];
    uint64_t recibidos=0; int v=0;
    while(!trab.encontrado.load() && v<20000){
        size_t n=kg_export(&trab.tabla,pub,ini,fin,DB,buf,sizeof(buf),512);
        if(n){ uint32_t m=0; if(kg_import(&master,pub,ini,fin,DB,buf,n,&m)) recibidos+=m; }
        v++; struct timespec ts={0,200000}; nanosleep(&ts,NULL);
    }
    trab.parar.store(1); pthread_join(h,NULL);
    /* Un ultimo barrido, por si quedaba algo sin mandar. */
    size_t n=kg_export(&trab.tabla,pub,ini,fin,DB,buf,sizeof(buf),0);
    if(n){ uint32_t m=0; if(kg_import(&master,pub,ini,fin,DB,buf,n,&m)) recibidos+=m; }

    OK(trab.encontrado.load()==1, "el trabajador la encuentra");
    int solo_bajo=(trab.k[1]|trab.k[2]|trab.k[3])==0;
    OK(solo_bajo && trab.k[0]==k, "y es la correcta");
    OK(recibidos>0 && recibidos==trab.tabla.guardados,
       "el master ha recibido la tabla entera del trabajador");
    OK(master.encontrado.load()==0,
       "y AUN ASI no puede cerrarla: falta la mitad que dp_insert no guarda");
    printf("       %llu puntos recibidos, y no bastan. Por eso hace falta el aviso de clave.\n",
           (unsigned long long)recibidos);
    kg_free(&master); kg_free(&trab);
}

/* ---- 5 y 6. Varios trabajadores: aqui SI resuelve el master ----
 *
 * Con varios, la mitad manso y la mitad salvaje de una misma colision caen en
 * aparatos distintos: ninguno de los dos la ve, y solo aparece al juntar las
 * tablas. Esto es lo que hace que repartir sirva para algo.
 *
 * UNA SOLA VUELTA NO DEMUESTRA NADA, Y ESTA PRUEBA LO APRENDIO A GOLPES.
 *
 * La primera colision del conjunto cae dentro de un mismo aparato o entre dos,
 * y las dos cosas pasan mas o menos con la misma frecuencia. Si cae dentro de
 * uno, ese trabajador la resuelve solo — y entonces el master NO puede repetir
 * la cuenta, porque de esa pareja dp_insert no llego a guardar la segunda
 * mitad y por tanto no se exporta nunca (es el limite de la prueba 4).
 *
 * O sea que "el master la resuelve" es cara o cruz. Con la semilla fija que
 * habia aqui salia cara, y la prueba se dio por buena. Al mejorar el reparto de
 * salida de los canguros (politica 2) la misma semilla empezo a salir cruz y la
 * prueba se puso roja sin que nada estuviera roto. Repetirla seis veces tampoco
 * servia de nada: con la semilla fija, las seis vueltas son LA MISMA TIRADA.
 *
 * Asi que se tira varias veces con semillas DISTINTAS y se mira el recuento:
 *   - alguien la resuelve siempre (ni una vuelta puede quedar sin resolver);
 *   - y el master la resuelve al menos una vez, que es lo que hay que
 *     demostrar: que una colision repartida entre dos aparatos se cierra.
 */
/* 24 y no 6. Con dos trabajadores el master gana una de cada dos, asi que con 6
 * vueltas la prueba se pondria roja ella sola una vez de cada 64: un 1,6 % de
 * las compilaciones, sin que nada estuviera mal. Una prueba que falla sola
 * ensena a no mirar el rojo, que es peor que no tenerla. Con 24 la probabilidad
 * baja a una entre 16 millones, y la prueba entera tarda un segundo. */
#define VUELTAS_MP 24

static void prueba_master_pasivo(int n_trabajadores,const char *et){
    printf("\n%s:\n",et);
    unsigned long long a=1ULL<<27, b=(1ULL<<28)-1, k=a+123456789ULL%((1ULL<<27));
    const int DB=7, TB=16;
    uint8_t pub[33],ini[32],fin[32];
    static uint8_t buf[1<<22];

    int gana_master=0, gana_trab=0, sin_resolver=0, master_anduvo=0;
    uint64_t total_puntos=0, mal_clave=0;

    for(int v=0;v<VUELTAS_MP;v++){
        KangarooCtx master; monta(&master,a,b,k,pub,ini,fin,DB,TB);
        KangarooCtx trab[4];
        pthread_t hilo[4]; Arg arg[4];
        for(int i=0;i<n_trabajadores;i++){
            uint8_t p2[33],i2[32],f2[32];
            monta(&trab[i],a,b,k,p2,i2,f2,DB,TB);
            /* Semilla distinta en cada vuelta Y en cada trabajador. */
            arg[i]=(Arg){&trab[i],128,
                         (uint64_t)(0x1000+i*7919+v*104729)};
            pthread_create(&hilo[i],NULL,anda,&arg[i]);
        }

        uint64_t total=0;
        /* Recoger mientras quede alguien andando. Antes esto daba 20000 vueltas
           de 200 us pasara lo que pasara: cuatro segundos de reloj por prueba,
           casi todos sin nada que recoger. */
        for(;;){
            int vivos=0;
            for(int i=0;i<n_trabajadores;i++){
                size_t n=kg_export(&trab[i].tabla,pub,ini,fin,DB,buf,sizeof(buf),512);
                if(n){ uint32_t met=0;
                       if(kg_import(&master,pub,ini,fin,DB,buf,n,&met)) total+=met; }
                if(!trab[i].encontrado.load()) vivos++;
            }
            if(master.encontrado.load() || !vivos) break;
            struct timespec ts={0,200000}; nanosleep(&ts,NULL);
        }
        for(int i=0;i<n_trabajadores;i++) trab[i].parar.store(1);
        for(int i=0;i<n_trabajadores;i++) pthread_join(hilo[i],NULL);
        /* Un ultimo barrido: lo que quedara sin mandar cuando pararon. */
        for(int i=0;i<n_trabajadores;i++){
            size_t n=kg_export(&trab[i].tabla,pub,ini,fin,DB,buf,sizeof(buf),0);
            if(n){ uint32_t met=0;
                   if(kg_import(&master,pub,ini,fin,DB,buf,n,&met)) total+=met; }
        }

        if(master.saltos.load()!=0) master_anduvo++;
        total_puntos+=total;
        if(master.encontrado.load()){
            gana_master++;
            int solo_bajo=(master.k[1]|master.k[2]|master.k[3])==0;
            if(!solo_bajo || master.k[0]!=k) mal_clave++;
        }else{
            int alguno=0;
            for(int i=0;i<n_trabajadores;i++){
                if(trab[i].encontrado.load()){
                    alguno=1;
                    int solo_bajo=(trab[i].k[1]|trab[i].k[2]|trab[i].k[3])==0;
                    if(!solo_bajo || trab[i].k[0]!=k) mal_clave++;
                }
            }
            if(alguno) gana_trab++; else sin_resolver++;
        }
        kg_free(&master);
        for(int i=0;i<n_trabajadores;i++) kg_free(&trab[i]);
    }

    printf("       %d vueltas: master %d, trabajador %d, sin resolver %d"
           " (%llu puntos al master)\n",
           VUELTAS_MP,gana_master,gana_trab,sin_resolver,
           (unsigned long long)total_puntos);
    OK(master_anduvo==0, "el master no ha dado ni un salto");
    OK(sin_resolver==0, "en todas las vueltas la resuelve alguien");
    /* No "al menos una": al menos un cuarto. Con dos trabajadores la mitad de
       las colisiones son cruzadas y con tres, dos tercios, asi que un cuarto
       sobra por abajo y aun asi salta si el reparto se degrada de verdad. */
    OK(gana_master*4>=VUELTAS_MP,
       "y el master cierra por su cuenta al menos un cuarto de ellas");
    OK(mal_clave==0, "todas las claves encontradas son la correcta");
}

int main(){
    printf("Kangaroo repartido entre aparatos\n");
    prueba_colision_cruzada();
    prueba_rechazos();
    prueba_solo_lo_nuevo();
    prueba_un_trabajador();
    prueba_master_pasivo(2,"5. Dos trabajadores y un master que solo escucha");
    prueba_master_pasivo(3,"6. Tres trabajadores y un master que solo escucha");
    printf("\n%s\n", fallos ? "HAY FALLOS" : "TODO CORRECTO");
    return fallos?1:0;
}
