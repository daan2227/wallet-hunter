/* Direcciones de destino -> scriptPubKey (addr_decode.h): vectores de BIP350
 * (válidos e inválidos), base58check con errata, y b58enc con cargas largas
 * (el intermedio de BIP38 desbordaba la pila). */
#include <stdio.h>
#include <string>
#include "../../app/src/main/cpp/addr_encode.h"
#include "../../app/src/main/cpp/addr_decode.h"

static std::string hex(const std::string &s){ static const char*d="0123456789abcdef"; std::string o;
    for(unsigned char c:s){ o+=d[c>>4]; o+=d[c&15]; } return o; }

int main(){
    int fallos=0;
    struct V{ const char *a, *spk; } validos[]={
        {"BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4","0014751e76e8199196d454941c45d1b3a323f1433bd6"},
        {"tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7","00201863143c14c5166804bd19203356da136c985678cd4d27a1b8c6329604903262"},
        {"BC1SW50QGDZ25J","6002751e"},
        {"bc1zw508d6qejxtdg4y5r3zarvaryvaxxpcs","5210751e76e8199196d454941c45d1b3a323"},
        {"tb1qqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesrxh6hy","0020000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433"},
        {"tb1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesf3hn0c","5120000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433"},
        {"bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0","512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"},
        {"1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2","76a91477bff20c60e522dfaa3350c39b030a5d004e839a88ac"},
        {"3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy","a914b472a266d0bd89c13706a4132ccfb16f7c3b9fcb87"},
        {"1111111111111111111114oLvT2","76a914000000000000000000000000000000000000000088ac"},
    };
    for(auto &v:validos){
        std::string got=hex(addr_destino_spk(v.a));
        if(got==v.spk) printf("  OK   %.24s…\n",v.a); else { printf("  MAL  %s -> %s\n",v.a,got.c_str()); fallos++; }
    }
    /* Direcciones válidas para BIP350 (versión futura, programa de 40 bytes) pero
       NO gastables hoy: pagarlas pierde el dinero. Se rechazan para enviar. */
    const char *invalidos[]={
        "bc1pw508d6qejxtdg4y5r3zarvary0c5xw7kw508d6qejxtdg4y5r3zarvary0c5xw7kt5nd6y",
        "tc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq5zuyut",
        "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd",
        "tb1z0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqglt7rf",
        "BC1S0XLXVLHEMJA6C4DQV22UAPCTQUPFHLXM9H8Z3K2E72Q4K9HCZ7VQ54WELL",
        "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh",
        "tb1q0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq24jc47",
        "bc1p38j9r5y49hruaue7wxjce0updqjuyyx0kh56v8s25huc6995vvpql3jow4",
        "BC130XLXVLHEMJA6C4DQV22UAPCTQUPFHLXM9H8Z3K2E72Q4K9HCZ7VQ7ZWS8R",
        "bc1pw5dgrnzv",
        "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7v8n0nx0muaewav253zgeav",
        "BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P",
        "tb1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq47Zagq",
        "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7v07qwwzcrf",
        "tb1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vpggkg4j",
        "bc1gmk9yu",
        /* errata de un carácter en base58 (checksum) */
        "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN3",
        /* bech32 v0 con una errata */
        "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T5",
        /* demasiado largo para base58 de 25 bytes */
        "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2zz",
        "", "1", "bc1", "notanaddress",
    };
    int ninv=0;
    for(const char *a:invalidos){
        std::string got=addr_destino_spk(a);
        if(got.empty()) ninv++; else { printf("  MAL  aceptada: %s -> %s\n",a,hex(got).c_str()); fallos++; }
    }
    printf("  OK   %d direcciones inválidas rechazadas\n",ninv);
    /* Red: una de testnet no vale en mainnet y viceversa. */
    if(!addr_destino_spk("tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7",0).empty() ||
       !addr_destino_spk("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2",1).empty()){ printf("  MAL  red cruzada aceptada\n"); fallos++; }
    else printf("  OK   red cruzada rechazada\n");
    /* b58enc con 49 bytes de carga (53 con checksum): el caso que desbordaba. */
    { uint8_t pl[49]; for(int i=0;i<49;i++) pl[i]=(uint8_t)(0xff-i); char out[100]; b58enc(pl,49,out,sizeof out);
      size_t l=strlen(out); if(l>=70&&l<=74) printf("  OK   b58enc de 53 bytes: %zu caracteres\n",l); else { printf("  MAL  b58enc %zu\n",l); fallos++; } }
    /* Taproot con programa de 20 bytes (bech32m, checksum bueno): no se puede gastar. */
    if(!addr_destino_spk("bc1pqqqsyqcyq5rqwzqfpg9scrgwpugpzysntwgkaa").empty()){ printf("  MAL  p2tr de 20 bytes aceptado\n"); fallos++; }
    else printf("  OK   p2tr de 20 bytes rechazado\n");
    /* La red manda: una dirección de testnet no vale en mainnet y viceversa (para la firma). */
    if(!addr_destino_spk("tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7",0).empty()){ printf("  MAL  tb1 en mainnet aceptada\n"); fallos++; }
    else printf("  OK   tb1 rechazada en mainnet\n");
    printf(fallos?"FALLOS %d\n":"TODO CORRECTO\n",fallos);
    return fallos?1:0;
}
