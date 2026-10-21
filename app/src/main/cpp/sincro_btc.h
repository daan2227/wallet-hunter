#pragma once
/* Cliente ligero (BIP157/158) sin sockets: máquina de estados que recibe bytes de
 * cada par y devuelve bytes para enviarle. Quien lo use (Kotlin, o el simulador de
 * las pruebas) sólo abre y cierra conexiones y reparte los bytes.
 *
 * Qué se fía y qué no:
 *   - Las cabeceras se validan solas (prueba de trabajo, dificultad, tiempo) y la
 *     rama de más trabajo gana.
 *   - Las cabeceras de filtro se piden a `min_pares` pares distintos y tienen que
 *     coincidir. Si difieren, se baja ese bloque y se comprueba cuál de los filtros
 *     contiene todas sus salidas: el que no, es mentiroso y se expulsa.
 *   - Cada filtro que llega tiene que dar el hash acordado.
 *   - Cada bloque descargado tiene que ser el pedido, con su raíz de Merkle y sin
 *     mutar, y su filtro tiene que contener todas sus salidas.
 *   - Una reorganización deshace lo ya aplicado por encima de la bifurcación.
 * Límite que no se puede resolver con este protocolo: si TODOS los pares a los que
 * se pregunta mienten igual, un filtro que omita un pago a la cartera no se nota. */
#include <set>
#include "almacen_btc.h"
#include "p2p_btc.h"
#include "tx_btc.h"
#include "gcs_bip158.h"

namespace btc {

struct Utxo {
    std::array<uint8_t, 32> txid; uint32_t vout; uint64_t valor;
    std::vector<uint8_t> spk; uint32_t altura; bool coinbase;
};

enum class Fase { Conectando, Cabeceras, FiltrosCab, Filtros, Bloques, Listo, Error };

/* Qué se sabe de una transacción propia que se ha difundido:
 *   Pendiente   - aún no se la ha pedido ningún par;
 *   Enviada     - al menos un par la pidió y se le dio;
 *   Propagada   - un par que NO la recibió de nosotros la anunció (señal de que corre por la red);
 *   Confirmada  - está en un bloque de la cadena principal;
 *   Reemplazada - un bloque gastó alguna de sus entradas con otra transacción. */
enum class EstadoDifusion { Desconocida, Pendiente, Enviada, Propagada, Confirmada, Reemplazada };

struct ConfigCliente {
    uint32_t magic = MAGIC_PRINCIPAL;
    uint32_t min_pares = 2;          /* pares que tienen que coincidir en las cabeceras de filtro */
    uint32_t desde = 0;              /* primera altura a escanear (fecha de la cartera) */
    uint64_t nonce = 0;
    std::string agente = "/WalletHunter:1/";
    uint32_t lote_filtros = 1000;
    uint64_t timeout_ms = 30000;
    uint64_t timeout_saludo_ms = 60000;
    /* Sólo difundir transacciones: vale cualquier nodo (no hace falta que sirva filtros ni la
     * cadena entera) y no se sincroniza nada. */
    bool solo_difusion = false;
};

class Cliente {
public:
    Cliente(const ConfigCliente &cfg, Almacen &alm, std::vector<std::vector<uint8_t>> scripts)
        : cfg_(cfg), alm_(alm) { fijar_scripts(scripts); reiniciar_escaneo(cfg.desde); }

    /* ---- conexiones ---- */
    /* `relay` = true pide al par que nos anuncie las transacciones que corren por la red
     * (BIP37): se usa en conexiones aparte, sólo para ver si una transacción nuestra se
     * propaga. Cuesta ancho de banda, así que las conexiones normales van sin él. */
    void par_nuevo(int id, uint64_t ahora_ms, bool relay = false) {
        Par &p = pares_.emplace(id, Par(id, cfg_.magic)).first->second;
        p.t_alta = ahora_ms; p.relay = relay;
        Version v; v.servicios = 0; v.tiempo = (int64_t)(ahora_ms / 1000); v.nonce = cfg_.nonce;
        v.agente = cfg_.agente; v.altura = (int32_t)alm_.altura(); v.relay = relay;
        enviar(p, "version", crear_version(v));
    }
    void par_cerrado(int id, uint64_t ahora_ms) {
        auto it = pares_.find(id);
        if (it == pares_.end()) return;
        it->second.muerto = true;
        reevaluar(it->second);
        avanzar(ahora_ms);
    }
    void recibido(int id, const uint8_t *p, size_t n, uint64_t ahora_ms) {
        auto it = pares_.find(id);
        if (it == pares_.end() || it->second.muerto) return;
        Par &par = it->second;
        try {
            par.fl.alimentar(p, n);
            Mensaje m;
            while (!par.muerto && par.fl.siguiente(m)) tratar(par, m, ahora_ms);
        } catch (const std::exception &e) {
            banear(par, std::string("mensaje inválido: ") + e.what());
        }
        avanzar(ahora_ms);
    }
    void tick(uint64_t ahora_ms) {
        for (auto &kv : pares_) {
            Par &p = kv.second;
            if (p.muerto) continue;
            // Resta saturada: si el reloj del teléfono retrocede, no se expulsa a todos de golpe.
            if (!p.listo && trans(ahora_ms, p.t_alta) > cfg_.timeout_saludo_ms) banear(p, "no completó el saludo");
            else if (p.esperando && trans(ahora_ms, p.t_pend) > cfg_.timeout_ms) banear(p, "sin respuesta");
        }
        avanzar(ahora_ms);
    }
    std::vector<uint8_t> tomar_salida(int id) {
        auto it = salida_.find(id);
        if (it == salida_.end()) return {};
        auto v = std::move(it->second); salida_.erase(it); return v;
    }
    std::vector<int> tomar_cierres() { auto v = cerrar_; cerrar_.clear(); return v; }

