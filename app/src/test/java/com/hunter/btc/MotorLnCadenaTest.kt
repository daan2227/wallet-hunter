package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La elección de la fuente de cadena del nodo Lightning (sin arrancar nada). */
class MotorLnCadenaTest {
    private fun mal(f: () -> Unit) = try { f(); false } catch (e: IllegalArgumentException) { true }

    @Test fun electrum() {
        assertEquals("electrum" to "ssl://electrum.example.com:50002", MotorLn.cadena("electrum", " ssl://electrum.example.com:50002 ", false, null, 8332))
        assertEquals("electrum" to "tcp://192.168.1.10:50001", MotorLn.cadena("electrum", "tcp://192.168.1.10:50001", true, null, 8332))
        assertTrue(mal { MotorLn.cadena("electrum", "electrum.example.com:50002", false, null, 8332) })
        assertTrue(mal { MotorLn.cadena("electrum", "ssl://host", false, null, 8332) })
    }

    @Test fun core() {
        assertEquals("bitcoind" to "127.0.0.1:8332:__cookie__:abcd", MotorLn.cadena("core", "", false, "__cookie__:abcd\n", 8332))
        assertTrue(mal { MotorLn.cadena("core", "", false, null, 8332) })          // Core parado
        assertTrue(mal { MotorLn.cadena("core", "", true, "__cookie__:x", 8332) })  // testnet
    }

    @Test fun esplora() {
        assertEquals("esplora" to "https://blockstream.info/api", MotorLn.cadena("esplora", "", false, null, 8332))
        assertEquals("esplora" to "https://blockstream.info/testnet/api", MotorLn.cadena("esplora", "", true, null, 8332))
        assertEquals("esplora" to "https://mi.esplora.lan/api", MotorLn.cadena("esplora", "https://mi.esplora.lan/api/", false, null, 8332))
        assertTrue(mal { MotorLn.cadena("esplora", "http://example.com/api", false, null, 8332) })
    }
}
