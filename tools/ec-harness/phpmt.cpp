/* Verifica php_mtrand.h contra PHP real (PHP 8.3, mt_srand/mt_rand). Los vectores de
 * abajo salen de ejecutar PHP: 16 bytes con semilla 1 para cada (modo, patrón).
 *   mt19937 AND_FF : 18,117,198,...   mt19937 RANGE255: 37,235,140,...
 *   php     AND_FF : 100,3,198,...    php     RANGE255: 148,1,184,...   etc.
 * Y buscar() encuentra una semilla conocida y rechaza una entropía ajena. */
#include "../../app/src/main/cpp/php_mtrand.h"
#include <cstdio>
#include <atomic>
static int fallos = 0;
static void debe(bool ok, const char *q) { if (!ok) { fallos++; printf("MAL  %s\n", q); } }

/* Vectores autoritativos de PHP 8.3, semilla 1, 16 bytes. */
static const uint8_t V_MT_ANDFF[16] = {18,117,198,36,127,196,101,66,167,224,72,64,230,35,118,254};
static const uint8_t V_MT_R255 [16] = {37,235,140,72,255,137,203,133,79,192,144,129,204,71,237,252};
static const uint8_t V_MT_HEX  [16] = {91,200,249,181,240,1,199,220,105,36,229,36,188,172,222,36};
static const uint8_t V_PH_ANDFF[16] = {100,3,198,36,9,178,19,66,209,150,62,54,230,85,0,136};
static const uint8_t V_PH_R255 [16] = {148,1,184,238,254,222,179,255,219,194,233,155,47,157,166,85};
static const uint8_t V_PH_HEX  [16] = {144,190,253,191,220,233,41,165,110,125,101,88,56,236,247,94};

static void cmp(int modo, int patron, const uint8_t *esperado, const char *q) {
    uint8_t buf[16];
    phpmt::entropia(1, buf, 16, modo, patron);
    bool ok = true; int kbad = -1;
    for (int k = 0; k < 16; k++) if (buf[k] != esperado[k]) { ok = false; if (kbad < 0) kbad = k; }
    debe(ok, q);
    if (!ok) printf("  en byte %d: motor=%u php=%u\n", kbad, buf[kbad], esperado[kbad]);
}

int main() {
    /* vectores base de mt_rand() sin args */
    { phpmt::Mt mt; mt.sembrar(1, phpmt::MT19937);    debe(mt.mt_rand() == 895547922u,  "seed1 MT19937 primer mt_rand()"); }
    { phpmt::Mt mt; mt.sembrar(1, phpmt::PHP_LEGACY); debe(mt.mt_rand() == 1244335972u, "seed1 legacy primer mt_rand()"); }

    /* los 6 combos (modo x patron) byte-exactos contra PHP */
    cmp(phpmt::MT19937,    phpmt::AND_FF,   V_MT_ANDFF, "MT19937 AND_FF  == PHP");
    cmp(phpmt::MT19937,    phpmt::RANGE255, V_MT_R255,  "MT19937 RANGE255 == PHP");
    cmp(phpmt::MT19937,    phpmt::HEXNIB,   V_MT_HEX,   "MT19937 HEXNIB   == PHP");
    cmp(phpmt::PHP_LEGACY, phpmt::AND_FF,   V_PH_ANDFF, "legacy  AND_FF   == PHP");
    cmp(phpmt::PHP_LEGACY, phpmt::RANGE255, V_PH_R255,  "legacy  RANGE255 == PHP");
    cmp(phpmt::PHP_LEGACY, phpmt::HEXNIB,   V_PH_HEX,   "legacy  HEXNIB   == PHP");

    /* buscar encuentra una semilla conocida en cada patrón */
    std::atomic<bool> cancel(false); std::atomic<uint64_t> hechas(0);
    uint8_t obj[16];
    for (int patron = 0; patron <= 2; patron++) {
        phpmt::entropia(424242, obj, 16, phpmt::MT19937, patron);
        debe(phpmt::buscar(obj, 16, 424000, 425000, &cancel, &hechas, phpmt::MT19937, patron) == 424242, "buscar MT19937 encuentra 424242");
        phpmt::entropia(99999, obj, 16, phpmt::PHP_LEGACY, patron);
        debe(phpmt::buscar(obj, 16, 99000, 100000, &cancel, &hechas, phpmt::PHP_LEGACY, patron) == 99999, "buscar legacy encuentra 99999");
    }
    /* una entropía al azar no aparece en un rango pequeño */
    uint8_t ajena[16]; for (int i = 0; i < 16; i++) ajena[i] = (uint8_t)(i * 37 + 11);
    debe(phpmt::buscar(ajena, 16, 0, 5000, &cancel, &hechas, phpmt::MT19937, phpmt::AND_FF) == -1, "entropia ajena no aparece en [0,5000)");

    printf("%s\n", fallos ? "HAY FALLOS" : "TODO CORRECTO");
    return fallos ? 1 : 0;
}
