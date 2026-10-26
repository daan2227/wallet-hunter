#pragma once
/* Almacén de cabeceras: la cadena principal más las ramas recientes, con
 * reorganizaciones por trabajo acumulado (no por longitud), como Bitcoin Core.
 *
 * La principal se guarda como una tabla de cabeceras de 80 bytes por altura (a 900 mil
 * bloques son 72 MB) con un índice hash->altura sólo de las últimas `ventana`; lo más
 * viejo no se puede bifurcar (max_reorg). El contexto de validación de cada cabecera
 * (nBits esperados, mediana de tiempo, inicio del periodo) se reconstruye al vuelo.
 *
 * Persistencia: el fichero es la concatenación de las cabeceras 1..punta (el génesis
 * va dentro del programa). Al cargar se vuelve a validar todo; si algo falla se conserva
 * el prefijo bueno y quien llama recorta el fichero. Nada de lo guardado se da por
 * bueno sin comprobar. */
#include <array>
#include <map>
#include <unordered_map>
#include "cadena_btc.h"

namespace btc {

typedef std::array<uint8_t, 32> Hash32;
typedef std::array<uint8_t, 80> Cab80;

/* Aritmética de 256 bits en big-endian: lo justo para el trabajo acumulado. */
struct Num256 {
    uint8_t b[32] = {0};
    bool operator>(const Num256 &o) const { return memcmp(b, o.b, 32) > 0; }
    void sumar(const Num256 &o) {
        int c = 0;
        for (int i = 31; i >= 0; i--) { int v = b[i] + o.b[i] + c; b[i] = (uint8_t)v; c = v >> 8; }
    }
};

/* Trabajo de un bloque: 2^256 / (objetivo + 1), calculado como (~objetivo / (objetivo + 1)) + 1. */
static Num256 trabajo_de_bits(uint32_t bits) {
    Num256 w;
    uint8_t t[32];
    if (!bits_a_objetivo(bits, t)) return w;
    uint8_t n[32], d[32];
    for (int i = 0; i < 32; i++) n[i] = (uint8_t)~t[i];
    memcpy(d, t, 32);
    for (int i = 31; i >= 0; i--) { if (++d[i] != 0) break; }
    bool cero = true; for (int i = 0; i < 32; i++) if (d[i]) cero = false;
    if (cero) return w;                             /* objetivo = 2^256-1: no pasa nunca */
    uint8_t q[32] = {0}, r[32] = {0};
    for (int bit = 0; bit < 256; bit++) {
        int sale = r[0] >> 7;                      /* el resto puede llegar a 257 bits */
        for (int i = 0; i < 31; i++) r[i] = (uint8_t)((r[i] << 1) | (r[i + 1] >> 7));
        r[31] = (uint8_t)((r[31] << 1) | ((n[bit >> 3] >> (7 - (bit & 7))) & 1));
        if (sale || memcmp(r, d, 32) >= 0) {
            int c = 0;
            for (int i = 31; i >= 0; i--) { int v = r[i] - d[i] - c; c = v < 0; r[i] = (uint8_t)v; }
            q[bit >> 3] |= (uint8_t)(0x80 >> (bit & 7));
        }
    }
    memcpy(w.b, q, 32);
    Num256 uno; uno.b[31] = 1; w.sumar(uno);
    return w;
}

enum class Estado { Extiende, Duplicada, Rama, Reorg, Huerfana, Rechazada, MuyProfunda };

struct Resultado {
    Estado estado = Estado::Rechazada;
    ErrCabecera err = ErrCabecera::Ok;
    uint32_t fork = 0;        /* Reorg: altura del último bloque común */
    uint32_t deshechos = 0;   /* Reorg: bloques que dejan de ser de la principal */
    uint32_t nuevos = 0;      /* Reorg: bloques que pasan a serlo */
};

class Almacen {
public:
    Almacen(const ParamsRed &p, const uint8_t *genesis80, uint32_t max_reorg = 1000, uint32_t ventana = 3000)
        : p_(p), max_reorg_(max_reorg), ventana_(ventana > max_reorg + 12 ? ventana : max_reorg + 12) {
        Cab80 g; memcpy(g.data(), genesis80, 80);
        principal_.push_back(g);
        indice_[clave(hash_de(g))] = 0;
    }

    uint32_t altura() const { return (uint32_t)principal_.size() - 1; }
    Hash32 hash_en(uint32_t h) const { return hash_de(principal_.at(h)); }
    const Cab80 &cabecera_en(uint32_t h) const { return principal_.at(h); }
    Hash32 punta() const { return hash_en(altura()); }
    uint32_t bits_punta() const { return parsear_cabecera(principal_.back().data(), 80).bits; }

