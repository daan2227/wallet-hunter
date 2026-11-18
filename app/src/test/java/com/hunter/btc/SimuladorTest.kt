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
}
