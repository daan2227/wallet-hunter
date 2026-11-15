#pragma once
/* Auditoría de ENTROPÍA DEGENERADA.
 *
 * Algunas carteras (o pruebas que se quedaron en producción) no usan un CSPRNG:
 * toman una entropía trivial. Si TU semilla sale de una de estas, cualquiera la
 * reconstruye probando un puñado de valores. Modelos cubiertos:
 *
 *   0  REPETIDO   todos los bytes iguales: entropía = [b,b,b,…], b en 0..255.
 *                 (b=0 es la famosa "abandon abandon … about".)
 *   1  SECUENCIAL entropía[k] = (inicio + k) mod 256: 00 01 02 03…, o desde otro
 *                 byte de salida.
 *   2  ENTERO_LE  la clave/entropía ES un número pequeño, en little-endian
 *                 (privkey = 1, 2, 3 … barridas sin parar).
 *   3  ENTERO_BE  igual pero big-endian.
 *
 * Igual que milksad.h: sin estado global ni JNI, puro, para probar en el
 * escritorio (tools/ec-harness/entdebil.cpp) y que el motor solo lo envuelva.
 * La comparación es contra la entropía OBJETIVO (los 16/32 bytes de la semilla
 * del usuario); si un modelo la reproduce dentro de [ini,fin), es débil. */
#include <cstdint>
#include <atomic>

namespace entdebil {

/* La entropía que da el modelo para el parámetro [sem]. Para los enteros, los
 * bytes que no caben en 64 bits salen a cero (sem es uint64). */
inline void entropia(uint64_t sem, uint8_t *out, int n, int modo) {
    switch (modo) {
        case 0: for (int k = 0; k < n; k++) out[k] = (uint8_t)(sem & 0xFF); break;
        case 1: for (int k = 0; k < n; k++) out[k] = (uint8_t)((sem + (uint64_t)k) & 0xFF); break;
        case 2: for (int k = 0; k < n; k++) { int sh = 8 * k;        out[k] = (sh < 64) ? (uint8_t)((sem >> sh) & 0xFF) : 0; } break;
        case 3: for (int k = 0; k < n; k++) { int sh = 8 * (n-1-k);  out[k] = (sh >= 0 && sh < 64) ? (uint8_t)((sem >> sh) & 0xFF) : 0; } break;
        default: for (int k = 0; k < n; k++) out[k] = 0; break;
    }
}

/* Busca en [ini,fin) una [sem] del modelo cuya entropía sea exactamente la del
 * objetivo. Devuelve la sem (>=0), -1 si no aparece, -2 si se canceló. */
inline int64_t buscar(const uint8_t *objetivo, int n, uint64_t ini, uint64_t fin,
                      std::atomic<bool> *cancel, std::atomic<uint64_t> *hechas, int modo) {
    if (n <= 0 || n > 64) return -1;
    uint8_t buf[64];
    for (uint64_t s = ini; s < fin; s++) {
        if ((s & 0xFFFFu) == 0 && cancel && cancel->load()) return -2;
        entropia(s, buf, n, modo);
        bool ok = true;
        for (int k = 0; k < n; k++) if (buf[k] != objetivo[k]) { ok = false; break; }
        if (hechas) hechas->fetch_add(1);
        if (ok) return (int64_t)s;
    }
    return -1;
}

} // namespace entdebil
