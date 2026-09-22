/* Aritmetica modulo el orden del grupo.
 *
 * Hace falta para el mapa de negacion: cambiar P por -P obliga a cambiar la
 * distancia d por -d, y hasta ahora las distancias solo crecian porque todos
 * los saltos iban hacia delante.
 *
 * Comprobar que "n-1 mas 1 da 0" no basta: eso solo dice que la cuenta es
 * consistente consigo misma. Lo que hay que comprobar es que ATA CON LA CURVA,
 * porque para eso existe:
 *
 *     (a+b mod n)*G  ==  a*G + b*G
 *     (-a  mod n)*G  ==  -(a*G)
 *
 * Si n estuviera mal escrito —un digito cambiado de las 64 cifras hexadecimales
 * que tiene— las cuentas entre ellas seguirian cuadrando y solo fallaria esto.
 * Y fallaria en silencio: las distancias irian mal y la busqueda no encontraria
 * nada, que es el sintoma de todos los fallos de este motor.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include "../../app/src/main/cpp/kangaroo.h"

static int fallos=0;
#define OK(cond,et) do{ if(cond) printf("  OK   %s\n",et); \
                        else { printf("MAL  %s\n",et); fallos++; } }while(0)

static uint64_t rng=0x243F6A8885A308D3ULL;
static uint64_t sig(void){ rng^=rng<<13; rng^=rng>>7; rng^=rng<<17; return rng; }

/* Un escalar al azar menor que n. Por rechazo, que aqui sobra tiempo. */
static void azar(sc_t r){
    for(;;){
        for(int i=0;i<4;i++) r[i]=sig();
        if(sc_cmp(r,SC_N)<0) return;
    }
}

/* Los dos puntos iguales, en afin. Devuelve 1 si coinciden. */
static int mismo(const JP *A,const JP *B){
    int ia=1,ib=1;
    for(int i=0;i<4;i++){ if(A->z[i]) ia=0; if(B->z[i]) ib=0; }
    if(ia||ib) return ia&&ib;             /* los dos en el infinito */
    fe_t ax,ay,bx,by;
    kg_normalize(A,ax,ay); kg_normalize(B,bx,by);
    return memcmp(ax,bx,32)==0 && memcmp(ay,by,32)==0;
}

static void por_G(JP *R,const sc_t k){ kg_scalar_mul(R,k,FIELD_GX,FIELD_GY); }

