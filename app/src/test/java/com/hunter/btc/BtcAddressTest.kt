package com.hunter.btc

import org.junit.Assert.assertEquals
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
}
