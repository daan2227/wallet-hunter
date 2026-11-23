package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimuladorTest {
    private val eps = 1e-6
    private fun nuevo() = Simulador()
    private val T0 = 1_700_000_000L - 1_700_000_000L % (8 * 3600)   // frontera de funding

    @Test fun liquidacionLong10x() {
        val s = nuevo()
        assertNull(s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, t = T0))
        assertEquals(90.5, s.posiciones[0].liquidacion, eps)
        assertEquals(10_000 - 100 - 0.5, s.fut, eps)                     // margen + taker 0,05 % de 1000
        s.procesar("B", 90.0, 101.0, 95.0, T0 + 60)
        assertTrue(s.posiciones.isEmpty())
        assertEquals(10_000 - 100.5, s.fut, eps)                          // el margen se pierde
        assertEquals("Liquidated", s.historial.last().motivo)
    }

    @Test fun takeProfitLong() {
        val s = nuevo()
        assertNull(s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, tp = 110.0, t = T0))
        s.procesar("B", 99.0, 111.0, 108.0, T0 + 60)
        assertTrue(s.posiciones.isEmpty())
        // +100 de PnL (10 unidades × 10), comisión de salida 110×10×0,05 % = 0,55
        assertEquals(10_000 - 100.5 + 100 + 100 - 0.55, s.fut, eps)
    }

    @Test fun siTocaTpYSlGanaSl() {
        val s = nuevo()
        s.abrirFuturo("B", "", "BTC", false, 100.0, 5, 100.0, tp = 90.0, sl = 105.0, t = T0)
        s.procesar("B", 89.0, 106.0, 100.0, T0 + 60)
        assertEquals("Stop-loss", s.historial.last().motivo)
        assertEquals(105.0, s.historial.last().salida, eps)
    }

    @Test fun slMasAllaDeLiquidacionSeRechaza() {
        val s = nuevo()
        assertTrue(s.abrirFuturo("B", "", "BTC", true, 100.0, 50, 100.0, sl = 97.0) != null)   // liq ≈ 98,5
    }

    @Test fun spotCompraYVenta() {
        val s = nuevo()
        assertNull(s.comprarSpot("E", "", "ETH", 1000.0, 50.0))
        assertEquals(19.98, s.tenencias[0].qty, eps)
        assertNull(s.venderSpot("E", 19.98, 60.0))
        assertTrue(s.tenencias.isEmpty())
        val bruto = 19.98 * 60
        assertEquals(9_000 + bruto * 0.999, s.spot, eps)
        assertEquals(bruto * 0.999 - 1000, s.historial.last().pnl, eps)
    }

    @Test fun limiteSpotVentaYCancelar() {
        val s = nuevo()
        s.comprarSpot("E", "", "ETH", 1000.0, 50.0)
        val q = s.tenencias[0].qty
        assertNull(s.ordenLimite(Simulador.Mercado.SPOT, "E", "", "ETH", false, 70.0, 0.0, q / 2, 1, 0.0, 0.0))
        assertEquals(q / 2, s.tenencias[0].qty, eps)
        s.cancelarOrden(s.ordenes[0].id)
        assertEquals(q, s.tenencias[0].qty, eps)
        assertEquals(1000.0, s.tenencias[0].coste, eps)
    }

    @Test fun limiteSpotVentaSeLlena() {
        val s = nuevo()
        s.comprarSpot("E", "", "ETH", 1000.0, 50.0)
        val q = s.tenencias[0].qty
        s.ordenLimite(Simulador.Mercado.SPOT, "E", "", "ETH", false, 70.0, 0.0, q, 1, 0.0, 0.0)
        s.procesar("E", 60.0, 69.0, 65.0); assertEquals(1, s.ordenes.size)
        s.procesar("E", 66.0, 71.0, 70.5)
        assertTrue(s.ordenes.isEmpty()); assertTrue(s.tenencias.isEmpty())
        assertEquals(9_000 + q * 70 * 0.999, s.spot, eps)
    }

    @Test fun limiteFuturoSeLlenaAlTocar() {
        val s = nuevo()
        assertNull(s.ordenLimite(Simulador.Mercado.FUT, "B", "", "BTC", true, 95.0, 100.0, 0.0, 10, 0.0, 0.0, T0))
        assertEquals(10_000 - 100 - 0.2, s.fut, eps)                      // margen + maker 0,02 %
        s.procesar("B", 96.0, 101.0, 98.0, T0 + 60); assertTrue(s.posiciones.isEmpty())
        s.procesar("B", 94.0, 97.0, 96.0, T0 + 120)
        assertEquals(1, s.posiciones.size); assertEquals(95.0, s.posiciones[0].entrada, eps)
        assertEquals(10_000 - 100.2, s.fut, eps)
    }

    @Test fun fundingCada8h() {
        val s = nuevo()
        s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, t = T0)
        val antes = s.fut
        s.procesar("B", 100.0, 100.0, 100.0, T0 + 8 * 3600 + 5)
        assertEquals(antes - 1000 * 0.0001, s.fut, eps)                   // el long paga
    }

    @Test fun jsonIdaYVuelta() {
        val s = nuevo()
        s.abrirFuturo("B", "{}", "BTC", true, 100.0, 10, 100.0, tp = 120.0, t = T0)
        s.comprarSpot("E", "{}", "ETH", 500.0, 50.0)
        s.ordenLimite(Simulador.Mercado.SPOT, "E", "{}", "ETH", false, 80.0, 0.0, 1.0, 1, 0.0, 0.0)
        val r = Simulador.deJson(s.aJson())
        assertEquals(s.spot, r.spot, eps); assertEquals(s.fut, r.fut, eps); assertEquals(s.netoDepositado, r.netoDepositado, eps); assertEquals(1, r.posiciones.size); assertEquals(120.0, r.posiciones[0].tp, eps)
        assertEquals(1, r.ordenes.size); assertEquals(s.tenencias[0].qty, r.tenencias[0].qty, eps)
        assertEquals(s.patrimonio(), r.patrimonio(), eps)
    }

    @Test fun transferenciaDepositoYRetiro() {
        val s = nuevo()
        assertNull(s.transferir(Simulador.Mercado.SPOT, 2_500.0))
        assertEquals(7_500.0, s.spot, eps); assertEquals(12_500.0, s.fut, eps)
        assertTrue(s.transferir(Simulador.Mercado.SPOT, 9_000.0) != null)        // no hay tanto
        assertNull(s.depositar(Simulador.Mercado.FUT, 1_000.0))
        assertNull(s.retirar(Simulador.Mercado.SPOT, 500.0))
        assertEquals(20_500.0, s.netoDepositado, eps)
        assertEquals(0.0, s.rendimiento(), eps)                                 // mover dinero no es ganar
    }

    @Test fun ordenMinima() {
        val s = nuevo()
        assertTrue(s.comprarSpot("E", "", "ETH", 4.0, 50.0) != null)
        assertTrue(s.abrirFuturo("B", "", "BTC", true, 1.0, 2, 100.0) != null)   // 2 VUSD de nocional
        assertNull(s.abrirFuturo("B", "", "BTC", true, 1.0, 5, 100.0))
    }

    @Test fun anadirMargenAlejaLiquidacion() {
        val s = nuevo()
        s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, t = T0)
        assertEquals(90.5, s.posiciones[0].liquidacion, eps)
        assertNull(s.agregarMargen(s.posiciones[0].id, 100.0))
        assertEquals(80.5, s.posiciones[0].liquidacion, eps)                   // 100 − (200 − 5)/10
        s.procesar("B", 85.0, 100.0, 90.0, T0 + 60)
        assertEquals(1, s.posiciones.size)
    }

    @Test fun cierreParcial() {
        val s = nuevo()
        s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, t = T0)
        val antes = s.fut
        s.cerrarFuturo(s.posiciones[0].id, 110.0, fraccion = 0.5)
        assertEquals(5.0, s.posiciones[0].qty, eps); assertEquals(50.0, s.posiciones[0].margen, eps)
        assertEquals(antes + 50 + 50 - 110 * 5 * 0.0005, s.fut, eps)
        assertEquals(90.5, s.posiciones[0].liquidacion, eps)                   // misma liquidación
    }

    // ── Modo one-way / hedge y margen cruzado ──
    @Test fun oneWayOrdenContrariaReduce() {
        val s = nuevo()
        s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, t = T0)           // 10 BTC long
        s.abrirFuturo("B", "", "BTC", false, 40.0, 10, 110.0, t = T0)           // 400/110 ≈ 3.636 short → reduce
        assertEquals(1, s.posiciones.size); assertTrue(s.posiciones[0].long)
        assertEquals(10 - 400 / 110.0, s.posiciones[0].qty, 1e-9)
    }

    @Test fun oneWayMismoLadoSeSuma() {
        val s = nuevo()
        s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, t = T0)
        s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 120.0, t = T0)
        assertEquals(1, s.posiciones.size)
        val p = s.posiciones[0]
        assertEquals(10 + 1000 / 120.0, p.qty, 1e-9)
        assertEquals(2000 / p.qty, p.entrada, 1e-9)                             // media ponderada
        assertEquals(200.0, p.margen, eps)
    }

    @Test fun hedgeLongYShortJuntos() {
        val s = nuevo()
        assertNull(s.ponerHedge(true))
        s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, t = T0)
        s.abrirFuturo("B", "", "BTC", false, 100.0, 10, 100.0, t = T0)
        assertEquals(2, s.posiciones.size)
        assertTrue(s.ponerHedge(false) != null)                                  // con posiciones no se cambia
    }

    @Test fun cruzadoUsaTodoElSaldo() {
        val s = nuevo()
        s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, t = T0, cruzado = true)
        val p = s.posiciones[0]
        // E = fut (9899.5) + 100; M = 5 → 100 − (9999.5 − 5)/10 < 0: no se puede liquidar
        assertEquals(0.0, s.liquidacion(p), 1e-6)
        s.procesar("B", 85.0, 100.0, 90.0, T0 + 60)                               // aislado se liquidaría (90,5); cruzado no
        assertEquals(1, s.posiciones.size)
    }

    @Test fun liquidacionCruzadaPierdeElSaldo() {
        val s = nuevo()
        s.retirar(Simulador.Mercado.FUT, 9_000.0)
        s.abrirFuturo("B", "", "BTC", true, 500.0, 20, 100.0, t = T0, cruzado = true)   // 100 BTC, fut ≈ 495
        val liq = s.liquidacion(s.posiciones[0])
        s.procesar("B", liq - 0.01, 100.0, liq, T0 + 60)
        assertTrue(s.posiciones.isEmpty()); assertEquals(0.0, s.fut, eps)
    }

    // ── Órdenes avanzadas ──
    @Test fun stopMarketCompraSpot() {
        val s = nuevo(); s.precios["E"] = 50.0
        assertNull(s.nuevaOrden(Simulador.Mercado.SPOT, "E", "", "ETH", true, Simulador.TipoOrden.STOP, 0.0, 55.0, 0.0, 100.0, 0.0, 1, 0.0, 0.0))
        s.procesar("E", 49.0, 54.0, 53.0); assertEquals(1, s.ordenes.size)
        s.procesar("E", 53.0, 56.0, 55.5)
        assertTrue(s.ordenes.isEmpty()); assertEquals(100 / 55.0 * 0.999, s.tenencias[0].qty, 1e-9)
    }

    @Test fun stopDebeEstarDelLadoCorrecto() {
        val s = nuevo(); s.precios["E"] = 50.0
        assertTrue(s.nuevaOrden(Simulador.Mercado.SPOT, "E", "", "ETH", true, Simulador.TipoOrden.STOP, 0.0, 45.0, 0.0, 100.0, 0.0, 1, 0.0, 0.0) != null)
    }

    @Test fun stopLimitSeConvierteEnLimite() {
        val s = nuevo(); s.precios["E"] = 50.0
        s.nuevaOrden(Simulador.Mercado.SPOT, "E", "", "ETH", true, Simulador.TipoOrden.STOP_LIMIT, 56.0, 55.0, 0.0, 100.0, 0.0, 1, 0.0, 0.0)
        s.procesar("E", 54.0, 55.5, 55.4)
        assertEquals(Simulador.TipoOrden.LIMIT, s.ordenes[0].tipo)
        s.procesar("E", 55.9, 57.0, 56.5)
        assertTrue(s.ordenes.isEmpty())
    }

    @Test fun trailingStopVentaSigueAlMaximo() {
        val s = nuevo(); s.comprarSpot("E", "", "ETH", 1000.0, 50.0); s.precios["E"] = 50.0
        val q = s.tenencias[0].qty
        assertNull(s.nuevaOrden(Simulador.Mercado.SPOT, "E", "", "ETH", false, Simulador.TipoOrden.TRAIL, 0.0, 0.0, 0.05, 0.0, q, 1, 0.0, 0.0))
        s.procesar("E", 58.0, 60.0, 60.0)                                         // máximo 60 → disparo 57
        assertEquals(60.0, s.ordenes[0].extremo, eps)
        s.procesar("E", 56.9, 59.0, 57.0)
        assertTrue(s.ordenes.isEmpty())
        assertEquals(57.0, s.historial.last().salida, eps)
    }

    @Test fun ocoSpotUnaCancelaLaOtra() {
        val s = nuevo(); s.comprarSpot("E", "", "ETH", 1000.0, 50.0); s.precios["E"] = 50.0
        val q = s.tenencias[0].qty
        assertNull(s.ocoSpot("E", q, 60.0, 45.0))
        assertEquals(2, s.ordenes.size)
        s.procesar("E", 44.0, 50.0, 46.0)                                         // toca el SL
        assertTrue(s.ordenes.isEmpty()); assertTrue(s.tenencias.isEmpty())
        assertEquals(9_000 + q * 45 * 0.999, s.spot, eps)
    }

    @Test fun ocoCancelarDevuelveTodo() {
        val s = nuevo(); s.comprarSpot("E", "", "ETH", 1000.0, 50.0); s.precios["E"] = 50.0
        val q = s.tenencias[0].qty
        s.ocoSpot("E", q, 60.0, 45.0)
        s.cancelarOrden(s.ordenes[1].id)
        assertTrue(s.ordenes.isEmpty()); assertEquals(q, s.tenencias[0].qty, eps); assertEquals(1000.0, s.tenencias[0].coste, eps)
    }

    @Test fun reduceOnlyCierraSinAbrir() {
        val s = nuevo(); s.precios["B"] = 100.0
        s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, t = T0)
        assertNull(s.nuevaOrden(Simulador.Mercado.FUT, "B", "", "BTC", false, Simulador.TipoOrden.LIMIT, 110.0, 0.0, 0.0, 0.0, 4.0, 10, 0.0, 0.0, reduce = true, t = T0))
        s.procesar("B", 105.0, 111.0, 110.0, T0 + 60)
        assertEquals(1, s.posiciones.size); assertTrue(s.posiciones[0].long); assertEquals(6.0, s.posiciones[0].qty, eps)
    }

    // ── Earn, bots y convert ──
    @Test fun earnAbonaCadaDia() {
        val s = nuevo(); val dia = 20_000L * 86_400
        assertNull(s.suscribirEarn(1_000.0, dia + 100))
        s.devengar(dia + 2 * 86_400 + 5)
        assertEquals(1000 * Math.pow(1 + 0.05 / 365, 2.0), s.earn, 1e-9)
        assertNull(s.redimirEarn(s.earn, dia + 2 * 86_400 + 10))
        assertEquals(0.0, s.earn, eps)
    }

    @Test fun dcaCompraPeriodicamente() {
        val s = nuevo()
        assertNull(s.crearDca("E", "", "ETH", 100.0, 3600, T0))
        s.procesar("E", 50.0, 50.0, 50.0, T0 + 10)
        s.procesar("E", 50.0, 50.0, 50.0, T0 + 20)                                // aún no toca
        s.procesar("E", 40.0, 40.0, 40.0, T0 + 3600 + 5)
        assertEquals(2, s.bots[0].compras)
        assertEquals(10_000 - 200.0, s.spot, eps)
    }

    @Test fun gridCompraAbajoYVendeArriba() {
        val s = nuevo()
        assertNull(s.crearGrid("E", "", "ETH", 90.0, 110.0, 4, 400.0, 100.0))       // niveles 90 95 100 105 110
        val b = s.bots[0]
        assertEquals(2, b.conBase.count { it })                                    // celdas 100–105 y 105–110 compradas
        s.procesar("E", 94.0, 100.0, 96.0)                                         // compra en 95 (y 90 no)
        assertEquals(3, b.conBase.count { it })
        s.procesar("E", 96.0, 101.0, 100.5)                                        // vende la celda 95–100
        assertTrue(b.beneficio > 0)
        val antes = s.spot
        s.pararBot(b.id, 100.0)
        assertTrue(s.bots.isEmpty()); assertTrue(s.spot > antes)
    }

    @Test fun convertCriptoACripto() {
        val s = nuevo()
        s.comprarSpot("E", "", "ETH", 1000.0, 50.0)
        val q = s.tenencias[0].qty
        assertNull(s.convertir("E", q, 50.0, Triple("B", "", "BTC"), 100.0))
        assertTrue(s.tenencias.none { it.clave == "E" })
        assertEquals(q * 50 * 0.998 / 100, s.tenencias.first { it.clave == "B" }.qty, 1e-9)
        assertNull(s.convertir(null, 100.0, 0.0, Triple("B", "", "BTC"), 100.0))
        assertTrue(s.convertir("B", 1e-6, 100.0, null, 0.0) != null)              // menos del mínimo
    }

    @Test fun jsonConTodo() {
        val s = nuevo(); s.precios["E"] = 50.0
        s.comprarSpot("E", "{}", "ETH", 1000.0, 50.0)
        s.ocoSpot("E", s.tenencias[0].qty / 2, 60.0, 45.0)
        s.crearGrid("E", "{}", "ETH", 40.0, 60.0, 5, 500.0, 50.0)
        s.suscribirEarn(100.0)
        s.ponerHedge(true)
        s.abrirFuturo("B", "{}", "BTC", true, 100.0, 10, 100.0, cruzado = true)
        val r = Simulador.deJson(s.aJson())
        assertEquals(s.patrimonio(), r.patrimonio(), 1e-6)
        assertEquals(2, r.ordenes.size); assertEquals(r.ordenes[0].grupo, r.ordenes[1].grupo)
        assertTrue(r.hedge); assertTrue(r.posiciones[0].cruzado); assertEquals(1, r.bots.size)
        assertEquals(s.bots[0].conBase.toList(), r.bots[0].conBase.toList())
    }

    // ── Bot IA ──
    private fun conBotIA(apalMax: Int = 3): Simulador = nuevo().apply {
        assertNull(crearBotIA("B", "", "BTC", "1h", 100.0, apalMax, 60, T0))
    }

    @Test fun botIAAbreConPlanYCuentaElTP() {
        val s = conBotIA(); val b = s.bots[0]
        assertTrue(s.senalIA(b.id, 1, 70, 95.0, 110.0, 5, 100.0, T0, T0))
        val p = s.posiciones.single()
        assertTrue(p.long); assertEquals(3, p.apal); assertEquals(95.0, p.sl, eps); assertEquals(110.0, p.tp, eps)
        assertEquals(p.id, b.posId); assertEquals("AI bot", s.historial.last().motivo)
        assertTrue(!s.senalIA(b.id, -1, 90, 0.0, 0.0, 1, 100.0, T0, T0 + 10))       // misma vela: no decide otra vez
        s.procesar("B", 99.0, 111.0, 109.0, T0 + 60)
        assertTrue(s.posiciones.isEmpty())
        assertEquals(1, b.operaciones); assertEquals(1, b.ganadas); assertEquals(0L, b.posId)
        assertEquals(s.historial.last().pnl, b.beneficio, eps); assertTrue(b.beneficio > 0)
    }

    @Test fun botIAGiraConSenalContraria() {
        val s = conBotIA(); val b = s.bots[0]
        s.senalIA(b.id, 1, 70, 95.0, 110.0, 3, 100.0, T0, T0)
        s.senalIA(b.id, -1, 75, 104.0, 92.0, 3, 98.0, T0 + 3600, T0 + 3600)
        val p = s.posiciones.single()
        assertTrue(!p.long); assertEquals(p.id, b.posId)
        assertEquals(1, b.operaciones); assertEquals(0, b.ganadas); assertTrue(b.beneficio < 0)
    }

    @Test fun botIARespetaUmbralYPosicionesDelUsuario() {
        val s = conBotIA(); val b = s.bots[0]
        s.senalIA(b.id, 1, 50, 95.0, 110.0, 3, 100.0, T0, T0)                          // por debajo del 60 %
        assertTrue(s.posiciones.isEmpty())
        assertNull(s.abrirFuturo("B", "", "BTC", false, 50.0, 2, 100.0, t = T0))
        s.senalIA(b.id, 1, 80, 95.0, 110.0, 3, 100.0, T0 + 3600, T0 + 3600)
        assertEquals(1, s.posiciones.size); assertTrue(!s.posiciones[0].long)          // no toca la del usuario
        assertEquals(0L, b.posId)
    }

    @Test fun botIABajaApalancamientoSiElStopQuedaTrasLaLiquidacion() {
        val s = conBotIA(apalMax = 20); val b = s.bots[0]
        s.senalIA(b.id, 1, 80, 94.0, 110.0, 20, 100.0, T0, T0)
        assertEquals(15, s.posiciones.single().apal)
    }

    @Test fun botIAPararCierraYSeGuarda() {
        val s = conBotIA(); val b = s.bots[0]
        s.senalIA(b.id, 1, 70, 95.0, 110.0, 3, 100.0, T0, T0)
        val r = Simulador.deJson(s.aJson()); val rb = r.bots.single()
        assertEquals("IA", rb.tipo); assertEquals("1h", rb.iv); assertEquals(60, rb.umbral); assertEquals(3, rb.apalMax)
        assertEquals(b.posId, rb.posId); assertEquals(T0, rb.ultVela)
        r.pararBot(rb.id, 102.0, T0 + 60)
        assertTrue(r.bots.isEmpty()); assertTrue(r.posiciones.isEmpty())
        assertEquals("AI bot stopped", r.historial.last().motivo)
        assertTrue(s.crearBotIA("B", "", "BTC", "1h", 100.0, 3, 60, T0) != null)        // uno por par
    }
}
