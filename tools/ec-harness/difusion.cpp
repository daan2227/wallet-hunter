/* Difusión de transacciones propias por P2P: se anuncia (inv) a unos pocos pares, sólo
 * se entrega a quien la pide, se ve que se propaga porque otro par con relay la anuncia,
 * y se sigue hasta que un bloque la confirma o la reemplaza. Y lo que no puede pasar:
 * transacciones mal formadas, entregar a quien no se le ofreció, dar por propagada una
 * transacción por lo que diga un par que la recibió de nosotros. */
#include "sim_red.h"

static Bytes S1, S2, X;

static std::shared_ptr<Cadena_> hacer_honesta() {
    auto h = std::make_shared<Cadena_>();
    for (int k = 0; k <= 30; k++) {
        std::vector<TxSim> txs; Bytes cb = {0x51};
        if (k == 5) { TxSim a; a.ins = {{h->txid(1, 0), 0}}; a.outs = {{50000, S1}, {70000, X}}; txs.push_back(a); }
        if (k == 9) { TxSim b; b.ins = {{h->txid(2, 0), 0}}; b.outs = {{30000, S2}}; b.testigo = true; txs.push_back(b); }
        h->agregar(txs, cb);
    }
    return h;
}

struct Mundo {
    std::shared_ptr<Cadena_> ch;
    Almacen alm; Cliente c; std::vector<std::unique_ptr<Nodo>> nodos; Red red;
    Mundo(std::shared_ptr<Cadena_> cad, uint32_t min_pares = 1)
        : ch(cad), alm(PR, cad->b[0].cab.data(), 50, 200), c(cfgm(min_pares), alm, {S1, S2}), red(c) {}
    static ConfigCliente cfgm(uint32_t mp) { ConfigCliente k; k.min_pares = mp; k.nonce = 999; k.lote_filtros = 7; return k; }
    Nodo *nodo(int id, bool relay = false) {
        nodos.emplace_back(new Nodo()); Nodo *n = nodos.back().get(); n->ch = ch; n->nonce = 1000 + id;
        red.conectar(id, n, relay); return n;
    }
    void cambiar_cadena(std::shared_ptr<Cadena_> nueva) { ch = nueva; for (auto &n : nodos) n->ch = nueva; }
};

