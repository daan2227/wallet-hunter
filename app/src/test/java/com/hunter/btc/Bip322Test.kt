package com.hunter.btc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * BIP322 contra los vectores OFICIALES (bip-0322/basic-test-vectors.json y generated-test-vectors.json).
 * Aquí no está el motor nativo: ECDSA y BIP340 van con BigInteger (EcLento), sólo para la prueba. En la app
 * los hace secp256k1 (ec "ecdsa_ok", "schnorr_ok", "schnorr"), probado con estas mismas firmas en el banco.
 */
class Bip322Test {
    private fun json(n: String) = JSONObject(javaClass.classLoader!!.getResourceAsStream(n)!!.bufferedReader().readText())
    private val basico by lazy { json("bip322_basic.json") }
    private val generado by lazy { json("bip322_gen.json") }

    private val N = EcLento.N
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun info(a: String) = (BtcAddress.validate(a, false) as BtcAddress.Result.Valid).info
    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b)

    // ── Criptografía de prueba ──

    private fun punto(pub: ByteArray): Pair<BigInteger, BigInteger> = if (pub.size == 33) EcLento.parse(pub)
        else BigInteger(1, pub.copyOfRange(1, 33)) to BigInteger(1, pub.copyOfRange(33, 65))

    /** DER estricto mínimo: 30 L 02 lr r 02 ls s. */
    private fun rs(der: ByteArray): Pair<BigInteger, BigInteger>? {
        if (der.size < 8 || der[0].toInt() != 0x30 || der[1].toInt() != der.size - 2 || der[2].toInt() != 2) return null
        val lr = der[3].toInt(); if (4 + lr + 2 > der.size || der[4 + lr].toInt() != 2) return null
        val ls = der[5 + lr].toInt(); if (6 + lr + ls != der.size) return null
        return BigInteger(1, der.copyOfRange(4, 4 + lr)) to BigInteger(1, der.copyOfRange(6 + lr, 6 + lr + ls))
    }

    private val verificador = object : Bip322.Verificador {
        override fun ecdsa(pub: ByteArray, hash: ByteArray, der: ByteArray): Boolean {
            val (r, s) = rs(der) ?: return false
            if (r.signum() <= 0 || r >= N || s.signum() <= 0 || s > N.shiftRight(1)) return false   // s bajo
            val w = s.modInverse(N); val z = BigInteger(1, hash)
            val R = EcLento.add(EcLento.mul(z * w % N), EcLento.mul(r * w % N, punto(pub))) ?: return false
            return R.first.mod(N) == r
        }
        override fun schnorr(xonly: ByteArray, hash: ByteArray, firma64: ByteArray): Boolean {
            val Pp = try { EcLento.parse(byteArrayOf(2) + xonly) } catch (e: Exception) { return false }
            val r = BigInteger(1, firma64.copyOfRange(0, 32)); val s = BigInteger(1, firma64.copyOfRange(32, 64))
            if (r >= P || s >= N) return false
            val e = BigInteger(1, EcLento.tagged("BIP0340/challenge", firma64.copyOfRange(0, 32) + xonly + hash)).mod(N)
            val R = EcLento.add(EcLento.mul(s), EcLento.mul(N - e, Pp)) ?: return false
            return !R.second.testBit(0) && R.first == r
        }
    }

    private fun hmac(k: ByteArray, d: ByteArray) =
        javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(k, "HmacSHA256")) }.doFinal(d)

    /** ECDSA con nonce RFC6979 (el de secp256k1 por defecto) y s bajo, en DER. */
    private fun firmarEcdsa(d: BigInteger, h: ByteArray): ByteArray {
        val x = EcLento.b32(d); val hh = EcLento.b32(BigInteger(1, h).mod(N))
        var V = ByteArray(32) { 1 }; var K = ByteArray(32)
        K = hmac(K, V + byteArrayOf(0) + x + hh); V = hmac(K, V); K = hmac(K, V + byteArrayOf(1) + x + hh); V = hmac(K, V)
        var k: BigInteger
        while (true) { V = hmac(K, V); k = BigInteger(1, V); if (k.signum() > 0 && k < N) break; K = hmac(K, V + byteArrayOf(0)); V = hmac(K, V) }
        val r = EcLento.mul(k)!!.first.mod(N)
        var s = k.modInverse(N) * (BigInteger(1, h) + r * d) % N
        if (s > N.shiftRight(1)) s = N - s
        fun ent(v: BigInteger): ByteArray { val b = v.toByteArray(); return byteArrayOf(2, b.size.toByte()) + b }
        val cuerpo = ent(r) + ent(s)
        return byteArrayOf(0x30, cuerpo.size.toByte()) + cuerpo
    }

    /** BIP340 con aux. */
    private fun firmarSchnorr(d0: BigInteger, m: ByteArray, aux: ByteArray): ByteArray {
        val Pp = EcLento.mul(d0)!!; val d = if (Pp.second.testBit(0)) N - d0 else d0; val px = EcLento.b32(Pp.first)
        val t = EcLento.b32(d.xor(BigInteger(1, EcLento.tagged("BIP0340/aux", aux))))
        val k0 = BigInteger(1, EcLento.tagged("BIP0340/nonce", t + px + m)).mod(N)
        val R = EcLento.mul(k0)!!; val k = if (R.second.testBit(0)) N - k0 else k0
        val rx = EcLento.b32(R.first)
        val e = BigInteger(1, EcLento.tagged("BIP0340/challenge", rx + px + m)).mod(N)
        return rx + EcLento.b32((k + e * d) % N)
    }

    private fun privDeWif(w: String): BigInteger = BigInteger(1, BtcAddress.base58CheckDecode(w)!!.copyOfRange(1, 33))

    /** La privada ajustada de Taproot (BIP86, sin árbol), como la calcula la pantalla con el motor. */
    private fun ajustada(d: BigInteger): BigInteger {
        val Pp = EcLento.mul(d)!!
        val dd = if (Pp.second.testBit(0)) N - d else d
        return (dd + BigInteger(1, Bip322.tapTweak(EcLento.b32(Pp.first)))).mod(N)
    }

    private fun firmarCon(wif: String, direccion: String, mensaje: String): String? {
        val d = privDeWif(wif)
        return Bip322.firmar(info(direccion), mensaje.toByteArray(), EcLento.ser(EcLento.mul(d)!!),
            { h -> firmarEcdsa(d, h) }, { h -> firmarSchnorr(ajustada(d), h, ByteArray(32)) })
    }

    // ── Pruebas ──

    @Test fun hashesDeTransacciones() {
        val a = basico.getJSONArray("tx_hashes")
        for (i in 0 until a.length()) {
            val t = a.getJSONObject(i); val m = t.getString("message").toByteArray(Charsets.UTF_8)
            val spend = Bip322.toSpend(BtcAddress.scriptPubKey(info(t.getString("address"))), m)
            assertEquals(t.getString("message_hash"), hex(Bip322.hashMensaje(m)))
            assertEquals(t.getString("to_spend_tx_hash"), hex(Bip322.txid(spend).reversedArray()))
            assertEquals(t.getString("to_sign_tx_hash"), hex(Bip322.txid(Bip322.toSign(spend)).reversedArray()))
        }
    }

    private fun veredicto(s: JSONObject, firma: String) =
        Bip322.verificar(info(s.getString("address")), s.getString("message").toByteArray(Charsets.UTF_8), firma, verificador)

    private val entendidos = setOf("p2wpkh", "p2tr", "p2sh-p2wpkh", "p2pkh")

    @Test fun firmasSimplesOficiales() {
        var validas = 0
        for (doc in listOf(basico, generado)) {
            val a = doc.getJSONArray("simple")
            for (i in 0 until a.length()) {
                val s = a.getJSONObject(i); val firmas = s.getJSONArray("bip322_signatures")
                for (j in 0 until firmas.length()) {
                    val v = veredicto(s, firmas.getString(j))
                    if (s.getString("type") in entendidos) { assertEquals("${s.getString("type")} $j", Bip322.Veredicto.Valida(), v); validas++ }
                    else assertTrue("${s.getString("type")}: $v", v is Bip322.Veredicto.Inconclusa)
                }
            }
        }
        assertTrue("se comprobaron firmas de verdad", validas >= 6)
    }

    @Test fun firmasCompletasOficiales() {
        val a = generado.getJSONArray("full"); var validas = 0
        for (i in 0 until a.length()) {
            val s = a.getJSONObject(i); val v = veredicto(s, s.getJSONArray("bip322_signatures").getString(0))
            if (s.getString("type") in entendidos) {
                assertEquals(s.getString("type"), Bip322.Veredicto.Valida(s.getLong("lock_time"), s.getLong("sequence")), v); validas++
            } else assertTrue("${s.getString("type")}: $v", v is Bip322.Veredicto.Inconclusa)
        }
        assertEquals(4, validas)
    }

    @Test fun ningunaFirmaErroneaPasaPorBuena() {
        for (doc in listOf(basico, generado)) {
            val a = doc.getJSONArray("error")
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                val v = Bip322.verificar(info(e.getString("address")), e.getString("message").toByteArray(Charsets.UTF_8),
                    e.getString("signature"), verificador)
                assertTrue("${e.getString("description")}: $v", v !is Bip322.Veredicto.Valida)
            }
        }
    }

    @Test fun firmaP2wpkhIgualQueElVector() {
        // Cada vector trae dos firmas: la primera es la de Bitcoin Core, que repite el nonce hasta que r sale
        // corto ("low-R"); la segunda es RFC6979 a secas, la de secp256k1 por defecto (y la de firmarHash).
        // Con RFC6979 sale EXACTAMENTE la segunda: la serialización es la del BIP, byte a byte.
        val wif = "L3VFeEujGtevx9w18HD1fhRbCH67Az2dpCymeRE1SoPK6XQtaN2k"; val a = "bc1q9vza2e8x573nczrlzms0wvx3gsqjx7vavgkx0l"
        assertEquals("smpAkgwRQIhAPkJ1Q4oYS0htvyuSFHLxRQpFAY56b70UvE7Dxazen0ZAiAtZfFz1S6T6I23MWI2lK/pcNTWncuyL8UL+oMdydVgzAEhAsfxIAMZZEKUPYWI4BruhAQjzFT8FSFSajuFwrDL1Yhy",
            firmarCon(wif, a, ""))
        assertEquals("smpAkgwRQIhAOzyynlqt93lOKJr+wmmxIens//zPzl9tqIOua93wO6MAiBi5n5EyAcPScOjf1lAqIUIQtr3zKNeavYabHyR8eGhowEhAsfxIAMZZEKUPYWI4BruhAQjzFT8FSFSajuFwrDL1Yhy",
            firmarCon(wif, a, "Hello World"))
    }

    @Test fun firmarYVerificarCadaTipo() {
        // Las claves de los vectores con sus direcciones: firmar aquí y verificar debe dar válida; con otro mensaje, no.
        val casos = ArrayList<Pair<String, String>>()
        for (doc in listOf(basico, generado)) for (sec in listOf("simple", "full")) {
            val a = doc.optJSONArray(sec) ?: continue
            for (i in 0 until a.length()) { val s = a.getJSONObject(i)
                if (s.getString("type") in entendidos) casos.add(s.getJSONArray("private_keys").getString(0) to s.getString("address")) }
        }
        assertTrue(casos.map { info(it.second).type }.toSet().containsAll(listOf(BtcAddress.Type.P2WPKH, BtcAddress.Type.P2TR, BtcAddress.Type.P2SH, BtcAddress.Type.P2PKH)))
        for ((wif, dir) in casos) {
            val f = firmarCon(wif, dir, "Proof I own this, 2026")!!
            assertEquals(dir, Bip322.Veredicto.Valida(), Bip322.verificar(info(dir), "Proof I own this, 2026".toByteArray(), f, verificador))
            assertTrue(dir, Bip322.verificar(info(dir), "Something else".toByteArray(), f, verificador) is Bip322.Veredicto.Invalida)
        }
    }

    @Test fun ajusteTaprootDaLaDireccion() {
        // La privada ajustada firma por la clave de salida: su x es el programa de la dirección bc1p.
        val d = privDeWif("KyrSGCFPhqZMjCe5fNTYddiLMp4tMj4gLKuJ26TsB2rvr1VJGPbt")
        assertEquals(hex(info("bc1pss0zhytly75awhm6x2hhvd5lnzv3vssgrf9axfheq8ldyzn88ges79fler").program),
            hex(EcLento.b32(EcLento.mul(ajustada(d))!!.first)))
    }

    @Test fun claveQueNoEsDeLaDireccionNoFirma() {
        val otra = "KySmn2yeCukjHXnSu3M6vX7tNok4weu1FKbNEuVvm2b3ZidKhB4L"
        assertEquals(null, firmarCon(otra, "bc1q9vza2e8x573nczrlzms0wvx3gsqjx7vavgkx0l", "x"))
    }

    // ── Prueba de fondos ──

    @Test fun pruebasDeFondosOficiales() {
        val a = generado.getJSONArray("proof_of_funds")
        for (i in 0 until a.length()) {
            val s = a.getJSONObject(i)
            val f = Bip322.verificarFondos(info(s.getString("address")), s.getString("message").toByteArray(Charsets.UTF_8),
                s.getJSONArray("bip322_signatures").getString(0), verificador)
            assertEquals(s.getString("type"), Bip322.Veredicto.Valida(s.getLong("lock_time"), s.getLong("sequence")), f.veredicto)
            val esperadas = ArrayList<Pair<Long, String>>()
            val grupos = s.getJSONArray("additional_inputs")
            for (g in 0 until grupos.length()) { val ms = grupos.getJSONArray(g)
                for (k in 0 until ms.length()) ms.getJSONObject(k).let { esperadas.add(it.getLong("value") to it.getString("pk_script")) } }
            assertEquals(esperadas, f.monedas.map { it.previa.valor to hex(it.previa.script) })
        }
    }

    @Test fun pruebaDeFondosConImporteCambiadoNoVale() {
        for (i in 0 until generado.getJSONArray("proof_of_funds").length()) {
            val s = generado.getJSONArray("proof_of_funds").getJSONObject(i)
            val b = java.util.Base64.getDecoder().decode(s.getJSONArray("bip322_signatures").getString(0).drop(3))
            val valor = byteArrayOf(0x4e, 0x46, 0x05, 0, 0, 0, 0, 0)          // 345678 en LE: la primera moneda
            val p = (0..b.size - 8).first { k -> (0 until 8).all { b[k + it] == valor[it] } }
            b[p] = (b[p] + 1).toByte()
            val f = Bip322.verificarFondos(info(s.getString("address")), s.getString("message").toByteArray(Charsets.UTF_8),
                "pof" + java.util.Base64.getEncoder().encodeToString(b), verificador)
            assertTrue("${s.getString("type")}: ${f.veredicto}", f.veredicto is Bip322.Veredicto.Invalida)
        }
    }

    private fun clave(wif: String): Bip322.Clave {
        val d = privDeWif(wif)
        return Bip322.Clave(EcLento.ser(EcLento.mul(d)!!), { h -> firmarEcdsa(d, h) }, { h -> firmarSchnorr(ajustada(d), h, ByteArray(32)) })
    }

    @Test fun firmarYVerificarFondosDeCadaTipo() {
        // Monedas de los cuatro tipos con claves de los vectores; las P2PKH llevan su transacción previa entera.
        val pof = generado.getJSONArray("proof_of_funds")
        val wifs = ArrayList<String>(); val scripts = ArrayList<String>()
        for (i in 0 until pof.length()) { val s = pof.getJSONObject(i); val ks = s.getJSONArray("private_keys"); val g = s.getJSONArray("additional_inputs")
            for (x in 0 until g.length()) { val ms = g.getJSONArray(x); for (k in 0 until ms.length()) { val m = ms.getJSONObject(k)
                wifs.add(ks.getString(m.getInt("private_key_index"))); scripts.add(m.getString("pk_script")) } } }
        wifs.add("KySmn2yeCukjHXnSu3M6vX7tNok4weu1FKbNEuVvm2b3ZidKhB4L"); scripts.add(hex(BtcAddress.scriptPubKey(info("bc1qqthe0hz8klx90e7stf6shclhsvqd5ly96pn53v"))))
        val monedas = scripts.mapIndexed { k, sh ->
            val spk = ByteArray(sh.length / 2) { sh.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val valor = 10_000L * (k + 1)
            val previa = Bip322.serializar(Bip322.Tx(2, listOf(Bip322.Entrada(ByteArray(32) { k.toByte() }, 0, byteArrayOf(0x51), 0xffffffffL, emptyList())),
                listOf(Bip322.Salida(1, byteArrayOf(0x6a)), Bip322.Salida(valor, spk)), 0), false)
            Bip322.Moneda(Bip322.txid(Bip322.leer(previa)!!), 1, Bip322.Previa(valor, spk), if (Bip322.tipoDe(spk) == BtcAddress.Type.P2PKH) previa else null)
        }
        assertEquals(setOf(BtcAddress.Type.P2PKH, BtcAddress.Type.P2SH, BtcAddress.Type.P2TR, BtcAddress.Type.P2WPKH), monedas.map { Bip322.tipoDe(it.previa.script) }.toSet())
        for ((wifD, dir) in listOf("L3VFeEujGtevx9w18HD1fhRbCH67Az2dpCymeRE1SoPK6XQtaN2k" to "bc1q9vza2e8x573nczrlzms0wvx3gsqjx7vavgkx0l",
                                   "KyrSGCFPhqZMjCe5fNTYddiLMp4tMj4gLKuJ26TsB2rvr1VJGPbt" to "bc1pss0zhytly75awhm6x2hhvd5lnzv3vssgrf9axfheq8ldyzn88ges79fler")) {
            val msg = "These coins are mine".toByteArray()
            val firma = Bip322.firmarFondos(info(dir), msg, monedas, listOf(clave(wifD)) + wifs.map { clave(it) })!!
            val f = Bip322.verificarFondos(info(dir), msg, firma, verificador)
            assertEquals(dir, Bip322.Veredicto.Valida(), f.veredicto)
            assertEquals(monedas.map { it.previa.valor }, f.monedas.map { it.previa.valor })
            assertTrue(Bip322.verificarFondos(info(dir), "Other".toByteArray(), firma, verificador).veredicto is Bip322.Veredicto.Invalida)
        }
        // Una moneda con la clave de otra: no se firma.
        assertEquals(null, Bip322.firmarFondos(info("bc1q9vza2e8x573nczrlzms0wvx3gsqjx7vavgkx0l"), "x".toByteArray(), monedas.take(1),
            listOf(clave("L3VFeEujGtevx9w18HD1fhRbCH67Az2dpCymeRE1SoPK6XQtaN2k"), clave("KySmn2yeCukjHXnSu3M6vX7tNok4weu1FKbNEuVvm2b3ZidKhB4L"))))
    }

    @Test fun firmaClasicaSeReconoce() {
        val sig = java.util.Base64.getEncoder().encodeToString(byteArrayOf(31) + ByteArray(64) { 1 })
        assertTrue(Bip322.verificar(info("bc1q9vza2e8x573nczrlzms0wvx3gsqjx7vavgkx0l"), "x".toByteArray(), sig, verificador) is Bip322.Veredicto.Inconclusa)
    }
}
