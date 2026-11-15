/* Verifica php_mtrand.h contra PHP real: con semilla 1 el primer mt_rand() vale
 * 895547922 (modo MT19937) y 1244335972 (modo legacy MT_RAND_PHP). Y buscar()
 * encuentra una semilla conocida y rechaza una entropía ajena. */
#include "../../app/src/main/cpp/php_mtrand.h"
#include <cstdio>
#include <atomic>
static int fallos = 0;
static void debe(bool ok, const char *q) { if (!ok) { fallos++; printf("MAL  %s\n", q); } }
int main() {
    /* vectores exactos de PHP */
    { phpmt::Mt mt; mt.sembrar(1, phpmt::MT19937);
      uint32_t v = mt.mt_rand(); debe(v == 895547922u, "seed 1 MT19937 -> primer mt_rand()"); if (v != 895547922u) printf("  obtuve %u\n", v); }
    { phpmt::Mt mt; mt.sembrar(1, phpmt::PHP_LEGACY);
      uint32_t v = mt.mt_rand(); debe(v == 1244335972u, "seed 1 legacy -> primer mt_rand()"); if (v != 1244335972u) printf("  obtuve %u\n", v); }

    std::atomic<bool> cancel(false); std::atomic<uint64_t> hechas(0);
    /* buscar encuentra una semilla conocida (ambos modos) */
    uint8_t obj[16];
    phpmt::entropia(424242, obj, 16, phpmt::MT19937);
    debe(phpmt::buscar(obj, 16, 424000, 425000, &cancel, &hechas, phpmt::MT19937) == 424242, "buscar MT19937 encuentra 424242");
    phpmt::entropia(99999, obj, 16, phpmt::PHP_LEGACY);
    debe(phpmt::buscar(obj, 16, 99000, 100000, &cancel, &hechas, phpmt::PHP_LEGACY) == 99999, "buscar legacy encuentra 99999");
    /* una entropía al azar no aparece en un rango pequeño */
    uint8_t ajena[16]; for (int i = 0; i < 16; i++) ajena[i] = (uint8_t)(i * 37 + 11);
    debe(phpmt::buscar(ajena, 16, 0, 5000, &cancel, &hechas, phpmt::MT19937) == -1, "entropia ajena no aparece en [0,5000)");
    /* los dos modos dan secuencias distintas para la misma semilla */
    uint8_t a[16], b[16];
    phpmt::entropia(7, a, 16, phpmt::MT19937);
    phpmt::entropia(7, b, 16, phpmt::PHP_LEGACY);
    { bool dif = false; for (int i = 0; i < 16; i++) if (a[i] != b[i]) dif = true; debe(dif, "MT19937 y legacy difieren"); }

    printf("%s\n", fallos ? "HAY FALLOS" : "TODO CORRECTO");
    return fallos ? 1 : 0;
}
