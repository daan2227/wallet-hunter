/* Mensajes P2P: marco, version, headers, filtros (BIP157), inv. Vector real: el
 * verack de la red principal. Y todo lo que tiene que rechazarse: magic o checksum
 * erróneos, longitud desmesurada (antes de reservar memoria), comando mal formado,
 * cabeceras con transacciones, CompactSize no canónico, cortes, bytes sobrantes, y
 * una tanda de entradas aleatorias y mutadas que no pueden romper nada (ASan/UBSan). */
#include "p2p_btc.h"
#include <stdio.h>
#include <string>

using namespace btc;

static int fallos = 0;
static void ok(bool c, const char *nombre) {
    printf("%s  %s\n", c ? "OK  " : "MAL ", nombre);
    if (!c) fallos++;
}
static std::string hex(const std::vector<uint8_t> &v) {
    std::string s; char t[3];
    for (auto b : v) { snprintf(t, 3, "%02x", b); s += t; }
    return s;
}
template <class F> static bool lanza(F f) { try { f(); } catch (const ErrP2P &) { return true; } return false; }

static bool alimentar_todo(Flujo &f, const std::vector<uint8_t> &b, size_t paso, std::vector<Mensaje> &out) {
    for (size_t i = 0; i < b.size(); i += paso) {
        try { f.alimentar(&b[i], std::min(paso, b.size() - i)); } catch (const ErrP2P &) { return false; }
        Mensaje m; while (f.siguiente(m)) out.push_back(m);
    }
    return true;
}

