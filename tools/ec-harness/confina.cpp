/* Verifica que Kangaroo SOLO busca dentro de [a,b] (el patrón de "ceros"):
 *   1) una clave DENTRO del rango se encuentra (y sale exactamente esa),
 *   2) una clave en CADA EXTREMO (a y b) se encuentra,
 *   3) una clave FUERA del rango (justo por debajo de a y por encima de b) NO
 *      se encuentra ni con un presupuesto de saltos muy amplio — prueba de que
 *      el motor nunca se sale del intervalo. */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <time.h>
#include <atomic>
#include <thread>
#include "../../app/src/main/cpp/kangaroo.h"

static void be_from_u64(uint8_t *be,unsigned long long v){
    memset(be,0,32); for(int i=0;i<8;i++) be[31-i]=(uint8_t)(v>>(8*i));
}
static unsigned long long u64_from_sc(const sc_t s){ return s[0]; }
static void pub_de(unsigned long long k,uint8_t *pub33){
    sc_t s; sc_set_u64(s,k);
    JP P; kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);
    fe_t x,y; kg_normalize(&P,x,y);
    pub33[0]=(y[0]&1)?0x03:0x02;
    for(int w=0;w<4;w++) for(int b=0;b<8;b++)
        pub33[1+(3-w)*8+(7-b)]=(uint8_t)(x[w]>>(b*8));
}
static int fallos=0;
static void debe(int ok,const char*q){ if(!ok){fallos++; printf("MAL  %s\n",q);} }

/* Encuentra la clave k en [a,b] (bucle hasta hallarla). Devuelve 1 si la halló
 * igual a k. */
static int dentro(unsigned long long k,unsigned long long a,unsigned long long b,int dbits,int nk){
    uint8_t pub[33],ini[32],fin[32];
    pub_de(k,pub); be_from_u64(ini,a); be_from_u64(fin,b);
    KangarooCtx c;
    if(!kg_setup(&c,pub,ini,fin,dbits,18)){ printf("  setup fallo\n"); return 0; }
    kg_run(&c,nk,0xABCDEF ^ k);
    int ok = c.encontrado.load() && u64_from_sc(c.k)==k && !c.k[1] && !c.k[2] && !c.k[3];
    kg_free(&c);
    return ok;
}

/* Corre Kangaroo con un objetivo FUERA de [a,b] y un presupuesto de 'saltos_max'
 * saltos. Devuelve 1 si (correctamente) NO encontró nada. */
static int fuera(unsigned long long k,unsigned long long a,unsigned long long b,
                 int dbits,int nk,long long saltos_max){
    uint8_t pub[33],ini[32],fin[32];
    pub_de(k,pub); be_from_u64(ini,a); be_from_u64(fin,b);
    KangarooCtx c;
    if(!kg_setup(&c,pub,ini,fin,dbits,18)){ printf("  setup fallo\n"); return 0; }
    /* Corta por saltos: un vigilante pone parar=1 al pasar el presupuesto. */
    std::atomic<bool> fin_hilo(false);
    std::thread vig([&]{
        while(!fin_hilo.load()){
            if(c.saltos.load() > saltos_max || c.encontrado.load()){ c.parar.store(1); return; }
            struct timespec ts{0,2*1000*1000}; nanosleep(&ts,nullptr);
        }
    });
    kg_run(&c,nk,0x13579 ^ k);
    fin_hilo.store(true); vig.join();
    int no_hallo = !c.encontrado.load();
    kg_free(&c);
    return no_hallo;
}

int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    unsigned long long a=524288ULL, b=1048575ULL;   /* intervalo de 20 bits */

    printf("Dentro del rango (debe encontrar):\n");
    debe(dentro(700000ULL,a,b,6,64), "k=700000 dentro");
    debe(dentro(600000ULL,a,b,6,64), "k=600000 dentro");
    printf("En los extremos exactos a y b (debe encontrar):\n");
    debe(dentro(a,a,b,6,64), "k=a (extremo bajo)");
    debe(dentro(b,a,b,6,64), "k=b (extremo alto)");

    /* Para 'fuera' damos un presupuesto ENORME frente al coste normal: una
     * búsqueda de 20 bits se resuelve en ~2^10..2^11 saltos; damos 2^22. Si el
     * motor saliera del intervalo, hallaría la clave externa dentro de eso. */
    long long presupuesto = 1LL<<22;
    printf("Fuera del rango (NO debe encontrar, presupuesto %lld saltos):\n", presupuesto);
    debe(fuera(a-1ULL, a,b,6,64,presupuesto), "k=a-1 (justo por debajo) no se halla");
    debe(fuera(b+1ULL, a,b,6,64,presupuesto), "k=b+1 (justo por encima) no se halla");
    debe(fuera(100ULL,  a,b,6,64,presupuesto), "k=100 (muy por debajo) no se halla");
    debe(fuera(5000000ULL, a,b,6,64,presupuesto), "k=5000000 (muy por encima) no se halla");

    printf("%s\n", fallos ? "HAY FALLOS" : "TODO CORRECTO");
    return fallos ? 1 : 0;
}
