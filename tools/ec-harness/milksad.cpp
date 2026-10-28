/* Verifica milksad.h contra la implementación REAL de bx (libstdc++: mt19937 + uniform_int_distribution<uint16_t>(0,255)):
   - el byte es mt()>>24 (10 semillas, 2M cada una),
   - seed 0 / 16 bytes da la entropía de la frase "milk sad …",
   - buscar() encuentra la semilla conocida y rechaza una entropía ajena. */
#include "milksad.h"
#include <random>
#include <cstdio>
#include <cstring>
#include <atomic>
static int fallos = 0;
static void debe(bool ok, const char *q) { if (!ok) { fallos++; printf("MAL  %s\n", q); } }
int main() {
    for (uint32_t s : {0u,1u,42u,1000u,123456789u,0xFFFFFFFFu,0x80000000u,777u,2024u,1700000000u}) {
        std::mt19937 tw(s); std::uniform_int_distribution<uint16_t> d(0,255); milksad::Mt g; g.seed(s);
        bool ok = true; for (long i=0;i<2000000;i++) if ((uint8_t)d(tw) != (uint8_t)(g.next()>>24)) { ok=false; break; }
        debe(ok, "byte == bx (libstdc++)");
    }
    uint8_t ent[16]; milksad::entropia(0, ent, 16);
    uint8_t esperado[16] = {0x8c,0x97,0xb7,0xd8,0x9a,0xdb,0x8b,0xd8,0x6c,0x9f,0xa5,0x62,0x70,0x4c,0xe4,0x0e};
    debe(memcmp(ent, esperado, 16) == 0, "seed 0 / 128 bits == entropia de 'milk sad'");

    std::atomic<bool> cancel(false); std::atomic<uint64_t> hechas(0);
    debe(milksad::buscar(esperado, 16, 0, 1000, &cancel, &hechas) == 0, "buscar encuentra la semilla 0");
    uint8_t objetivo[24]; milksad::entropia(123456, objetivo, 24);
    debe(milksad::buscar(objetivo, 24, 123000, 124000, &cancel, &hechas) == 123456, "buscar encuentra una semilla concreta (24B)");
    uint8_t ajena[16]; for (int i=0;i<16;i++) ajena[i] = (uint8_t)(i*17+1);
    debe(milksad::buscar(ajena, 16, 0, 2000000, &cancel, &hechas) == -1, "una entropia ajena no aparece");
    printf(fallos ? "\nHAY FALLOS: %d\n" : "\nTodas las pruebas correctas.\n", fallos);
    return fallos ? 1 : 0;
}