int main() {
    /* verack real */
    auto verack = serializar(MAGIC_PRINCIPAL, "verack", {});
    ok(hex(verack) == "f9beb4d976657261636b000000000000000000005df6e0e2", "verack == vector real de la red");

    /* flujo: de byte en byte, juntos y partidos */
    {
        auto a = serializar(MAGIC_PRINCIPAL, "ping", crear_nonce(0x1122334455667788ULL));
        auto b = serializar(MAGIC_PRINCIPAL, "verack", {});
        std::vector<uint8_t> ab = a; ab.insert(ab.end(), b.begin(), b.end());
        for (size_t paso : {size_t(1), size_t(7), size_t(24), ab.size()}) {
            Flujo f(MAGIC_PRINCIPAL); std::vector<Mensaje> m;
            bool bien = alimentar_todo(f, ab, paso, m) && m.size() == 2 && m[0].cmd == "ping" && m[1].cmd == "verack"
                        && leer_nonce(m[0].payload) == 0x1122334455667788ULL && f.pendiente() == 0;
            ok(bien, paso == 1 ? "flujo: dos mensajes byte a byte" : paso == 7 ? "flujo: en trozos de 7" :
                     paso == 24 ? "flujo: en trozos de 24" : "flujo: de golpe");
        }
    }

    /* version */
    {
        Version v; v.servicios = NODE_NETWORK | NODE_WITNESS | NODE_COMPACT_FILTERS; v.tiempo = 1700000000;
        v.nonce = 42; v.agente = "/WalletHunter:1/"; v.altura = 900000; v.relay = false;
        auto pl = crear_version(v);
        Version w = leer_version(pl);
        ok(w.version == 70016 && w.servicios == v.servicios && w.tiempo == v.tiempo && w.nonce == 42 &&
           w.agente == v.agente && w.altura == 900000 && !w.relay, "version: ida y vuelta");
        ok(par_inservible(w) == nullptr, "par con red, testigo y filtros: sirve");
        Version s = w; s.servicios &= ~NODE_COMPACT_FILTERS; ok(par_inservible(s) != nullptr, "par sin filtros: no sirve");
        s = w; s.servicios &= ~NODE_NETWORK; ok(par_inservible(s) != nullptr, "par sin cadena completa: no sirve");
        s = w; s.servicios &= ~NODE_WITNESS; ok(par_inservible(s) != nullptr, "par sin testigo: no sirve");
        s = w; s.version = 70015; ok(par_inservible(s) != nullptr, "protocolo antiguo: no sirve");
        s = w; s.altura = -5; ok(par_inservible(s) != nullptr, "altura negativa: no sirve");
        auto corto = pl; corto.resize(corto.size() - 6);
        ok(lanza([&] { leer_version(corto); }), "version cortada: rechazada");
        Version largo = v; largo.agente = std::string(257, 'a');
        ok(lanza([&] { leer_version(crear_version(largo)); }), "user agent de 257 bytes: rechazado");
        auto sin_relay = pl; sin_relay.pop_back();
        ok(leer_version(sin_relay).relay, "version sin byte de relay: relay = true (BIP37)");
    }

    /* getheaders / headers */
    {
        std::array<uint8_t, 32> h{}; h[0] = 1;
        uint8_t cero[32] = {0};
        auto g = crear_getheaders({h}, cero);
        ok(g.size() == 4 + 1 + 32 + 32, "getheaders: tamaño");
        ok(lanza([&] { crear_getheaders({}, cero); }), "getheaders sin localizador: rechazado");
        ok(lanza([&] { crear_getheaders(std::vector<std::array<uint8_t, 32>>(102), cero); }), "getheaders con 102 hashes: rechazado");
        std::vector<std::array<uint8_t, 80>> hs(3); for (int i = 0; i < 3; i++) hs[i].fill((uint8_t)(i + 1));
        auto pl = crear_headers(hs);
        auto r = leer_headers(pl);
        ok(r.size() == 3 && r[2][0] == 3, "headers: ida y vuelta");
        auto mas = pl; mas.push_back(0);
        ok(lanza([&] { leer_headers(mas); }), "headers: bytes sobrantes rechazados");
        auto cortado = pl; cortado.resize(cortado.size() - 1);
        ok(lanza([&] { leer_headers(cortado); }), "headers: cortado rechazado");
        auto con_tx = pl; con_tx[1 + 80] = 1;
        ok(lanza([&] { leer_headers(con_tx); }), "headers: cabecera con transacciones rechazada");
        std::vector<std::array<uint8_t, 80>> muchas(2001);
        ok(lanza([&] { leer_headers(crear_headers(muchas)); }), "headers: 2001 rechazadas");
        std::vector<uint8_t> noc = {0xfd, 0x01, 0x00};   /* 1 codificado en 3 bytes */
        ok(lanza([&] { leer_headers(noc); }), "CompactSize no canónico rechazado");
        std::vector<uint8_t> noc4 = {0xfe, 0xff, 0xff, 0x00, 0x00};
        ok(lanza([&] { leer_headers(noc4); }), "CompactSize no canónico (4 bytes) rechazado");
        std::vector<uint8_t> enorme = {0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x7f};
        ok(lanza([&] { leer_headers(enorme); }), "contador enorme rechazado sin reservar memoria");
    }

    /* filtros */
    {
        PideFiltros q; q.tipo = 0; q.inicio = 123456; q.parar.fill(9);
        auto pl = crear_getcf(q); auto r = leer_getcf(pl);
        ok(r.inicio == 123456 && r.parar[31] == 9 && r.tipo == 0, "getcfilters/getcfheaders: ida y vuelta");
        auto mas = pl; mas.push_back(1);
        ok(lanza([&] { leer_getcf(mas); }), "getcf: bytes sobrantes rechazados");
        CfHeaders c; c.parar.fill(1); c.previa.fill(2); c.hashes.assign(5, std::array<uint8_t, 32>());
        c.hashes[4].fill(7);
        auto cp = crear_cfheaders(c); auto d = leer_cfheaders(cp);
        ok(d.hashes.size() == 5 && d.hashes[4][0] == 7 && d.previa[0] == 2, "cfheaders: ida y vuelta");
        c.hashes.assign(2001, std::array<uint8_t, 32>());
        ok(lanza([&] { leer_cfheaders(crear_cfheaders(c)); }), "cfheaders: 2001 hashes rechazados");
        auto cc = cp; cc.resize(cc.size() - 3);
        ok(lanza([&] { leer_cfheaders(cc); }), "cfheaders: cortado rechazado");
        Cfilter f; f.bloque.fill(3); f.filtro = {1, 2, 3, 4};
        auto fp = crear_cfilter(f); auto g = leer_cfilter(fp);
        ok(g.filtro == f.filtro && g.bloque[0] == 3, "cfilter: ida y vuelta");
        auto largo = fp; largo[1 + 32] = 200;   /* promete 200 bytes, trae 4 */
        ok(lanza([&] { leer_cfilter(largo); }), "cfilter: longitud mayor que los datos rechazada");
        std::vector<uint8_t> inmenso = fp; inmenso.resize(1 + 32); inmenso.push_back(0xfe);
        for (int i = 0; i < 4; i++) inmenso.push_back(0xff);
        ok(lanza([&] { leer_cfilter(inmenso); }), "cfilter: longitud enorme rechazada");
    }

    /* inv */
    {
        std::vector<Inv> v(3); for (auto &x : v) { x.tipo = INV_TX | INV_FLAG_TESTIGO; x.hash.fill(5); }
        auto r = leer_inv(crear_inv(v));
        ok(r.size() == 3 && r[1].tipo == (INV_TX | INV_FLAG_TESTIGO) && r[1].hash[0] == 5, "inv: ida y vuelta");
        ok(lanza([&] { crear_inv(std::vector<Inv>(50001)); }), "inv: 50001 elementos rechazados al crear");
        std::vector<uint8_t> grande = {0xfd, 0x51, 0xc3};   /* 50001 */
        ok(lanza([&] { leer_inv(grande); }), "inv: 50001 elementos rechazados al leer");
    }

    /* marco: errores */
    {
        auto bueno = serializar(MAGIC_PRINCIPAL, "ping", crear_nonce(1));
        std::vector<Mensaje> m;
        { Flujo f(MAGIC_TESTNET3); ok(!alimentar_todo(f, bueno, bueno.size(), m), "magic de otra red: rechazado"); }
        { auto x = bueno; x[24] ^= 1; Flujo f(MAGIC_PRINCIPAL); ok(!alimentar_todo(f, x, x.size(), m), "payload alterado: checksum rechazado"); }
        { auto x = bueno; x[20] ^= 1; Flujo f(MAGIC_PRINCIPAL); ok(!alimentar_todo(f, x, x.size(), m), "checksum alterado: rechazado"); }
        { auto x = bueno; x[16] = x[17] = x[18] = x[19] = 0xff; x.resize(24);
          Flujo f(MAGIC_PRINCIPAL); ok(!alimentar_todo(f, x, x.size(), m), "longitud 4 GB: rechazada solo con la cabecera"); }
        { auto x = bueno; x[16] = 0x41; x[17] = 0x42; x[18] = 0x3d; x[19] = 0x00; x.resize(24);   /* 4.000.001 */
          Flujo f(MAGIC_PRINCIPAL); ok(!alimentar_todo(f, x, x.size(), m), "payload de 4.000.001 bytes: rechazado"); }
        { auto x = bueno; x[4] = 0; Flujo f(MAGIC_PRINCIPAL); ok(!alimentar_todo(f, x, x.size(), m), "comando vacío: rechazado"); }
        { auto x = bueno; x[4 + 5] = 'x'; x[4 + 4] = 0; Flujo f(MAGIC_PRINCIPAL); ok(!alimentar_todo(f, x, x.size(), m), "comando con basura tras el NUL: rechazado"); }
        { auto x = bueno; x[4] = 0x07; Flujo f(MAGIC_PRINCIPAL); ok(!alimentar_todo(f, x, x.size(), m), "comando con carácter de control: rechazado"); }
        { Flujo f(MAGIC_PRINCIPAL); auto x = bueno; x[0] ^= 1; alimentar_todo(f, x, x.size(), m);
          ok(lanza([&] { f.alimentar(bueno.data(), bueno.size()); }), "tras un error el flujo queda roto"); }
        ok(lanza([&] { serializar(MAGIC_PRINCIPAL, "comandomuylargo", {}); }), "comando de más de 12 caracteres: rechazado al crear");
        ok(lanza([&] { serializar(MAGIC_PRINCIPAL, "x", std::vector<uint8_t>(MAX_PAYLOAD + 1)); }), "payload mayor que el máximo: rechazado al crear");
        { Flujo f(MAGIC_PRINCIPAL); std::vector<Mensaje> mm; bool bien = alimentar_todo(f, bueno, 10, mm) && mm.size() == 1 && f.pendiente() == 0;
          ok(bien, "tras un mensaje completo no queda nada pendiente"); }
        { Flujo f(MAGIC_PRINCIPAL); std::vector<Mensaje> mm; auto x = bueno; x.resize(x.size() - 1);
          bool sin_error = alimentar_todo(f, x, x.size(), mm); ok(sin_error && mm.empty(), "mensaje a medias: espera sin error"); }
    }

    /* mutaciones y ruido: nada puede romper el programa */
    {
        uint64_t s = 0x9e3779b97f4a7c15ULL;
        auto rnd = [&]() { s ^= s << 13; s ^= s >> 7; s ^= s << 17; return s; };
        std::vector<std::vector<uint8_t>> semillas;
        Version v; v.agente = "/x/"; v.servicios = 1 | 8 | 64;
        semillas.push_back(serializar(MAGIC_PRINCIPAL, "version", crear_version(v)));
        std::vector<std::array<uint8_t, 80>> hs(4); semillas.push_back(serializar(MAGIC_PRINCIPAL, "headers", crear_headers(hs)));
        CfHeaders c; c.hashes.assign(3, std::array<uint8_t, 32>()); semillas.push_back(serializar(MAGIC_PRINCIPAL, "cfheaders", crear_cfheaders(c)));
        Cfilter f; f.filtro = {1, 2, 3}; semillas.push_back(serializar(MAGIC_PRINCIPAL, "cfilter", crear_cfilter(f)));
        std::vector<Inv> iv(2); semillas.push_back(serializar(MAGIC_PRINCIPAL, "inv", crear_inv(iv)));
        int aceptados = 0, rechazados = 0;
        for (int it = 0; it < 30000; it++) {
            auto x = semillas[rnd() % semillas.size()];
            int k = 1 + rnd() % 4;
            for (int j = 0; j < k; j++) x[rnd() % x.size()] ^= (uint8_t)(1 << (rnd() % 8));
            if (rnd() % 5 == 0) x.resize(rnd() % x.size());
            Flujo fl(MAGIC_PRINCIPAL);
            try {
                fl.alimentar(x.data(), x.size());
                Mensaje m;
                while (fl.siguiente(m)) {
                    try {
                        if (m.cmd == "version") leer_version(m.payload);
                        else if (m.cmd == "headers") leer_headers(m.payload);
                        else if (m.cmd == "cfheaders") leer_cfheaders(m.payload);
                        else if (m.cmd == "cfilter") leer_cfilter(m.payload);
                        else if (m.cmd == "inv") leer_inv(m.payload);
                        aceptados++;
                    } catch (const ErrP2P &) { rechazados++; }
                }
            } catch (const ErrP2P &) { rechazados++; }
        }
        for (int it = 0; it < 5000; it++) {
            std::vector<uint8_t> x(rnd() % 600); for (auto &b : x) b = (uint8_t)rnd();
            Flujo fl(MAGIC_PRINCIPAL);
            try { fl.alimentar(x.data(), x.size()); } catch (const ErrP2P &) {}
            try { leer_headers(x); } catch (const ErrP2P &) {}
            try { leer_cfheaders(x); } catch (const ErrP2P &) {}
            try { leer_cfilter(x); } catch (const ErrP2P &) {}
            try { leer_inv(x); } catch (const ErrP2P &) {}
            try { leer_version(x); } catch (const ErrP2P &) {}
        }
        printf("     (mutaciones: %d pasan, %d rechazadas)\n", aceptados, rechazados);
        ok(rechazados > 0, "35000 entradas mutadas o aleatorias sin romper nada");
    }

    printf(fallos ? "\n*** p2p FALLA ***\n" : "\nTODO CORRECTO\n");
    return fallos ? 1 : 0;
}
