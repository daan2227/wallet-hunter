#pragma once
/* Cadena de cabeceras: encadenado, dificultad y tiempo. Sin red.
 * Parte de un punto de control (o del génesis) y acepta cabeceras de una en una;
 * cada una tiene que enlazar con la anterior, llevar los nBits que toca, cumplir
 * su prueba de trabajo y un tiempo razonable. Todo igual que Bitcoin Core
 * (pow.cpp y ContextualCheckBlockHeader). */
#include <algorithm>
#include <map>
#include "cabecera_btc.h"

namespace btc {

struct ParamsRed {
    uint32_t limite_bits;   /* nBits del objetivo máximo (dificultad mínima) */
    uint32_t intervalo;     /* bloques entre ajustes (2016) */
    uint32_t objetivo_s;    /* segundos que debería durar un periodo (1209600) */
    bool reajusta;          /* false: sin ajuste (regtest) */
};

static ParamsRed red_principal() { return {0x1d00ffff, 2016, 1209600, true}; }

/* Núcleo de CalculateNextWorkRequired: nuevo nBits a partir de los de la última
 * cabecera del periodo y del tiempo que tardó el periodo. */
static uint32_t siguiente_bits(const ParamsRed &p, uint32_t bits_ultimo, uint32_t t_primero, uint32_t t_ultimo) {
    int64_t real = (int64_t)t_ultimo - (int64_t)t_primero;
    int64_t minimo = p.objetivo_s / 4, maximo = (int64_t)p.objetivo_s * 4;
    if (real < minimo) real = minimo;
    if (real > maximo) real = maximo;
    uint8_t t[32], lim[32];
    if (!bits_a_objetivo(bits_ultimo, t) || !bits_a_objetivo(p.limite_bits, lim)) return p.limite_bits;
    /* t * real / objetivo_s con 36 bytes de holgura (real < 2^25, así que cabe). */
    uint8_t w[36] = {0};
    uint64_t acarreo = 0;
    for (int i = 35; i >= 0; i--) {
        uint64_t v = (i >= 4 ? (uint64_t)t[i - 4] : 0) * (uint64_t)real + acarreo;
        w[i] = (uint8_t)v; acarreo = v >> 8;
    }
    uint64_t resto = 0;
    for (int i = 0; i < 36; i++) {
        uint64_t v = (resto << 8) | w[i];
        w[i] = (uint8_t)(v / p.objetivo_s); resto = v % p.objetivo_s;
    }
    bool pasa = false;
    for (int i = 0; i < 4; i++) if (w[i]) pasa = true;
    if (!pasa && memcmp(w + 4, lim, 32) > 0) pasa = true;
    if (pasa) return p.limite_bits;
    /* Compacto (GetCompact). */
    int tam = 32, ini = 0;
    while (ini < 32 && w[4 + ini] == 0) { ini++; tam--; }
    uint32_t c;
    if (tam <= 3) {
        c = 0;
        for (int i = 0; i < tam; i++) c = (c << 8) | w[4 + ini + i];
        c <<= 8 * (3 - tam);
    } else {
        c = ((uint32_t)w[4 + ini] << 16) | ((uint32_t)w[5 + ini] << 8) | w[6 + ini];
    }
    if (c & 0x00800000) { c >>= 8; tam++; }
    return c | ((uint32_t)tam << 24);
}

enum class ErrCabecera { Ok, NoEncadena, BitsMalos, ObjetivoAlto, SinTrabajo, TiempoBajo, TiempoAlto, Checkpoint };

/* Estado necesario para seguir una cadena sin guardar todas las cabeceras. */
struct PuntoDeControl {
    uint32_t altura; uint8_t hash[32];
    uint32_t bits, tiempo;
    std::vector<uint32_t> ultimos;   /* tiempos de los últimos (hasta 11) bloques, el más reciente al final */
    uint32_t t_inicio_periodo;       /* tiempo del bloque de altura múltiplo del intervalo */
};

class Cadena {
public:
    Cadena(const ParamsRed &p, const PuntoDeControl &pc) : p_(p), pc_(pc) {}

    /* Arranca desde el génesis (80 bytes). */
    static Cadena desde_genesis(const ParamsRed &p, const uint8_t *cab80) {
        Cabecera c = parsear_cabecera(cab80, 80);
        PuntoDeControl pc; pc.altura = 0; hash_cabecera(cab80, pc.hash);
        pc.bits = c.bits; pc.tiempo = c.tiempo; pc.ultimos = {c.tiempo}; pc.t_inicio_periodo = c.tiempo;
        return Cadena(p, pc);
    }

    /* Hash que tiene que tener la cabecera de esa altura: protege contra una cadena
     * falsa aunque tenga trabajo válido. */
    void exigir_hash(uint32_t altura, const uint8_t hash[32]) {
        std::vector<uint8_t> h(hash, hash + 32);
        exigidos_[altura] = h;
    }

    ErrCabecera anadir(const uint8_t *cab80, uint32_t ahora) {
        Cabecera c = parsear_cabecera(cab80, 80);
        if (memcmp(c.prev, pc_.hash, 32) != 0) return ErrCabecera::NoEncadena;
        uint32_t altura = pc_.altura + 1;
        uint32_t esperados = pc_.bits;
        if (p_.reajusta && altura % p_.intervalo == 0)
            esperados = siguiente_bits(p_, pc_.bits, pc_.t_inicio_periodo, pc_.tiempo);
        if (c.bits != esperados) return ErrCabecera::BitsMalos;
        uint8_t t[32], lim[32];
        if (!bits_a_objetivo(c.bits, t) || !bits_a_objetivo(p_.limite_bits, lim)) return ErrCabecera::BitsMalos;
        if (memcmp(t, lim, 32) > 0) return ErrCabecera::ObjetivoAlto;
        if (!prueba_de_trabajo(cab80)) return ErrCabecera::SinTrabajo;
        std::vector<uint32_t> o = pc_.ultimos;
        std::sort(o.begin(), o.end());
        if (c.tiempo <= o[o.size() / 2]) return ErrCabecera::TiempoBajo;
        if ((uint64_t)c.tiempo > (uint64_t)ahora + 7200) return ErrCabecera::TiempoAlto;
        uint8_t h[32]; hash_cabecera(cab80, h);
        auto it = exigidos_.find(altura);
        if (it != exigidos_.end() && memcmp(it->second.data(), h, 32) != 0) return ErrCabecera::Checkpoint;
        pc_.altura = altura; memcpy(pc_.hash, h, 32);
        pc_.bits = c.bits; pc_.tiempo = c.tiempo;
        pc_.ultimos.push_back(c.tiempo);
        if (pc_.ultimos.size() > 11) pc_.ultimos.erase(pc_.ultimos.begin());
        if (p_.reajusta && altura % p_.intervalo == 0) pc_.t_inicio_periodo = c.tiempo;
        return ErrCabecera::Ok;
    }

    const PuntoDeControl &punta() const { return pc_; }

private:
    ParamsRed p_;
    PuntoDeControl pc_;
    std::map<uint32_t, std::vector<uint8_t>> exigidos_;
};

}  // namespace btc
