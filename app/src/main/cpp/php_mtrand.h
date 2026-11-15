#pragma once
/* Auditoría de carteras web hechas con el mt_rand() de PHP.
 *
 * Muchos generadores web antiguos de claves/paper-wallets usaban mt_rand() de PHP
 * en vez de un CSPRNG, sembrado con la hora o un valor pequeño. El Mersenne Twister
 * de PHP es el MT19937 estándar (mismo init 1812433253, mismo tempering), con DOS
 * particularidades propias:
 *   - mt_rand() de usuario devuelve el valor de 32 bits "templado" DESPLAZADO >> 1
 *     (un número de 31 bits).
 *   - Antes de PHP 7.1 había un bug en el "twist" (modo MT_RAND_PHP): usa el bit bajo
 *     de la palabra ACTUAL en vez de la SIGUIENTE. Genera una secuencia distinta, que
 *     el audit de Milk Sad (MT19937 correcto) NO cubre.
 *
 * Modelos de bytes cubiertos (varían según el sitio; ver enum Patron):
 *   AND_FF   chr(mt_rand() & 0xFF)  ==  mt_rand() % 256
 *   RANGE255 chr(mt_rand(0,255))    (escalado dependiente de la versión)
 *   HEXNIB   dechex con dos mt_rand(0,15) por byte
 * Igual que milksad.h: puro, sin estado global ni JNI, para probar en el escritorio
 * (tools/ec-harness/phpmt.cpp, validado bit-exacto contra PHP 8.3 real).
 *
 * Verificado contra PHP real: con semilla 1, el primer mt_rand() vale 895547922 en
 * modo MT19937 y 1244335972 en modo legacy MT_RAND_PHP. */
#include <cstdint>
#include <atomic>

namespace phpmt {

enum { MT19937 = 0, PHP_LEGACY = 1 };

struct Mt {
    uint32_t s[624];
    int idx;
    int modo;   /* 0 = MT19937 (PHP >= 7.1), 1 = MT_RAND_PHP legacy (< 7.1) */

    static inline uint32_t hiBit(uint32_t u)  { return u & 0x80000000u; }
    static inline uint32_t loBit(uint32_t u)  { return u & 0x00000001u; }
    static inline uint32_t loBits(uint32_t u) { return u & 0x7FFFFFFFu; }
    static inline uint32_t mixBits(uint32_t u, uint32_t v) { return hiBit(u) | loBits(v); }

    inline uint32_t twist(uint32_t m, uint32_t u, uint32_t v) {
        /* MT19937 usa loBit(v) (palabra siguiente); el bug legacy usa loBit(u) (actual). */
        uint32_t sel = (modo == MT19937) ? loBit(v) : loBit(u);
        return m ^ (mixBits(u, v) >> 1) ^ ((uint32_t)(-(int32_t)sel) & 0x9908b0dfu);
    }

    void sembrar(uint32_t seed, int m) {
        modo = m;
        s[0] = seed;
        for (int i = 1; i < 624; i++) {
            uint32_t prev = s[i - 1];
            s[i] = (uint32_t)(1812433253u * (prev ^ (prev >> 30)) + (uint32_t)i);
        }
        idx = 624;   /* fuerza el reload en el primer next() */
    }

    void reload() {
        const int N = 624, M = 397;
        uint32_t *p = s;
        for (int i = N - M; i--; ++p) *p = twist(p[M],     p[0], p[1]);
        for (int i = M;    --i; ++p)  *p = twist(p[M - N], p[0], p[1]);
        *p = twist(p[M - N], p[0], s[0]);
        idx = 0;
    }

    uint32_t next() {   /* palabra de 32 bits templada (como el 'generate' interno) */
        if (idx >= 624) reload();
        uint32_t y = s[idx++];
        y ^= (y >> 11);
        y ^= (y << 7)  & 0x9d2c5680u;
        y ^= (y << 15) & 0xefc60000u;
        y ^= (y >> 18);
        return y;
    }

    uint32_t mt_rand() { return next() >> 1; }   /* mt_rand() de usuario: 31 bits */

    /* mt_rand(0, M) exacto de PHP (M < 2^31). En modo MT19937 (>=7.1) usa la ruta
     * php_random_range32 (4 bytes de generate(), máscara si (M+1) es potencia de 2,
     * si no rechazo + módulo). En modo legacy usa el "bad scaling": floor((M+1)*r/2^31)
     * con r = tempered>>1. Verificado contra PHP real. */
    uint32_t rand_rango(uint32_t M) {
        if (modo == MT19937) {
            uint32_t umax = M + 1u;              /* inclusivo */
            uint32_t result = next();            /* tempered de 32 bits (generate) */
            if ((umax & (umax - 1u)) == 0u) return result & (umax - 1u);
            uint32_t limit = 0xFFFFFFFFu - (0xFFFFFFFFu % umax) - 1u;
            int intentos = 0;
            while (result > limit) { result = next(); if (++intentos > 1000000) break; }
            return result % umax;
        } else {
            uint32_t r = next() >> 1;            /* 31 bits */
            return (uint32_t)(((uint64_t)(M + 1u) * (uint64_t)r) >> 31);
        }
    }
};

/* Patrones de derivación de bytes observados en generadores web PHP. */
enum Patron {
    AND_FF   = 0,   /* chr(mt_rand() & 0xFF)  ==  mt_rand() % 256  (byte bajo del 31-bit) */
    RANGE255 = 1,   /* chr(mt_rand(0,255))    (escalado dependiente del modo) */
    HEXNIB   = 2    /* dechex: dos mt_rand(0,15) por byte (hi<<4 | lo) */
};

/* n bytes de entropía con el modelo (modo MT, patron de bytes). */
inline void entropia(uint32_t seed, uint8_t *out, int n, int modo, int patron) {
    Mt mt; mt.sembrar(seed, modo);
    switch (patron) {
        case RANGE255:
            for (int k = 0; k < n; k++) out[k] = (uint8_t)mt.rand_rango(255);
            break;
        case HEXNIB:
            for (int k = 0; k < n; k++) { uint32_t hi = mt.rand_rango(15), lo = mt.rand_rango(15); out[k] = (uint8_t)((hi << 4) | lo); }
            break;
        case AND_FF:
        default:
            for (int k = 0; k < n; k++) out[k] = (uint8_t)(mt.mt_rand() & 0xFFu);
            break;
    }
}

/* Busca en [ini,fin) una semilla que reproduzca la entropía objetivo con el modelo
 * (modo, patron). Devuelve la semilla (>=0), -1 si no aparece, -2 si se canceló. */
inline int64_t buscar(const uint8_t *objetivo, int n, uint64_t ini, uint64_t fin,
                      std::atomic<bool> *cancel, std::atomic<uint64_t> *hechas, int modo, int patron) {
    if (n <= 0 || n > 64) return -1;
    uint8_t buf[64];
    for (uint64_t s = ini; s < fin; s++) {
        if ((s & 0xFFFFu) == 0 && cancel && cancel->load()) return -2;
        entropia((uint32_t)s, buf, n, modo, patron);
        bool ok = true;
        for (int k = 0; k < n; k++) if (buf[k] != objetivo[k]) { ok = false; break; }
        if (hechas) hechas->fetch_add(1);
        if (ok) return (int64_t)s;
    }
    return -1;
}

} // namespace phpmt
