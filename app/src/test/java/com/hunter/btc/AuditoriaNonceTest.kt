package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transacciones P2PKH construidas con secp256k1 en Python: la misma clave firma dos mensajes distintos
 *  con el mismo nonce (r repetido) en "EXPUESTA", y con nonces distintos en "LIMPIA". */
class AuditoriaNonceTest {
    private val pub = "034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa"
    private val expuesta = listOf("01000000010000000000000000000000000000000000000000000000000000000000000000000000006c493046022100466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f270221000d49791a24ca4ebeaa3bdcee6381fef16d98b29d9ccb0cbf42da08faf4212b090121034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aaffffffff0000000000", "01000000010000000000000000000000000000000000000000000000000000000000000000000000006c493046022100466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f2702210017e4f205d03324bac793688c46791ed3b17d4aa6158e1f2b48c572fcbbf815d80121034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aaffffffff0000000000")
    private val limpia = listOf("01000000010000000000000000000000000000000000000000000000000000000000000000000000006c493046022100466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f270221000d49791a24ca4ebeaa3bdcee6381fef16d98b29d9ccb0cbf42da08faf4212b090121034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aaffffffff0000000000", "01000000010000000000000000000000000000000000000000000000000000000000000000000000006c4930460221003c72addb4fdf09af94f0c94d7fe92a386a7e70cf8a1d85916386bb2535c7b1b1022100be5756a675e65dc61c61f55e425c0d2480de84d5d93a9ff9e5ce589eeb61521e0121034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aaffffffff0000000000")

    @Test fun detectaNonceRepetido() {
        assertEquals(listOf(pub), AuditoriaNonce.clavesExpuestas(expuesta))
    }

    @Test fun nonceDistintoNoAvisa() {
        assertTrue(AuditoriaNonce.clavesExpuestas(limpia).isEmpty())
    }

    @Test fun laMismaTxRepetidaNoAvisa() {
        // Ver la misma transacción dos veces no es reutilizar nonce.
        assertTrue(AuditoriaNonce.clavesExpuestas(listOf(expuesta[0], expuesta[0])).isEmpty())
    }

    @Test fun leeLasFirmas() {
        val f = AuditoriaNonce.firmasDe(expuesta[0])
        assertEquals(1, f.size); assertEquals(pub, f[0].first)
    }

    @Test fun basuraNoRevienta() {
        assertTrue(AuditoriaNonce.firmasDe("00").isEmpty())
        assertTrue(AuditoriaNonce.clavesExpuestas(listOf("zz", "")).isEmpty())
    }
}