    /* ---- control ---- */
    void actualizar(uint64_t ahora_ms) {       /* buscar bloques nuevos */
        if (fase_ == Fase::Listo || fase_ == Fase::Conectando) { fase_ = Fase::Conectando; avanzar(ahora_ms); }
    }
    void reescanear(std::vector<std::vector<uint8_t>> scripts, uint32_t desde, uint64_t ahora_ms) {
        abortar_lote_cf();
        fijar_scripts(scripts); reiniciar_escaneo(desde); fase_ = Fase::Conectando; error_.clear();
        // Se sueltan las peticiones de filtros y bloques en curso, pero no la de cabeceras: repetirla
        // haría que el par contestara dos veces y pareciera que repite cabeceras ya conocidas.
        for (auto &kv : pares_) kv.second.esperando &= ~(E_CFH | E_CF | E_BLK | E_DCF | E_DBLK);
        lote_cfh_ = false; blk_en_vuelo_ = false; cf_driver_ = -1; disp_.activa = false;
        avanzar(ahora_ms);
    }

    /* ---- difusión de transacciones propias ---- */
    /* Registra la transacción y se la ofrece (inv) a hasta 8 pares; sólo se entrega a quien
     * la pide. Lanza si no es una transacción bien formada, si es una coinbase o si pesa más
     * de 400 kB. Llamarla otra vez con la misma transacción no hace nada. */
    std::array<uint8_t, 32> difundir(const std::vector<uint8_t> &raw, uint64_t ahora_ms) {
        if (raw.size() > 400000) throw ErrP2P("transacción demasiado grande");
        TxBloque t = leer_tx(raw);
        if (t.coinbase) throw ErrP2P("no se difunde una coinbase");
        std::string k((const char *)t.txid.data(), 32);
        if (!difusiones_.count(k)) {
            Difusion d; d.raw = raw; d.txid = t.txid; d.entradas = t.entradas;
            difusiones_[k] = d;
        }
        ofrecer_difusiones(ahora_ms);
        return t.txid;
    }
    /* Con la difusión en pausa las transacciones se registran pero no se ofrecen. Sirve para
     * esperar a tener la conexión con relay lista: si la transacción se anuncia antes, esa
     * conexión no verá nunca el aviso. Al reanudar se ofrecen las pendientes. */
    void pausar_difusion(bool pausa, uint64_t ahora_ms) {
        pausa_difusion_ = pausa;
        if (!pausa) ofrecer_difusiones(ahora_ms);
    }
    bool par_listo(int id) const {
        auto it = pares_.find(id);
        return it != pares_.end() && it->second.listo && !it->second.muerto;
    }
    EstadoDifusion estado_difusion(const std::array<uint8_t, 32> &txid) const {
        auto it = difusiones_.find(std::string((const char *)txid.data(), 32));
        return it == difusiones_.end() ? EstadoDifusion::Desconocida : it->second.estado;
    }
    uint32_t altura_difusion(const std::array<uint8_t, 32> &txid) const {
        auto it = difusiones_.find(std::string((const char *)txid.data(), 32));
        return it == difusiones_.end() ? 0 : it->second.altura;
    }

    /* ---- resultado ---- */
    Fase fase() const { return fase_; }
    const std::string &error() const { return error_; }
    const std::vector<std::string> &registro() const { return registro_; }
    std::vector<Utxo> utxos() const { std::vector<Utxo> v; for (auto &kv : utxos_) v.push_back(kv.second); return v; }
    uint64_t saldo() const { uint64_t s = 0; for (auto &kv : utxos_) s += kv.second.valor; return s; }
    uint32_t altura_procesada() const { return sig_cf_ == 0 ? 0 : sig_cf_ - 1; }
    /* true sólo si TODAS las cabeceras de filtro aceptadas se cruzaron entre dos o más nodos. */
    bool verificado() const { return cfg_.min_pares >= 2 && lotes_cfh_ > 0 && lotes_cfh_varios_ == lotes_cfh_; }
    size_t bloques_descargados() const { return descargados_; }
    size_t pares_vivos() const { size_t n = 0; for (auto &kv : pares_) if (!kv.second.muerto) n++; return n; }
    size_t pares_listos() const { return listos().size(); }

private:
    typedef std::array<uint8_t, 32> H;
    enum Esp : int { E_HEADERS = 1, E_CFH = 2, E_CF = 4, E_BLK = 8, E_DCF = 16, E_DBLK = 32 };
    struct Par {
        Par(int i, uint32_t magic) : id(i), fl(magic) {}
        int id; Flujo fl; bool v_rx = false, ack_rx = false, listo = false, muerto = false;
        Version v; uint64_t t_alta = 0, t_pend = 0; int esperando = 0;
        bool hdr_fin = false; uint32_t inesperados = 0; bool relay = false;
        uint32_t cf_descartar = 0;      /* cfilters de un lote abortado que aún pueden llegar: se ignoran */
        bool cfh_ok = false; CfHeaders cfh;
    };
    struct Efecto { std::vector<Utxo> anadidos, gastados; };
    struct Difusion {
        std::vector<uint8_t> raw; H txid{}; std::vector<EntradaTx> entradas;
        EstadoDifusion estado = EstadoDifusion::Pendiente; uint32_t altura = 0;
        std::set<int> ofrecida, servida;
    };
    struct Disputa {
        bool activa = false; uint32_t h = 0; std::vector<int> pares; int proveedor_bloque = -1;
        std::map<int, std::vector<uint8_t>> filtros; std::vector<uint8_t> bloque; bool bloque_ok = false;
        std::map<int, H> declarado;
    };

