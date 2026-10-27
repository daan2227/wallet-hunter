package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovimientosTest {
    private val js = """{"address":"x","chain_stats":{"funded_txo_count":2,"funded_txo_sum":150000,"spent_txo_count":1,"spent_txo_sum":50000,"tx_count":3},
        "mempool_stats":{"funded_txo_count":1,"funded_txo_sum":20000,"spent_txo_count":0,"spent_txo_sum":0,"tx_count":1}}"""

    @Test fun leeEsplora() { assertEquals(Movimientos.Estado(4, 120000), Movimientos.deEsplora(js)) }

    @Test fun comparar() {
        val a = Movimientos.Estado(3, 100000)
        assertNull(Movimientos.comparar(null, a))                       // primera vez: sólo se apunta
        assertNull(Movimientos.comparar(a, a))
        assertEquals(Movimientos.Cambio.Entra(5000), Movimientos.comparar(a, Movimientos.Estado(4, 105000)))
        assertEquals(Movimientos.Cambio.Sale(100000), Movimientos.comparar(a, Movimientos.Estado(4, 0)))
        assertEquals(Movimientos.Cambio.Otro, Movimientos.comparar(a, Movimientos.Estado(5, 100000)))
        // Una pendiente que desaparece (reemplazada): baja el saldo sin más transacciones.
        assertEquals(Movimientos.Cambio.Sale(20000), Movimientos.comparar(Movimientos.Estado(4, 120000), Movimientos.Estado(3, 100000)))
        assertEquals("4|120000", Movimientos.Estado(4, 120000).texto())
        assertEquals(Movimientos.Estado(4, 120000), Movimientos.Estado.de("4|120000"))
    }
}
