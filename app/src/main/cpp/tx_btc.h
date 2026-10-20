#pragma once
/* Un bloque entero: transacciones (txid, entradas, salidas) y raíz de Merkle.
 * El cliente ligero baja un bloque sólo cuando su filtro coincide, y antes de fiarse
 * de él comprueba que su raíz de Merkle es la de la cabecera (que ya tiene prueba de
 * trabajo) y que no está "mutado" (CVE-2012-2459). */
#include <array>
#include "bloques_btc.h"
#include "cabecera_btc.h"

namespace btc {

struct EntradaTx { std::array<uint8_t, 32> txid; uint32_t vout; };
struct SalidaTx { uint64_t valor; std::vector<uint8_t> spk; };
struct TxBloque {
    std::array<uint8_t, 32> txid;      /* orden interno */
    std::vector<EntradaTx> entradas;
    std::vector<SalidaTx> salidas;
    bool coinbase = false;
};

/* Raíz de Merkle (orden interno). `mutada` se pone si algún nivel tiene dos hashes
 * contiguos iguales en una posición par: dos bloques distintos darían la misma raíz. */
static std::array<uint8_t, 32> raiz_merkle(std::vector<std::array<uint8_t, 32>> nivel, bool &mutada) {
    mutada = false;
    if (nivel.empty()) { std::array<uint8_t, 32> z; z.fill(0); return z; }
    while (nivel.size() > 1) {
        for (size_t i = 0; i + 1 < nivel.size(); i += 2) if (nivel[i] == nivel[i + 1]) mutada = true;
        if (nivel.size() & 1) nivel.push_back(nivel.back());
        std::vector<std::array<uint8_t, 32>> sig;
        for (size_t i = 0; i < nivel.size(); i += 2) {
            uint8_t b[64]; memcpy(b, nivel[i].data(), 32); memcpy(b + 32, nivel[i + 1].data(), 32);
            std::array<uint8_t, 32> h; dsha(b, 64, h.data()); sig.push_back(h);
        }
        nivel.swap(sig);
    }
    return nivel[0];
}

/* Lee las transacciones de un bloque. Lanza si está mal formado, si no tiene
 * exactamente una coinbase al principio, o si sobran bytes. */
static std::vector<TxBloque> leer_bloque(const std::vector<uint8_t> &bloque) {
    if (bloque.size() < 81) throw std::runtime_error("bloque corto");
    Lector r{bloque.data(), bloque.size()};
    r.saltar(80);
    uint64_t ntx = r.varint();
    if (ntx == 0 || ntx > (bloque.size() - 81) / 10 + 1) throw std::runtime_error("número de transacciones imposible");
    std::vector<TxBloque> txs;
    for (uint64_t t = 0; t < ntx; t++) {
        TxBloque tx;
        size_t ini = r.pos;
        r.u32();
        bool segwit = false;
        if (r.pos + 2 <= r.n && r.p[r.pos] == 0x00 && r.p[r.pos + 1] == 0x01) { segwit = true; r.pos += 2; }
        size_t ini_vin = r.pos;
        uint64_t nin = r.varint();
        if (nin == 0) throw std::runtime_error("transacción sin entradas");
        if (nin > r.n - r.pos) throw std::runtime_error("demasiadas entradas");
        for (uint64_t i = 0; i < nin; i++) {
            EntradaTx e; r.need(36); memcpy(e.txid.data(), r.p + r.pos, 32); r.pos += 32; e.vout = r.u32();
            r.saltar(r.varint()); r.u32();
            tx.entradas.push_back(e);
        }
        uint64_t nout = r.varint();
        if (nout > r.n - r.pos) throw std::runtime_error("demasiadas salidas");
        for (uint64_t i = 0; i < nout; i++) {
            SalidaTx s; s.valor = r.u64(); s.spk = r.bytes(r.varint());
            tx.salidas.push_back(s);
        }
        size_t fin_vout = r.pos;
        if (segwit) for (uint64_t i = 0; i < nin; i++) {
            uint64_t items = r.varint();
            if (items > r.n - r.pos) throw std::runtime_error("testigo imposible");
            for (uint64_t k = 0; k < items; k++) r.saltar(r.varint());
        }
        r.need(4);
        size_t lock = r.pos; r.pos += 4;
        /* txid: versión + entradas/salidas + locktime, sin marcador ni testigo. */
        std::vector<uint8_t> sin;
        sin.insert(sin.end(), r.p + ini, r.p + ini + 4);
        sin.insert(sin.end(), r.p + ini_vin, r.p + fin_vout);
        sin.insert(sin.end(), r.p + lock, r.p + lock + 4);
        dsha(sin.data(), sin.size(), tx.txid.data());
        static const uint8_t nulo[32] = {0};
        tx.coinbase = tx.entradas.size() == 1 && memcmp(tx.entradas[0].txid.data(), nulo, 32) == 0 && tx.entradas[0].vout == 0xffffffffu;
        txs.push_back(tx);
        (void)ini;
    }
    if (r.pos != r.n) throw std::runtime_error("bytes sobrantes tras el bloque");
    if (!txs[0].coinbase) throw std::runtime_error("la primera transacción no es coinbase");
    for (size_t i = 1; i < txs.size(); i++) if (txs[i].coinbase) throw std::runtime_error("coinbase repetida");
    return txs;
}

/* ¿La raíz de Merkle de las transacciones es la de la cabecera? Y no mutada. */
static bool merkle_valido(const std::vector<uint8_t> &bloque, const std::vector<TxBloque> &txs) {
    std::vector<std::array<uint8_t, 32>> hs;
    for (auto &t : txs) hs.push_back(t.txid);
    bool mutada;
    auto raiz = raiz_merkle(hs, mutada);
    return !mutada && memcmp(raiz.data(), bloque.data() + 36, 32) == 0;
}

}  // namespace btc