    ConfigCliente cfg_; Almacen &alm_;
    std::map<int, Par> pares_;
    std::map<int, std::vector<uint8_t>> salida_; std::vector<int> cerrar_;
    Fase fase_ = Fase::Conectando; std::string error_; std::vector<std::string> registro_;
    std::vector<std::vector<uint8_t>> scripts_; std::set<std::vector<uint8_t>> set_scripts_;
    uint32_t desde_ = 0, sig_cfh_ = 0, sig_cf_ = 0;
    int64_t rollback_a_ = -1;
    std::vector<H> fh_, fhash_;                 /* fh_[0] = ancla; fh_[k] = cabecera de la altura desde_+k-1 */
    std::map<std::string, Utxo> utxos_;
    std::map<uint32_t, Efecto> efectos_;
    std::map<uint32_t, std::vector<uint8_t>> filtros_pend_;
    std::vector<uint32_t> pendientes_;
    size_t descargados_ = 0;
    /* lote de cabeceras de filtro */
    bool lote_cfh_ = false; uint32_t cfh_b0_ = 0, cfh_b1_ = 0; std::vector<int> cfh_pares_;
    /* lote de filtros */
    bool lote_cf_ = false; uint32_t cf_b0_ = 0, cf_b1_ = 0, cf_n_ = 0; int cf_driver_ = -1;
    int blk_driver_ = -1; bool blk_en_vuelo_ = false;
    Disputa disp_;
    std::map<std::string, Difusion> difusiones_;
    bool pausa_difusion_ = false;
    std::vector<int64_t> desfases_;       /* hora del nodo - hora local, uno por saludo */
    size_t lotes_cfh_ = 0, lotes_cfh_varios_ = 0;

    static uint64_t trans(uint64_t ahora, uint64_t desde) { return ahora > desde ? ahora - desde : 0; }
    static std::string clave_utxo(const H &t, uint32_t v) { return std::string((const char *)t.data(), 32) + std::string((const char *)&v, 4); }
    static void clave_filtro(const H &h, uint64_t &k0, uint64_t &k1) {
        k0 = k1 = 0;
        for (int i = 7; i >= 0; i--) { k0 = (k0 << 8) | h[i]; k1 = (k1 << 8) | h[8 + i]; }
    }
    static H hash_filtro(const std::vector<uint8_t> &f) { H h; dsha(f.data(), f.size(), h.data()); return h; }
    static H siguiente_fh(const H &hash, const H &previa) {
        uint8_t b[64]; memcpy(b, hash.data(), 32); memcpy(b + 32, previa.data(), 32);
        H o; dsha(b, 64, o.data()); return o;
    }

    void fijar_scripts(const std::vector<std::vector<uint8_t>> &s) {
        scripts_.clear(); set_scripts_.clear();
        for (auto &x : s) if (!x.empty() && set_scripts_.insert(x).second) scripts_.push_back(x);
    }
    void reiniciar_escaneo(uint32_t desde) {
        desde_ = desde; sig_cfh_ = desde; sig_cf_ = desde; rollback_a_ = -1;
        fh_.assign(1, H{}); fhash_.clear(); utxos_.clear(); efectos_.clear();
        filtros_pend_.clear(); pendientes_.clear(); descargados_ = 0;
    }

    void enviar(Par &p, const std::string &cmd, const std::vector<uint8_t> &pl) {
        auto b = serializar(cfg_.magic, cmd, pl);
        auto &o = salida_[p.id]; o.insert(o.end(), b.begin(), b.end());
    }
    void log(const std::string &s) { registro_.push_back(s); if (registro_.size() > 200) registro_.erase(registro_.begin()); }
    void banear(Par &p, const std::string &motivo) {
        if (p.muerto) return;
        p.muerto = true; cerrar_.push_back(p.id);
        log("par " + std::to_string(p.id) + " expulsado: " + motivo);
        reevaluar(p);
    }
    /* Deja de esperar el lote de filtros en curso. Lo ya recogido de ese lote se descarta (se volverá a
     * pedir entero) y lo que el par aún mande se ignora sin contarlo como mensaje no pedido. */
    void abortar_lote_cf() {
        if (!lote_cf_) return;
        lote_cf_ = false;
        for (auto it = pendientes_.begin(); it != pendientes_.end();)
            it = (*it >= cf_b0_ && *it <= cf_b1_) ? pendientes_.erase(it) : std::next(it);
        for (auto it = filtros_pend_.begin(); it != filtros_pend_.end();)
            it = (it->first >= cf_b0_ && it->first <= cf_b1_) ? filtros_pend_.erase(it) : std::next(it);
        auto d = pares_.find(cf_driver_);
        if (d != pares_.end() && !d->second.muerto) {
            d->second.cf_descartar += cf_b1_ - (cf_b0_ + cf_n_) + 1;
            d->second.esperando &= ~E_CF;
        }
    }

