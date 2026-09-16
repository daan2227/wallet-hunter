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

    printf("\n%s\n", f?"HAY FALLOS":"TODO CORRECTO");
    return f;
}
