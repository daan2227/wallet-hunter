#pragma once
/*
 * Auditoría "Milk Sad" (CVE-2023-39910): el comando `bx seed` de libbitcoin generaba la entropía con un
 * Mersenne Twister sembrado con sólo 32 bits (a menudo la hora), así que todo el espacio de claves posibles
 * es 2^32. Quien usara una cartera creada así puede ver su clave reconstruida por cualquiera.
 *
 * Esto NO ataca a nadie: comprueba si TU PROPIA entropía (los 16/24/32 bytes de tu frase) pudo salir de ese
 * generador, barriendo las semillas de 32 bits. Cada byte de entropía es los 8 bits altos de una palabra del
 * MT, verificado contra la implementación real de libbitcoin (libstdc++): seed 0 / 128 bits da la frase que
 * empieza por "milk sad" (de ahí el nombre). Ver tools/ec-harness/milksad.cpp.
 *
 * Sin estado global ni JNI: se prueba en el escritorio y el motor sólo lo envuelve.
 */
#include <cstdint>
#include <cstring>
#include <atomic>

namespace milksad {

/* MT19937 estándar (igual en toda implementación; sólo la "distribución" difería). */
struct Mt {
    uint32_t mt[624]; int i;
    void seed(uint32_t s) { mt[0] = s; for (int k = 1; k < 624; k++) mt[k] = 1812433253u * (mt[k-1] ^ (mt[k-1] >> 30)) + (uint32_t)k; i = 624; }
    uint32_t next() {
        if (i >= 624) {
            for (int k = 0; k < 624; k++) {
                uint32_t y = (mt[k] & 0x80000000u) | (mt[(k+1) % 624] & 0x7fffffffu);
                mt[k] = mt[(k+397) % 624] ^ (y >> 1); if (y & 1) mt[k] ^= 2567483615u;
            }
            i = 0;
        }
        uint32_t y = mt[i++]; y ^= y >> 11; y ^= (y << 7) & 2636928640u; y ^= (y << 15) & 4022730752u; y ^= y >> 18; return y;
    }
};

/* Un byte de entropía de bx = los 8 bits altos de la siguiente palabra del MT (== uniform_int_distribution<uint16_t>(0,255) de libstdc++). */
inline void entropia(uint32_t sem, uint8_t *out, int n) { Mt g; g.seed(sem); for (int k = 0; k < n; k++) out[k] = (uint8_t)(g.next() >> 24); }

/*
 * Busca en [ini, fin) una semilla de 32 bits cuya entropía de [n] bytes sea [objetivo]. Devuelve la semilla
 * (como int64) o -1 si no la encuentra. [cancel] corta, [hechas] lleva la cuenta para la pantalla.
 */
inline int64_t buscar(const uint8_t *objetivo, int n, uint64_t ini, uint64_t fin,
                      std::atomic<bool> *cancel, std::atomic<uint64_t> *hechas) {
    if (n < 16 || n > 32) return -1;
    uint8_t buf[32];
    uint64_t lote = 0;
    for (uint64_t s = ini; s < fin; s++) {
        if ((++lote & 0x3FFFF) == 0) { if (hechas) hechas->fetch_add(0x40000); if (cancel && cancel->load()) return -1; }
        Mt g; g.seed((uint32_t)s);
        bool ok = true;
        for (int k = 0; k < n; k++) { buf[k] = (uint8_t)(g.next() >> 24); if (buf[k] != objetivo[k]) { ok = false; break; } }
        if (ok) { if (hechas) hechas->fetch_add(lote & 0x3FFFF); return (int64_t)s; }
    }
    if (hechas) hechas->fetch_add(lote & 0x3FFFF);
    return -1;
}

} // namespace milksad
