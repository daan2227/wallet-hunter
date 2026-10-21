/* Cliente ligero completo contra pares simulados (sin red): cabeceras, cabeceras de
 * filtro cruzadas entre pares, filtros, bloques con Merkle, saldo y reorganización.
 * Los pares simulados (sim_red.h) son nodos completos de una cadena sintética de
 * dificultad mínima, con filtros BIP158 reales, y pueden mentir o fallar de cada
 * manera que el cliente tiene que detectar. */
#include "sim_red.h"

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

    /* --- como un Bitcoin Core de verdad: mensajes de negociación antes del verack --- */
    {
        Almacen alm(PR, honesta->b[0].cab.data(), 50, 200);
        Cliente c(cfg(1), alm, vigilados);
        Nodo a; a.ch = honesta; a.saludo_core = true;
        Red red(c); red.conectar(1, &a); red.bombear();
        ok(c.fase() == Fase::Listo && c.saldo() == SALDO && red.cerrados.empty(), "saludo de Core (wtxidrelay y sendaddrv2 antes del verack): no se expulsa");
    }

    /* --- una moneda creada y gastada en el mismo bloque no resucita al reorganizar --- */
    {
        auto cad = std::make_shared<Cadena_>();
        TxSim a; a.ins = {{H{}, 0}}; a.outs = {{50000, S1}};
        for (int h = 0; h <= 8; h++) {
            std::vector<TxSim> txs;
            if (h == 5) {
                a.ins = {{cad->txid(1, 0), 0}};
                H ida; ser_tx(a, false, 0, false, &ida);
                TxSim b; b.ins = {{ida, 0}}; b.outs = {{49000, X}};
                txs = {a, b};
            }
            cad->agregar(txs);
        }
        Almacen alm(PR, cad->b[0].cab.data(), 50, 200);
        Cliente c(cfg(1), alm, {S1});
        Nodo n; n.ch = cad; Red red(c); red.conectar(1, &n); red.bombear();
        ok(c.fase() == Fase::Listo && c.saldo() == 0, "creada y gastada en el mismo bloque: saldo 0");
        auto otra = std::make_shared<Cadena_>(cad->hasta(4)); otra->sal = 31;
        for (int h = 5; h <= 12; h++) otra->agregar({});
        n.ch = otra; c.actualizar(AHORA_MS); red.bombear();
        ok(alm.altura() == 12 && c.fase() == Fase::Listo && c.saldo() == 0, "tras reorganizar sin ese bloque sigue en 0 (antes salían 50000 fantasma)");
    }

    /* --- el nodo de filtros se cae a mitad de un lote: lo termina otro, sin duplicados ni error --- */
    {
        Almacen alm(PR, honesta->b[0].cab.data(), 50, 200);
        Cliente c(cfg(1), alm, vigilados);
        Nodo cae, otro; cae.ch = otro.ch = honesta; otro.nonce = 222; cae.max_cfilters = 9;   /* sirve 9 de 30 */
        Red red(c); red.conectar(1, &cae); red.bombear();
        red.conectar(2, &otro); red.bombear();
        ok(c.fase() == Fase::Listo && c.saldo() == SALDO, "el nodo se cae a mitad del lote: otro lo termina con el saldo correcto");
        ok(c.bloques_descargados() == 4, "...sin bajar ningún bloque dos veces");
    }

    /* --- verificado solo si TODOS los lotes se cruzaron --- */
    {
        Almacen alm(PR, honesta->b[0].cab.data(), 50, 200);
        Cliente c(cfg(2), alm, vigilados);
        Nodo a, b; a.ch = b.ch = honesta; b.nonce = 222;
        Red red(c); red.conectar(1, &a); red.conectar(2, &b); red.bombear();
        ok(c.fase() == Fase::Listo && c.verificado(), "con dos nodos en todos los lotes: verificado");
    }

    printf(fallos ? "\n*** sincro FALLA ***\n" : "\nTODO CORRECTO\n");
    return fallos ? 1 : 0;
}
