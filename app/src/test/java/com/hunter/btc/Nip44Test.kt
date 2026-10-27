package com.hunter.btc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectores oficiales de NIP-44 v2 (paulmillr/nip44). La clave de conversación desde las privadas va en el banco nativo. */
class Nip44Test {
    private val v by lazy { JSONObject(javaClass.classLoader!!.getResourceAsStream("nip44_vectores.json")!!.bufferedReader().readText()).getJSONObject("v2") }
    private fun h(s: String) = Descriptor.bytes(s)

    @Test fun clavesDeMensaje() {
        val o = v.getJSONObject("valid").getJSONObject("get_message_keys")
        val conv = h(o.getString("conversation_key")); val ks = o.getJSONArray("keys")
        for (i in 0 until ks.length()) {
            val k = ks.getJSONObject(i)
            val (ck, cn, hk) = Nip44.clavesMensaje(conv, h(k.getString("nonce")))
            assertEquals(k.getString("chacha_key"), Descriptor.hex(ck)); assertEquals(k.getString("chacha_nonce"), Descriptor.hex(cn))
            assertEquals(k.getString("hmac_key"), Descriptor.hex(hk))
        }
    }

    @Test fun relleno() {
        val a = v.getJSONObject("valid").getJSONArray("calc_padded_len")
        for (i in 0 until a.length()) assertEquals(a.getJSONArray(i).getInt(1), Nip44.largoRelleno(a.getJSONArray(i).getInt(0)))
    }

    @Test fun cifrarYDescifrar() {
        for (nombre in listOf("encrypt_decrypt", "encrypt_decrypt_long_msg")) {
            val a = v.getJSONObject("valid").getJSONArray(nombre)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (!o.has("plaintext") || !o.has("payload")) continue
                val conv = h(o.getString("conversation_key"))
                assertEquals(o.getString("payload"), Nip44.cifrar(o.getString("plaintext"), conv, h(o.getString("nonce"))))
                assertEquals(o.getString("plaintext"), Nip44.descifrar(o.getString("payload"), conv))
            }
        }
    }

    @Test fun losInvalidosSeRechazan() {
        val a = v.getJSONObject("invalid").getJSONArray("decrypt")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val r = try { Nip44.descifrar(o.getString("payload"), h(o.getString("conversation_key"))); false } catch (e: Exception) { true }
            assertTrue("caso $i: ${o.optString("note")}", r)
        }
    }

    @Test fun nip04IdaYVuelta() {
        val k = ByteArray(32) { it.toByte() }
        assertEquals("hola ñ", Nip44.descifrar04(Nip44.cifrar04("hola ñ", k), k))
    }
}