    /* Un par que desaparece deja lotes a medias: se descartan para repetirlos con otros. */
    void reevaluar(Par &p) {
        if (lote_cfh_) for (int i : cfh_pares_) if (i == p.id) { lote_cfh_ = false; for (int j : cfh_pares_) { auto it = pares_.find(j); if (it != pares_.end()) it->second.esperando &= ~E_CFH; } }
        if (lote_cf_ && cf_driver_ == p.id) abortar_lote_cf();
        if (blk_en_vuelo_ && blk_driver_ == p.id) blk_en_vuelo_ = false;
        if (disp_.activa) {
            bool es = p.id == disp_.proveedor_bloque;
            for (int i : disp_.pares) if (i == p.id) es = true;
            if (es) { disp_.activa = false; lote_cfh_ = false; for (auto &kv : pares_) kv.second.esperando &= ~(E_DCF | E_DBLK); }
        }
    }
    std::vector<int> listos() const {
        std::vector<int> v; for (auto &kv : pares_) if (kv.second.listo && !kv.second.muerto) v.push_back(kv.first); return v;
    }
    void marcar(Par &p, int e, uint64_t ahora_ms) { p.esperando |= e; p.t_pend = ahora_ms; }

    /* ---- mensajes ---- */
    void tratar(Par &p, const Mensaje &m, uint64_t ahora_ms) {
        if (m.cmd == "version") {
            if (p.v_rx) { banear(p, "version repetida"); return; }
            p.v = leer_version(m.payload); p.v_rx = true;
            desfases_.push_back((int64_t)p.v.tiempo - (int64_t)(ahora_ms / 1000));
            if (p.v.nonce == cfg_.nonce && cfg_.nonce != 0) { banear(p, "conexión con nosotros mismos"); return; }
            if (cfg_.solo_difusion) {
                if (p.v.version < 70001) { banear(p, "versión de protocolo antigua"); return; }
            } else if (const char *motivo = par_inservible(p.v)) { banear(p, motivo); return; }
            enviar(p, "verack", {});
            if (p.ack_rx) p.listo = true;
            return;
        }
        if (m.cmd == "verack") {
            if (!p.v_rx || p.ack_rx) { banear(p, "verack fuera de orden"); return; }
            p.ack_rx = true; p.listo = true; return;
        }
        if (!p.listo) {
            // Entre su version y su verack, Bitcoin Core manda mensajes de negociación (wtxidrelay,
            // sendaddrv2, sendtxrcncl…) además de pings. Expulsarlos dejaba fuera a casi todos los
            // nodos reales. Se admiten esos; cualquier otra cosa (datos) antes del saludo, no.
            static const char *negociacion[] = {"wtxidrelay", "sendaddrv2", "sendtxrcncl", "sendheaders",
                                                "sendcmpct", "feefilter", "addr", "addrv2", "getaddr"};
            if (!p.v_rx) { banear(p, "mensaje antes de su version"); return; }
            if (m.cmd == "ping") { enviar(p, "pong", crear_nonce(leer_nonce(m.payload))); return; }
            for (auto *n : negociacion) if (m.cmd == n) return;
            banear(p, "mensaje antes de terminar el saludo"); return;
        }
        if (m.cmd == "ping") { enviar(p, "pong", crear_nonce(leer_nonce(m.payload))); return; }
        if (m.cmd == "headers") { en_headers(p, m, ahora_ms); return; }
        if (m.cmd == "cfheaders") { en_cfheaders(p, m, ahora_ms); return; }
        if (m.cmd == "cfilter") { en_cfilter(p, m, ahora_ms); return; }
        if (m.cmd == "block") { en_bloque(p, m, ahora_ms); return; }
        if (m.cmd == "getdata") { en_getdata(p, m); return; }
        if (m.cmd == "inv") { en_inv(p, m); return; }
        /* addr, inv, sendheaders, feefilter, sendcmpct, getheaders, ...: no nos hacen falta. */
    }
    void inesperado(Par &p, const char *que) {
        if (++p.inesperados > 20) banear(p, std::string("demasiados mensajes no pedidos (") + que + ")");
    }

    /* ---- difusión ---- */
    Difusion *buscar_difusion(const std::array<uint8_t, 32> &h) {
        auto it = difusiones_.find(std::string((const char *)h.data(), 32));
        return it == difusiones_.end() ? nullptr : &it->second;
    }
    void ofrecer_difusiones(uint64_t ahora_ms) {
        (void)ahora_ms;
        if (pausa_difusion_) return;
        for (auto &kv : difusiones_) {
            Difusion &d = kv.second;
            if (d.estado != EstadoDifusion::Pendiente && d.estado != EstadoDifusion::Enviada) continue;
            size_t vivas = 0;
            for (int id : d.ofrecida) { auto it = pares_.find(id); if (it != pares_.end() && !it->second.muerto) vivas++; }
            for (auto &q : pares_) {
                if (vivas >= 8) break;
                Par &p = q.second;
                if (!p.listo || p.muerto || p.relay || d.ofrecida.count(p.id)) continue;
                enviar(p, "inv", crear_inv({{INV_TX, d.txid}}));
                d.ofrecida.insert(p.id); vivas++;
            }
        }
    }
    /* Sólo se entrega una transacción a quien se la ofrecimos y la pide con la marca de testigo. */
    void en_getdata(Par &p, const Mensaje &m) {
        for (auto &iv : leer_inv(m.payload)) {
            if ((iv.tipo & ~INV_FLAG_TESTIGO) != INV_TX || !(iv.tipo & INV_FLAG_TESTIGO)) continue;
            Difusion *d = buscar_difusion(iv.hash);
            if (!d || !d->ofrecida.count(p.id)) continue;
            enviar(p, "tx", d->raw);
            d->servida.insert(p.id);
            if (d->estado == EstadoDifusion::Pendiente) d->estado = EstadoDifusion::Enviada;
        }
    }
    /* Un par con relay que anuncia nuestra transacción sin haberla recibido de nosotros. */
    void en_inv(Par &p, const Mensaje &m) {
        auto v = leer_inv(m.payload);
        if (!p.relay) return;
        for (auto &iv : v) {
            if ((iv.tipo & ~INV_FLAG_TESTIGO) != INV_TX) continue;
            Difusion *d = buscar_difusion(iv.hash);
            if (!d || d->ofrecida.count(p.id)) continue;
            if (d->estado == EstadoDifusion::Pendiente || d->estado == EstadoDifusion::Enviada) d->estado = EstadoDifusion::Propagada;
        }
    }

