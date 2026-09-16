/* El parseo del rango nunca se probó: en los tests de Kangaroo le pasaba
   buffers de 32 bytes ya montados, no el texto que le llega desde la app. Por
   ahí se coló que rechazara las longitudes impares. */
#include <stdio.h>
#include <string.h>
#include <pthread.h>
#include "../../app/src/main/cpp/kangaroo.h"

static int prueba(const char*h,const char*esperado){
    uint8_t b[32];
    if(!kg_hex_a_be32(h,b)){ printf("MAL  \"%s\" -> rechazado\n",h); return 1; }
    char got[65]; for(int i=0;i<32;i++) sprintf(got+i*2,"%02x",b[i]); got[64]=0;
    int ok=!strcmp(got,esperado);
    printf("%s  %-44s -> %s\n", ok?"OK ":"MAL", h, ok?"correcto":got);
    return !ok;
}
static int rechaza(const char*h){
    uint8_t b[32];
    int r=kg_hex_a_be32(h,b);
    printf("%s  rechaza \"%s\"\n", r?"MAL":"OK ", h);
    return r?1:0;
}
int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    int f=0;
    printf("Longitud impar, que es lo que fallaba:\n");
    /* 2^139, el inicio del puzzle #140: 35 dígitos */
    f+=prueba("8000000000000000000000000000000000",
              "0000000000000000000000000000008000000000000000000000000000000000");
    f+=prueba("8","0000000000000000000000000000000000000000000000000000000000000008");
    f+=prueba("abc","0000000000000000000000000000000000000000000000000000000000000abc");
    printf("\nLongitud par:\n");
    f+=prueba("ff","00000000000000000000000000000000000000000000000000000000000000ff");
    f+=prueba("8000000000000000000000000000000000000000",
              "0000000000000000000000008000000000000000000000000000000000000000");
    printf("\nCasos que deben rechazarse:\n");
    f+=rechaza("");
    f+=rechaza("xyz");
    f+=rechaza("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef0");
    printf("\nCon prefijo y con ceros delante:\n");
    f+=prueba("0x7f","000000000000000000000000000000000000000000000000000000000000007f");
    f+=prueba("0007f","000000000000000000000000000000000000000000000000000000000000007f");
    printf("\n%s\n", f?"HAY FALLOS":"TODO CORRECTO");
    return f;
}
