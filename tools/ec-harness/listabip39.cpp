/* La lista BIP39 que compila el motor es la oficial: 2048 palabras, sin
 * repetir, y el SHA-256 de english.txt. La que había tenía 139 de menos y
 * "zoo" de relleno, y el modo BIP39 del escáner generaba frases imposibles. */
#include <stdio.h>
#include <string.h>
#include <string>
#include <set>
#include <openssl/sha.h>
static const char *BIP39[]={
#include "../../app/src/main/cpp/bip39_words.h"
};
int main(){
    int n=(int)(sizeof(BIP39)/sizeof(BIP39[0])), fallos=0;
    std::string todo; std::set<std::string> u;
    for(int i=0;i<n;i++){ todo+=BIP39[i]; todo+='\n'; u.insert(BIP39[i]); }
    uint8_t h[32]; SHA256((const uint8_t*)todo.data(),todo.size(),h);
    char hx[65]; for(int i=0;i<32;i++) sprintf(hx+2*i,"%02x",h[i]);
    const char *oficial="2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda";
    if(n==2048) printf("  OK   2048 palabras\n"); else { printf("  MAL  %d palabras\n",n); fallos++; }
    if((int)u.size()==n) printf("  OK   ninguna repetida\n"); else { printf("  MAL  %d repetidas\n",n-(int)u.size()); fallos++; }
    if(!strcmp(hx,oficial)) printf("  OK   SHA-256 de english.txt\n"); else { printf("  MAL  sha %s\n",hx); fallos++; }
    printf(fallos?"FALLOS %d\n":"TODO CORRECTO\n",fallos);
    return fallos?1:0;
}