    /* ---- cabeceras ---- */
    /* Hora para juzgar si una cabecera está "en el futuro": la del teléfono corregida con la mediana de lo
     * que dicen los nodos (como Core, con tope de 70 min). Con el reloj atrasado más de 2 h, de otro modo
     * se rechazaba cada cabecera nueva y se expulsaba a todos los pares. */
    uint32_t hora_ajustada(uint64_t ahora_ms) const {
        int64_t desfase = 0;
        if (!desfases_.empty()) {
            std::vector<int64_t> d = desfases_; std::sort(d.begin(), d.end());
            desfase = std::max<int64_t>(-4200, std::min<int64_t>(4200, d[d.size() / 2]));
        }
        int64_t t = (int64_t)(ahora_ms / 1000) + desfase;
        return t < 0 ? 0 : (t > 0xFFFFFFFFLL ? 0xFFFFFFFFu : (uint32_t)t);
    }
    void pedir_headers(Par &p, uint64_t ahora_ms) {
        uint8_t cero[32] = {0};
        enviar(p, "getheaders", crear_getheaders(alm_.localizador(), cero));
        marcar(p, E_HEADERS, ahora_ms); p.hdr_fin = false;
    }
    void en_headers(Par &p, const Mensaje &m, uint64_t ahora_ms) {
        if (!(p.esperando & E_HEADERS)) { inesperado(p, "headers"); return; }
        p.esperando &= ~E_HEADERS;
        auto hs = leer_headers(m.payload);
        bool progreso = false;
        for (auto &h : hs) {
            Resultado r = alm_.anadir(h.data(), hora_ajustada(ahora_ms));
            switch (r.estado) {
                case Estado::Extiende: case Estado::Rama: progreso = true; break;
                case Estado::Reorg:
                    progreso = true;
                    if (rollback_a_ < 0 || (int64_t)r.fork < rollback_a_) rollback_a_ = r.fork;
                    log("reorganización: bifurcación en " + std::to_string(r.fork) + ", " + std::to_string(r.deshechos) + " bloques deshechos");
                    break;
                case Estado::Duplicada: break;
                case Estado::Huerfana: banear(p, "cabeceras que no enlazan"); return;
                case Estado::MuyProfunda: banear(p, "bifurcación demasiado profunda"); return;
                case Estado::Rechazada: banear(p, "cabecera inválida"); return;
            }
        }
        if (hs.size() >= MAX_CABECERAS) {
            if (!progreso) { banear(p, "repite cabeceras ya conocidas"); return; }
            pedir_headers(p, ahora_ms);
        } else p.hdr_fin = true;
    }

    /* ---- deshacer lo aplicado por encima de una bifurcación ---- */
    void deshacer(uint32_t fork) {
        while (!efectos_.empty() && efectos_.rbegin()->first > fork) {
            Efecto &e = efectos_.rbegin()->second;
            for (auto &u : e.anadidos) utxos_.erase(clave_utxo(u.txid, u.vout));
            for (auto &u : e.gastados) utxos_[clave_utxo(u.txid, u.vout)] = u;
            efectos_.erase(std::prev(efectos_.end()));
        }
        for (auto &kv : difusiones_) {
            Difusion &d = kv.second;
            if ((d.estado == EstadoDifusion::Confirmada || d.estado == EstadoDifusion::Reemplazada) && d.altura > fork) {
                d.estado = d.servida.empty() ? EstadoDifusion::Pendiente : EstadoDifusion::Enviada; d.altura = 0;
            }
        }
        size_t nuevo_fh = fork + 1 >= desde_ ? (size_t)(fork + 1 - desde_) : 0;
        if (fh_.size() > nuevo_fh + 1) fh_.resize(nuevo_fh + 1);
        if (fhash_.size() > nuevo_fh) fhash_.resize(nuevo_fh);
        for (auto it = filtros_pend_.begin(); it != filtros_pend_.end();) it = it->first > fork ? filtros_pend_.erase(it) : std::next(it);
        pendientes_.erase(std::remove_if(pendientes_.begin(), pendientes_.end(), [&](uint32_t h) { return h > fork; }), pendientes_.end());
        if (sig_cfh_ > fork + 1) sig_cfh_ = std::max(fork + 1, desde_);
        if (sig_cf_ > fork + 1) sig_cf_ = std::max(fork + 1, desde_);
        abortar_lote_cf();
        lote_cfh_ = false; blk_en_vuelo_ = false; disp_.activa = false;
        for (auto &kv : pares_) kv.second.esperando &= ~(E_CFH | E_CF | E_BLK | E_DCF | E_DBLK);
    }

