#pragma once
/* Cliente ligero completo contra pares simulados (sin red): cabeceras, cabeceras de
 * filtro cruzadas entre pares, filtros, bloques con Merkle, saldo y reorganización.
 * Los pares simulados son nodos completos de una cadena sintética de dificultad
 * mínima, con filtros BIP158 reales, y pueden mentir o fallar de cada manera que
 * el cliente tiene que detectar. */
#include "sincro_btc.h"
#include <stdio.h>
#include <memory>

using namespace btc;
typedef std::array<uint8_t, 32> H;
typedef std::vector<uint8_t> Bytes;

static int fallos = 0;
static void ok(bool c, const char *nombre) { printf("%s  %s\n", c ? "OK  " : "MAL ", nombre); if (!c) fallos++; }

static const ParamsRed PR = {0x207fffff, 2016, 1209600, false};
static const uint64_t AHORA_MS = 4000000000000ULL;

/* ---------------- cadena sintética ---------------- */
struct TxSim { std::vector<std::pair<H, uint32_t>> ins; std::vector<std::pair<uint64_t, Bytes>> outs; bool testigo = false; };
struct BloqueSim { Bytes raw, cab; std::vector<TxSim> txs; Bytes filtro; H fhash, fheader; };

static void pv(Bytes &o, uint64_t v) { putvar(o, v); }
static Bytes ser_tx(const TxSim &t, bool coinbase, uint32_t sello, bool testigo, H *txid) {
    Bytes leg; put32(leg, 1);
    Bytes cuerpo;
    pv(cuerpo, coinbase ? 1 : t.ins.size());
    if (coinbase) { cuerpo.insert(cuerpo.end(), 32, 0); put32(cuerpo, 0xffffffffu); pv(cuerpo, 4); put32(cuerpo, sello); put32(cuerpo, 0xffffffffu); }
    else for (auto &i : t.ins) { cuerpo.insert(cuerpo.end(), i.first.begin(), i.first.end()); put32(cuerpo, i.second); pv(cuerpo, 0); put32(cuerpo, 0xffffffffu); }
    Bytes salidas; pv(salidas, t.outs.size());
    for (auto &o : t.outs) { put64(salidas, o.first); pv(salidas, o.second.size()); salidas.insert(salidas.end(), o.second.begin(), o.second.end()); }
    leg.insert(leg.end(), cuerpo.begin(), cuerpo.end()); leg.insert(leg.end(), salidas.begin(), salidas.end()); put32(leg, 0);
    if (txid) dsha(leg.data(), leg.size(), txid->data());
    if (!testigo) return leg;
    Bytes full; put32(full, 1); full.push_back(0); full.push_back(1);
    full.insert(full.end(), cuerpo.begin(), cuerpo.end()); full.insert(full.end(), salidas.begin(), salidas.end());
    size_t nin = coinbase ? 1 : t.ins.size();
    for (size_t i = 0; i < nin; i++) { pv(full, 1); pv(full, 2); full.push_back(0xaa); full.push_back(0xbb); }
    put32(full, 0);
    return full;
}

struct Cadena_ {
    std::vector<BloqueSim> b;
    std::map<std::string, Bytes> spk_de;    /* salida -> script, para los filtros */
    std::set<Bytes> omitir;                 /* scripts que esta versión de los filtros calla */
    uint32_t sal = 0;

