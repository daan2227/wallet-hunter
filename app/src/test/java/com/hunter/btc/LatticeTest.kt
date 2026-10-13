package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.util.Random

/**
 * Pruebas del ataque de retícula (Lattice.kt) con firmas FABRICADAS aquí mismo:
 * una clave conocida, nonces de tamaño fijo y la aritmética de secp256k1 en
 * BigInteger. No necesitan emulador: son JVM pura.
 *
 * La comprobación de cada candidato es el punto entero (x e y), no sólo la x:
 * con la x sola, d y -d pasarían ambos y la prueba no distinguiría el signo.
 */
class LatticeTest {

    private val SWEEP_ESPERADO = setOf(64, 80, 96, 110, 120, 128, 140, 152, 168, 184, 200, 216, 232, 244)

    private val p = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val n = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val gx = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val gy = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)

    /** Punto afín (x, y); null = infinito. */
    private fun suma(a: Pair<BigInteger, BigInteger>?, b: Pair<BigInteger, BigInteger>?): Pair<BigInteger, BigInteger>? {
        if (a == null) return b
        if (b == null) return a
        val (x1, y1) = a; val (x2, y2) = b
        if (x1 == x2 && y1.add(y2).mod(p).signum() == 0) return null
        val l = if (x1 == x2 && y1 == y2) {
            x1.pow(2).multiply(BigInteger.valueOf(3)).multiply(y1.shiftLeft(1).modInverse(p)).mod(p)
        } else {
            y2.subtract(y1).mod(p).multiply(x2.subtract(x1).mod(p).modInverse(p)).mod(p)
        }
        val x3 = l.pow(2).subtract(x1).subtract(x2).mod(p)
        val y3 = l.multiply(x1.subtract(x3)).subtract(y1).mod(p)
        return x3 to y3
    }

    private fun mul(k: BigInteger): Pair<BigInteger, BigInteger>? {
        var r: Pair<BigInteger, BigInteger>? = null
        var q: Pair<BigInteger, BigInteger>? = gx to gy
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) r = suma(r, q)
            q = suma(q, q)
        }
        return r
    }

    /** Firma (r, s, z) con nonce k: r = x(kG) mod n, s = k⁻¹ (z + r·d) mod n. */
    private fun firma(d: BigInteger, k: BigInteger, z: BigInteger): Triple<BigInteger, BigInteger, BigInteger> {
        val r = mul(k)!!.first.mod(n)
        val s = k.modInverse(n).multiply(z.add(r.multiply(d))).mod(n)
        return Triple(r, s, z)
    }

    /** Firmas de la misma clave d con nonces uniformes en [1, 2^bits). */
    private fun firmas(rnd: Random, d: BigInteger, m: Int, bits: Int): List<Triple<BigInteger, BigInteger, BigInteger>> =
        List(m) {
            val k = BigInteger(bits, rnd).add(BigInteger.ONE)
            val z = BigInteger(256, rnd).mod(n)
            firma(d, k, z)
        }

    private fun clave(rnd: Random): BigInteger = BigInteger(256, rnd).mod(n - BigInteger.ONE).add(BigInteger.ONE)

    @Test
    fun recuperaConNoncesDeBitsFijos() {
        val rnd = Random(1)
        // Mismas combinaciones que tools/ec-harness/lattice_hnp.py: (firmas, bits).
        for ((m, bits) in listOf(3 to 110, 4 to 128, 5 to 160)) {
            repeat(3) {
                val d = clave(rnd)
                val pub = mul(d)!!
                val sigs = firmas(rnd, d, m, bits)
                val got = Lattice.recover(sigs, bits) { c -> mul(c) == pub }
                assertEquals("m=$m bits=$bits", d, got)
            }
        }
    }

    @Test
    fun recoverAutoAdivinaElTamano() {
        val rnd = Random(2)
        val d = clave(rnd)
        val pub = mul(d)!!
        val sigs = firmas(rnd, d, 5, 160)
        val res = Lattice.recoverAuto(sigs) { c -> mul(c) == pub }
        assertNotNull("sin resultado", res)
        assertEquals(d, res!!.first)
        // La clave es la verdadera (la comprueba el punto), y el tamaño con el
        // que salió tiene que ser uno del barrido. No hace falta que sea el
        // mínimo que cubre los nonces: con 5 firmas, un tamaño menor también
        // puede dar la clave correcta.
        assertTrue(res.second in SWEEP_ESPERADO)
    }

    @Test
    fun nonceCompletosNoDanFalsaClave() {
        val rnd = Random(3)
        val d = clave(rnd)
        val pub = mul(d)!!
        val sigs = firmas(rnd, d, 4, 256)
        // Con nonces de 256 bits no hay nada que recuperar: no debe devolver
        // ninguna clave, porque la comprobación de punto la rechaza.
        assertNull(Lattice.recover(sigs, 128) { c -> mul(c) == pub })
    }

    @Test
    fun cancelarParaElBarrido() {
        val rnd = Random(4)
        val d = clave(rnd)
        val pub = mul(d)!!
        val sigs = firmas(rnd, d, 5, 160)
        assertNull(Lattice.recoverAuto(sigs, cancel = { true }) { c -> mul(c) == pub })
    }
}
