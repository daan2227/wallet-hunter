#pragma once
/* Lectura de un bloque serializado, lo justo para el filtro de BIP158: los
 * scripts de salida de cada transacción. Ningún dato sale de aquí hacia la red. */
#include <stdint.h>
#include <string.h>
#include <stdexcept>
#include <vector>

namespace btc {

struct Lector {
    const uint8_t *p; size_t n; size_t pos = 0;
    void need(size_t k) const { if (pos + k > n) throw std::runtime_error("bloque cortado"); }
    uint8_t u8() { need(1); return p[pos++]; }
    uint32_t u32() { need(4); uint32_t v = p[pos] | (p[pos+1] << 8) | (p[pos+2] << 16) | ((uint32_t)p[pos+3] << 24); pos += 4; return v; }
    uint64_t u64() { need(8); uint64_t v = 0; for (int i = 7; i >= 0; i--) v = (v << 8) | p[pos + i]; pos += 8; return v; }
    uint64_t varint() {
        uint8_t f = u8();
        if (f < 0xfd) return f;
        int k = f == 0xfd ? 2 : f == 0xfe ? 4 : 8;
        uint64_t v = 0; need(k);
        for (int i = k - 1; i >= 0; i--) v = (v << 8) | p[pos + i];
        pos += k;
        return v;
    }
    std::vector<uint8_t> bytes(uint64_t k) {
        if (k > n - pos) throw std::runtime_error("bloque cortado");
        std::vector<uint8_t> v(p + pos, p + pos + k); pos += (size_t)k; return v;
    }
    void saltar(uint64_t k) { if (k > n - pos) throw std::runtime_error("bloque cortado"); pos += (size_t)k; }
};

/* Scripts de salida de todas las transacciones, sin OP_RETURN y sin vacíos. */
static std::vector<std::vector<uint8_t>> scripts_de_salida(const std::vector<uint8_t> &bloque) {
    Lector r{bloque.data(), bloque.size()};
    std::vector<std::vector<uint8_t>> out;
    r.saltar(80);                                  /* cabecera */
    uint64_t ntx = r.varint();
    for (uint64_t t = 0; t < ntx; t++) {
        r.u32();                                   /* versión */
        bool segwit = false;
        if (r.pos + 2 <= r.n && r.p[r.pos] == 0x00 && r.p[r.pos + 1] == 0x01) { segwit = true; r.pos += 2; }
        uint64_t nin = r.varint();
        for (uint64_t i = 0; i < nin; i++) {
            r.saltar(32); r.u32();                 /* outpoint */
            r.saltar(r.varint());                  /* scriptSig */
            r.u32();                               /* secuencia */
        }
        uint64_t nout = r.varint();
        for (uint64_t i = 0; i < nout; i++) {
            r.u64();                               /* importe */
            uint64_t l = r.varint();
            auto spk = r.bytes(l);
            if (!spk.empty() && spk[0] != 0x6a) out.push_back(spk);   /* 0x6a = OP_RETURN */
        }
        if (segwit) for (uint64_t i = 0; i < nin; i++) {
            uint64_t items = r.varint();
            for (uint64_t k = 0; k < items; k++) r.saltar(r.varint());
        }
        r.u32();                                   /* locktime */
    }
    return out;
}

}  // namespace btc