    static std::string k(const H &t, uint32_t v) { return std::string((const char *)t.data(), 32) + std::string((const char *)&v, 4); }
    void agregar(std::vector<TxSim> txs, Bytes pago_coinbase = Bytes{0x51}, uint64_t valor_cb = 5000000000ULL) {
        BloqueSim bl; uint32_t h = (uint32_t)b.size();
        TxSim cb; cb.outs.push_back({valor_cb, pago_coinbase});
        std::vector<TxSim> todas = {cb}; todas.insert(todas.end(), txs.begin(), txs.end());
        Bytes cuerpo; std::vector<H> ids;
        for (size_t i = 0; i < todas.size(); i++) {
            H id; Bytes raw = ser_tx(todas[i], i == 0, h * 1000 + sal, todas[i].testigo, &id);
            ids.push_back(id); cuerpo.insert(cuerpo.end(), raw.begin(), raw.end());
            for (uint32_t o = 0; o < todas[i].outs.size(); o++) spk_de[k(id, o)] = todas[i].outs[o].second;
        }
        bool m; H raiz = raiz_merkle(ids, m);
        Bytes cab(80, 0);
        auto p32 = [&](size_t o, uint32_t x) { for (int i = 0; i < 4; i++) cab[o + i] = (uint8_t)(x >> (8 * i)); };
        p32(0, 0x20000000);
        if (h > 0) memcpy(&cab[4], hash_(b.back().cab).data(), 32);
        memcpy(&cab[36], raiz.data(), 32);
        uint32_t t = h == 0 ? 1000 : parsear_cabecera(b.back().cab.data(), 80).tiempo + 600;
        p32(68, t); p32(72, PR.limite_bits);
        for (uint32_t n = 0;; n++) { p32(76, n); if (prueba_de_trabajo(cab.data())) break; }
        bl.cab = cab; bl.raw = cab; pv(bl.raw, todas.size()); bl.raw.insert(bl.raw.end(), cuerpo.begin(), cuerpo.end());
        bl.txs = todas; b.push_back(bl);
        refiltrar();
    }
    static H hash_(const Bytes &c) { H h; hash_cabecera(c.data(), h.data()); return h; }
    Bytes filtro_de(size_t i) const {
        const BloqueSim &bl = b[i];
        std::vector<Bytes> el;
        for (size_t t = 0; t < bl.txs.size(); t++) {
            for (auto &o : bl.txs[t].outs) if (!o.second.empty() && o.second[0] != 0x6a) el.push_back(o.second);
            if (t > 0) for (auto &in : bl.txs[t].ins) { auto it = spk_de.find(k(in.first, in.second)); if (it != spk_de.end() && !it->second.empty()) el.push_back(it->second); }
        }
        el.erase(std::remove_if(el.begin(), el.end(), [&](const Bytes &x) { return omitir.count(x) > 0; }), el.end());
        H bh = hash_(bl.cab); uint64_t k0 = 0, k1 = 0;
        for (int j = 7; j >= 0; j--) { k0 = (k0 << 8) | bh[j]; k1 = (k1 << 8) | bh[8 + j]; }
        return gcs::construir(el, k0, k1);
    }
    void refiltrar() {
        H prev{};
        for (size_t i = 0; i < b.size(); i++) {
            b[i].filtro = filtro_de(i);
            dsha(b[i].filtro.data(), b[i].filtro.size(), b[i].fhash.data());
            uint8_t buf[64]; memcpy(buf, b[i].fhash.data(), 32); memcpy(buf + 32, prev.data(), 32);
            dsha(buf, 64, b[i].fheader.data()); prev = b[i].fheader;
        }
    }
    /* Copia con los mismos bloques hasta la altura h incluida. */
    Cadena_ hasta(uint32_t h) const {
        Cadena_ c; c.sal = sal;
        for (uint32_t i = 0; i <= h; i++) {
            c.b.push_back(b[i]);
            for (size_t t = 0; t < b[i].txs.size(); t++) {
                H id; ser_tx(b[i].txs[t], t == 0, i * 1000 + sal, false, &id);
                for (uint32_t o = 0; o < b[i].txs[t].outs.size(); o++) c.spk_de[k(id, o)] = b[i].txs[t].outs[o].second;
            }
        }
        return c;
    }
    H txid(uint32_t h, size_t t) const { H id; ser_tx(b[h].txs[t], t == 0, h * 1000 + sal, false, &id); return id; }
};

