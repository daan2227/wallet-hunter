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
    int pedidos = 0;

    Bytes recibe(const Bytes &in) {
        Bytes out;
        auto env = [&](const std::string &c, const Bytes &p) { auto m = serializar(MAGIC_PRINCIPAL, c, p); out.insert(out.end(), m.begin(), m.end()); };
        fl.alimentar(in.data(), in.size());
        Mensaje m;
        while (fl.siguiente(m)) {
            if (m.cmd == "version") {
                Version v; v.servicios = servicios; v.nonce = nonce; v.altura = (int32_t)ch->b.size() - 1; v.tiempo = 4000000000LL; v.agente = "/sim/";
                env("version", crear_version(v)); env("verack", {});
                if (basura_tras_saludo) for (int i = 0; i < 30; i++) env("cfilter", Bytes(40, 0));
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
                    Cfilter f; f.bloque = Cadena_::hash_(ch->b[k].cab); f.filtro = ch->b[k].filtro;
                    if (filtro_alterado && !f.filtro.empty()) f.filtro.back() ^= 1;
                    env("cfilter", crear_cfilter(f));
                }
            } else if (m.cmd == "getdata") {
                for (auto &iv : leer_inv(m.payload)) {
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
    Cliente &c; std::map<int, Nodo *> n; std::set<int> cerrados;
    explicit Red(Cliente &cl) : c(cl) {}
    void conectar(int id, Nodo *nodo) { n[id] = nodo; c.par_nuevo(id, AHORA_MS); }
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

int main() {
    const Bytes S1 = script(0x11), S2 = script(0x22), X = script(0x99);

    /* Cadena con: A en 5 (50000->S1, 70000->X), B en 9 (30000->S2, con testigo), C en 12 (gasta A:0, 20000->S2),
       coinbase a S1 en 15. Hasta 30. */
    auto honesta = std::make_shared<Cadena_>();
    for (int h = 0; h <= 30; h++) {
        std::vector<TxSim> txs;
        Bytes cb = {0x51}; uint64_t vcb = 5000000000ULL;
        if (h == 5) { TxSim a; a.ins = {{honesta->txid(1, 0), 0}}; a.outs = {{50000, S1}, {70000, X}}; txs.push_back(a); }
        if (h == 9) { TxSim b; b.ins = {{honesta->txid(2, 0), 0}}; b.outs = {{30000, S2}}; b.testigo = true; txs.push_back(b); }
        if (h == 12) { TxSim c; c.ins = {{honesta->txid(5, 1), 0}}; c.outs = {{20000, S2}, {29000, X}}; txs.push_back(c); }
        if (h == 15) { cb = S1; vcb = 5000; }
        honesta->agregar(txs, cb, vcb);
    }
    const uint64_t SALDO = 30000 + 20000 + 5000;
    std::vector<Bytes> vigilados = {S1, S2};

    /* --- sincronización honesta con dos pares --- */
    {
        Almacen alm(PR, honesta->b[0].cab.data(), 50, 200);
        Cliente c(cfg(2), alm, vigilados);
        Nodo a, b; a.ch = b.ch = honesta; b.nonce = 222;
        Red red(c); red.conectar(1, &a); red.conectar(2, &b);
        red.bombear();
        ok(c.fase() == Fase::Listo, "dos pares honestos: sincronización terminada");
        ok(alm.altura() == 30, "las 30 cabeceras están en el almacén");
        ok(c.saldo() == SALDO, "saldo = 30000 + 20000 + 5000 (gasto de A descontado)");
        ok(c.utxos().size() == 3, "tres monedas sin gastar");
        ok(c.bloques_descargados() == 4, "sólo se bajan los 4 bloques que tocan (5, 9, 12 y 15)");
        ok(c.verificado(), "verificado contra dos pares");
        bool cb15 = false; for (auto &u : c.utxos()) if (u.altura == 15 && u.coinbase && u.valor == 5000) cb15 = true;
        ok(cb15, "la coinbase de la altura 15 se marca como coinbase");

        /* Se alarga la cadena y se actualiza. */
        auto mas = std::make_shared<Cadena_>(*honesta);
        for (int h = 31; h <= 33; h++) { TxSim t; if (h == 32) { t.ins = {{mas->txid(3, 0), 0}}; t.outs = {{7000, S2}}; } mas->agregar(h == 32 ? std::vector<TxSim>{t} : std::vector<TxSim>{}); }
        a.ch = b.ch = mas;
        c.actualizar(AHORA_MS); red.bombear();
        ok(c.fase() == Fase::Listo && alm.altura() == 33 && c.saldo() == SALDO + 7000, "bloques nuevos: se actualiza sin repetir lo hecho");
        ok(c.bloques_descargados() == 5, "...bajando sólo el bloque nuevo que toca");
    }

    /* --- un solo par: funciona pero no está verificado --- */
    {
        Almacen alm(PR, honesta->b[0].cab.data(), 50, 200);
        Cliente c(cfg(1), alm, vigilados);
        Nodo a; a.ch = honesta; Red red(c); red.conectar(1, &a); red.bombear();
        ok(c.fase() == Fase::Listo && c.saldo() == SALDO && !c.verificado(), "un solo par: da el saldo pero no se marca como verificado");
    }

    /* --- fecha de la cartera: se salta lo anterior --- */
    {
        Almacen alm(PR, honesta->b[0].cab.data(), 50, 200);
        Cliente c(cfg(2, 10), alm, vigilados);
        Nodo a, b; a.ch = b.ch = honesta; b.nonce = 222; Red red(c); red.conectar(1, &a); red.conectar(2, &b); red.bombear();
        ok(c.fase() == Fase::Listo && c.saldo() == 20000 + 5000 + 0, "escaneo desde la altura 10: no ve lo anterior (B en 9)");
    }

    /* --- un par miente en el filtro (omite S1 desde el principio) --- */
    {
        auto mentira = std::make_shared<Cadena_>(*honesta); mentira->omitir = {S1}; mentira->refiltrar();
        Almacen alm(PR, honesta->b[0].cab.data(), 50, 200);
        Cliente c(cfg(2), alm, vigilados);
        Nodo bueno, malo, otro; bueno.ch = honesta; malo.ch = mentira; otro.ch = honesta; malo.nonce = 222; otro.nonce = 333;
        Red red(c); red.conectar(1, &malo); red.conectar(2, &bueno);
        red.bombear();
        bool expulsado = red.cerrados.count(1) > 0 && red.cerrados.count(2) == 0;
        ok(expulsado, "filtro que omite un pago: el mentiroso es expulsado y el honesto no");
        ok(c.fase() == Fase::Conectando, "queda esperando otro par (ya no hay dos)");
        red.conectar(3, &otro); red.bombear();
        ok(c.fase() == Fase::Listo && c.saldo() == SALDO, "con un tercer par honesto termina con el saldo correcto");
    }

    /* --- dos pares mienten distinto cada uno --- */
    {
        auto m1 = std::make_shared<Cadena_>(*honesta); m1->omitir = {S1}; m1->refiltrar();
        auto m2 = std::make_shared<Cadena_>(*honesta); m2->omitir = {S2}; m2->refiltrar();
        Almacen alm(PR, honesta->b[0].cab.data(), 50, 200);
        Cliente c(cfg(2), alm, vigilados);
        Nodo a, b; a.ch = m1; b.ch = m2; b.nonce = 222; Red red(c); red.conectar(1, &a); red.conectar(2, &b); red.bombear();
        /* Difieren primero en la altura 5, donde sólo el que calla S1 contradice al bloque. */
        ok(c.fase() == Fase::Conectando && red.cerrados.count(1) == 1 && red.cerrados.count(2) == 0,
           "dos mentirosos distintos: cae el que contradice al bloque en la primera diferencia");
    }

    /* --- el filtro de los dos omite lo mismo: lo detecta el bloque si llega a bajarse --- */
    {
        /* Ambos omiten S1 sólo en la altura 5 pero el bloque 5 se baja por S2 en 9? No: otra altura.
           Aquí el filtro callaría una salida AJENA (X) que el bloque sí contiene: incoherente. */
        auto m = std::make_shared<Cadena_>(*honesta); m->omitir = {X}; m->refiltrar();
        Almacen alm(PR, honesta->b[0].cab.data(), 50, 200);
        Cliente c(cfg(2), alm, vigilados);
        Nodo a, b; a.ch = b.ch = m; b.nonce = 222; Red red(c); red.conectar(1, &a); red.conectar(2, &b); red.bombear();
        ok(c.fase() == Fase::Error, "los dos filtros callan una salida del bloque: se detecta al bajarlo y se para con error");
        ok(c.saldo() == 0 || c.fase() == Fase::Error, "...sin dar un saldo como bueno");
    }

    /* --- fallos de un par que se resuelven con otro --- */
    struct Caso { const char *nombre; void (*mal)(Nodo &); };
    Caso casos[] = {
        {"bloque con Merkle roto", [](Nodo &n) { n.merkle_roto = true; }},
        {"bloque que no es el pedido", [](Nodo &n) { n.bloque_equivocado = true; }},
        {"filtro con un byte cambiado", [](Nodo &n) { n.filtro_alterado = true; }},
        {"cabeceras con prueba de trabajo falsa", [](Nodo &n) { n.cabeceras_malas = true; }},
        {"cabeceras repetidas sin avanzar", [](Nodo &n) { n.cabeceras_repetidas = true; }},
        {"no responde nada", [](Nodo &n) { n.silencio = true; }},
        {"inunda con mensajes no pedidos", [](Nodo &n) { n.basura_tras_saludo = true; }},
        {"sin servicio de filtros", [](Nodo &n) { n.servicios = NODE_NETWORK | NODE_WITNESS; }},
        {"sin testigo", [](Nodo &n) { n.servicios = NODE_NETWORK | NODE_COMPACT_FILTERS; }},
        {"se conecta con nosotros mismos", [](Nodo &n) { n.nonce = 999; }},
    };
    for (auto &cs : casos) {
        Almacen alm(PR, honesta->b[0].cab.data(), 50, 200);
        Cliente c(cfg(1), alm, vigilados);
        Nodo malo, bueno; malo.ch = bueno.ch = honesta; bueno.nonce = 222; cs.mal(malo);
        Red red(c); red.conectar(1, &malo); red.conectar(2, &bueno);
        red.bombear();
        if (!strcmp(cs.nombre, "no responde nada")) { c.tick(AHORA_MS + 31000); red.bombear(); }
        bool listo = c.fase() == Fase::Listo && c.saldo() == SALDO;
        bool echado = red.cerrados.count(1) > 0;
        std::string nom = std::string(cs.nombre) + ": se expulsa y el otro par da el saldo correcto";
        ok(listo && echado && red.cerrados.count(2) == 0, nom.c_str());
    }

    /* --- mensaje con checksum roto --- */
    {
        Almacen alm(PR, honesta->b[0].cab.data(), 50, 200);
        Cliente c(cfg(1), alm, vigilados);
        c.par_nuevo(1, AHORA_MS);
        Bytes v = serializar(MAGIC_PRINCIPAL, "version", crear_version([] { Version v; v.servicios = NODE_NETWORK | NODE_WITNESS | NODE_COMPACT_FILTERS; v.nonce = 5; return v; }()));
        v[20] ^= 1; c.recibido(1, v.data(), v.size(), AHORA_MS);
        ok(!c.tomar_cierres().empty(), "checksum roto en el saludo: se corta");
        c.par_nuevo(2, AHORA_MS);
        auto ping = serializar(MAGIC_PRINCIPAL, "ping", crear_nonce(1));
        c.recibido(2, ping.data(), ping.size(), AHORA_MS);
        ok(!c.tomar_cierres().empty(), "mensaje antes de terminar el saludo: se corta");
        c.par_nuevo(3, AHORA_MS);
        auto va = serializar(MAGIC_PRINCIPAL, "verack", {});
        c.recibido(3, va.data(), va.size(), AHORA_MS);
        ok(!c.tomar_cierres().empty(), "verack sin version previa: se corta");
    }

    /* --- reorganización: lo aplicado por encima de la bifurcación se deshace --- */
    {
        Almacen alm(PR, honesta->b[0].cab.data(), 50, 200);
        Cliente c(cfg(2), alm, vigilados);
        Nodo a, b; a.ch = b.ch = honesta; b.nonce = 222;
        Red red(c); red.conectar(1, &a); red.conectar(2, &b); red.bombear();
        ok(c.fase() == Fase::Listo && c.saldo() == SALDO, "antes de la reorganización: saldo completo");
        /* Rama desde 10: sin el gasto C en 12 y sin la coinbase de 15, y más larga. */
        auto otra = std::make_shared<Cadena_>(honesta->hasta(10)); otra->sal = 77;
        for (int h = 11; h <= 34; h++) {
            std::vector<TxSim> txs;
            if (h == 20) { TxSim t; t.ins = {{otra->txid(4, 0), 0}}; t.outs = {{9000, S2}}; txs.push_back(t); }
            otra->agregar(txs);
        }
        a.ch = b.ch = otra;
        c.actualizar(AHORA_MS); red.bombear();
        ok(alm.altura() == 34, "el almacén sigue la rama con más trabajo");
        /* Quedan: A:0 (50000) sin gastar, B (30000) y lo nuevo en 20 (9000). C y la coinbase de 15 ya no existen. */
        ok(c.fase() == Fase::Listo && c.saldo() == 50000 + 30000 + 9000, "tras la reorganización: se deshacen C y la coinbase, reaparece A");
        bool a_vuelve = false; for (auto &u : c.utxos()) if (u.altura == 5 && u.valor == 50000) a_vuelve = true;
        ok(a_vuelve, "la moneda que C había gastado vuelve a estar disponible");
    }

    /* --- sin pares suficientes: espera, no inventa --- */
    {
        Almacen alm(PR, honesta->b[0].cab.data(), 50, 200);
        Cliente c(cfg(2), alm, vigilados);
        Nodo a; a.ch = honesta; Red red(c); red.conectar(1, &a); red.bombear();
        ok(c.fase() == Fase::Conectando && c.saldo() == 0, "con un solo par y exigiendo dos: espera sin dar saldo");
    }

    /* --- reescaneo con otros scripts --- */
    {
        Almacen alm(PR, honesta->b[0].cab.data(), 50, 200);
        Cliente c(cfg(1), alm, {S1});
        Nodo a; a.ch = honesta; Red red(c); red.conectar(1, &a); red.bombear();
        ok(c.fase() == Fase::Listo && c.saldo() == 5000, "sólo S1: tras gastar A queda la coinbase de 5000");
        c.reescanear({S1, S2}, 0, AHORA_MS); red.bombear();
        ok(c.fase() == Fase::Listo && c.saldo() == SALDO, "reescaneo con S2 añadida: saldo completo");
    }

    printf(fallos ? "\n*** sincro FALLA ***\n" : "\nTODO CORRECTO\n");
    return fallos ? 1 : 0;
}
