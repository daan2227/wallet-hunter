/* fe_sqr y fe_inv nuevos contra los viejos.
 *
 * Los dos se han reescrito por velocidad, y los dos son exactamente el tipo de
 * cambio que puede estar mal sin que se note: un cuadrado equivocado o un
 * inverso equivocado no revientan, solo hacen que el canguro busque para
 * siempre algo que no esta. Asi que aqui se comparan contra las definiciones
 * lentas y evidentes:
 *
 *   fe_sqr(a)  tiene que dar lo mismo que fe_mul(a,a)
 *   fe_inv(a)  tiene que cumplir a * inv(a) == 1
 *
 * y ademas contra el inverso viejo bit a bit, que es codigo que ya llevaba
 * tiempo funcionando. */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "../../app/src/main/cpp/jac_batch.h"

/* El inverso de antes: exponenciacion bit a bit sobre p-2. Lento y obvio. */
static void inv_lento(fe_t r,const fe_t a){
    const uint64_t EXP[4]={
        0xFFFFFFFEFFFFFC2DULL,0xFFFFFFFFFFFFFFFFULL,
        0xFFFFFFFFFFFFFFFFULL,0xFFFFFFFFFFFFFFFFULL
    };
    fe_t res; res[0]=1;res[1]=res[2]=res[3]=0;
    for(int w=3;w>=0;w--)
        for(int b=63;b>=0;b--){
            fe_mul(res,res,res);
            if((EXP[w]>>b)&1) fe_mul(res,res,a);
        }
    memcpy(r,res,32);
}

static uint64_t sem=0x243F6A8885A308D3ULL;
static uint64_t azar(){
    sem^=sem<<13; sem^=sem>>7; sem^=sem<<17; return sem;
}

static int igual(const fe_t a,const fe_t b){ return memcmp(a,b,32)==0; }
static void pinta(const char*et,const fe_t a){
    printf("  %s = %016llx%016llx%016llx%016llx\n",et,
        (unsigned long long)a[3],(unsigned long long)a[2],
        (unsigned long long)a[1],(unsigned long long)a[0]);
}

static int fallos=0;

/* Producto contra un valor sabido, para clavar casos concretos. */
static void set_hex(fe_t r,const char*h){
    for(int w=0;w<4;w++){
        char b[17]; memcpy(b,h+(3-w)*16,16); b[16]=0;
        r[w]=strtoull(b,NULL,16);
    }
}
static void prueba_mul_fijo(const char*ha,const char*hb,const char*hesp,const char*et){
    fe_t a,b,esp,r;
    set_hex(a,ha); set_hex(b,hb); set_hex(esp,hesp);
    fe_mul(r,a,b);
    if(memcmp(r,esp,32)){
        printf("MAL  mul %s\n",et); pinta("dio  ",r); pinta("esp  ",esp); fallos++;
    } else printf("  OK   %s\n",et);
}

static void prueba_sqr(const fe_t a,const char*et){
    fe_t nuevo,viejo;
    fe_sqr(nuevo,a);
    fe_mul(viejo,a,a);
    if(!igual(nuevo,viejo)){
        printf("MAL  sqr %s\n",et); pinta("a    ",a);
        pinta("sqr  ",nuevo); pinta("mul  ",viejo); fallos++;
    }
}

static void prueba_inv(const fe_t a,const char*et){
    fe_t nuevo,viejo,uno;
    fe_inv(nuevo,a);
    inv_lento(viejo,a);
    if(!igual(nuevo,viejo)){
        printf("MAL  inv %s (no coincide con el lento)\n",et);
        pinta("a    ",a); pinta("nuevo",nuevo); pinta("viejo",viejo); fallos++;
        return;
    }
    fe_mul(uno,a,nuevo);
    int es_cero = (a[0]|a[1]|a[2]|a[3])==0;
    int es_uno  = uno[0]==1 && !uno[1] && !uno[2] && !uno[3];
    if(!es_cero && !es_uno){
        printf("MAL  inv %s (a*inv(a) != 1)\n",et);
        pinta("a    ",a); pinta("a*inv",uno); fallos++;
    }
}

/* Aliasing: fe_sqr(a,a) tiene que dar lo mismo que con destino aparte.
   Es el fallo que ya aparecio una vez en jp_dbl. */
static void prueba_alias(const fe_t a){
    fe_t aparte, mismo;
    fe_sqr(aparte,a);
    memcpy(mismo,a,32); fe_sqr(mismo,mismo);
    if(!igual(aparte,mismo)){ printf("MAL  fe_sqr(a,a) aliasado\n"); fallos++; }

    fe_t ia, im;
    fe_inv(ia,a);
    memcpy(im,a,32); fe_inv(im,im);
    if(!igual(ia,im)){ printf("MAL  fe_inv(a,a) aliasado\n"); fallos++; }
}