/* ---------------- nodo simulado ---------------- */
struct Nodo {
    std::shared_ptr<Cadena_> ch;
    Flujo fl{MAGIC_PRINCIPAL};
    uint64_t servicios = NODE_NETWORK | NODE_WITNESS | NODE_COMPACT_FILTERS;
    uint64_t nonce = 111;
    bool silencio = false;            /* no contesta nada tras el saludo */
    bool merkle_roto = false;         /* sirve bloques con una transacción alterada */
    bool bloque_equivocado = false;   /* sirve otro bloque del pedido */
    bool filtro_alterado = false;     /* sirve cfilter con un byte cambiado */
    bool cabeceras_malas = false;     /* sirve cabeceras con el nonce cambiado */
    bool cabeceras_repetidas = false; /* siempre las mismas 2000 */
    bool basura_tras_saludo = false;
    bool ignora_inv = false;          /* nunca pide las transacciones que se le anuncian */
    bool rechaza_tx = false;          /* las pide pero no las acepta ni las retransmite */
    bool relay = false;               /* lo pidió en su version */
    bool ping_flood = false;           /* 150 pings seguidos tras el saludo */
    bool saludo_core = false;         /* como Bitcoin Core: wtxidrelay y sendaddrv2 entre version y verack */
    int max_cfilters = -1;            /* tras servir tantos cfilters, se desconecta */
    bool desconectar = false; int servidos = 0;
    std::set<H> mempool; std::vector<H> nuevos;
    int invs_recibidos = 0, txs_recibidos = 0, pedidos = 0;

    Bytes recibe(const Bytes &in) {
        Bytes out;
        auto env = [&](const std::string &c, const Bytes &p) { auto m = serializar(MAGIC_PRINCIPAL, c, p); out.insert(out.end(), m.begin(), m.end()); };
        fl.alimentar(in.data(), in.size());
        Mensaje m;
        while (fl.siguiente(m)) {
            if (m.cmd == "version") {
                relay = leer_version(m.payload).relay;
                Version v; v.servicios = servicios; v.nonce = nonce; v.altura = (int32_t)ch->b.size() - 1; v.tiempo = 4000000000LL; v.agente = "/sim/";
                env("version", crear_version(v));
                if (saludo_core) { env("wtxidrelay", {}); env("sendaddrv2", {}); }
                env("verack", {});
                if (basura_tras_saludo) for (int i = 0; i < 30; i++) env("cfilter", Bytes(40, 0));
                if (ping_flood) for (int i = 0; i < 150; i++) env("ping", crear_nonce(i));
                continue;
            }
            if (silencio || m.cmd == "verack") continue;
            if (m.cmd == "ping") env("pong", m.payload);
            else if (m.cmd == "getheaders") {
                pedidos++;
                LectorP2P r{m.payload.data(), m.payload.size()}; r.u32(); uint64_t n = r.varint();
                uint32_t desde = 1;
                for (uint64_t i = 0; i < n; i++) {
                    H h; r.leer(h.data(), 32);
                    for (uint32_t k = 0; k < ch->b.size(); k++) if (Cadena_::hash_(ch->b[k].cab) == h) { desde = k + 1; goto hallado; }
                }
                hallado:
                std::vector<std::array<uint8_t, 80>> hs;
                if (cabeceras_repetidas) { std::array<uint8_t, 80> a; memcpy(a.data(), ch->b[1].cab.data(), 80); hs.assign(2000, a); }
                else for (uint32_t k = desde; k < ch->b.size() && hs.size() < 2000; k++) { std::array<uint8_t, 80> a; memcpy(a.data(), ch->b[k].cab.data(), 80); if (cabeceras_malas) a[76] ^= 0xff; hs.push_back(a); }
                env("headers", crear_headers(hs));
            } else if (m.cmd == "getcfheaders") {
                PideFiltros q = leer_getcf(m.payload);
                int64_t stop = alt(q.parar); if (stop < 0 || q.inicio > stop) continue;
                CfHeaders c; c.parar = q.parar;
                c.previa = q.inicio == 0 ? H{} : ch->b[q.inicio - 1].fheader;
                for (uint32_t k = q.inicio; k <= (uint32_t)stop; k++) c.hashes.push_back(ch->b[k].fhash);
                env("cfheaders", crear_cfheaders(c));
            } else if (m.cmd == "getcfilters") {
                PideFiltros q = leer_getcf(m.payload);
                int64_t stop = alt(q.parar); if (stop < 0 || q.inicio > stop) continue;
                for (uint32_t k = q.inicio; k <= (uint32_t)stop; k++) {
                    if (max_cfilters >= 0 && servidos++ >= max_cfilters) { desconectar = true; break; }
                    Cfilter f; f.bloque = Cadena_::hash_(ch->b[k].cab); f.filtro = ch->b[k].filtro;
                    if (filtro_alterado && !f.filtro.empty()) f.filtro.back() ^= 1;
                    env("cfilter", crear_cfilter(f));
                }
            } else if (m.cmd == "inv") {
                invs_recibidos++;
                if (ignora_inv) continue;
                std::vector<Inv> pide;
                for (auto &iv : leer_inv(m.payload)) if ((iv.tipo & ~INV_FLAG_TESTIGO) == INV_TX && !mempool.count(iv.hash)) pide.push_back({INV_TX | INV_FLAG_TESTIGO, iv.hash});
                if (!pide.empty()) env("getdata", crear_inv(pide));
            } else if (m.cmd == "tx") {
                txs_recibidos++;
                TxBloque t = leer_tx(m.payload);
                if (!rechaza_tx && mempool.insert(t.txid).second) nuevos.push_back(t.txid);
            } else if (m.cmd == "getdata") {
                for (auto &iv : leer_inv(m.payload)) {
                    if ((iv.tipo & ~INV_FLAG_TESTIGO) != INV_BLOQUE) continue;
                    int64_t k = alt(iv.hash); if (k < 0) continue;
                    if (bloque_equivocado) k = k > 1 ? k - 1 : k + 1;
                    Bytes raw = ch->b[k].raw;
                    if (merkle_roto) raw[raw.size() - 12] ^= 1;
                    env("block", raw);
                }
            }
        }
        return out;
    }
    int64_t alt(const H &h) const { for (uint32_t k = 0; k < ch->b.size(); k++) if (Cadena_::hash_(ch->b[k].cab) == h) return k; return -1; }
};

