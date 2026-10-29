package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploradorTest {
    private fun cerca(a: Double, b: Double) = assertEquals(a, b, 1e-9)

    @Test fun proyectarLlenaBloquesDeMayorAMenorTasa() {
        // 1,5 MvB a 50, 0,8 MvB a 20, 0,3 MvB a 5 → 2,6 MvB: dos bloques llenos y uno a medias.
        val m = Explorador.leerMempool("""{"count":9000,"vsize":2600000,"total_fee":123456,
            "fee_histogram":[[20.0,800000],[50.0,1500000],[5.0,300000]]}""")!!
        assertEquals(9000L, m.txs)
        val b = Explorador.proyectar(m.histograma)
        assertEquals(3, b.size)
        assertEquals(1_000_000L, b[0].vsize); cerca(50.0, b[0].minima); cerca(50.0, b[0].maxima)
        assertEquals(1_000_000L, b[1].vsize); cerca(20.0, b[1].minima); cerca(50.0, b[1].maxima); cerca(50.0, b[1].mediana)   // mitad y mitad: la mediana es donde se llega a la mitad
        assertEquals(600_000L, b[2].vsize); cerca(5.0, b[2].minima); cerca(20.0, b[2].maxima)
        // Dónde entra una transacción: a 60 en el siguiente; a 30 en el segundo; a 1 en el tercero (no está lleno).
        assertEquals(0, Explorador.posicion(60.0, b)); assertEquals(1, Explorador.posicion(30.0, b)); assertEquals(2, Explorador.posicion(1.0, b))
        // Con todo lleno y la tasa por debajo de todo, queda detrás de lo proyectado.
        val llenos = Explorador.proyectar(listOf(10.0 to 5_000_000L), 3)
        assertEquals(3, llenos.size); assertNull(Explorador.posicion(2.0, llenos))
        assertEquals(0, Explorador.posicion(1.0, emptyList()))
    }

    @Test fun halving() {
        val h = Explorador.halving(840_000)
        assertEquals(1_050_000L, h.siguiente); assertEquals(210_000L, h.faltan)
        assertEquals(312_500_000L, h.subvencionAhora); assertEquals(156_250_000L, h.subvencionDespues)
        assertEquals(1L, Explorador.halving(839_999).faltan); assertEquals(625_000_000L, Explorador.halving(839_999).subvencionAhora)
        assertEquals(0L, Explorador.halving(64L * 210_000).subvencionAhora)
    }

    @Test fun ajusteDeDificultad() {
        // 1008 bloques del periodo en 1008 × 540 s (9 min): +11,1 %, faltan 1008.
        val ini = Explorador.inicioPeriodo(866_880 + 1008)
        assertEquals(866_880L, ini)
        val a = Explorador.ajuste(866_880 + 1008, 1_000_000, 1_000_000 + 1008L * 540)!!
        assertEquals(1008L, a.bloquesHechos); assertEquals(1008L, a.faltan); cerca(540.0, a.mediaSeg)
        assertEquals(0.1111, a.cambio, 1e-3); cerca(1008 * 540.0, a.segundosHasta)
        // Topes del protocolo y periodo recién empezado.
        assertEquals(3.0, Explorador.ajuste(866_881, 0, 1)!!.cambio, 0.0)
        assertNull(Explorador.ajuste(866_880, 0, 100))
    }

    @Test fun transaccionBloqueYDireccion() {
        val tx = """{"txid":"ab","version":2,"locktime":0,"vin":[{"txid":"x","vout":0,"sequence":4294967293,"prevout":{"value":50000}}],
            "vout":[{"value":30000},{"value":19000}],"size":222,"weight":561,"fee":1000,
            "status":{"confirmed":true,"block_height":850000,"block_time":1720000000}}"""
        val r = Explorador.leerTx(tx, 850_005)!!
        assertEquals(6L, r.confirmaciones); assertEquals(141L, r.vsize); assertTrue(r.rbf)
        assertEquals(49_000L, r.totalSalidas); assertEquals(1000.0 / 141, r.tasa, 1e-9)
        val pendiente = Explorador.leerTx(tx.replace("\"confirmed\":true", "\"confirmed\":false").replace("4294967293", "4294967295"), 850_005)!!
        assertEquals(0L, pendiente.confirmaciones); assertNull(pendiente.altura); assertTrue(!pendiente.rbf)
        val bs = Explorador.leerBloques("""[{"id":"0000ab","height":850000,"timestamp":1720000000,"tx_count":3000,"size":1500000,"weight":3990000}]""")
        assertEquals(850_000L, bs[0].altura); assertEquals(3000L, bs[0].txs)
        val s = Explorador.leerDireccion("""{"chain_stats":{"funded_txo_sum":100000,"spent_txo_sum":40000,"tx_count":3},
            "mempool_stats":{"funded_txo_sum":5000,"spent_txo_sum":0,"tx_count":1}}""")!!
        assertEquals(60_000L, s.confirmado); assertEquals(5_000L, s.sinConfirmar); assertEquals(4L, s.txs)
    }

    @Test fun queSeBusca() {
        assertEquals(Explorador.Busqueda.BloqueAltura(840000), Explorador.que("840000"))
        assertTrue(Explorador.que("00000000000000000002a7c4c1e48d76c5a37902165a270156b7a8d72728a054") is Explorador.Busqueda.BloqueHash)
        assertTrue(Explorador.que("4A5E1E4BAAB89F3A32518A88C31BC87F618F76673E2CC77AB2127B7AFDEDA33B") is Explorador.Busqueda.Tx)
        assertTrue(Explorador.que("bc1q9vza2e8x573nczrlzms0wvx3gsqjx7vavgkx0l") is Explorador.Busqueda.Direccion)
        assertNull(Explorador.que("hola"))
    }
}
