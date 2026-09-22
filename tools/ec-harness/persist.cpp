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

    /* 7) Una tabla de la version 2 se tira si el rango es grande.
     *
     * Hasta la version 2, la tabla de saltos guardaba el salto i en un uint64_t
     * y a partir de i=63 valia CERO: el punto se movia y la distancia no. En
     * rangos de mas de 118 bits eso envenenaba la tabla entera. No hay forma de
     * mirar una entrada y saber si es buena, asi que hay que tirar el fichero.
     *
     * Tirar la tabla de alguien son dias de movil perdidos, asi que tiene que
     * estar cubierto por las dos partes: que se tira cuando el rango es grande,
     * y que NO se tira cuando es pequeno, donde nunca hubo problema.
     */
    {
        /* Se fabrica a mano un fichero de version 2: una cabecera y una
           entrada. No se usa dp_save, que escribe la version de ahora. */
        auto escribe_v2=[&](const char *ruta,const uint8_t *pi,const uint8_t *pf){
            FILE *g=fopen(ruta,"wb");
            if(!g) return false;
            KgCab h; memset(&h,0,sizeof(h));
            h.magic=KG_MAGIC; h.ver=KG_VER_CON_ENVIADO; h.dbits=7; h.ops=0; h.n=1;
            memcpy(h.pub,pub,33); memcpy(h.ini,pi,32); memcpy(h.fin,pf,32);
            fwrite(&h,sizeof(h),1,g);
            uint64_t kx[2]={0x1111111111111111ULL,0x2222222222222222ULL};
            sc_t d; sc_set_u64(d,4242);
            uint8_t manso=1, env=0;
            fwrite(kx,8,2,g); fwrite(d,8,4,g);
            fwrite(&manso,1,1,g); fwrite(&env,1,1,g);
            fclose(g);
            return true;
        };

        /* Rango pequeno: se sigue leyendo. */
        escribe_v2("/tmp/kg_v2_peq.dat",ini,fin);
        KangarooCtx c8; kg_setup(&c8,pub,ini,fin,7,18);
        uint64_t l_peq=dp_load(&c8.tabla,"/tmp/kg_v2_peq.dat",pub,ini,fin,7,NULL);
        printf("%s  rango pequeno: la tabla de la version 2 se sigue leyendo (%llu)\n",
               l_peq==1?"OK ":"MAL", (unsigned long long)l_peq);
        f+=(l_peq!=1);
        kg_free(&c8);

        /* Rango grande: el del #140. */
        uint8_t gi[32],gf[32];
        memset(gi,0,32); gi[32-1-139/8]=(uint8_t)(1u<<(139%8));   /* 2^139 */
        memset(gf,0,32); for(int i=0;i<=139;i++) gf[31-i/8]|=(uint8_t)(1u<<(i%8));
        uint8_t gpub[33];
        {   /* una publica cualquiera dentro de ese rango */
            sc_t s; sc_from_be32(s,gi); sc_add_u64(s,7);
            JP P; kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);
            fe_t x,y; kg_normalize(&P,x,y);
            gpub[0]=(y[0]&1)?3:2;
            for(int w=0;w<4;w++) for(int bb=0;bb<8;bb++)
                gpub[1+(3-w)*8+(7-bb)]=(uint8_t)(x[w]>>(bb*8));
        }
        FILE *g=fopen("/tmp/kg_v2_gra.dat","wb");
        if(g){
            KgCab h; memset(&h,0,sizeof(h));
            h.magic=KG_MAGIC; h.ver=KG_VER_CON_ENVIADO; h.dbits=7; h.ops=0; h.n=1;
            memcpy(h.pub,gpub,33); memcpy(h.ini,gi,32); memcpy(h.fin,gf,32);
            fwrite(&h,sizeof(h),1,g);
            uint64_t kx[2]={0x3333333333333333ULL,0x4444444444444444ULL};
            sc_t d; sc_set_u64(d,4242);
            uint8_t manso=1, env=0;
            fwrite(kx,8,2,g); fwrite(d,8,4,g);
            fwrite(&manso,1,1,g); fwrite(&env,1,1,g);
            fclose(g);
        }
        KangarooCtx c9; kg_setup(&c9,gpub,gi,gf,7,18);
        uint64_t l_gra=dp_load(&c9.tabla,"/tmp/kg_v2_gra.dat",gpub,gi,gf,7,NULL);
        printf("%s  rango grande (#140): la tabla de la version 2 se tira (%llu)\n",
               l_gra==0?"OK ":"MAL", (unsigned long long)l_gra);
        f+=(l_gra!=0);
        kg_free(&c9);
        remove("/tmp/kg_v2_peq.dat"); remove("/tmp/kg_v2_gra.dat");
    }

    /* 8) Una tabla guardada SIN mapa de negacion se lee CON el, y al reves.
     *
     * El mapa de negacion traslada el objetivo al centro del intervalo, asi que
     * las distancias de los salvajes pasan a medirse desde otro sitio: P' era
     * P-a*G y P'' es P-(a+W/2)*G. Como P' = P'' + (W/2)*G, la conversion es
     * exacta —sumar o restar W/2— y no hay que tirar el trabajo de nadie.
     *
     * Si la conversion estuviera mal, la tabla cargaria igual, el contador de
     * puntos diria lo mismo y NADA resolveria. Otra vez el fallo sin sintoma.
     * Se comprueba con la invariante: un salvaje tiene que estar en P''+d*G.
     */
    {
        int antes=kg_negacion;

        /* Guardar sin negacion. */
        kg_negacion=0;
        KangarooCtx cs; kg_setup(&cs,pub,ini,fin,7,18);
        pthread_t th2;
        struct B{KangarooCtx*c;}; static B br; br.c=&cs;
        pthread_create(&th2,NULL,[](void*p)->void*{
            B*x=(B*)p; kg_run(x->c,32,4321); return NULL;},&br);
        struct timespec ts2={0,120*1000*1000}; nanosleep(&ts2,NULL);
        cs.parar.store(1); pthread_join(th2,NULL);
        uint64_t guardados=cs.tabla.guardados;
        dp_save(&cs.tabla,"/tmp/kg_sin_neg.dat",pub,ini,fin,7,0);
        kg_free(&cs);

        /* Leerla con negacion: el contexto ya esta centrado. */
        kg_negacion=1;
        KangarooCtx cn; kg_setup(&cn,pub,ini,fin,7,18);
        uint64_t leidas=dp_load(&cn.tabla,"/tmp/kg_sin_neg.dat",pub,ini,fin,7,NULL);
        printf("%s  se lee una tabla de antes del mapa de negacion (%llu de %llu)\n",
               (leidas>0&&leidas==guardados)?"OK ":"MAL",
               (unsigned long long)leidas,(unsigned long long)guardados);
        f+=!(leidas>0&&leidas==guardados);

        /* Y sus salvajes tienen que cuadrar con el objetivo NUEVO. */
        uint64_t sal=0, sal_ok=0, man=0, man_ok=0;
        for(uint64_t i=0;i<=cn.tabla.mask;i++){
            DP *sl=&cn.tabla.slots[i];
            if(!sl->usado) continue;
            JP P; kg_scalar_mul(&P,sl->dist,FIELD_GX,FIELD_GY);
            int inf=1; for(int z=0;z<4;z++) if(P.z[z]) inf=0;
            if(!sl->manso){
                if(inf){ sal++; continue; }
                fe_t dx,dy; kg_normalize(&P,dx,dy);
                JP W2; jp_add_affine(&W2,&cn.objetivo,dx,dy); P=W2;
                inf=1; for(int z=0;z<4;z++) if(P.z[z]) inf=0;
            }
            if(inf){ if(sl->manso) man++; else sal++; continue; }
            fe_t x,y; kg_normalize(&P,x,y);
            int bien=(x[0]==sl->kx[0]&&x[1]==sl->kx[1]);
            if(sl->manso){ man++; man_ok+=bien; } else { sal++; sal_ok+=bien; }
        }
        printf("%s  y las distancias quedan bien (mansos %llu/%llu, salvajes %llu/%llu)\n",
               (man==man_ok&&sal==sal_ok&&sal>0)?"OK ":"MAL",
               (unsigned long long)man_ok,(unsigned long long)man,
               (unsigned long long)sal_ok,(unsigned long long)sal);
        f+=!(man==man_ok&&sal==sal_ok&&sal>0);

        /* Y al reves NO: una tabla escrita con negacion no se puede leer sin
           ella, porque el signo de cada punto no se guarda y un motor sin
           negacion solo prueba uno. Peor que perder trabajo: una colision que
           no cuadra por el signo se COME un punto bueno, porque dp_insert avisa
           de la pareja y no guarda el que llega. */
        dp_save(&cn.tabla,"/tmp/kg_con_neg.dat",pub,ini,fin,7,0);
        kg_negacion=0;
        KangarooCtx cv; kg_setup(&cv,pub,ini,fin,7,18);
        uint64_t vuelta=dp_load(&cv.tabla,"/tmp/kg_con_neg.dat",pub,ini,fin,7,NULL);
        printf("%s  una tabla con negacion NO se lee sin negacion (%llu leidas)\n",
               vuelta==0?"OK ":"MAL",(unsigned long long)vuelta);
        f+=(vuelta!=0);
        kg_free(&cn); kg_free(&cv);
        remove("/tmp/kg_sin_neg.dat"); remove("/tmp/kg_con_neg.dat");
        kg_negacion=antes;
    }

    printf("\n%s\n", f?"HAY FALLOS":"TODO CORRECTO");
    return f;
}
