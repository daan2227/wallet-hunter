package com.hunter.btc

import java.math.BigInteger

/**
 * secp256k1 con BigInteger, SOLO para las pruebas en la JVM (allí no está el motor nativo): derivación
 * pública BIP32 y la salida Taproot de BIP86. Lento y sin cuidado de tiempo constante; no va en la app.
 */
object EcLento {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16) to
                    BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val SIETE = BigInteger.valueOf(7)

    fun add(a: Pair<BigInteger, BigInteger>?, b: Pair<BigInteger, BigInteger>?): Pair<BigInteger, BigInteger>? {
        if (a == null) return b; if (b == null) return a
        if (a.first == b.first && (a.second + b.second).mod(P) == BigInteger.ZERO) return null
        val l = if (a == b) BigInteger.valueOf(3).multiply(a.first).multiply(a.first).multiply(a.second.shiftLeft(1).modInverse(P)).mod(P)
                else (b.second - a.second).multiply((b.first - a.first).modInverse(P)).mod(P)
        val x = (l * l - a.first - b.first).mod(P)
        return x to (l * (a.first - x) - a.second).mod(P)
    }
    fun mul(k: BigInteger, p0: Pair<BigInteger, BigInteger> = G): Pair<BigInteger, BigInteger>? {
        var r: Pair<BigInteger, BigInteger>? = null; var p: Pair<BigInteger, BigInteger>? = p0; var e = k
        while (e.signum() > 0) { if (e.testBit(0)) r = add(r, p); p = add(p, p); e = e.shiftRight(1) }
        return r
    }
    fun ser(p: Pair<BigInteger, BigInteger>): ByteArray =
        byteArrayOf(if (p.second.testBit(0)) 3 else 2) + b32(p.first)
    fun b32(x: BigInteger): ByteArray { val b = x.toByteArray(); return ByteArray(32).also { val n = minOf(32, b.size); System.arraycopy(b, b.size - n, it, 32 - n, n) } }
    fun parse(c: ByteArray): Pair<BigInteger, BigInteger> {
        val x = BigInteger(1, c.copyOfRange(1, 33))
        var y = (x.pow(3) + SIETE).mod(P).modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.testBit(0) != (c[0].toInt() == 3)) y = P - y
        return x to y
    }
    private fun hmac512(k: ByteArray, d: ByteArray) =
        javax.crypto.Mac.getInstance("HmacSHA512").apply { init(javax.crypto.spec.SecretKeySpec(k, "HmacSHA512")) }.doFinal(d)
    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b)

    val derivador = Descriptor.Derivador { kc, ruta ->
        val b = Descriptor.bytes(kc); var k = b.copyOfRange(0, 33); var c = b.copyOfRange(33, 65)
        for (s in ruta.split('/')) {
            val i = s.toLong()
            val I = hmac512(c, k + byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            k = ser(add(parse(k), mul(BigInteger(1, I.copyOfRange(0, 32))))!!); c = I.copyOfRange(32, 64)
        }
        Descriptor.hex(k)
    }
    fun tagged(tag: String, m: ByteArray): ByteArray { val t = sha(tag.toByteArray()); return sha(t + t + m) }
    val tweaker = Descriptor.Tweaker { x ->
        val xb = Descriptor.bytes(x)
        val p = parse(byteArrayOf(2) + xb)
        val q = add(p, mul(BigInteger(1, tagged("TapTweak", xb))))!!
        Descriptor.hex(b32(q.first))
    }
}
