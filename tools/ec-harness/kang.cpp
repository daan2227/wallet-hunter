/* Resuelve logaritmos discretos de los que YA sabemos la respuesta y comprueba
   que Kangaroo da exactamente esa. Es la unica prueba que vale: si el
   algoritmo estuviera mal, "no encontrar nada" se ve igual que "aun no". */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <time.h>
#include "../../app/src/main/cpp/kangaroo.h"

static void be_from_u64(uint8_t *be,unsigned long long v){
    memset(be,0,32); for(int i=0;i<8;i++) be[31-i]=(uint8_t)(v>>(8*i));
}
static unsigned long long u64_from_sc(const sc_t s){ return s[0]; }

/* Clave publica comprimida de k*G */
static void pub_de(unsigned long long k,uint8_t *pub33){
    sc_t s; sc_set_u64(s,k);
    JP P; kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);
    fe_t x,y; kg_normalize(&P,x,y);
    pub33[0]=(y[0]&1)?0x03:0x02;
    for(int w=0;w<4;w++) for(int b=0;b<8;b++)
        pub33[1+(3-w)*8+(7-b)]=(uint8_t)(x[w]>>(b*8));
}

static int prueba(unsigned long long k,unsigned long long a,unsigned long long b,
                  int dp_bits,int n_kang){
    uint8_t pub[33],ini[32],fin[32];
    pub_de(k,pub); be_from_u64(ini,a); be_from_u64(fin,b);
    KangarooCtx c;
    if(!kg_setup(&c,pub,ini,fin,dp_bits,18)){ printf("  setup fallo\n"); return 1; }
    clock_t t0=clock();
    kg_run(&c,n_kang,0x1234567 + k);
    double seg=(double)(clock()-t0)/CLOCKS_PER_SEC;
    int ok=0;
    if(c.encontrado.load()){
        ok = (u64_from_sc(c.k)==k) && !c.k[1] && !c.k[2] && !c.k[3];
        printf("  %s  k=%llu  encontrada=%llu  (%lld saltos, %.2fs)\n",
               ok?"OK ":"MAL", k, (unsigned long long)u64_from_sc(c.k),
               (long long)c.saltos.load(), seg);
    }else{
        printf("  MAL  k=%llu  no encontrada (%lld saltos, %.2fs)\n",
               k,(long long)c.saltos.load(),seg);
    }
    kg_free(&c);
    return ok?0:1;
}

