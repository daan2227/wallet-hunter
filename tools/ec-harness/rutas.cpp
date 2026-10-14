/* Rutas BIP32 estrictas (ruta_bip32.h): las válidas dan sus índices y las
 * demás se rechazan. Antes "44'/0'/0'/0/0" o "m/84h/0h/0h/0/0" derivaban otra
 * clave sin avisar. */
#include <stdio.h>
#include <string.h>
#include "../../app/src/main/cpp/ruta_bip32.h"
int main(){
    int f=0; uint32_t ix[32];
    const uint32_t H=0x80000000u;
    struct V{ const char *r; int n; uint32_t i[5]; } ok[]={
        {"m",0,{0}},
        {"m/0",1,{0}},
        {"m/84'/0'/0'/0/5",5,{84|H,0|H,0|H,0,5}},
        {"m/84h/0h/0h/1/12",5,{84|H,0|H,0|H,1,12}},
        {"m/44H/1'/0'/0/2147483647",5,{44|H,1|H,0|H,0,2147483647u}},
    };
    for(auto &v:ok){
        int n=ruta_bip32_parse(v.r,ix,32);
        bool b=(n==v.n); for(int k=0;b&&k<n;k++) b=(ix[k]==v.i[k]);
        printf("  %s   %s\n",b?"OK ":"MAL",v.r); f+=!b;
    }
    const char *mal[]={ "", "44'/0'/0'/0/0", "m/", "m//0", "m/84'/0'/0'/0/", "m/x", "m/84''", "m/2147483648",
                        "m/99999999999", "m/1/2a", " m/0", "M/0", "m/0 ", "m/-1", nullptr };
    int r=0; for(int k=0; mal[k]; k++) if(ruta_bip32_parse(mal[k],ix,32)<0) r++; else { printf("  MAL  aceptada: \"%s\"\n",mal[k]); f++; }
    printf("  OK   %d rutas inválidas rechazadas\n", r);
    char larga[200]="m"; for(int k=0;k<40;k++) strcat(larga,"/1");
    if(ruta_bip32_parse(larga,ix,32)<0) printf("  OK   más niveles que el tope: rechazada\n"); else { printf("  MAL  tope\n"); f++; }
    printf(f?"FALLOS %d\n":"TODO CORRECTO\n",f); return f?1:0;
}