    /* ---- cabeceras de filtro ---- */
    void en_cfheaders(Par &p, const Mensaje &m, uint64_t ahora_ms) {
        (void)ahora_ms;
        if (!(p.esperando & E_CFH) || !lote_cfh_) { inesperado(p, "cfheaders"); return; }
        p.esperando &= ~E_CFH;
        CfHeaders c = leer_cfheaders(m.payload);
        H esperado_parar = to_h(alm_.hash_en(cfh_b1_));
        if (c.tipo != 0 || c.parar != esperado_parar || c.hashes.size() != (size_t)(cfh_b1_ - cfh_b0_ + 1)) {
            banear(p, "cfheaders que no corresponde a lo pedido"); return;
        }
        if (cfh_b0_ > desde_ && c.previa != fh_[cfh_b0_ - desde_]) { banear(p, "cfheaders no encadena con lo anterior"); return; }
        if (cfh_b0_ == 0 && c.previa != H{}) { banear(p, "la cabecera previa al génesis no es cero"); return; }
        p.cfh = c; p.cfh_ok = true;
        evaluar_lote_cfh();
    }
    static H to_h(const Hash32 &h) { H r; memcpy(r.data(), h.data(), 32); return r; }

    void evaluar_lote_cfh() {
        std::vector<Par *> ps;
        for (int id : cfh_pares_) { auto &p = pares_.at(id); if (!p.cfh_ok) return; ps.push_back(&p); }
        /* ¿Coinciden la ancla y todos los hashes? */
        size_t n = ps[0]->cfh.hashes.size();
        int64_t primera = -1; bool ancla_igual = true;
        for (size_t k = 1; k < ps.size(); k++) if (ps[k]->cfh.previa != ps[0]->cfh.previa) ancla_igual = false;
        for (size_t i = 0; i < n && primera < 0; i++)
            for (size_t k = 1; k < ps.size(); k++) if (ps[k]->cfh.hashes[i] != ps[0]->cfh.hashes[i]) { primera = (int64_t)i; break; }
        if (!ancla_igual) {
            // Con la ancla distinta no se puede saber quién tiene razón: se sueltan los dos y se sigue con
            // otros pares, en vez de parar todo con un error que no tiene salida.
            for (auto *q : ps) banear(*q, "cabecera de filtro de partida distinta");
            lote_cfh_ = false;
            return;
        }
        if (primera >= 0) { abrir_disputa(cfh_b0_ + (uint32_t)primera, ps); return; }
        /* Todos de acuerdo: se fijan. */
        if (cfh_b0_ == desde_) fh_[0] = ps[0]->cfh.previa;
        for (size_t i = 0; i < n; i++) {
            fhash_.push_back(ps[0]->cfh.hashes[i]);
            fh_.push_back(siguiente_fh(ps[0]->cfh.hashes[i], fh_.back()));
        }
        lotes_cfh_++; if (ps.size() >= 2) lotes_cfh_varios_++;
        sig_cfh_ = cfh_b1_ + 1; lote_cfh_ = false;
        for (auto *p : ps) { p->cfh_ok = false; p->cfh = CfHeaders(); }
    }

    void fallar(const std::string &motivo) { error_ = motivo; fase_ = Fase::Error; log("ERROR: " + motivo); }

    /* ---- disputa: dos pares dan hashes de filtro distintos para la misma altura ---- */
    void abrir_disputa(uint32_t h, const std::vector<Par *> &ps) {
        disp_ = Disputa(); disp_.activa = true; disp_.h = h;
        log("disputa de filtros en la altura " + std::to_string(h));
        uint64_t ahora = ps[0]->t_pend;
        H bh = to_h(alm_.hash_en(h));
        for (auto *p : ps) {
            disp_.pares.push_back(p->id);
            disp_.declarado[p->id] = p->cfh.hashes[h - cfh_b0_];
            PideFiltros q; q.tipo = 0; q.inicio = h; q.parar = bh;
            enviar(*p, "getcfilters", crear_getcf(q)); marcar(*p, E_DCF, ahora);
        }
        disp_.proveedor_bloque = ps[0]->id;
        enviar(*ps[0], "getdata", crear_inv({{INV_BLOQUE | INV_FLAG_TESTIGO, bh}})); marcar(*ps[0], E_DBLK, ahora);
    }
    void resolver_disputa() {
        Disputa &d = disp_;
        if (!d.bloque_ok || d.filtros.size() != d.pares.size()) return;
        std::vector<int> buenos, malos;
        auto spks = scripts_de_salida(d.bloque);
        H bh = to_h(alm_.hash_en(d.h)); uint64_t k0, k1; clave_filtro(bh, k0, k1);
        for (int id : d.pares) {
            auto &f = d.filtros[id];
            bool bien = hash_filtro(f) == d.declarado[id];
            if (bien) try {
                auto cj = gcs::decodificar(f, k0, k1);
                for (auto &s : spks) if (!cj.tiene(s)) { bien = false; break; }
            } catch (const std::exception &) { bien = false; }
            (bien ? buenos : malos).push_back(id);
        }
        d.activa = false; lote_cfh_ = false;
        for (auto &kv : pares_) { kv.second.esperando &= ~(E_CFH | E_DCF | E_DBLK); kv.second.cfh_ok = false; }
        if (buenos.empty() || malos.empty()) {
            // O ninguno es coherente con el bloque, o los dos lo son y no se puede decidir cuál sobra (uno
            // podría llevar elementos de más). Parar con un error no tenía salida: se sueltan los dos
            // pares de la disputa y se sigue con otros.
            log(buenos.empty() ? "disputa de filtros: ninguno es coherente con el bloque"
                               : "disputa de filtros: los dos son coherentes, no se puede decidir");
            for (int id : d.pares) banear(pares_.at(id), "filtros en disputa sin resolver");
            return;
        }
        for (int id : malos) banear(pares_.at(id), "filtro que omite salidas del bloque");
    }

