/* La lista de coincidencias no puede crecer sin freno.
 *
 * EL FALLO QUE LA TRAJO. El escaneo secuencial, al llegar al final del rango,
 * vuelve a empezar. Con un rango grande eso no pasa nunca. El puzzle #1 tiene
 * UNA sola clave: la encuentra, reinicia, la vuelve a encontrar, miles de veces
 * por segundo. Cada vez se anadia una entrada a la lista y una linea al
 * fichero. El servicio pedia la lista entera concatenada una vez por segundo y
 * le hacia .lines(). A los pocos minutos: 244 MB de 256 y la app muerta.
 *
 * El movil se cerro de verdad, y el aviso que dejo no hablaba del escaneo ni del
 * puzzle: hablaba de kotlin.text.StringsKt.lines(). Entre la causa y el sintoma
 * no habia ninguna pista.
 *
 * Lo que se prueba aqui no es "el puzzle #1": es que una lista que solo crece
 * acaba igual por cualquier camino —una direccion repetida en el CSV, un rango
 * que se reescanea, un trabajador que reenvia—. Por eso la primera prueba
 * reproduce el bucle tal cual, con un millon de vueltas.
 */
#include <stdio.h>
#include <string>
#include "../../app/src/main/cpp/coincidencias.h"

static int fallos=0;
#define OK(cond,et) do{ if(cond) printf("  OK   %s\n",et); \
                        else { printf("MAL  %s\n",et); fallos++; } }while(0)

int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    printf("La lista de coincidencias\n");

    printf("\n1. El bucle del puzzle #1: la misma clave un millon de veces:\n");
    {
        Coincidencias c; coinc_init(&c);
        const std::string misma="MATCH|ADDR:1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH"
                                "|BTC:0|WIF:x|HEX:0000...0001";
        int nuevas=0;
        for(int i=0;i<1000000;i++) nuevas+=coinc_add(&c,misma);
        printf("       %d se dieron por nuevas, %zu quedan guardadas,"
               " %llu descartadas por repetidas\n",
               nuevas,coinc_cuantas(&c),c.repetidas);
        OK(nuevas==1, "solo la primera cuenta como nueva");
        OK(coinc_cuantas(&c)==1, "y la lista se queda en una entrada");
        /* Sin esto, el texto que pide el servicio una vez por segundo tendria
           un millon de lineas. Eso es lo que mataba la app. */
        OK(coinc_texto(&c).size()<200, "el texto que se entrega sigue siendo corto");
        OK(c.total==1000000, "pero el total sigue contando todas");
    }

    printf("\n2. Muchas distintas: se guardan las ultimas y no mas:\n");
    {
        Coincidencias c; coinc_init(&c);
        for(int i=0;i<COINC_TOPE*5;i++){
            char b[64]; snprintf(b,sizeof(b),"MATCH|n=%d",i);
            coinc_add(&c,std::string(b));
        }
        OK(coinc_cuantas(&c)==COINC_TOPE, "la lista se queda en el tope");
        std::string t=coinc_texto(&c);
        /* Se tiran las viejas, no las nuevas: lo ultimo encontrado es lo que
           interesa ver. */
        char ult[64]; snprintf(ult,sizeof(ult),"MATCH|n=%d\n",COINC_TOPE*5-1);
        OK(t.find(ult)!=std::string::npos, "la ultima esta");
        OK(t.find("MATCH|n=0\n")==std::string::npos, "y la primera ya no");
        OK(c.total==(unsigned long long)COINC_TOPE*5,
           "el total no se ve afectado por el tope");
    }

    printf("\n3. Sacarlas de una en una:\n");
    {
        Coincidencias c; coinc_init(&c);
        coinc_add(&c,"una"); coinc_add(&c,"dos");
        OK(coinc_pop(&c)=="una", "sale primero la mas antigua");
        OK(coinc_pop(&c)=="dos", "y luego la siguiente");
        OK(coinc_pop(&c)=="",    "y despues cadena vacia, no basura");
        OK(coinc_cuantas(&c)==0, "la lista queda vacia");
    }

    printf("\n4. Distintas de verdad no se confunden:\n");
    {
        Coincidencias c; coinc_init(&c);
        OK(coinc_add(&c,"MATCH|ADDR:A|HEX:1")==1, "la primera entra");
        OK(coinc_add(&c,"MATCH|ADDR:A|HEX:2")==1, "otra que solo cambia en la clave, tambien");
        OK(coinc_add(&c,"MATCH|ADDR:A|HEX:1")==0, "y la repetida no");
        OK(coinc_cuantas(&c)==2, "quedan dos");
    }

    printf("\n%s\n", fallos?"HAY FALLOS":"TODO CORRECTO");
    return fallos?1:0;
}
