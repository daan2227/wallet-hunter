/* Comprueba que guardar y recuperar la tabla conserva el trabajo, y que un
   fichero de OTRO puzzle se rechaza en vez de mezclarse. */
#include <stdio.h>
#include <vector>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include "../../app/src/main/cpp/kangaroo.h"

static void be_u64(uint8_t*be,unsigned long long v){
    memset(be,0,32); for(int i=0;i<8;i++) be[31-i]=(uint8_t)(v>>(8*i));
}
static void pub_de(unsigned long long k,uint8_t*p33){
    sc_t s; sc_set_u64(s,k); JP P; kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);
    fe_t x,y; kg_normalize(&P,x,y);
    p33[0]=(y[0]&1)?3:2;
    for(int w=0;w<4;w++) for(int b=0;b<8;b++)
        p33[1+(3-w)*8+(7-b)]=(uint8_t)(x[w]>>(b*8));
}
int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    int f=0;
    const unsigned long long a=8388608ULL,b=16777215ULL,k=12345678ULL;
    uint8_t pub[33],ini[32],fin[32];
    pub_de(k,pub); be_u64(ini,a); be_u64(fin,b);

    /* 1) Correr un poco, guardar, y ver que el fichero tiene lo que habia. */
    KangarooCtx c1;
    if(!kg_setup(&c1,pub,ini,fin,7,18)){ printf("setup fallo\n"); return 1; }
    c1.parar.store(0);
    /* Un rebano pequeno y parado pronto, para tener pocos distinguidos. */
    pthread_t th;
    struct A{KangarooCtx*c;}; static A ar; ar.c=&c1;
    pthread_create(&th,NULL,[](void*p)->void*{
        A*x=(A*)p; kg_run(x->c,32,999); return NULL;},&ar);
    struct timespec ts={0,150*1000*1000}; nanosleep(&ts,NULL);
    c1.parar.store(1); pthread_join(th,NULL);
    uint64_t antes=c1.tabla.guardados;
    long long ops=c1.saltos.load();
    int ok=dp_save(&c1.tabla,"/tmp/kg_test.dat",pub,ini,fin,7,(uint64_t)ops);
    printf("%s  guardar: %llu puntos, %lld ops\n", ok?"OK ":"MAL",
           (unsigned long long)antes,ops);
    f+=!ok;
    kg_free(&c1);

    /* 2) Cargarlo en una tabla nueva. */
    KangarooCtx c2;
    if(!kg_setup(&c2,pub,ini,fin,7,18)){ printf("setup2 fallo\n"); return 1; }
    uint64_t ops_ant=0;
    uint64_t n=dp_load(&c2.tabla,"/tmp/kg_test.dat",pub,ini,fin,7,&ops_ant);
    int m = (n==antes) && ((long long)ops_ant==ops);
    printf("%s  recuperar: %llu puntos, %llu ops\n", m?"OK ":"MAL",
           (unsigned long long)n,(unsigned long long)ops_ant);
    f+=!m;
    kg_free(&c2);

    /* 3) Fichero de OTRO puzzle: tiene que rechazarse. */
    uint8_t otro[33]; pub_de(k+1,otro);
    KangarooCtx c3; kg_setup(&c3,otro,ini,fin,7,18);
    uint64_t n3=dp_load(&c3.tabla,"/tmp/kg_test.dat",otro,ini,fin,7,NULL);
    printf("%s  otro puzzle rechazado (%llu leidas)\n", n3==0?"OK ":"MAL",
           (unsigned long long)n3);
    f+=(n3!=0);
    kg_free(&c3);

    /* 4) Otro criterio de distinguido: tambien se rechaza, porque las entradas
          no significan lo mismo. */
    KangarooCtx c4; kg_setup(&c4,pub,ini,fin,9,18);
    uint64_t n4=dp_load(&c4.tabla,"/tmp/kg_test.dat",pub,ini,fin,9,NULL);
    printf("%s  otro dbits rechazado (%llu leidas)\n", n4==0?"OK ":"MAL",
           (unsigned long long)n4);
    f+=(n4!=0);
    kg_free(&c4);

    /* 5) Y lo importante: recuperar y seguir hasta encontrar la clave. */
    KangarooCtx c5;
    kg_setup(&c5,pub,ini,fin,7,18);
    dp_load(&c5.tabla,"/tmp/kg_test.dat",pub,ini,fin,7,NULL);
    kg_run(&c5,128,4242);
    int ok5=c5.encontrado.load() && c5.k[0]==k && !c5.k[1] && !c5.k[2] && !c5.k[3];
    printf("%s  sigue tras recuperar: k=%llu\n", ok5?"OK ":"MAL",
           (unsigned long long)c5.k[0]);
    f+=!ok5;
    kg_free(&c5);

    /* 6) La marca de "ya enviado" sobrevive al guardado.
     *
     * Sin esto, al recuperar la tabla TODAS las entradas volvian a contar como
     * sin mandar y el trabajador reenviaba la tabla entera al maestro. Con la
     * pausa a distancia eso pasa en cada pausa, no solo al reiniciar la app.
     *
     * Se ve desde fuera con kg_export, que es quien decide que mandar: si la
     * marca se ha guardado, tras recuperar no hay NADA nuevo que enviar.
     */
    {
        KangarooCtx c6; kg_setup(&c6,pub,ini,fin,7,18);
        dp_load(&c6.tabla,"/tmp/kg_test.dat",pub,ini,fin,7,NULL);
        /* Primer export: se lleva todo lo que haya y lo marca. */
        size_t cap=kg_export_bytes(4096);
        std::vector<uint8_t> buf(cap);
        size_t n_1=kg_export(&c6.tabla,pub,ini,fin,7,buf.data(),cap,4096);
        /* Segundo: ya no queda nada nuevo. */
        size_t n_2=kg_export(&c6.tabla,pub,ini,fin,7,buf.data(),cap,4096);
        printf("%s  export marca lo enviado (1o %s, 2o %s)\n",
               (n_1>0 && n_2==0)?"OK ":"MAL",
               n_1>0?"con datos":"vacio", n_2==0?"vacio":"con datos");
        f+=!(n_1>0 && n_2==0);

        /* Ahora se guarda CON las marcas puestas y se vuelve a recuperar. */
        dp_save(&c6.tabla,"/tmp/kg_test2.dat",pub,ini,fin,7,0);
        KangarooCtx c7; kg_setup(&c7,pub,ini,fin,7,18);
        dp_load(&c7.tabla,"/tmp/kg_test2.dat",pub,ini,fin,7,NULL);
        size_t n_3=kg_export(&c7.tabla,pub,ini,fin,7,buf.data(),cap,4096);
        printf("%s  tras recuperar no reenvia (%s)\n",
               n_3==0?"OK ":"MAL", n_3==0?"nada que mandar":"reenvia la tabla");
        f+=(n_3!=0);
        kg_free(&c6); kg_free(&c7);
        remove("/tmp/kg_test2.dat");
    }

    printf("\n%s\n", f?"HAY FALLOS":"TODO CORRECTO");
    return f;
}
