package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcAddressTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Vectores publicados (BIP173, BIP350, BIP13 y la P2PKH de k=1). */
    @Test fun scriptDeCadaTipo() {
        val casos = mapOf(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4" to "0014751e76e8199196d454941c45d1b3a323f1433bd6",
            "1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH" to "76a914" + "751e76e8199196d454941c45d1b3a323f1433bd6" + "88ac",
        )
        for ((addr, spkEsperado) in casos) {
            val r = BtcAddress.validate(addr, false) as BtcAddress.Result.Valid
            assertEquals(addr, spkEsperado, hex(BtcAddress.scriptPubKey(r.info)))
        }
    }

    private fun invalida(a: String, testnet: Boolean = false) = BtcAddress.validate(a, testnet) is BtcAddress.Result.Invalid

    /** Antes lanzaba IndexOutOfBounds: escribir un "1" de más tumbaba la app. */
    @Test fun direccionesMalFormadasNoLanzan() {
        assertTrue(invalida("bc1qqqqqqqqqqq1"))
        assertTrue(invalida("bc1qqqqqqqqqqqqqq1"))
        assertTrue(invalida("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t41"))   // un 1 de más
        assertTrue(invalida("bc1"))
        assertTrue(invalida("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5"))    // suma de comprobación
        assertTrue(invalida("bc1qw508d6qEjxtdg4y5r3zarvary0c5xw7kv8f3t4"))    // mayúsculas y minúsculas
        assertTrue(invalida("xx1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))    // ni bc ni tb
        assertTrue(invalida("1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMX"))
    }

    @Test fun direccionesValidasDeLosBip() {
        for (a in listOf("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4",
                         "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"))
            assertTrue(a, BtcAddress.validate(a, false) is BtcAddress.Result.Valid)
        assertTrue(BtcAddress.validate("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", true) is BtcAddress.Result.Valid)
        assertTrue(invalida("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", false))   // red equivocada
    }
}
