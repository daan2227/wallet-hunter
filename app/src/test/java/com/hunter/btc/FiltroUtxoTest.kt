package com.hunter.btc

import org.junit.Assert.*
import org.junit.Test

class FiltroUtxoTest {
    @Test fun sinFiltroPasaTodo() {
        val f = FiltroUtxo()
        assertTrue(f.numerico(1, 0, false, 0, 1)); assertFalse(f.pideScript)
        assertTrue(f.textual("P2PKH", "1abc", "", "", "", "ab", "2020-01-01"))
        assertEquals("no filter (every coin)", f.resumen())
    }
    @Test fun numericos() {
        val f = FiltroUtxo(minSat = 100, maxSat = 1000, alturaMin = 10, alturaMax = 20, voutMax = 3, confMin = 5, coinbase = 1)
        assertTrue(f.numerico(500, 15, true, 2, 6))
        assertFalse(f.numerico(99, 15, true, 2, 6)); assertFalse(f.numerico(1001, 15, true, 2, 6))
        assertFalse(f.numerico(500, 9, true, 2, 6)); assertFalse(f.numerico(500, 21, true, 2, 6))
        assertFalse(f.numerico(500, 15, true, 4, 6)); assertFalse(f.numerico(500, 15, true, 2, 4))
        assertFalse(f.numerico(500, 15, false, 2, 6))      // sólo minadas
        assertTrue(FiltroUtxo(coinbase = 2).numerico(1, 1, false, 0, 1)); assertFalse(FiltroUtxo(coinbase = 2).numerico(1, 1, true, 0, 1))
    }
    @Test fun textuales() {
        val f = FiltroUtxo(tipos = setOf("P2TR", "P2PK"), clave = 1, prefijoClave = "04", direccionContiene = "ABC",
            txidEmpieza = "ab", hashEmpieza = "1f", scriptContiene = "ac", fechaDesde = "2020-01-01", fechaHasta = "2020-12-31").normalizado()
        assertTrue(f.textual("P2PK", "1xAbCz", "04ff", "1f00", "41ac", "abcd", "2020-06-01"))
        assertFalse(f.textual("P2PKH", "1xAbCz", "04ff", "1f00", "41ac", "abcd", "2020-06-01"))   // tipo
        assertFalse(f.textual("P2PK", "1xAbCz", "", "1f00", "41ac", "abcd", "2020-06-01"))        // sin clave
        assertFalse(f.textual("P2PK", "1xAbCz", "02ff", "1f00", "41ac", "abcd", "2020-06-01"))    // prefijo
        assertFalse(f.textual("P2PK", "1xyz", "04ff", "1f00", "41ac", "abcd", "2020-06-01"))      // dirección
        assertFalse(f.textual("P2PK", "1xAbCz", "04ff", "1f00", "41ac", "cdab", "2020-06-01"))    // txid
        assertFalse(f.textual("P2PK", "1xAbCz", "04ff", "2f00", "41ac", "abcd", "2020-06-01"))    // hash
        assertFalse(f.textual("P2PK", "1xAbCz", "04ff", "1f00", "4100", "abcd", "2020-06-01"))    // script
        assertFalse(f.textual("P2PK", "1xAbCz", "04ff", "1f00", "41ac", "abcd", "2019-12-31"))    // fecha
        assertFalse(f.textual("P2PK", "1xAbCz", "04ff", "1f00", "41ac", "abcd", "2021-01-01"))
        assertTrue(FiltroUtxo(clave = 2).textual("P2PKH", "", "", "", "", "", ""))
        assertTrue(f.pideScript && f.pideFecha && f.pideTxid)
    }
    @Test fun importesYSerializacion() {
        assertEquals(100_000L, FiltroUtxo.btcASat("0.001")); assertEquals(150_000_000L, FiltroUtxo.btcASat("1,5"))
        assertNull(FiltroUtxo.btcASat("abc")); assertNull(FiltroUtxo.btcASat("0.123456789")); assertNull(FiltroUtxo.btcASat(""))
        val f = FiltroUtxo(minSat = 5, tipos = setOf("P2TR"), clave = 1, fechaDesde = "2021-02-03", alturaMax = 900_000, coinbase = 2).normalizado()
        assertEquals(f, FiltroUtxo.deJson(f.aJson()))
        assertEquals(FiltroUtxo(), FiltroUtxo.deJson("no es json")); assertEquals(FiltroUtxo(), FiltroUtxo.deJson(null))
        // Lo inválido se vuelve neutro.
        val m = FiltroUtxo(fechaDesde = "03/02/2021", prefijoClave = "zz", tipos = setOf("XX", "P2SH")).normalizado()
        assertEquals("", m.fechaDesde); assertEquals("", m.prefijoClave); assertEquals(setOf("P2SH"), m.tipos)
        assertTrue(f.resumen().contains("type: P2TR") && f.resumen().contains("with public key") && f.resumen().contains("no mined"))
    }
}
