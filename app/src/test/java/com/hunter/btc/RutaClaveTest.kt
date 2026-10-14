package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RutaClaveTest {
    @Test fun recepcion() {
        assertEquals("m/44'/0'/0'/0/0", RutaClave.ruta("p2pkh_0", false))
        assertEquals("m/49'/0'/0'/0/7", RutaClave.ruta("p2sh_7", false))
        assertEquals("m/84'/0'/0'/0/12", RutaClave.ruta("p2wpkh_12", false))
        assertEquals("m/86'/0'/0'/0/3", RutaClave.ruta("p2tr_3", false))
    }
    @Test fun cambio() {
        assertEquals("m/84'/0'/0'/1/5", RutaClave.ruta(RutaClave.clave("p2wpkh", 5, true), false))
        assertEquals("p2tr_ch_2", RutaClave.clave("p2tr", 2, true))
        assertEquals("m/86'/0'/0'/1/2", RutaClave.ruta("p2tr_ch_2", false))
    }
    @Test fun testnetUsaCoinType1() {
        assertEquals("m/84'/1'/0'/0/0", RutaClave.ruta("p2wpkh_0", true))
        assertEquals("m/44'/1'/0'/1/4", RutaClave.ruta("p2pkh_ch_4", true))
    }
    @Test fun indiceIlegibleNoFirmaConOtraClave() {
        for (k in listOf("p2wpkh_", "p2wpkh_x", "p2wpkh_-1", "p2wpkh_ch", "p2wpkh_zz_3", "p2wpkh", "wif_0", "", "p2wpkh_1_2"))
            assertNull(k, RutaClave.ruta(k, false))
    }
}