    void paso_cfheaders(uint64_t ahora_ms) {
        uint32_t T = alm_.altura();
        if (sig_cfh_ > T) { fase_ = Fase::Filtros; return; }
        auto l = listos();
        if (l.size() < cfg_.min_pares) { fase_ = Fase::Conectando; return; }
        if (lote_cfh_ || disp_.activa) return;
        cfh_b0_ = sig_cfh_; cfh_b1_ = std::min<uint32_t>(T, cfh_b0_ + 1999);
        cfh_pares_.assign(l.begin(), l.begin() + std::min<size_t>(cfg_.min_pares ? cfg_.min_pares : 1, l.size()));
        PideFiltros q; q.tipo = 0; q.inicio = cfh_b0_; q.parar = to_h(alm_.hash_en(cfh_b1_));
        for (int id : cfh_pares_) {
            Par &p = pares_.at(id); p.cfh_ok = false;
            enviar(p, "getcfheaders", crear_getcf(q)); marcar(p, E_CFH, ahora_ms);
        }
        lote_cfh_ = true;
    }

    /* ---- filtros ---- */
    void paso_filtros(uint64_t ahora_ms) {
        if (sig_cf_ >= sig_cfh_) { fase_ = Fase::Bloques; return; }
        auto l = listos();
        if (l.empty()) { fase_ = Fase::Conectando; return; }
        if (lote_cf_) return;
        Par &d = pares_.at(l[0]);
        cf_b0_ = sig_cf_; cf_b1_ = std::min<uint32_t>(sig_cfh_ - 1, cf_b0_ + cfg_.lote_filtros - 1); cf_n_ = 0; cf_driver_ = d.id;
        PideFiltros q; q.tipo = 0; q.inicio = cf_b0_; q.parar = to_h(alm_.hash_en(cf_b1_));
        enviar(d, "getcfilters", crear_getcf(q)); marcar(d, E_CF, ahora_ms); lote_cf_ = true;
    }
    void en_cfilter(Par &p, const Mensaje &m, uint64_t ahora_ms) {
        Cfilter c = leer_cfilter(m.payload);
        if (disp_.activa && (p.esperando & E_DCF)) {
            if (c.tipo != 0 || c.bloque != to_h(alm_.hash_en(disp_.h))) { banear(p, "cfilter que no corresponde a lo pedido"); return; }
            p.esperando &= ~E_DCF; disp_.filtros[p.id] = c.filtro; resolver_disputa(); return;
        }
        if (p.cf_descartar > 0 && (!(p.esperando & E_CF) || !lote_cf_ || cf_driver_ != p.id ||
                                    c.bloque != to_h(alm_.hash_en(cf_b0_ + cf_n_)))) {
            p.cf_descartar--; return;     // resto de un lote que se abortó
        }
        if (!(p.esperando & E_CF) || !lote_cf_ || cf_driver_ != p.id) { inesperado(p, "cfilter"); return; }
        uint32_t h = cf_b0_ + cf_n_;
        if (c.tipo != 0 || c.bloque != to_h(alm_.hash_en(h))) { banear(p, "cfilter fuera de orden"); return; }
        if (hash_filtro(c.filtro) != fhash_[h - desde_]) { banear(p, "filtro que no da el hash acordado"); return; }
        p.t_pend = ahora_ms;
        uint64_t k0, k1; clave_filtro(c.bloque, k0, k1);
        bool coincide = false;
        try {
            auto cj = gcs::decodificar(c.filtro, k0, k1);
            for (auto &s : scripts_) if (cj.tiene(s)) { coincide = true; break; }
        }
        catch (const std::exception &) { banear(p, "filtro mal formado"); return; }
        if (coincide && std::find(pendientes_.begin(), pendientes_.end(), h) == pendientes_.end()) {
            pendientes_.push_back(h); filtros_pend_[h] = c.filtro;
        }
        cf_n_++;
        if (h == cf_b1_) { sig_cf_ = cf_b1_ + 1; lote_cf_ = false; p.esperando &= ~E_CF; }
    }

