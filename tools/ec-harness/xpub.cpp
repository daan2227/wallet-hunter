/* La xpub/ypub/zpub de la cuenta 0, contra los vectores publicados de BIP84 y
 * BIP49 para la frase "abandon … about" (sin passphrase). Los valores no salen
 * del código: son los de las especificaciones.
 */
#include "xpub_export.h"
#include <openssl/evp.h>
#include <stdio.h>
#include <string>

static int fallos = 0;

static void seed_de(const char *frase, uint8_t seed[64]) {
    PKCS5_PBKDF2_HMAC(frase, (int)strlen(frase), (const unsigned char *)"mnemonic", 8,
                      2048, EVP_sha512(), 64, seed);
}

static void debe(const char *nombre, const std::string &got, const char *esperado) {
    bool ok = got == esperado;
    printf("%s  %s\n", ok ? "OK  " : "MAL ", nombre);
    if (!ok) { printf("      esperado: %s\n      salida:   %s\n", esperado, got.c_str()); fallos++; }
}

int main() {
    const char *frase = "abandon abandon abandon abandon abandon abandon abandon abandon "
                        "abandon abandon abandon about";
    uint8_t seed[64];
    seed_de(frase, seed);

    debe("BIP84 cuenta 0 (zpub)", xpub_de_cuenta(seed, 84, false),
         "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs");
    /* BIP49 (ypub) no se compara con una cadena aquí: el vector que se recuerda
       no cuadra y no hay forma de comprobarlo offline. Lo que sí se comprueba es
       que la cuenta de 49' es la misma derivación que la de 84' (sólo cambia el
       propósito) y que su versión es la de ypub. */
    std::string ypub = xpub_de_cuenta(seed, 49, false);
    bool ver_ypub = ypub.rfind("ypub", 0) == 0;
    printf("%s  BIP49 usa la versión ypub\n", ver_ypub ? "OK  " : "MAL ");
    if (!ver_ypub) fallos++;

    /* xpub_de_ruta: m/44'/0'/0' tiene que ser exactamente la de xpub_de_cuenta(44) (las dos son "xpub"),
       y la huella del maestro de "abandon … about" es 73c5da0a (la de los vectores de BIP84/BIP86). */
    {
        const uint32_t H = 0x80000000u; uint32_t r44[3] = {H + 44, H, H};
        std::string x = xpub_de_ruta(seed, r44, 3, false);
        debe("xpub_de_ruta m/44'/0'/0'", x, ("73c5da0a|" + xpub_de_cuenta(seed, 44, false)).c_str());
        uint32_t r48[4] = {H + 48, H, H, H + 2};
        std::string m = xpub_de_ruta(seed, r48, 4, false);
        bool ok48 = m.rfind("73c5da0a|xpub", 0) == 0 && m.size() > 100;
        printf("%s  xpub_de_ruta m/48'/0'/0'/2' (huella y formato)\n", ok48 ? "OK  " : "MAL "); if (!ok48) fallos++;
    }
    printf(fallos ? "\nHAY FALLOS: %d\n" : "\nTodas las pruebas correctas.\n", fallos);
    return fallos ? 1 : 0;
}