int main() {
    S1 = script(0x11); S2 = script(0x22); X = script(0x99);
    auto honesta = hacer_honesta();

    /* La transacción propia: gasta la moneda de 30000 de la altura 9 (txid 1, salida 0). */
    TxSim mia; mia.ins = {{honesta->txid(9, 1), 0}}; mia.outs = {{29000, X}}; mia.testigo = true;
    H id_mia; Bytes raw = ser_tx(mia, false, 0, true, &id_mia);

    /* --- recorrido completo --- */
    {
        Mundo w(honesta);
        Nodo *a = w.nodo(1), *b = w.nodo(2), *c3 = w.nodo(3), *mon = w.nodo(4, true);
        w.red.bombear();
        ok(w.c.fase() == Fase::Listo && w.c.saldo() == 80000, "antes de difundir: saldo = 50000 + 30000");
        H id = w.c.difundir(raw, AHORA_MS);
        ok(id == id_mia, "difundir devuelve el txid");
        ok(w.c.estado_difusion(id) == EstadoDifusion::Pendiente, "recién difundida: pendiente hasta que un par la pida");
        w.red.bombear();
        ok(w.c.estado_difusion(id) == EstadoDifusion::Propagada, "propagada: el par con relay la ve circular");
        ok(a->txs_recibidos + b->txs_recibidos + c3->txs_recibidos == 3, "los tres pares normales la recibieron una vez");
        ok(mon->invs_recibidos == 0, "al par con relay no se le ofreció (para que su aviso valga)");

        /* Un bloque la confirma. */
        auto mas = std::make_shared<Cadena_>(*honesta); mas->agregar({mia});
        w.cambiar_cadena(mas);
        w.c.actualizar(AHORA_MS); w.red.bombear();
        ok(w.c.estado_difusion(id) == EstadoDifusion::Confirmada && w.c.altura_difusion(id) == 31, "confirmada en la altura 31");
        ok(w.c.saldo() == 50000, "y la moneda gastada ya no cuenta en el saldo");
    }

    /* --- transacciones inválidas --- */
    {
        Mundo w(honesta); w.nodo(1); w.red.bombear();
        auto lanza = [&](const Bytes &b) { try { w.c.difundir(b, AHORA_MS); } catch (const std::exception &) { return true; } return false; };
        ok(lanza(Bytes{1, 2, 3}), "basura: rechazada");
        Bytes corta = raw; corta.resize(corta.size() - 5);
        ok(lanza(corta), "transacción cortada: rechazada");
        Bytes larga = raw; larga.push_back(0);
        ok(lanza(larga), "bytes sobrantes: rechazados");
        TxSim cb; cb.outs = {{1, X}};
        Bytes rcb = ser_tx(cb, true, 5, false, nullptr);
        ok(lanza(rcb), "una coinbase no se difunde");
        ok(lanza(Bytes(400001, 0)), "más de 400 kB: rechazada");
        ok(w.c.estado_difusion(H{}) == EstadoDifusion::Desconocida, "txid desconocido: Desconocida");
    }

    /* --- idempotencia y límite de pares --- */
    {
        Mundo w(honesta);
        std::vector<Nodo *> ns; for (int i = 1; i <= 10; i++) ns.push_back(w.nodo(i));
        w.red.bombear();
        w.c.difundir(raw, AHORA_MS); w.c.difundir(raw, AHORA_MS); w.c.difundir(raw, AHORA_MS);
        w.red.bombear();
        int con_inv = 0, repetidos = 0; for (auto *n : ns) { if (n->invs_recibidos > 0) con_inv++; if (n->invs_recibidos > 1) repetidos++; }
        ok(con_inv == 8 && repetidos == 0, "tres llamadas iguales: 8 de 10 pares reciben un solo aviso cada uno");

        /* Los otros dos piden la transacción sin que se les haya ofrecido: no se les da. */
        int sin_aviso = 0; for (int i = 0; i < 10; i++) if (ns[i]->invs_recibidos == 0) sin_aviso++;
        ok(sin_aviso == 2, "dos pares sin aviso");
        for (int i = 0; i < 10; i++) if (ns[i]->invs_recibidos == 0) {
            auto gd = serializar(MAGIC_PRINCIPAL, "getdata", crear_inv({{INV_TX | INV_FLAG_TESTIGO, id_mia}}));
            w.c.recibido(i + 1, gd.data(), gd.size(), AHORA_MS);
        }
        w.red.bombear();
        int extra = 0; for (auto *n : ns) extra += n->txs_recibidos; 
        ok(extra == 8, "a quien no se le ofreció no se le entrega (8 entregas en total)");
    }

    /* --- nadie la pide --- */
    {
        Mundo w(honesta); Nodo *a = w.nodo(1); a->ignora_inv = true; w.red.bombear();
        H id = w.c.difundir(raw, AHORA_MS); w.red.bombear();
        ok(w.c.estado_difusion(id) == EstadoDifusion::Pendiente, "ningún par la pide: sigue pendiente (no se da por enviada)");
    }

    /* --- la piden pero nadie la retransmite --- */
    {
        Mundo w(honesta); Nodo *a = w.nodo(1); a->rechaza_tx = true; w.nodo(2, true); w.red.bombear();
        H id = w.c.difundir(raw, AHORA_MS); w.red.bombear();
        ok(w.c.estado_difusion(id) == EstadoDifusion::Enviada, "se entregó pero nadie la retransmite: queda en Enviada, no en Propagada");
    }

    /* --- un par sin relay no puede dar por propagada la transacción --- */
    {
        Mundo w(honesta); w.nodo(1); Nodo *b = w.nodo(2); b->ignora_inv = true; w.red.bombear();
        H id = w.c.difundir(raw, AHORA_MS); w.red.bombear();
        auto inv = serializar(MAGIC_PRINCIPAL, "inv", crear_inv({{INV_TX, id}}));
        w.c.recibido(2, inv.data(), inv.size(), AHORA_MS);
        ok(w.c.estado_difusion(id) != EstadoDifusion::Propagada, "el aviso de un par sin relay no cuenta como propagación");
    }

    /* --- un par con relay que anuncia otra cosa --- */
    {
        Mundo w(honesta); w.nodo(1)->rechaza_tx = true; w.nodo(2, true); w.red.bombear();
        H id = w.c.difundir(raw, AHORA_MS); w.red.bombear();
        H otro = id; otro[0] ^= 1;
        auto inv = serializar(MAGIC_PRINCIPAL, "inv", crear_inv({{INV_TX, otro}}));
        w.c.recibido(2, inv.data(), inv.size(), AHORA_MS);
        ok(w.c.estado_difusion(id) == EstadoDifusion::Enviada, "el aviso de otra transacción no confunde");
    }

    /* --- reemplazada por otra que gasta lo mismo --- */
    {
        Mundo w(honesta); w.nodo(1); w.red.bombear();
        H id = w.c.difundir(raw, AHORA_MS); w.red.bombear();
        TxSim rival; rival.ins = {{honesta->txid(9, 1), 0}}; rival.outs = {{25000, X}};
        auto mas = std::make_shared<Cadena_>(*honesta); mas->agregar({rival});
        w.cambiar_cadena(mas); w.c.actualizar(AHORA_MS); w.red.bombear();
        ok(w.c.estado_difusion(id) == EstadoDifusion::Reemplazada, "un bloque gasta lo mismo con otra transacción: Reemplazada");
        ok(w.c.altura_difusion(id) == 31, "...en la altura 31");
    }

    /* --- una reorganización que quita el bloque de la confirmación --- */
    {
        Mundo w(honesta); w.nodo(1); w.nodo(2); w.red.bombear();
        H id = w.c.difundir(raw, AHORA_MS); w.red.bombear();
        auto con = std::make_shared<Cadena_>(*honesta); con->agregar({mia});
        w.cambiar_cadena(con); w.c.actualizar(AHORA_MS); w.red.bombear();
        ok(w.c.estado_difusion(id) == EstadoDifusion::Confirmada, "preparación: confirmada");
        auto sin = std::make_shared<Cadena_>(honesta->hasta(30)); sin->sal = 55; sin->agregar({}); sin->agregar({});
        w.cambiar_cadena(sin); w.c.actualizar(AHORA_MS); w.red.bombear();
        ok(w.c.estado_difusion(id) == EstadoDifusion::Enviada && w.c.altura_difusion(id) == 0, "la reorganización la saca del bloque: vuelve a Enviada");
        ok(w.c.saldo() == 80000, "y la moneda gastada vuelve al saldo");
    }

    /* --- difundir antes de tener pares --- */
    {
        Mundo w(honesta);
        H id = w.c.difundir(raw, AHORA_MS);
        ok(w.c.estado_difusion(id) == EstadoDifusion::Pendiente, "sin pares: pendiente");
        Nodo *a = w.nodo(1); w.red.bombear();
        ok(a->invs_recibidos == 1 && w.c.estado_difusion(id) == EstadoDifusion::Enviada, "cuando llega un par, se le ofrece y la pide");
    }

    printf(fallos ? "\n*** difusion FALLA ***\n" : "\nTODO CORRECTO\n");
    return fallos ? 1 : 0;
}