    /* ---- bloques ---- */
    void paso_bloques(uint64_t ahora_ms) {
        if (sig_cf_ < sig_cfh_) { fase_ = Fase::Filtros; return; }
        if (pendientes_.empty()) { fase_ = Fase::Listo; return; }
        if (blk_en_vuelo_) return;
        auto l = listos();
        if (l.empty()) { fase_ = Fase::Conectando; return; }
        Par &d = pares_.at(l[0]);
        uint32_t h = pendientes_.front();
        enviar(d, "getdata", crear_inv({{INV_BLOQUE | INV_FLAG_TESTIGO, to_h(alm_.hash_en(h))}}));
        marcar(d, E_BLK, ahora_ms); blk_driver_ = d.id; blk_en_vuelo_ = true;
    }
    void en_bloque(Par &p, const Mensaje &m, uint64_t ahora_ms) {
        (void)ahora_ms;
        bool de_disputa = disp_.activa && (p.esperando & E_DBLK);
        bool normal = blk_en_vuelo_ && (p.esperando & E_BLK) && blk_driver_ == p.id;
        if (!de_disputa && !normal) { inesperado(p, "block"); return; }
        uint32_t h = de_disputa ? disp_.h : pendientes_.front();
        if (m.payload.size() < 80) { banear(p, "bloque corto"); return; }
        Hash32 recibido; hash_cabecera(m.payload.data(), recibido.data());
        if (recibido != alm_.hash_en(h)) { banear(p, "bloque que no es el pedido"); return; }
        std::vector<TxBloque> txs;
        try { txs = leer_bloque(m.payload); } catch (const std::exception &e) { banear(p, std::string("bloque mal formado: ") + e.what()); return; }
        if (!merkle_valido(m.payload, txs)) { banear(p, "raíz de Merkle del bloque no coincide"); return; }
        if (de_disputa) {
            p.esperando &= ~E_DBLK; disp_.bloque = m.payload; disp_.bloque_ok = true; resolver_disputa(); return;
        }
        p.esperando &= ~E_BLK; blk_en_vuelo_ = false;
        /* El filtro tiene que contener todas las salidas del bloque. */
        {
            auto f = filtros_pend_.find(h);
            if (f == filtros_pend_.end()) { fallar("falta el filtro del bloque " + std::to_string(h)); return; }
            uint64_t k0, k1; clave_filtro(to_h(alm_.hash_en(h)), k0, k1);
            try {
                auto cj = gcs::decodificar(f->second, k0, k1);
                for (auto &s : scripts_de_salida(m.payload)) if (!cj.tiene(s)) {
                    fallar("el filtro acordado no contiene todas las salidas del bloque " + std::to_string(h)); return;
                }
            } catch (const std::exception &) { fallar("filtro acordado ilegible en el bloque " + std::to_string(h)); return; }
        }
        aplicar(h, txs);
        descargados_++;
        filtros_pend_.erase(h);
        pendientes_.erase(pendientes_.begin());
    }
    void aplicar(uint32_t h, const std::vector<TxBloque> &txs) {
        Efecto e;
        for (auto &tx : txs) {
            if (Difusion *d = buscar_difusion(tx.txid)) { d->estado = EstadoDifusion::Confirmada; d->altura = h; }
            for (auto &kv : difusiones_) {
                Difusion &d = kv.second;
                if (d.estado == EstadoDifusion::Confirmada || d.estado == EstadoDifusion::Reemplazada || d.txid == tx.txid) continue;
                for (auto &i1 : d.entradas) for (auto &i2 : tx.entradas)
                    if (i1.txid == i2.txid && i1.vout == i2.vout) { d.estado = EstadoDifusion::Reemplazada; d.altura = h; }
            }
            for (auto &in : tx.entradas) {
                auto it = utxos_.find(clave_utxo(in.txid, in.vout));
                if (it != utxos_.end()) {
                    // Si esta moneda se creó en este mismo bloque no existía antes: deshacer el bloque no
                    // debe devolverla (estaba en `anadidos` y en `gastados`, y se resucitaba).
                    auto creada = std::find_if(e.anadidos.begin(), e.anadidos.end(), [&](const Utxo &u) {
                        return u.txid == it->second.txid && u.vout == it->second.vout; });
                    if (creada != e.anadidos.end()) e.anadidos.erase(creada);
                    else e.gastados.push_back(it->second);
                    utxos_.erase(it);
                }
            }
            for (uint32_t k = 0; k < tx.salidas.size(); k++) {
                if (!set_scripts_.count(tx.salidas[k].spk)) continue;
                Utxo u{tx.txid, k, tx.salidas[k].valor, tx.salidas[k].spk, h, tx.coinbase};
                utxos_[clave_utxo(u.txid, u.vout)] = u; e.anadidos.push_back(u);
            }
        }
        efectos_[h] = e;
    }

    /* ---- bucle principal ---- */
    void avanzar(uint64_t ahora_ms) {
        if (cfg_.solo_difusion) { ofrecer_difusiones(ahora_ms); return; }
        for (int vueltas = 0; vueltas < 20 && fase_ != Fase::Error; vueltas++) {
            Fase antes = fase_;
            switch (fase_) {
                case Fase::Conectando:
                    if (listos().size() >= std::max<uint32_t>(1, cfg_.min_pares)) {
                        for (auto &kv : pares_)
                            if (kv.second.listo && !kv.second.muerto && !(kv.second.esperando & E_HEADERS))
                                pedir_headers(kv.second, ahora_ms);
                        fase_ = Fase::Cabeceras;
                    }
                    break;
                case Fase::Cabeceras: {
                    size_t vivos = 0; bool todos = true;
                    for (auto &kv : pares_) if (kv.second.listo && !kv.second.muerto) { vivos++; if (!kv.second.hdr_fin) todos = false; }
                    if (vivos < std::max<uint32_t>(1, cfg_.min_pares)) { fase_ = Fase::Conectando; break; }
                    /* Un par que llegó después también tiene que sincronizar cabeceras. */
                    for (auto &kv : pares_) {
                        Par &q = kv.second;
                        if (q.listo && !q.muerto && !q.hdr_fin && !(q.esperando & E_HEADERS)) pedir_headers(q, ahora_ms);
                    }
                    if (todos) {
                        if (rollback_a_ >= 0) { deshacer((uint32_t)rollback_a_); rollback_a_ = -1; }
                        fase_ = Fase::FiltrosCab;
                    }
                    break;
                }
                case Fase::FiltrosCab: paso_cfheaders(ahora_ms); break;
                case Fase::Filtros: paso_filtros(ahora_ms); break;
                case Fase::Bloques: paso_bloques(ahora_ms); break;
                case Fase::Listo: case Fase::Error: break;
            }
            if (fase_ == antes) break;
        }
        if (fase_ != Fase::Error) ofrecer_difusiones(ahora_ms);
    }
};

}  // namespace btc
