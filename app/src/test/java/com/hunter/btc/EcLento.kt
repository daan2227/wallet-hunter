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

/** Las operaciones de HunterEngine.ec que usa SilentPayments, con BigInteger (sólo pruebas). */
object EcLentoOps {
    private fun bi(h: String) = java.math.BigInteger(1, Descriptor.bytes(h))
    private fun h32(x: java.math.BigInteger) = Descriptor.hex(EcLento.b32(x))
    val ec = SilentPayments.Ec { op, a, b ->
        when (op) {
            "pub" -> Descriptor.hex(EcLento.ser(EcLento.mul(bi(a))!!))
            "priv_sum" -> {
                val ks = a.split(','); val ng = b.split(',')
                var s = java.math.BigInteger.ZERO
                for ((i, k) in ks.withIndex()) { val v = bi(k); s += if (ng.getOrNull(i) == "1") EcLento.N - v else v }
                s = s.mod(EcLento.N); if (s.signum() == 0) "" else h32(s)
            }
            "pub_mul" -> EcLento.mul(bi(b), EcLento.parse(Descriptor.bytes(a)))?.let { Descriptor.hex(EcLento.ser(it)) } ?: ""
            "pub_tweak" -> EcLento.add(EcLento.parse(Descriptor.bytes(a)), EcLento.mul(bi(b)))?.let { Descriptor.hex(EcLento.ser(it)) } ?: ""
            "sp_send" -> {
                val (suma, op36) = a.split('|'); val (scan, spend) = b.split(':')
                val sa = bi(suma); val A = EcLento.ser(EcLento.mul(sa)!!)
                val ih = bi(Descriptor.hex(EcLento.tagged("BIP0352/Inputs", Descriptor.bytes(op36) + A)))
                val ecdh = EcLento.ser(EcLento.mul(ih.multiply(sa).mod(EcLento.N), EcLento.parse(Descriptor.bytes(scan)))!!)
                val t = bi(Descriptor.hex(EcLento.tagged("BIP0352/SharedSecret", ecdh + byteArrayOf(0, 0, 0, 0))))
                val p = EcLento.add(EcLento.parse(Descriptor.bytes(spend)), EcLento.mul(t))!!
                h32(p.first)
            }
            else -> ""
        }
    }
}

/** Derivación PRIVADA BIP32 y firma ECDSA (low-S, DER) con BigInteger: sólo para las pruebas. */
object EcLentoPriv {
    private fun hmac512(k: ByteArray, d: ByteArray) =
        javax.crypto.Mac.getInstance("HmacSHA512").apply { init(javax.crypto.spec.SecretKeySpec(k, "HmacSHA512")) }.doFinal(d)

    /** Privada (hex) de [ruta] ("48h/1h/0h/2h/0/0") desde una xprv/tprv maestra. */
    fun derivar(xprv: String, ruta: String): String {
        val raw = Descriptor.b58check(xprv)!!
        var k = java.math.BigInteger(1, raw.copyOfRange(46, 78)); var c = raw.copyOfRange(13, 45)
        for (s in ruta.split('/')) {
            val h = s.endsWith("h") || s.endsWith("'")
            val i = s.trimEnd('h', '\'').toLong() + if (h) 0x80000000L else 0L
            val datos = if (h) byteArrayOf(0) + EcLento.b32(k) else EcLento.ser(EcLento.mul(k)!!)
            val I = hmac512(c, datos + byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            k = (java.math.BigInteger(1, I.copyOfRange(0, 32)) + k).mod(EcLento.N); c = I.copyOfRange(32, 64)
        }
        return Descriptor.hex(EcLento.b32(k))
    }

    fun firmar(privHex: String, zHex: String): String {
        val d = java.math.BigInteger(1, Descriptor.bytes(privHex)); val z = java.math.BigInteger(1, Descriptor.bytes(zHex))
        val rnd = java.security.SecureRandom()
        while (true) {
            val k = java.math.BigInteger(256, rnd).mod(EcLento.N); if (k.signum() == 0) continue
            val r = EcLento.mul(k)!!.first.mod(EcLento.N); if (r.signum() == 0) continue
            var s = k.modInverse(EcLento.N).multiply(z.add(r.multiply(d))).mod(EcLento.N); if (s.signum() == 0) continue
            if (s > EcLento.N.shiftRight(1)) s = EcLento.N - s
            fun ent(x: java.math.BigInteger): ByteArray { val b = x.toByteArray(); return byteArrayOf(0x02, b.size.toByte()) + b }
            val rr = ent(r); val ss = ent(s)
            return Descriptor.hex(byteArrayOf(0x30, (rr.size + ss.size).toByte()) + rr + ss)
        }
    }
}

/** Schnorr BIP340 y ECDH x-only con BigInteger (sólo pruebas): la curva de Nostr en la JVM. */
object EcLentoNostr {
    private val N = EcLento.N
    private fun bi(h: String) = java.math.BigInteger(1, Descriptor.bytes(h))
    private fun b32(x: java.math.BigInteger) = EcLento.b32(x)
    private fun lift(x: ByteArray) = EcLento.parse(byteArrayOf(2) + x)
    val curva = NostrEvento.Curva { op, a, b ->
        when (op) {
            "xonly" -> Descriptor.hex(b32(EcLento.mul(bi(a))!!.first))
            "schnorr" -> {
                val (m, aux) = b.split('|').let { Descriptor.bytes(it[0]) to Descriptor.bytes(it[1]) }
                var d = bi(a); val P = EcLento.mul(d)!!; if (P.second.testBit(0)) d = N - d
                val px = b32(P.first)
                val t = b32(d).zip(EcLento.tagged("BIP0340/aux", aux).toList()) { x, y -> (x.toInt() xor y.toInt()).toByte() }.toByteArray()
                var k = java.math.BigInteger(1, EcLento.tagged("BIP0340/nonce", t + px + m)).mod(N)
                val R = EcLento.mul(k)!!; if (R.second.testBit(0)) k = N - k
                val e = java.math.BigInteger(1, EcLento.tagged("BIP0340/challenge", b32(R.first) + px + m)).mod(N)
                Descriptor.hex(b32(R.first) + b32(k.add(e.multiply(d)).mod(N)))
            }
            "schnorr_ok" -> try {
                val (m, s) = b.split('|').let { Descriptor.bytes(it[0]) to Descriptor.bytes(it[1]) }
                val P = lift(Descriptor.bytes(a)); val r = java.math.BigInteger(1, s.copyOfRange(0, 32)); val ss = java.math.BigInteger(1, s.copyOfRange(32, 64))
                val e = java.math.BigInteger(1, EcLento.tagged("BIP0340/challenge", s.copyOfRange(0, 32) + Descriptor.bytes(a) + m)).mod(N)
                val R = EcLento.add(EcLento.mul(ss), EcLento.mul(N - e, P))
                if (R != null && !R.second.testBit(0) && R.first == r) "1" else ""
            } catch (x: Exception) { "" }
            "ecdh_x" -> Descriptor.hex(b32(EcLento.mul(bi(a), lift(Descriptor.bytes(b)))!!.first))
            else -> ""
        }
    }
}
