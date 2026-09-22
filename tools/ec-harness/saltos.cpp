/* La invariante del canguro manso: esta SIEMPRE en dist*G.
 *
 * Un manso sale de d*G y en cada salto avanza jx[h] en el punto y jlen[h] en la
 * distancia. Si las dos cosas no van a la par, el canguro sigue andando y
 * sigue dejando puntos distinguidos en la tabla, pero su distancia ya no dice
 * donde esta. Una colision suya da una resta que no es la clave.
 *
 * Y NO SE NOTA. kg_resolver comprueba kp*G contra el objetivo antes de cantar
 * victoria, asi que no sale una clave falsa: sale que no hay clave. La UI
 * sigue contando millones de claves por segundo, la tabla sigue creciendo, los
 * moviles siguen calentando. Exactamente igual que si todo fuera bien.
 *
 * Por eso esto se comprueba aqui y con el rango GRANDE, que es el que se usa.
 * El fallo que la trajo: la tabla de saltos guardaba jlen en uint64_t y el
 * salto i vale 2^i, asi que a partir de i=63 no cabia y se guardaba CERO. Como
 * ademas KG_MAX_JUMPS estaba en 64, eso pasaba en TODOS los rangos de mas de
 * 118 bits: #140, #145 y #155, los unicos para los que se usa Kangaroo. Medido
 * entonces en el #140: 15 de 145 mansos en su sitio.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include "../../app/src/main/cpp/kangaroo.h"

static int fallos=0;
#define OK(cond,et) do{ if(cond) printf("  OK   %s\n",et); \
                        else { printf("MAL  %s\n",et); fallos++; } }while(0)

/* ini/fin = 2^bits .. 2^(bits+1)-1, en big-endian de 32 bytes. */
static void pot2_be(uint8_t *be,int bits){
    memset(be,0,32);
    be[31-bits/8] = (uint8_t)(1u<<(bits%8));
}
static void todo_unos_be(uint8_t *be,int bits){   /* 2^(bits+1)-1 */
    memset(be,0,32);
    for(int i=0;i<=bits;i++) be[31-i/8] |= (uint8_t)(1u<<(i%8));
}

typedef struct { KangarooCtx *c; int n_kang; uint64_t sem; } Arg;
static void *anda(void *p){ Arg *a=(Arg*)p; kg_run(a->c,a->n_kang,a->sem); return NULL; }

/* Comprueba la invariante sobre los mansos que hayan quedado en la tabla. */
static void prueba(int bits,const char *et){
    printf("\n%s (intervalo de %d bits):\n",et,bits);
    uint8_t ini[32],fin[32],pub[33];
    pot2_be(ini,bits); todo_unos_be(fin,bits);

    /* Un objetivo cualquiera dentro del rango. No se va a encontrar —ni falta—:
       lo que se mira son las huellas que van quedando por el camino. */
    {
        sc_t s; sc_from_be32(s,ini); sc_add_u64(s,12345);
        JP P; kg_scalar_mul(&P,s,FIELD_GX,FIELD_GY);
        fe_t x,y; kg_normalize(&P,x,y);
        pub[0]=(y[0]&1)?0x03:0x02;
        for(int w=0;w<4;w++) for(int b=0;b<8;b++)
            pub[1+(3-w)*8+(7-b)]=(uint8_t)(x[w]>>(b*8));
    }

    KangarooCtx c;
    if(!kg_setup(&c,pub,ini,fin,6,14)){ printf("  setup fallo\n"); fallos++; return; }
    {
        /* El salto mayor tiene que valer 2^(njumps-1). Se mira aqui ademas de
           la invariante porque asi el mensaje dice CUAL es el problema. */
        const sc_t *J=&c.jlen[c.njumps-1];
        int b=sc_bits(*J);
        printf("       njumps=%d, salto mayor = 2^%d%s\n",
               c.njumps, b?b-1:-1,
               (b-1)!=c.njumps-1 ? "   <-- NO ES 2^(njumps-1)":"");
    }

    pthread_t h; Arg a={&c,32,0xC0FFEE};
    pthread_create(&h,NULL,anda,&a);
    /* Andar hasta juntar bastantes distinguidos. */
    for(int v=0; v<40000 && c.tabla.guardados<300; v++){
        struct timespec ts={0,200000}; nanosleep(&ts,NULL);
    }
    c.parar.store(1); pthread_join(h,NULL);

    uint64_t mansos=0, buenos=0;
    for(uint64_t i=0;i<=c.tabla.mask;i++){
        DP *s=&c.tabla.slots[i];
        if(!s->usado || !s->manso) continue;
        mansos++;
        /* dist*G tiene que dar el punto guardado. */
        JP P; kg_scalar_mul(&P,s->dist,FIELD_GX,FIELD_GY);
        int inf=1; for(int z=0;z<4;z++) if(P.z[z]) inf=0;
        if(inf) continue;
        fe_t x,y; kg_normalize(&P,x,y);
        if(x[0]==s->kx[0] && x[1]==s->kx[1]) buenos++;
    }
    printf("       %llu mansos en la tabla, %llu en dist*G\n",
           (unsigned long long)mansos,(unsigned long long)buenos);
    OK(mansos>0, "hay mansos que comprobar");
    OK(mansos>0 && buenos==mansos,
       "todos los mansos estan donde dice su distancia");
    kg_free(&c);
}

int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    printf("Los saltos mueven el punto y la distancia a la par\n");
    /* Rango pequeno: la tabla de saltos cabe de sobra. Es el control — si esto
       tambien fallara, el fallo estaria en la prueba y no en el motor. */
    prueba(40,"1. Rango pequeno (control)");
    /* Rangos de verdad. El #140 es el que corre ahora mismo en los moviles. */
    prueba(119,"2. Justo donde la tabla de saltos se topa");
    prueba(139,"3. Puzzle #140");
    prueba(154,"4. Puzzle #155");
    printf("\n%s\n", fallos ? "HAY FALLOS" : "TODO CORRECTO");
    return fallos?1:0;
}