    /* Altura de ese hash en la principal, o -1 si no está (o es más viejo que la ventana). */
    int64_t altura_de(const Hash32 &h) const {
        auto it = indice_.find(clave(h));
        return it == indice_.end() ? -1 : (int64_t)it->second;
    }

    /* Localizador para getheaders: la punta, hacia atrás de uno en uno y luego a saltos
     * que se duplican, y el génesis al final. */
    std::vector<Hash32> localizador() const {
        std::vector<Hash32> v;
        int64_t h = altura(); int64_t paso = 1;
        while (h > 0) {
            v.push_back(hash_en((uint32_t)h));
            if (v.size() >= 10) paso *= 2;
            h -= paso;
        }
        v.push_back(hash_en(0));
        return v;
    }

    Resultado anadir(const uint8_t *cab80, uint32_t ahora) {
        Resultado r;
        Cab80 c; memcpy(c.data(), cab80, 80);
        Hash32 h = hash_de(c);
        if (altura_de(h) >= 0 || ramas_.count(clave(h))) { r.estado = Estado::Duplicada; return r; }
        Cabecera cc = parsear_cabecera(cab80, 80);
        Hash32 padre; memcpy(padre.data(), cc.prev, 32);

        /* Camino desde el padre hacia atrás por las ramas hasta la principal. */
        std::vector<Cab80> camino;                  /* del más cercano a la principal al padre */
        Hash32 cursor = padre;
        int64_t base = -1;
        for (uint32_t pasos = 0; ; pasos++) {
            base = altura_de(cursor);
            if (base >= 0) break;
            auto it = ramas_.find(clave(cursor));
            if (it == ramas_.end()) { r.estado = Estado::Huerfana; return r; }
            if (pasos > max_reorg_) { r.estado = Estado::MuyProfunda; return r; }
            camino.insert(camino.begin(), it->second.cab);
            memcpy(cursor.data(), parsear_cabecera(it->second.cab.data(), 80).prev, 32);
        }
        uint32_t fork = (uint32_t)base;
        if (altura() - fork > max_reorg_) { r.estado = Estado::MuyProfunda; return r; }

        /* Contexto del padre y validación. */
        uint32_t h_padre = fork + (uint32_t)camino.size();
        PuntoDeControl pc = contexto(fork, camino, h_padre, padre);
        ErrCabecera e = validar_hijo(p_, pc, cab80, ahora, h.data());
        if (e != ErrCabecera::Ok) { r.estado = Estado::Rechazada; r.err = e; return r; }
        uint32_t altura_nueva = h_padre + 1;
        auto ex = exigidos_.find(altura_nueva);
        if (ex != exigidos_.end() && ex->second != h) { r.estado = Estado::Rechazada; r.err = ErrCabecera::Checkpoint; return r; }
        /* Una rama que cruza una altura exigida con otro hash no puede ganar nunca. */

        if (camino.empty() && fork == altura()) {   /* extiende la punta */
            agregar_principal(c, h);
            r.estado = Estado::Extiende; return r;
        }

        camino.push_back(c);
        /* ¿Más trabajo que la principal desde la bifurcación? */
        Num256 wp, wr;
        for (uint32_t k = fork + 1; k <= altura(); k++)
            wp.sumar(trabajo_de_bits(parsear_cabecera(principal_[k].data(), 80).bits));
        for (auto &x : camino) wr.sumar(trabajo_de_bits(parsear_cabecera(x.data(), 80).bits));
        if (!(wr > wp)) {
            ramas_[clave(h)] = {c, altura_nueva};
            podar();
            r.estado = Estado::Rama; return r;
        }
        /* Reorganización: lo que sobra de la principal pasa a ramas, la rama entra. */
        r.estado = Estado::Reorg; r.fork = fork;
        if (reorg_min_ < 0 || (int64_t)fork < reorg_min_) reorg_min_ = fork;
        r.deshechos = altura() - fork; r.nuevos = (uint32_t)camino.size();
        while (altura() > fork) {
            Cab80 viejo = principal_.back();
            uint32_t hv = altura();
            indice_.erase(clave(hash_de(viejo)));
            principal_.pop_back();
            ramas_[clave(hash_de(viejo))] = {viejo, hv};
        }
        for (auto &x : camino) { Hash32 hx = hash_de(x); ramas_.erase(clave(hx)); agregar_principal(x, hx); }
        podar();
        return r;
    }

