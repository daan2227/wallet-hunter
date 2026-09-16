/* El codificador de direcciones, contra vectores conocidos.
 *
 * Hasta ahora esta pieza no tenia ni una prueba, porque vivia dentro de
 * hunter_jni.cpp y ese fichero no se puede compilar fuera de Android. Se saco a
 * addr_encode.h justo para poder ponerla aqui.
 *
 * Importa especialmente por el cambio de testnet. Cambiar el byte de version de
 * Base58 es trivial, pero en bech32 el prefijo humano ("bc" o "tb") ENTRA EN EL
 * CHECKSUM: si se escribe "tb" en la salida pero se calcula el checksum con
 * "bc", sale una direccion con la pinta correcta y el checksum malo. Nadie lo
 * veria hasta intentar cobrar.
 *
 * DE DONDE SALEN LOS VALORES ESPERADOS. Importa, porque es facil hacer trampa
 * aqui: si uno copia lo que el propio codigo imprime, la prueba no comprueba
 * nada. Estan anclados en cinco puntos que son vectores PUBLICADOS y que
 * cualquiera puede contrastar:
 *
 *   - bech32 mainnet y TESTNET     -> BIP173
 *   - bech32m (P2TR) mainnet       -> BIP086
 *   - WIF mainnet y testnet de k=1 -> el ejemplo mas repetido que existe
 *
 * Los tres restantes (P2PKH y P2SH de testnet, y P2TR de testnet) se sacaron de
 * una implementacion independiente en Python, no de esta. Y no van sueltos: el
 * mismo codigo que los genera acierta los cinco anclados de arriba, asi que si
 * estuviera mal, fallaria ahi primero.
 *
 * Al escribir esta prueba puse a mano los tres valores de testnet y los tres
 * estaban MAL. El codigo era correcto. Queda anotado porque el reflejo natural
 * al ver fallar una prueba es tocar el codigo.
 */
#include <stdio.h>
#include <string.h>
#include "../../app/src/main/cpp/addr_encode.h"

static int fallos=0;
static void cmp(const char *et,const char *dio,const char *esp){
    if(strcmp(dio,esp)==0) printf("  OK   %-34s %s\n",et,dio);
    else { printf("MAL  %s\n       dio: %s\n       esp: %s\n",et,dio,esp); fallos++; }
}
static void dehex(const char *h,uint8_t *o,int n){
    for(int i=0;i<n;i++){ unsigned v; sscanf(h+i*2,"%2x",&v); o[i]=(uint8_t)v; }
}

int main(){
    char a[MAX_ADDR];

    /* hash160 de la clave publica de k=1. Es el ejemplo mas repetido que hay. */
    uint8_t h[20]; dehex("751e76e8199196d454941c45d1b3a323f1433bd6",h,20);

    printf("P2PKH (Base58, version 0x00 / 0x6f):\n");
    h160_to_addr(h,a,false); cmp("mainnet",a,"1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH");
    h160_to_addr(h,a,true);  cmp("testnet",a,"mrCDrCybB6J1vRfbwM5hemdJz73FwDBC8r");

    printf("\nP2SH-P2WPKH (Base58, version 0x05 / 0xc4):\n");
    h160_to_p2sh(h,a,false); cmp("mainnet",a,"3JvL6Ymt8MVWiCNHC7oWU6nLeHNJKLZGLN");
    h160_to_p2sh(h,a,true);  cmp("testnet",a,"2NAUYAHhujozruyzpsFRP63mbrdaU5wnEpN");

    /* Aqui esta lo que de verdad hay que vigilar: el prefijo entra en el
       checksum, asi que un fallo NO se ve en los primeros caracteres. */
    printf("\nP2WPKH (bech32, prefijo dentro del checksum):\n");
    h160_to_bech32(h,a,false); cmp("mainnet",a,"bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4");
    h160_to_bech32(h,a,true);  cmp("testnet",a,"tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx");

    printf("\nP2TR (bech32m, constante de checksum distinta):\n");
    uint8_t x[32];
    dehex("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",x,32);
    xonly_to_p2tr(x,a,false);
    cmp("mainnet",a,"bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0");
    xonly_to_p2tr(x,a,true);
    cmp("testnet",a,"tb1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq47zagq");

    printf("\nWIF (version 0x80 / 0xef):\n");
    uint8_t k[32]; memset(k,0,32); k[31]=1;      /* clave privada = 1 */
    char w[64];
    pk_to_wif(k,w,false); cmp("mainnet",w,"KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn");
    pk_to_wif(k,w,true);  cmp("testnet",w,"cMahea7zqjxrtgAbB7LSGbcQUr1uX1ojuat9jZodMN87JcbXMTcA");

    /* Que mainnet y testnet no den lo mismo: si alguien deja el parametro sin
       usar, todo lo de arriba seguiria pasando salvo esto. */
    printf("\nLas dos redes tienen que dar cosas distintas:\n");
    char m[MAX_ADDR],t[MAX_ADDR];
    struct { const char *et; void (*f)(const uint8_t*,char*,bool); } fs[] = {
        {"P2PKH",  h160_to_addr},
        {"P2SH",   h160_to_p2sh},
        {"bech32", h160_to_bech32},
    };
    for(unsigned i=0;i<sizeof(fs)/sizeof(fs[0]);i++){
        fs[i].f(h,m,false); fs[i].f(h,t,true);
        if(strcmp(m,t)==0){ printf("MAL  %s da lo mismo en las dos redes\n",fs[i].et); fallos++; }
        else printf("  OK   %s difiere\n",fs[i].et);
    }

    printf("\n%s\n", fallos ? "HAY FALLOS" : "TODO CORRECTO");
    return fallos?1:0;
}
