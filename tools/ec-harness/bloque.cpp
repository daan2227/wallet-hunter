/* Lectura de bloques y raíz de Merkle. Vector real: la transacción coinbase del
 * génesis, cuyo txid es la raíz de Merkle de ese bloque. Y los rechazos: Merkle
 * distinto, bloque mutado por duplicado (CVE-2012-2459), cortes, bytes sobrantes,
 * sin coinbase, coinbase repetida, contadores imposibles y ruido. */
#include "tx_btc.h"
#include <stdio.h>
#include <string>

using namespace btc;

static int fallos = 0;
static void ok(bool c, const char *nombre) { printf("%s  %s\n", c ? "OK  " : "MAL ", nombre); if (!c) fallos++; }
static std::vector<uint8_t> desde_hex(const std::string &h) {
    std::vector<uint8_t> v(h.size() / 2);
    for (size_t i = 0; i < v.size(); i++) v[i] = (uint8_t)std::stoi(h.substr(2 * i, 2), nullptr, 16);
    return v;
}
static std::string a_hex(const uint8_t *p, size_t n) { std::string s; char t[3]; for (size_t i = 0; i < n; i++) { snprintf(t, 3, "%02x", p[i]); s += t; } return s; }
template <class F> static bool lanza(F f) { try { f(); } catch (const std::exception &) { return true; } return false; }

static const char *GEN_TX =
    "01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff4d04ffff001d0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73ffffffff0100f2052a01000000434104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac00000000";
static const char *GEN_RAIZ = "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a";   /* interno */

static std::vector<uint8_t> bloque_con(const std::vector<std::vector<uint8_t>> &txs, const uint8_t *raiz_forzada = nullptr) {
    std::vector<uint8_t> b(80, 0);
    b.push_back((uint8_t)txs.size());
    std::vector<std::array<uint8_t, 32>> hs;
    for (auto &t : txs) { b.insert(b.end(), t.begin(), t.end()); std::array<uint8_t, 32> h; dsha(t.data(), t.size(), h.data()); hs.push_back(h); }
    bool m; auto raiz = raiz_merkle(hs, m);
    memcpy(&b[36], raiz_forzada ? raiz_forzada : raiz.data(), 32);
    return b;
}
static std::vector<uint8_t> tx_simple(uint8_t semilla, bool coinbase) {
    std::vector<uint8_t> t = {1, 0, 0, 0, 1};
    for (int i = 0; i < 32; i++) t.push_back(coinbase ? 0 : semilla);
    for (int i = 0; i < 4; i++) t.push_back(coinbase ? 0xff : 0);
    t.push_back(0); for (int i = 0; i < 4; i++) t.push_back(0xff);
    t.push_back(1); for (int i = 0; i < 8; i++) t.push_back(i == 0 ? semilla : 0);
    t.push_back(1); t.push_back(0x51);
    for (int i = 0; i < 4; i++) t.push_back(0);
    return t;
}

int main() {
    auto gtx = desde_hex(GEN_TX);
    auto b = bloque_con({gtx});
    auto txs = leer_bloque(b);
    ok(txs.size() == 1 && txs[0].coinbase && a_hex(txs[0].txid.data(), 32) == GEN_RAIZ, "génesis: txid de la coinbase = raíz de Merkle conocida");
    ok(txs[0].salidas.size() == 1 && txs[0].salidas[0].valor == 5000000000ULL && txs[0].salidas[0].spk.size() == 67, "génesis: una salida de 50 BTC");
    ok(merkle_valido(b, txs), "génesis: Merkle válido");

    auto c = tx_simple(1, true), t1 = tx_simple(2, false), t2 = tx_simple(3, false), t3 = tx_simple(4, false);
    for (int n = 1; n <= 5; n++) {   /* distintos tamaños, incluidos impares */
        std::vector<std::vector<uint8_t>> v = {c};
        std::vector<std::vector<uint8_t>> otras = {t1, t2, t3, tx_simple(5, false)};
        for (int i = 0; i < n - 1; i++) v.push_back(otras[i]);
        auto bb = bloque_con(v); auto tt = leer_bloque(bb);
        if (!merkle_valido(bb, tt)) { ok(false, "Merkle de varios tamaños"); break; }
        if (n == 5) ok(true, "Merkle válido con 1 a 5 transacciones");
    }
    { auto bb = bloque_con({c, t1, t2}); bb[40] ^= 1; ok(!merkle_valido(bb, leer_bloque(bb)), "Merkle alterado: rechazado"); }
    { auto bb = bloque_con({c, t1, t2}); bb[81 + c.size() + 10] ^= 1; ok(!merkle_valido(bb, leer_bloque(bb)), "transacción alterada: Merkle rechazado"); }
    {   /* CVE-2012-2459: repetir la última transacción de un nivel impar da la misma raíz */
        auto honesto = bloque_con({c, t1, t2});
        auto mutado = bloque_con({c, t1, t2, t2});
        ok(memcmp(&honesto[36], &mutado[36], 32) == 0, "preparación: [c,t1,t2] y [c,t1,t2,t2] tienen la misma raíz");
        ok(merkle_valido(honesto, leer_bloque(honesto)) && !merkle_valido(mutado, leer_bloque(mutado)), "bloque mutado por duplicado: rechazado");
    }
    { auto bb = bloque_con({c, t1}); bb.resize(bb.size() - 3); ok(lanza([&] { leer_bloque(bb); }), "bloque cortado: rechazado"); }
    { auto bb = bloque_con({c, t1}); bb.push_back(0); ok(lanza([&] { leer_bloque(bb); }), "bytes sobrantes: rechazados"); }
    { auto bb = bloque_con({t1, t2}); ok(lanza([&] { leer_bloque(bb); }), "sin coinbase al principio: rechazado"); }
    { auto bb = bloque_con({c, c}); ok(lanza([&] { leer_bloque(bb); }), "coinbase repetida: rechazada"); }
    { auto bb = bloque_con({c}); bb[80] = 0; ok(lanza([&] { leer_bloque(bb); }), "bloque con 0 transacciones: rechazado"); }
    { auto bb = bloque_con({c}); bb[80] = 0xfe; bb.insert(bb.begin() + 81, {0xff, 0xff, 0xff, 0x7f}); ok(lanza([&] { leer_bloque(bb); }), "contador de transacciones imposible: rechazado"); }
    { auto bb = bloque_con({c}); bb[81 + 4] = 0; ok(lanza([&] { leer_bloque(bb); }), "transacción sin entradas: rechazada"); }
    {   /* ruido */
        uint64_t s = 88172645463325252ULL; int rechazos = 0;
        auto base = bloque_con({c, t1, t2});
        for (int it = 0; it < 20000; it++) {
            auto x = base;
            s ^= s << 13; s ^= s >> 7; s ^= s << 17;
            int k = 1 + s % 3; for (int j = 0; j < k; j++) { s ^= s << 13; s ^= s >> 7; s ^= s << 17; x[s % x.size()] ^= (uint8_t)(1 << (s % 8)); }
            if (it % 4 == 0) x.resize(s % x.size());
            try { leer_bloque(x); } catch (const std::exception &) { rechazos++; }
        }
        ok(rechazos > 0, "20000 bloques mutados o cortados sin romper nada");
    }
    printf(fallos ? "\n*** bloque FALLA ***\n" : "\nTODO CORRECTO\n");
    return fallos ? 1 : 0;
}