int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    printf("Aritmetica modulo el orden del grupo\n\n");

    printf("1. Casos de borde:\n");
    {
        sc_t uno; sc_set_u64(uno,1);
        sc_t nm1; sc_sub(nm1,SC_N,uno);        /* n-1 */
        sc_t r; sc_add_n(r,nm1,uno);
        int cero=1; for(int i=0;i<4;i++) if(r[i]) cero=0;
        OK(cero, "(n-1) + 1 == 0");

        sc_neg_n(r,nm1);
        OK(sc_cmp(r,uno)==0, "-(n-1) == 1");

        sc_t z; sc_zero(z); sc_neg_n(r,z);
        cero=1; for(int i=0;i<4;i++) if(r[i]) cero=0;
        OK(cero, "-0 == 0  (y no n, que no cabria)");

        /* El acarreo fuera de los 256 bits. n esta muy cerca de 2^256, asi que
           sumar dos valores grandes se sale, y ahi es donde una implementacion
           descuidada se rompe. */
        sc_t a,b; sc_copy(a,nm1); sc_copy(b,nm1);
        sc_add_n(r,a,b);                       /* (n-1)+(n-1) = n-2 */
        sc_t nm2; sc_t dos; sc_set_u64(dos,2); sc_sub(nm2,SC_N,dos);
        OK(sc_cmp(r,nm2)==0, "(n-1)+(n-1) == n-2, con acarreo de 256 bits");

        sc_sub_n(r,z,uno);
        OK(sc_cmp(r,nm1)==0, "0 - 1 == n-1");
    }

    printf("\n2. Consigo misma, 2000 valores al azar:\n");
    {
        int mal_ida=0, mal_neg=0, mal_rango=0;
        for(int t=0;t<2000;t++){
            sc_t a,b,r,s;
            azar(a); azar(b);
            sc_add_n(r,a,b); sc_sub_n(s,r,b);
            if(sc_cmp(s,a)!=0) mal_ida++;
            sc_neg_n(r,a); sc_neg_n(s,r);
            if(sc_cmp(s,a)!=0) mal_neg++;
            /* Todo resultado tiene que quedarse por debajo de n. */
            sc_add_n(r,a,b); if(sc_cmp(r,SC_N)>=0) mal_rango++;
            sc_sub_n(r,a,b); if(sc_cmp(r,SC_N)>=0) mal_rango++;
            sc_neg_n(r,a);   if(sc_cmp(r,SC_N)>=0) mal_rango++;
        }
        OK(mal_ida==0,  "a+b-b == a");
        OK(mal_neg==0,  "-(-a) == a");
        OK(mal_rango==0,"todo resultado se queda por debajo de n");
    }

    printf("\n3. Contra la curva, que es lo que de verdad importa:\n");
    {
        /* Valores pequenos, donde se puede seguir la cuenta a mano.
         *
         * Con a == b los dos puntos son el mismo y sumarlos es DOBLAR, no sumar:
         * la formula de la suma afin pide dividir por x2-x1, que ahi es cero.
         * No es un fallo del motor —el bucle nunca suma un punto consigo mismo,
         * y prim.cpp ya cubre ese caso aparte— era un fallo de esta prueba, que
         * llamaba a la operacion equivocada en 40 de las 1600 parejas. Se usa la
         * que toca en cada caso y asi quedan cubiertas las dos. */
        int mal=0, doblados=0;
        for(unsigned long long x=1;x<=40;x++){
            for(unsigned long long y=1;y<=40;y++){
                sc_t a,b,s; sc_set_u64(a,x); sc_set_u64(b,y);
                sc_add_n(s,a,b);
                JP A,B,S,AB;
                por_G(&A,a); por_G(&B,b); por_G(&S,s);
                if(x==y){ jp_dbl(&AB,&A); doblados++; }
                else{
                    fe_t bx,by; kg_normalize(&B,bx,by);
                    jp_add_affine(&AB,&A,bx,by);
                }
                if(!mismo(&S,&AB)) mal++;
            }
        }
        OK(mal==0, "(a+b)*G == a*G + b*G  para 1600 parejas pequenas");
        OK(doblados==40, "y 40 de ellas por el camino del doblado");

        /* Y con escalares de 256 bits al azar, que es como van las distancias
           de verdad en cuanto la negacion actua una vez. */
        mal=0;
        for(int t=0;t<60;t++){
            sc_t a,b,s; azar(a); azar(b);
            sc_add_n(s,a,b);
            JP A,B,S,AB;
            por_G(&A,a); por_G(&B,b); por_G(&S,s);
            fe_t bx,by; kg_normalize(&B,bx,by);
            jp_add_affine(&AB,&A,bx,by);
            if(!mismo(&S,&AB)) mal++;
        }
        OK(mal==0, "(a+b)*G == a*G + b*G  para 60 escalares de 256 bits");

        /* La negacion: (-a)*G tiene que ser el opuesto de a*G, o sea la misma x
           y la y cambiada de signo. Esto es lo que ata n con la curva: con un n
           equivocado, todo lo de arriba seguiria cuadrando y esto no. */
        mal=0;
        int mal_x=0;
        for(int t=0;t<60;t++){
            sc_t a,na; azar(a); sc_neg_n(na,a);
            JP A,NA; por_G(&A,a); por_G(&NA,na);
            fe_t ax,ay,nx,ny; kg_normalize(&A,ax,ay); kg_normalize(&NA,nx,ny);
            if(memcmp(ax,nx,32)!=0) mal_x++;
            fe_t cero,menos_ay; memset(cero,0,32); fe_sub(menos_ay,cero,ay);
            if(memcmp(menos_ay,ny,32)!=0) mal++;
        }
        OK(mal_x==0, "(-a)*G tiene la misma x que a*G");
        OK(mal==0,   "(-a)*G tiene la y cambiada de signo: es el opuesto");

        /* n*G = infinito. Es LA propiedad que define n. */
        JP N; por_G(&N,SC_N);
        int inf=1; for(int i=0;i<4;i++) if(N.z[i]) inf=0;
        OK(inf, "n*G es el punto en el infinito: n es el orden de verdad");
    }

    printf("\n%s\n", fallos?"HAY FALLOS":"TODO CORRECTO");
    return fallos?1:0;
}