/* ---------------- red de prueba ---------------- */
struct Red {
    Cliente &c; std::map<int, Nodo *> n; std::set<int> cerrados, monitores;
    explicit Red(Cliente &cl) : c(cl) {}
    void conectar(int id, Nodo *nodo, bool relay = false) {
        n[id] = nodo; c.par_nuevo(id, AHORA_MS, relay); if (relay) monitores.insert(id);
    }
    void bombear() {
        for (int it = 0; it < 100000; it++) {
            bool algo = false;
            for (auto &kv : n) {
                if (cerrados.count(kv.first)) continue;
                Bytes o = c.tomar_salida(kv.first);
                if (o.empty()) continue;
                algo = true;
                Bytes r;
                try { r = kv.second->recibe(o); } catch (...) {}
                if (!r.empty()) c.recibido(kv.first, r.data(), r.size(), AHORA_MS);
                if (kv.second->desconectar && !cerrados.count(kv.first)) { cerrados.insert(kv.first); c.par_cerrado(kv.first, AHORA_MS); }
                /* Lo que un nodo acepta en su mempool lo anuncia a los pares con relay. */
                if (!kv.second->nuevos.empty() && !monitores.count(kv.first)) {
                    std::vector<Inv> iv; for (auto &t : kv.second->nuevos) iv.push_back({INV_TX, t});
                    kv.second->nuevos.clear();
                    auto msg = serializar(MAGIC_PRINCIPAL, "inv", crear_inv(iv));
                    for (int mid : monitores) if (!cerrados.count(mid)) c.recibido(mid, msg.data(), msg.size(), AHORA_MS);
                }
            }
            for (int id : c.tomar_cierres()) { cerrados.insert(id); c.par_cerrado(id, AHORA_MS); algo = true; }
            if (!algo) break;
        }
    }
};

static Bytes script(uint8_t b) { Bytes s = {0x00, 0x14}; s.insert(s.end(), 20, b); return s; }

static ConfigCliente cfg(uint32_t min_pares, uint32_t desde = 0) {
    ConfigCliente c; c.magic = MAGIC_PRINCIPAL; c.min_pares = min_pares; c.desde = desde; c.nonce = 999; c.lote_filtros = 7; return c;
}

