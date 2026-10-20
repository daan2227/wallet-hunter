#pragma once
/* Mensajes del protocolo P2P de Bitcoin: marco (magic, comando, longitud,
 * checksum) y los mensajes que usa el cliente ligero (BIP157/158). Sin sockets:
 * sólo bytes dentro y fuera, así se prueba sin red. Todo lo que llega de un par es
 * hostil: cada lectura comprueba límites y cada incumplimiento lanza ErrP2P, que
 * quien llama convierte en "desconectar y no volver a usar a ese par". */
#include <array>
#include <deque>
#include <string>
#include "bloques_btc.h"
#include "cabecera_btc.h"

namespace btc {

struct ErrP2P : std::runtime_error { using std::runtime_error::runtime_error; };

const uint32_t MAGIC_PRINCIPAL = 0xd9b4bef9;   /* f9 be b4 d9 en el cable */
const uint32_t MAGIC_TESTNET3 = 0x0709110b;
const uint32_t MAX_PAYLOAD = 4000000;          /* MAX_PROTOCOL_MESSAGE_LENGTH */
const uint32_t MAX_CABECERAS = 2000;
const uint32_t MAX_INV = 50000;
const uint32_t VERSION_PROTOCOLO = 70016;
const uint64_t NODE_NETWORK = 1, NODE_WITNESS = 1 << 3, NODE_COMPACT_FILTERS = 1 << 6;

struct Mensaje { std::string cmd; std::vector<uint8_t> payload; };

static void put32(std::vector<uint8_t> &o, uint32_t v) { for (int i = 0; i < 4; i++) o.push_back((uint8_t)(v >> (8 * i))); }
static void put64(std::vector<uint8_t> &o, uint64_t v) { for (int i = 0; i < 8; i++) o.push_back((uint8_t)(v >> (8 * i))); }
static void putvar(std::vector<uint8_t> &o, uint64_t v) {
    if (v < 0xfd) o.push_back((uint8_t)v);
    else if (v <= 0xffff) { o.push_back(0xfd); o.push_back((uint8_t)v); o.push_back((uint8_t)(v >> 8)); }
    else if (v <= 0xffffffffULL) { o.push_back(0xfe); put32(o, (uint32_t)v); }
    else { o.push_back(0xff); put64(o, v); }
}

/* Lector con límites y errores de P2P. CompactSize no canónico se rechaza. */
struct LectorP2P {
    const uint8_t *p; size_t n; size_t pos = 0;
    void need(uint64_t k) const { if (k > n - pos) throw ErrP2P("mensaje cortado"); }
    uint8_t u8() { need(1); return p[pos++]; }
    uint32_t u32() { need(4); uint32_t v = p[pos] | (p[pos+1] << 8) | (p[pos+2] << 16) | ((uint32_t)p[pos+3] << 24); pos += 4; return v; }
    uint64_t u64() { need(8); uint64_t v = 0; for (int i = 7; i >= 0; i--) v = (v << 8) | p[pos + i]; pos += 8; return v; }
    uint64_t varint() {
        uint8_t f = u8();
        if (f < 0xfd) return f;
        int k = f == 0xfd ? 2 : f == 0xfe ? 4 : 8;
        need(k);
        uint64_t v = 0;
        for (int i = k - 1; i >= 0; i--) v = (v << 8) | p[pos + i];
        pos += k;
        if ((k == 2 && v < 0xfd) || (k == 4 && v <= 0xffff) || (k == 8 && v <= 0xffffffffULL))
            throw ErrP2P("CompactSize no canónico");
        return v;
    }
    void leer(uint8_t *dst, size_t k) { need(k); memcpy(dst, p + pos, k); pos += k; }
    std::vector<uint8_t> bytes(uint64_t k) { need(k); std::vector<uint8_t> v(p + pos, p + pos + k); pos += (size_t)k; return v; }
    void fin() const { if (pos != n) throw ErrP2P("bytes sobrantes"); }
};

/* ---------- marco ---------- */

static std::vector<uint8_t> serializar(uint32_t magic, const std::string &cmd, const std::vector<uint8_t> &payload) {
    if (cmd.empty() || cmd.size() > 12) throw ErrP2P("comando inválido");
    if (payload.size() > MAX_PAYLOAD) throw ErrP2P("payload demasiado grande");
    std::vector<uint8_t> o; put32(o, magic);
    for (size_t i = 0; i < 12; i++) o.push_back(i < cmd.size() ? (uint8_t)cmd[i] : 0);
    put32(o, (uint32_t)payload.size());
    uint8_t h[32]; dsha(payload.data(), payload.size(), h);
    o.insert(o.end(), h, h + 4);
    o.insert(o.end(), payload.begin(), payload.end());
    return o;
}

/* Decodifica el flujo de bytes de un par. Lanza ErrP2P en cuanto algo no cuadra: la
 * longitud se comprueba al leer la cabecera, antes de reservar memoria. */
class Flujo {
public:
    explicit Flujo(uint32_t magic) : magic_(magic) {}
    void alimentar(const uint8_t *p, size_t n) {
        if (roto_) throw ErrP2P("flujo roto");
        buf_.insert(buf_.end(), p, p + n);
        try { extraer(); } catch (...) { roto_ = true; throw; }
    }
    bool siguiente(Mensaje &m) {
        if (cola_.empty()) return false;
        m = std::move(cola_.front()); cola_.pop_front(); return true;
    }
    size_t pendiente() const { return buf_.size(); }
private:
    void extraer() {
        for (;;) {
            if (buf_.size() < 24) return;
            LectorP2P r{buf_.data(), 24};
            if (r.u32() != magic_) throw ErrP2P("magic distinto");
            char cmd[13]; memcpy(cmd, &buf_[4], 12); cmd[12] = 0;
            size_t l = strnlen(cmd, 12);
            for (size_t i = 0; i < l; i++) if ((uint8_t)cmd[i] < 0x21 || (uint8_t)cmd[i] > 0x7e) throw ErrP2P("comando con caracteres raros");
            for (size_t i = l; i < 12; i++) if (buf_[4 + i] != 0) throw ErrP2P("comando mal relleno");
            if (l == 0) throw ErrP2P("comando vacío");
            r.pos = 16;
            uint32_t len = r.u32();
            if (len > MAX_PAYLOAD) throw ErrP2P("payload demasiado grande");
            if (buf_.size() < 24 + (size_t)len) return;
            uint8_t h[32]; dsha(&buf_[24], len, h);
            if (memcmp(h, &buf_[20], 4) != 0) throw ErrP2P("checksum erróneo");
            cola_.push_back({std::string(cmd, l), std::vector<uint8_t>(buf_.begin() + 24, buf_.begin() + 24 + len)});
            buf_.erase(buf_.begin(), buf_.begin() + 24 + len);
        }
    }
    uint32_t magic_; std::vector<uint8_t> buf_; std::deque<Mensaje> cola_; bool roto_ = false;
};

/* ---------- version ---------- */

struct Version {
    int32_t version = VERSION_PROTOCOLO; uint64_t servicios = 0; int64_t tiempo = 0;
    uint64_t nonce = 0; std::string agente; int32_t altura = 0; bool relay = false;
};

static void put_addr(std::vector<uint8_t> &o, uint64_t serv) {
    put64(o, serv);
    for (int i = 0; i < 10; i++) o.push_back(0);
    o.push_back(0xff); o.push_back(0xff);          /* IPv4 mapeada */
    for (int i = 0; i < 4; i++) o.push_back(0);
    o.push_back(0); o.push_back(0);                /* puerto */
}

static std::vector<uint8_t> crear_version(const Version &v) {
    std::vector<uint8_t> o;
    put32(o, (uint32_t)v.version); put64(o, v.servicios); put64(o, (uint64_t)v.tiempo);
    put_addr(o, 0); put_addr(o, v.servicios);
    put64(o, v.nonce);
    putvar(o, v.agente.size()); o.insert(o.end(), v.agente.begin(), v.agente.end());
    put32(o, (uint32_t)v.altura);
    o.push_back(v.relay ? 1 : 0);
    return o;
}

static Version leer_version(const std::vector<uint8_t> &pl) {
    LectorP2P r{pl.data(), pl.size()};
    Version v;
    v.version = (int32_t)r.u32(); v.servicios = r.u64(); v.tiempo = (int64_t)r.u64();
    uint8_t basura[26]; r.leer(basura, 26);        /* addr_recv */
    r.leer(basura, 26);                            /* addr_from */
    v.nonce = r.u64();
    uint64_t la = r.varint();
    if (la > 256) throw ErrP2P("user agent demasiado largo");
    auto a = r.bytes(la); v.agente.assign(a.begin(), a.end());
    v.altura = (int32_t)r.u32();
    v.relay = r.pos < r.n ? r.u8() != 0 : true;    /* BIP37: ausente = true */
    return v;
}

/* ¿Sirve este par para nosotros? Hace falta bloques completos, filtros (BIP157),
 * testigo y una versión que conozca los mensajes que usamos. Devuelve el motivo. */
static const char *par_inservible(const Version &v) {
    if (v.version < 70016) return "versión de protocolo antigua";
    if (!(v.servicios & NODE_NETWORK)) return "no sirve la cadena completa";
    if (!(v.servicios & NODE_COMPACT_FILTERS)) return "no sirve filtros de bloque";
    if (!(v.servicios & NODE_WITNESS)) return "sin testigo";
    if (v.altura < 0) return "altura negativa";
    return nullptr;
}

/* ---------- ping, headers ---------- */

static std::vector<uint8_t> crear_nonce(uint64_t n) { std::vector<uint8_t> o; put64(o, n); return o; }
static uint64_t leer_nonce(const std::vector<uint8_t> &pl) {
    LectorP2P r{pl.data(), pl.size()}; uint64_t n = r.u64(); r.fin(); return n;
}

static std::vector<uint8_t> crear_getheaders(const std::vector<std::array<uint8_t, 32>> &localizador, const uint8_t parar[32]) {
    if (localizador.empty() || localizador.size() > 101) throw ErrP2P("localizador inválido");
    std::vector<uint8_t> o; put32(o, VERSION_PROTOCOLO); putvar(o, localizador.size());
    for (auto &h : localizador) o.insert(o.end(), h.begin(), h.end());
    o.insert(o.end(), parar, parar + 32);
    return o;
}

/* Hasta 2000 cabeceras de 80 bytes. Cada una va seguida de un contador de
 * transacciones que tiene que ser 0. */
static std::vector<std::array<uint8_t, 80>> leer_headers(const std::vector<uint8_t> &pl) {
    LectorP2P r{pl.data(), pl.size()};
    uint64_t c = r.varint();
    if (c > MAX_CABECERAS) throw ErrP2P("demasiadas cabeceras");
    std::vector<std::array<uint8_t, 80>> v;
    for (uint64_t i = 0; i < c; i++) {
        std::array<uint8_t, 80> h; r.leer(h.data(), 80);
        if (r.varint() != 0) throw ErrP2P("cabecera con transacciones");
        v.push_back(h);
    }
    r.fin();
    return v;
}
static std::vector<uint8_t> crear_headers(const std::vector<std::array<uint8_t, 80>> &hs) {
    std::vector<uint8_t> o; putvar(o, hs.size());
    for (auto &h : hs) { o.insert(o.end(), h.begin(), h.end()); o.push_back(0); }
    return o;
}

/* ---------- filtros de bloque (BIP157) ---------- */

struct PideFiltros { uint8_t tipo = 0; uint32_t inicio = 0; std::array<uint8_t, 32> parar{}; };
static std::vector<uint8_t> crear_getcf(const PideFiltros &q) {
    std::vector<uint8_t> o; o.push_back(q.tipo); put32(o, q.inicio); o.insert(o.end(), q.parar.begin(), q.parar.end());
    return o;
}
static PideFiltros leer_getcf(const std::vector<uint8_t> &pl) {
    LectorP2P r{pl.data(), pl.size()}; PideFiltros q;
    q.tipo = r.u8(); q.inicio = r.u32(); r.leer(q.parar.data(), 32); r.fin(); return q;
}

struct CfHeaders {
    uint8_t tipo = 0; std::array<uint8_t, 32> parar{}, previa{};
    std::vector<std::array<uint8_t, 32>> hashes;   /* hash de cada filtro, en orden de altura */
};
static CfHeaders leer_cfheaders(const std::vector<uint8_t> &pl) {
    LectorP2P r{pl.data(), pl.size()}; CfHeaders c;
    c.tipo = r.u8(); r.leer(c.parar.data(), 32); r.leer(c.previa.data(), 32);
    uint64_t k = r.varint();
    if (k > 2000) throw ErrP2P("demasiados hashes de filtro");
    for (uint64_t i = 0; i < k; i++) { std::array<uint8_t, 32> h; r.leer(h.data(), 32); c.hashes.push_back(h); }
    r.fin(); return c;
}
static std::vector<uint8_t> crear_cfheaders(const CfHeaders &c) {
    std::vector<uint8_t> o; o.push_back(c.tipo);
    o.insert(o.end(), c.parar.begin(), c.parar.end()); o.insert(o.end(), c.previa.begin(), c.previa.end());
    putvar(o, c.hashes.size());
    for (auto &h : c.hashes) o.insert(o.end(), h.begin(), h.end());
    return o;
}

struct Cfilter { uint8_t tipo = 0; std::array<uint8_t, 32> bloque{}; std::vector<uint8_t> filtro; };
static Cfilter leer_cfilter(const std::vector<uint8_t> &pl) {
    LectorP2P r{pl.data(), pl.size()}; Cfilter c;
    c.tipo = r.u8(); r.leer(c.bloque.data(), 32);
    uint64_t l = r.varint();
    if (l > MAX_PAYLOAD) throw ErrP2P("filtro demasiado grande");
    c.filtro = r.bytes(l); r.fin(); return c;
}
static std::vector<uint8_t> crear_cfilter(const Cfilter &c) {
    std::vector<uint8_t> o; o.push_back(c.tipo); o.insert(o.end(), c.bloque.begin(), c.bloque.end());
    putvar(o, c.filtro.size()); o.insert(o.end(), c.filtro.begin(), c.filtro.end());
    return o;
}

/* ---------- inv, getdata ---------- */

const uint32_t INV_TX = 1, INV_BLOQUE = 2, INV_FLAG_TESTIGO = 0x40000000;
struct Inv { uint32_t tipo; std::array<uint8_t, 32> hash; };
static std::vector<Inv> leer_inv(const std::vector<uint8_t> &pl) {
    LectorP2P r{pl.data(), pl.size()};
    uint64_t c = r.varint();
    if (c > MAX_INV) throw ErrP2P("inv demasiado grande");
    std::vector<Inv> v;
    for (uint64_t i = 0; i < c; i++) { Inv x; x.tipo = r.u32(); r.leer(x.hash.data(), 32); v.push_back(x); }
    r.fin(); return v;
}
static std::vector<uint8_t> crear_inv(const std::vector<Inv> &v) {
    if (v.size() > MAX_INV) throw ErrP2P("inv demasiado grande");
    std::vector<uint8_t> o; putvar(o, v.size());
    for (auto &x : v) { put32(o, x.tipo); o.insert(o.end(), x.hash.begin(), x.hash.end()); }
    return o;
}

}  // namespace btc
