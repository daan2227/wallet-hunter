package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpkDireccionTest {
    private fun h(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun vectores() {
        assertEquals("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", SpkDireccion.de(h("76a91477bff20c60e522dfaa3350c39b030a5d004e839a88ac")))
        assertEquals("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy", SpkDireccion.de(h("a914b472a266d0bd89c13706a4132ccfb16f7c3b9fcb87")))
        assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", SpkDireccion.de(h("0014751e76e8199196d454941c45d1b3a323f1433bd6")))
        assertEquals("tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7",
            SpkDireccion.de(h("00201863143c14c5166804bd19203356da136c985678cd4d27a1b8c6329604903262"), true))
        assertEquals("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0",
            SpkDireccion.de(h("512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")))
        assertEquals("1111111111111111111114oLvT2", SpkDireccion.de(h("76a914000000000000000000000000000000000000000088ac")))
    }
    @Test fun noEstandar() {
        assertNull(SpkDireccion.de(h("6a0401020304")))           // OP_RETURN
        assertNull(SpkDireccion.de(h("0010751e76e8199196d454941c45d1b3a323")))   // v0 de 16 bytes
    }
}