int main(){
    fe_t a;

    printf("Casos de borde:\n");
    struct { const char*et; uint64_t v[4]; } bordes[] = {
        {"0",              {0,0,0,0}},
        {"1",              {1,0,0,0}},
        {"2",              {2,0,0,0}},
        {"p-1",            {0xFFFFFFFEFFFFFC2EULL,~0ULL,~0ULL,~0ULL}},
        {"p-2",            {0xFFFFFFFEFFFFFC2DULL,~0ULL,~0ULL,~0ULL}},
        {"2^128",          {0,0,1,0}},
        {"2^255",          {0,0,0,0x8000000000000000ULL}},
        {"todo unos abajo",{~0ULL,0,0,0}},
        {"limbos altos",   {0,0,~0ULL,~0ULL}},
        /* El caso que mas guerra le da al triangulo de fe_sqr: todos los
           productos cruzados al maximo a la vez. */
        {"casi p",         {0xFFFFFFFEFFFFFC2EULL,~0ULL,~0ULL,0x7FFFFFFFFFFFFFFFULL}},
    };
    for(unsigned i=0;i<sizeof(bordes)/sizeof(bordes[0]);i++){
        memcpy(a,bordes[i].v,32);
        prueba_sqr(a,bordes[i].et);
        prueba_inv(a,bordes[i].et);
        prueba_alias(a);
        printf("  OK   %s\n",bordes[i].et);
    }

    /* El caso que destapo el acarreo perdido en la reduccion: (p-2^31)^2.
       El resultado tiene que ser 2^62 clavado, porque p-2^31 == -2^31 mod p.
       La version anterior daba exactamente C = 0x1000003D1 de menos. */
    printf("\nAcarreo de la reduccion (lo que estaba roto):\n");
    prueba_mul_fijo(
        "fffffffffffffffffffffffffffffffffffffffffffffffffffffffe7ffffc2f",
        "fffffffffffffffffffffffffffffffffffffffffffffffffffffffe7ffffc2f",
        "0000000000000000000000000000000000000000000000004000000000000000",
        "(p-2^31)^2 == 2^62");
    {   /* lo mismo por fe_sqr, que tiene su propio camino */
        fe_t a,r,esp;
        set_hex(a,"fffffffffffffffffffffffffffffffffffffffffffffffffffffffe7ffffc2f");
        set_hex(esp,"0000000000000000000000000000000000000000000000004000000000000000");
        fe_sqr(r,a);
        if(memcmp(r,esp,32)){ printf("MAL  sqr (p-2^31)^2\n"); pinta("dio  ",r); fallos++; }
        else printf("  OK   (p-2^31)^2 por fe_sqr\n");
    }
    /* Toda la familia (p-2^k)^2 == 2^(2k): cada k da un patron de acarreo
       distinto, y es justo la forma que hace cascada. */
    {   int malos=0;
        for(int k=1;k<128;k++){
            fe_t dosk,a,r,esp;
            memset(dosk,0,32); dosk[k/64]=1ULL<<(k%64);
            fe_sub(a,FP,dosk);                 /* a = p - 2^k */
            memset(esp,0,32);
            if(2*k<256) esp[(2*k)/64]=1ULL<<((2*k)%64);   /* 2^(2k) */
            fe_sqr(r,a);
            if(memcmp(r,esp,32)) malos++;
            fe_mul(r,a,a);
            if(memcmp(r,esp,32)) malos++;
        }
        printf("  %s   (p-2^k)^2 para k=1..127, mul y sqr: %d mal\n",
               malos?"MAL":"OK",malos);
        fallos+=malos;
    }

    printf("\n20000 valores al azar (sqr):\n");
    int antes0=fallos;
    for(int i=0;i<20000;i++){
        for(int w=0;w<4;w++) a[w]=azar();
        if(fe_cmp(a,FP)>=0) fe_sub(a,a,FP);
        prueba_sqr(a,"azar");
    }
    printf("  %s   %d fallos\n",(fallos-antes0)?"MAL":"OK",fallos-antes0);

    printf("\n500 valores al azar (inv, contra el lento):\n");
    int antes=fallos;
    for(int i=0;i<500;i++){
        for(int w=0;w<4;w++) a[w]=azar();
        if(fe_cmp(a,FP)>=0) fe_sub(a,a,FP);
        prueba_inv(a,"azar");
    }
    printf("  %s   %d fallos\n",(fallos-antes)?"MAL":"OK",fallos-antes);

    /* Valores pequenos: los limbos altos a cero son donde un acarreo mal
       puesto se cuela sin que los aleatorios lo vean. */
    printf("\nValores pequenos 1..3000:\n");
    antes=fallos;
    for(uint64_t k=1;k<=3000;k++){
        a[0]=k;a[1]=a[2]=a[3]=0;
        prueba_sqr(a,"pequeno");
        if(k<=200) prueba_inv(a,"pequeno");
    }
    printf("  %s   %d fallos\n",(fallos-antes)?"MAL":"OK",fallos-antes);

    printf("\n%s\n", fallos ? "HAY FALLOS" : "TODO CORRECTO");
    return fallos?1:0;
}
