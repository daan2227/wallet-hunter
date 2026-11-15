/* Verifica entropia_debil.h: los cuatro modelos dan lo que dicen y buscar()
 * encuentra la semilla conocida y rechaza una entropía ajena. */
#include "../../app/src/main/cpp/entropia_debil.h"
#include <cstdio>
#include <cstring>
#include <atomic>
static int fallos = 0;
static void debe(bool ok, const char *q) { if (!ok) { fallos++; printf("MAL  %s\n", q); } }
int main() {
    uint8_t buf[32];
    /* 0 REPETIDO */
    entdebil::entropia(0x42, buf, 16, 0);
    { bool ok = true; for (int k = 0; k < 16; k++) if (buf[k] != 0x42) ok = false; debe(ok, "repetido: todos 0x42"); }
    entdebil::entropia(0, buf, 32, 0);
    { bool ok = true; for (int k = 0; k < 32; k++) if (buf[k] != 0) ok = false; debe(ok, "repetido 0: entropia todo-ceros (abandon…)"); }
    /* 1 SECUENCIAL */
    entdebil::entropia(5, buf, 16, 1);
    { bool ok = true; for (int k = 0; k < 16; k++) if (buf[k] != (uint8_t)(5 + k)) ok = false; debe(ok, "secuencial desde 5"); }
    /* 2 ENTERO_LE */
    entdebil::entropia(0x0102030405ULL, buf, 16, 2);
    debe(buf[0]==0x05 && buf[1]==0x04 && buf[2]==0x03 && buf[3]==0x02 && buf[4]==0x01 && buf[5]==0 && buf[15]==0, "entero LE");
    /* 3 ENTERO_BE (n=8 para que quepa) */
    entdebil::entropia(0x0102030405060708ULL, buf, 8, 3);
    debe(buf[0]==0x01 && buf[7]==0x08, "entero BE");

    std::atomic<bool> cancel(false); std::atomic<uint64_t> hechas(0);
    /* buscar encuentra la semilla conocida en cada modo */
    uint8_t obj[16];
    entdebil::entropia(200, obj, 16, 0);
    debe(entdebil::buscar(obj, 16, 0, 256, &cancel, &hechas, 0) == 200, "buscar repetido encuentra 200");
    entdebil::entropia(77, obj, 16, 1);
    debe(entdebil::buscar(obj, 16, 0, 256, &cancel, &hechas, 1) == 77, "buscar secuencial encuentra 77");
    entdebil::entropia(123456789, obj, 16, 2);
    debe(entdebil::buscar(obj, 16, 123000000, 124000000, &cancel, &hechas, 2) == 123456789, "buscar entero LE encuentra 123456789");
    /* una entropía al azar no aparece como repetido */
    uint8_t ajena[16]; for (int i = 0; i < 16; i++) ajena[i] = (uint8_t)(i * 17 + 3);
    debe(entdebil::buscar(ajena, 16, 0, 256, &cancel, &hechas, 0) == -1, "entropia ajena no es repetida");

    printf("%s\n", fallos ? "HAY FALLOS" : "TODO CORRECTO");
    return fallos ? 1 : 0;
}