    /* Hash exigido a esa altura (punto de control). Debe ponerse antes de añadir. */
    void exigir_hash(uint32_t altura, const Hash32 &h) { exigidos_[altura] = h; }

    /* Cabeceras 1..punta, listas para el fichero. */
    std::vector<uint8_t> exportar(uint32_t desde = 1) const {
        std::vector<uint8_t> o;
        for (uint32_t k = desde; k <= altura(); k++) o.insert(o.end(), principal_[k].begin(), principal_[k].end());
        return o;
    }

    /* Carga un fichero: cuántas cabeceras seguidas son válidas. Lo que sobra (un
     * trozo de menos de 80 bytes, o una cabecera mala y todo lo que sigue) no se
     * toma. Quien llama recorta el fichero a 80 * devuelto. */
    size_t cargar(const uint8_t *p, size_t n) {
        size_t buenas = 0;
        for (size_t i = 0; i + 80 <= n; i += 80) {
            Resultado r = anadir(p + i, 0xFFFFFFFFu - 7200);
            if (r.estado != Estado::Extiende) break;
            buenas++;
        }
        return buenas;
    }

    size_t ramas() const { return ramas_.size(); }

    /* Altura de la bifurcación más baja desde la última vez que se preguntó, o -1 si no
     * hubo reorganización. Quien guarda el fichero recorta ahí antes de seguir añadiendo. */
    int64_t tomar_reorg_min() { int64_t r = reorg_min_; reorg_min_ = -1; return r; }

private:
    struct Rama { Cab80 cab; uint32_t altura; };
    static std::string clave(const Hash32 &h) { return std::string((const char *)h.data(), 32); }
    static Hash32 hash_de(const Cab80 &c) { Hash32 h; hash_cabecera(c.data(), h.data()); return h; }

    /* [hc] es el hash de [c], ya calculado por quien llama. Los de la ventana se guardan en un anillo
       para no rehacer el doble SHA-256 del que sale del índice. */
    void agregar_principal(const Cab80 &c, const Hash32 &hc) {
        principal_.push_back(c);
        uint32_t h = altura();
        indice_[clave(hc)] = h;
        if (anillo_.size() != (size_t)ventana_ + 1) anillo_.assign((size_t)ventana_ + 1, Hash32{});
        anillo_[h % anillo_.size()] = hc;
        if (h > ventana_) {
            uint32_t vieja = h - ventana_;
            /* Toda altura >= 1 de la principal entró por aquí (también al volver a entrar tras un
               reorg), y la ranura de [vieja] no la puede pisar nadie hasta la altura vieja+ventana+1. */
            indice_.erase(clave(anillo_[vieja % anillo_.size()]));
        }
    }

    void podar() {
        if (ramas_.size() <= 4000) return;
        for (auto it = ramas_.begin(); it != ramas_.end();)
            it = (it->second.altura + max_reorg_ < altura()) ? ramas_.erase(it) : std::next(it);
    }

    /* Estado del bloque de altura `h_padre`, que está en la rama `camino` colgada de `fork`. */
    PuntoDeControl contexto(uint32_t fork, const std::vector<Cab80> &camino, uint32_t h_padre, const Hash32 &hash_padre) const {
        auto en = [&](uint32_t k) -> const Cab80 & { return k > fork ? camino[k - fork - 1] : principal_[k]; };
        PuntoDeControl pc;
        pc.altura = h_padre;
        const Cab80 &pad = en(h_padre);
        Cabecera cp = parsear_cabecera(pad.data(), 80);
        memcpy(pc.hash, hash_padre.data(), 32);   /* es el prev de la cabecera que se añade: ya se conoce */
        pc.bits = cp.bits; pc.tiempo = cp.tiempo;
        uint32_t desde = h_padre >= 10 ? h_padre - 10 : 0;
        for (uint32_t k = desde; k <= h_padre; k++) pc.ultimos.push_back(parsear_cabecera(en(k).data(), 80).tiempo);
        uint32_t ini = p_.reajusta ? (h_padre / p_.intervalo) * p_.intervalo : 0;
        pc.t_inicio_periodo = parsear_cabecera(en(ini).data(), 80).tiempo;
        return pc;
    }

    ParamsRed p_;
    uint32_t max_reorg_, ventana_;
    int64_t reorg_min_ = -1;
    std::vector<Cab80> principal_;
    std::unordered_map<std::string, uint32_t> indice_;
    std::map<std::string, Rama> ramas_;
    std::map<uint32_t, Hash32> exigidos_;
    std::vector<Hash32> anillo_;
};

}  // namespace btc