int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    int f=0;
    printf("Intervalo de 20 bits (W = 1.048.576):\n");
    f+=prueba(700000ULL, 524288ULL, 1048575ULL, 6, 64);
    f+=prueba(600000ULL, 524288ULL, 1048575ULL, 6, 64);
    printf("Intervalo de 24 bits:\n");
    f+=prueba(12000000ULL, 8388608ULL, 16777215ULL, 7, 128);
    printf("Intervalo de 28 bits:\n");
    f+=prueba(200000000ULL, 134217728ULL, 268435455ULL, 8, 256);
    printf("Intervalo de 32 bits (W = 4.294.967.296):\n");
    f+=prueba(3000000000ULL, 2147483648ULL, 4294967295ULL, 9, 512);
    printf("\nk justo en los extremos:\n");
    f+=prueba(524288ULL, 524288ULL, 1048575ULL, 6, 64);
    f+=prueba(1048575ULL, 524288ULL, 1048575ULL, 6, 64);
    /* Tanda aleatoria: los casos elegidos a mano pueden esquivar sin querer
       justo el caso que falla. */
    printf("\n30 claves al azar en 24 bits:\n");
    srand(12345);
    int malas=0;
    for(int i=0;i<30;i++){
        unsigned long long a=8388608ULL, b=16777215ULL;
        unsigned long long k=a+((unsigned long long)rand()*rand())%(b-a+1);
        uint8_t pub[33],ini[32],fin[32];
        pub_de(k,pub); be_from_u64(ini,a); be_from_u64(fin,b);
        KangarooCtx c;
        if(!kg_setup(&c,pub,ini,fin,7,18)){ malas++; continue; }
        kg_run(&c,128,0xABCDEF+i);
        if(!c.encontrado.load() || u64_from_sc(c.k)!=k) malas++;
        kg_free(&c);
    }
    printf("  %s  %d de 30 mal\n", malas?"MAL":"OK ", malas);
    f+=malas;

    /* Multihilo sobre la MISMA tabla: es como va a correr en el movil. */
    printf("\nCuatro hilos compartiendo tabla, 36 bits:\n");
    {
        unsigned long long a=34359738368ULL, b=68719476735ULL, k=50000000000ULL;
        uint8_t pub[33],ini[32],fin[32];
        pub_de(k,pub); be_from_u64(ini,a); be_from_u64(fin,b);
        static KangarooCtx c;
        if(!kg_setup(&c,pub,ini,fin,10,20)) { printf("  setup fallo\n"); f++; }
        else {
            struct Arg{ KangarooCtx*c; uint64_t s; };
            static Arg args[4];
            pthread_t th[4];
            clock_t t0=clock();
            for(int i=0;i<4;i++){
                args[i].c=&c; args[i].s=0x9E3779B9ULL*(i+1);
                pthread_create(&th[i],NULL,[](void*p)->void*{
                    Arg*x=(Arg*)p; kg_run(x->c,512,x->s); return NULL; },&args[i]);
            }
            for(int i=0;i<4;i++) pthread_join(th[i],NULL);
            double seg=(double)(clock()-t0)/CLOCKS_PER_SEC;
            int ok=c.encontrado.load() && u64_from_sc(c.k)==k;
            printf("  %s  k=%llu  encontrada=%llu  (%lld saltos, %.1fs cpu)\n",
                   ok?"OK ":"MAL", k, (unsigned long long)u64_from_sc(c.k),
                   (long long)c.saltos.load(), seg);
            f+=!ok;
            kg_free(&c);
        }
    }

    /* Tabla COMPARTIDA de mansos (auditoría Weak): resolver un objetivo, guardar
       sólo los mansos, y reusarlos para OTRO objetivo del MISMO rango. La clave
       del segundo tiene que salir BIEN: si el reuso estuviera mal, saldría otra. */
    printf("\nReuso de tabla de mansos (28 bits):\n");
    {
        unsigned long long a=134217728ULL,b=268435455ULL,k1=200000000ULL,k2=180000000ULL;
        const char *ruta="/tmp/kg_mansos_test.tbl";
        remove(ruta);
        uint8_t pub1[33],pub2[33],ini[32],fin[32];
        pub_de(k1,pub1); pub_de(k2,pub2); be_from_u64(ini,a); be_from_u64(fin,b);
        int dbits=8;
        /* 1) resolver k1 y guardar sus mansos */
        KangarooCtx c1;
        if(kg_setup(&c1,pub1,ini,fin,dbits,18)){
            kg_run(&c1,256,0x111);
            int ok1=c1.encontrado.load() && u64_from_sc(c1.k)==k1;
            int gm=dp_save_mansos(&c1.tabla,ruta,ini,fin,dbits);
            printf("  %s  k1=%llu resuelta, mansos guardados=%d\n", (ok1&&gm)?"OK ":"MAL", k1, gm);
            f += !ok1; f += !gm;
            kg_free(&c1);
        } else { printf("  MAL  setup k1\n"); f++; }
        /* 2) resolver k2 (otro objetivo) cargando los mansos de k1 */
        KangarooCtx c2;
        if(kg_setup(&c2,pub2,ini,fin,dbits,18)){
            uint64_t nm=dp_load_mansos(&c2.tabla,ruta,ini,fin,dbits);
            kg_run(&c2,256,0x222);
            int ok2=c2.encontrado.load() && u64_from_sc(c2.k)==k2 && !c2.k[1] && !c2.k[2] && !c2.k[3];
            printf("  %s  k2=%llu con mansos reusados (cargados=%llu, %lld saltos)\n",
                   (ok2&&nm>0)?"OK ":"MAL", k2, (unsigned long long)nm, (long long)c2.saltos.load());
            f += !ok2; f += (nm==0);
            kg_free(&c2);
        } else { printf("  MAL  setup k2\n"); f++; }
        /* 3) una tabla de OTRO rango no se carga (no corrompe la búsqueda) */
        KangarooCtx c3;
        uint8_t ini2[32]; be_from_u64(ini2,a+1);
        if(kg_setup(&c3,pub2,ini2,fin,dbits,18)){
            uint64_t nm=dp_load_mansos(&c3.tabla,ruta,ini2,fin,dbits);
            printf("  %s  rango distinto no se carga (cargados=%llu, debe ser 0)\n", nm==0?"OK ":"MAL", (unsigned long long)nm);
            f += (nm!=0);
            kg_free(&c3);
        } else { printf("  MAL  setup rango distinto\n"); f++; }
        remove(ruta);
    }

    /* Multiobjetivo: varias claves públicas del MISMO rango resueltas en UNA
       sola pasada, contra un único rebaño de mansos. Cada clave tiene que salir
       exacta; si el despacho por objetivo estuviera mal, alguna no saldría. */
    printf("\nMultiobjetivo: varios objetivos en una pasada (28 bits):\n");
    {
        unsigned long long a=134217728ULL,b=268435455ULL;
        const int M=5;
        unsigned long long ks[M]={200000000ULL,180000000ULL,150000000ULL,260000000ULL,140000000ULL};
        uint8_t pubs[33*M], ini[32],fin[32];
        for(int j=0;j<M;j++) pub_de(ks[j],pubs+33*j);
        be_from_u64(ini,a); be_from_u64(fin,b);
        static KangarooCtx c;
        if(!kg_setup_multi(&c,pubs,M,ini,fin,8,20)){ printf("  MAL  setup_multi\n"); f++; }
        else{
            struct Arg{ KangarooCtx*c; uint64_t s; };
            static Arg args[4]; pthread_t th[4];
            clock_t t0=clock();
            for(int i=0;i<4;i++){ args[i].c=&c; args[i].s=0x55AAULL*(i+1);
                pthread_create(&th[i],NULL,[](void*p)->void*{
                    Arg*x=(Arg*)p; kg_run(x->c,256,x->s); return NULL; },&args[i]); }
            for(int i=0;i<4;i++) pthread_join(th[i],NULL);
            double seg=(double)(clock()-t0)/CLOCKS_PER_SEC;
            int malas=0;
            for(int j=0;j<M;j++){
                int ok=c.encontrado_obj[j].load() && u64_from_sc(c.k_obj[j])==ks[j]
                       && !c.k_obj[j][1] && !c.k_obj[j][2] && !c.k_obj[j][3];
                if(!ok){ malas++; printf("    MAL obj %d k=%llu hallada=%llu\n",
                         j, ks[j], (unsigned long long)u64_from_sc(c.k_obj[j])); }
            }
            printf("  %s  %d de %d objetivos en una pasada (%lld saltos, %.1fs cpu)\n",
                   malas?"MAL":"OK ", M-malas, M, (long long)c.saltos.load(), seg);
            f+=malas;
            kg_free(&c);
        }
    }

    printf("\n%s\n", f?"HAY FALLOS":"TODO CORRECTO");
    return f;
}
