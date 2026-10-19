/* La semilla de una frase con y sin passphrase, contra los vectores de BIP39
 * (la frase "abandon … about", con y sin la passphrase TREZOR). Los valores son
 * los publicados por el propio BIP39, no los que da el código.
 */
#include "frase_semilla.h"
#include <stdio.h>
#include <string>

static int fallos = 0;

static std::string hex(const uint8_t *b, int n) {
    std::string s; char t[3];
    for (int i = 0; i < n; i++) { snprintf(t, 3, "%02x", b[i]); s += t; }
    return s;
}

static void debe(const char *nombre, const std::string &got, const char *esperado) {
    bool ok = got == esperado;
    printf("%s  %s\n", ok ? "OK  " : "MAL ", nombre);
    if (!ok) { printf("      esperado: %s\n      salida:   %s\n", esperado, got.c_str()); fallos++; }
}

int main() {
    const std::string frase = "abandon abandon abandon abandon abandon abandon abandon abandon "
                              "abandon abandon abandon about";
    uint8_t seed[64];

    semilla_de_frase(frase.c_str(), frase.size(), seed);
    debe("sin passphrase = semilla BIP39 de siempre", hex(seed, 64),
         "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4");

    const std::string con = frase + "|TREZOR";
    semilla_de_frase(con.c_str(), con.size(), seed);
    debe("con passphrase TREZOR = vector de BIP39", hex(seed, 64),
         "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04");

    printf(fallos ? "\nHAY FALLOS: %d\n" : "\nTodas las pruebas correctas.\n", fallos);
    return fallos ? 1 : 0;
}
