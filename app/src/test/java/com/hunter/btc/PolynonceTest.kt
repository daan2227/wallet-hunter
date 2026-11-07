package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Pruebas del ataque Polynonce (Polynonce.kt) con firmas FABRICADAS aquí: una clave conocida y nonces
 * que siguen una recurrencia polinómica, usando la curva en BigInteger (EcLento). JVM pura, sin emulador.
 *
 * La confirmación de cada candidata es x(k·G) == r, igual que en la app, así que la prueba comprueba el
 * mismo camino que corre en el móvil (allí con la curva nativa).
 */
class PolynonceTest {

    private val N = Polynonce.N
    private val rnd = SecureRandom()
    private fun aleat(): BigInteger { while (true) { val x = BigInteger(256, rnd).mod(N); if (x.signum() != 0) return x } }

    /** x de k·G mod n (lo que la app saca de la pública nativa). */
    private fun xDeKG(k: BigInteger): BigInteger? = EcLento.mul(k.mod(N))?.first?.mod(N)

    /** Una firma (r,s,z) de la clave d con el nonce k dado, en low-S opcional. */
    private fun firma(d: BigInteger, k: BigInteger, lowS: Boolean): Polynonce.Firma {
        val r = EcLento.mul(k)!!.first.mod(N)
        val z = aleat()
        var s = k.modInverse(N).multiply(z.add(r.multiply(d))).mod(N)
        if (lowS && s > N.shiftRight(1)) s = N.subtract(s)
        return Polynonce.Firma(r, s, z)
    }

    private fun verificador(firmas: List<Polynonce.Firma>): (BigInteger) -> Boolean =
        { cand -> Polynonce.confirma(cand, firmas) { k -> xDeKG(k) } }

    /** Nonces por recurrencia polinómica k_{i+1} = c0 + c1 k + c2 k² + … (coef = c), desde k0. */
    private fun nonces(k0: BigInteger, c: List<BigInteger>, cuantos: Int): List<BigInteger> {
        val ks = ArrayList<BigInteger>(); var k = k0.mod(N)
        repeat(cuantos) {
            ks.add(k)
            var nx = BigInteger.ZERO; var pot = BigInteger.ONE
            for (coef in c) { nx = nx.add(coef.multiply(pot)).mod(N); pot = pot.multiply(k).mod(N) }
            k = nx
        }
        return ks
    }

    @Test fun recurrenciaLineal() {
        val d = aleat()
        val c = listOf(aleat(), aleat())                    // k_{i+1} = c0 + c1·k_i  (grado 1 ⇒ 4 firmas)
        val ks = nonces(aleat(), c, 4)
        val firmas = ks.map { firma(d, it, lowS = false) }
        val got = Polynonce.recuperar(firmas, verificar = verificador(firmas))
        assertNotNull("debía recuperar la clave (lineal)", got)
        assertEquals(d, got)
    }

    @Test fun recurrenciaCuadratica() {
        val d = aleat()
        val c = listOf(aleat(), aleat(), aleat())           // grado 2 ⇒ 5 firmas
        val ks = nonces(aleat(), c, 5)
        val firmas = ks.map { firma(d, it, lowS = false) }
        val got = Polynonce.recuperar(firmas, verificar = verificador(firmas))
        assertNotNull("debía recuperar la clave (cuadrática)", got)
        assertEquals(d, got)
    }

    @Test fun linealConLowS() {
        // Con normalización low-S algunas s quedan negadas: el motor prueba combinaciones de signo.
        val d = aleat()
        val c = listOf(aleat(), aleat())
        val ks = nonces(aleat(), c, 4)
        val firmas = ks.map { firma(d, it, lowS = true) }
        val got = Polynonce.recuperar(firmas, verificar = verificador(firmas))
        assertNotNull("debía recuperar pese a low-S", got)
        assertEquals(d, got)
    }

    @Test fun noncesSanosNoRecuperan() {
        // Nonces independientes de verdad: no hay recurrencia y NO debe salir clave (ni un falso positivo).
        val d = aleat()
        val firmas = (0 until 6).map { firma(d, aleat(), lowS = true) }
        val got = Polynonce.recuperar(firmas, verificar = verificador(firmas))
        assertNull("nonces aleatorios no deben recuperar nada", got)
    }
}
