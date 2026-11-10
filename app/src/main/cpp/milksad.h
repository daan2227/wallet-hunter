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
 * Variante Trust Wallet (extensión de navegador, CVE-2023-31290): el build WASM de wallet-core usaba
 * std::mt19937 sembrado con 32 bits y sacaba cada byte con `rng() & 0xFF` (los 8 bits BAJOS), no los
 * altos como bx. Fuente: el commit del arreglo d3c2eee de trustwallet/wallet-core (wasm/src/Random.cpp,
 * quitó `std::generate_n(buf,len,[&]{return rng()&0x000000ff;})`). Mismo MT, sólo cambia el byte, así que
 * se cubre con un [modo]: 0 = bx (>>24), 1 = Trust Wallet (&0xFF). Trust Wallet sólo hacía frases de 12
 * palabras (16 bytes) y la semilla no es una hora conocida: se barre todo 2^32.
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
inline uint8_t byte_de(Mt &g, int modo) { uint32_t w = g.next(); return (uint8_t)(modo == 1 ? (w & 0xFF) : (w >> 24)); }

inline void entropia(uint32_t sem, uint8_t *out, int n, int modo = 0) { Mt g; g.seed(sem); for (int k = 0; k < n; k++) out[k] = byte_de(g, modo); }

/*
 * Busca en [ini, fin) una semilla de 32 bits cuya entropía de [n] bytes sea [objetivo]. Devuelve la semilla
 * (como int64) o -1 si no la encuentra. [cancel] corta, [hechas] lleva la cuenta para la pantalla.
 */
inline int64_t buscar(const uint8_t *objetivo, int n, uint64_t ini, uint64_t fin,
                      std::atomic<bool> *cancel, std::atomic<uint64_t> *hechas, int modo = 0) {
    if (n < 16 || n > 32) return -1;
    uint8_t buf[32];
    uint64_t lote = 0;
    for (uint64_t s = ini; s < fin; s++) {
        if ((++lote & 0x3FFFF) == 0) { if (hechas) hechas->fetch_add(0x40000); if (cancel && cancel->load()) return -1; }
        Mt g; g.seed((uint32_t)s);
        bool ok = true;
        for (int k = 0; k < n; k++) { buf[k] = byte_de(g, modo); if (buf[k] != objetivo[k]) { ok = false; break; } }
        if (ok) { if (hechas) hechas->fetch_add(lote & 0x3FFFF); return (int64_t)s; }
    }
    if (hechas) hechas->fetch_add(lote & 0x3FFFF);
    return -1;
}

/*
 * Trust Wallet iOS 2018 (CVE-2024-23660). El app de iOS usó funciones de trezor-crypto pensadas sólo para
 * pruebas: random32() se sembraba una vez con srand((unsigned)time(NULL)) y la PRNG es MINSTD_RAND0 crudo
 * (state = 16807·state mod 2^31-1, SIN la guarda 0->1, por eso la semilla 0 da la clave todo-ceros). En el
 * commit, random32() empaqueta cuatro rand() y random_buffer() saca un byte por rand(), así que cada byte de
 * entropía es un rand() enmascarado: `rand() % 0xFF` en la variante A (trezor-crypto 2e528be) o `rand() & 0xFF`
 * en la B (009850). Verificado contra el fuente real de trezor-crypto (git). La semilla es una hora Unix, así
 * que se barre una ventana de fechas en vez de 2^32. Sólo frases de 12 palabras (16 bytes) en la práctica.
 */
struct Minstd { uint32_t s; void seed(uint32_t x) { s = x; } uint32_t next() { s = (uint32_t)(((uint64_t)s * 16807u) % 2147483647u); return s; } };

/* Un byte de entropía: una llamada a rand(), `% 0xFF` (variante A, por defecto) o `& 0xFF` (variante B, var==1). */
inline uint8_t byte_ios(Minstd &g, int var) { uint32_t r = g.next(); return (uint8_t)(var == 1 ? (r & 0xFF) : (r % 0xFF)); }

inline void entropia_ios(uint32_t t, uint8_t *out, int n, int var) { Minstd g; g.seed(t); for (int k = 0; k < n; k++) out[k] = byte_ios(g, var); }

/* Igual que buscar(), pero con la PRNG de Trust iOS; [ini,fin) es una ventana de horas Unix. */
inline int64_t buscar_ios(const uint8_t *objetivo, int n, uint64_t ini, uint64_t fin,
                          std::atomic<bool> *cancel, std::atomic<uint64_t> *hechas, int var) {
    if (n < 16 || n > 32) return -1;
    uint8_t buf[32];
    uint64_t lote = 0;
    for (uint64_t s = ini; s < fin; s++) {
        if ((++lote & 0x3FFFF) == 0) { if (hechas) hechas->fetch_add(0x40000); if (cancel && cancel->load()) return -1; }
        Minstd g; g.seed((uint32_t)s);
        bool ok = true;
        for (int k = 0; k < n; k++) { buf[k] = byte_ios(g, var); if (buf[k] != objetivo[k]) { ok = false; break; } }
        if (ok) { if (hechas) hechas->fetch_add(lote & 0x3FFFF); return (int64_t)s; }
    }
    if (hechas) hechas->fetch_add(lote & 0x3FFFF);
    return -1;
}

} // namespace milksad
