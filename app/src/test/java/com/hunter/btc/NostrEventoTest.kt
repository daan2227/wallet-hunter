package com.hunter.btc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NostrEventoTest {
    // Evento real firmado por nostr-sdk (rust-nostr 0.45): id y firma tienen que cuadrar.
    private val real = "{\"id\":\"5eb038a8564e9d4ab212565f36905a2324a0ac021fa7537d4f78c396daff254d\",\"pubkey\":\"8fc4ddfce4c8beb8c5741568a3b410732b40da1c15fb6b09483259c058104094\",\"created_at\":1793115652,\"kind\":1,\"tags\":[[\"p\",\"abababababababababababababababababababababababababababababababab\"]],\"content\":\"hola \\\"mundo\\\"\\n/ é\",\"sig\":\"81e1a25530fa0e8cabc4cbd2908f286a799bf607d1c36894b25bd3f6a500993f68e93304e6e5ddacac12c311c1e8705705add77a34d49598be58d5388c3f4fd6\"}"

    @Test fun leeYCompruebaUnEventoDeNostrSdk() {
        val e = NostrEvento.leer(JSONObject(real), EcLentoNostr.curva)
        assertNotNull(e)
        // Con el contenido tocado, el id ya no cuadra.
        assertNull(NostrEvento.leer(JSONObject(real).put("content", "otro"), EcLentoNostr.curva))
    }

    @Test fun firmarYLeer() {
        val e = NostrEvento.firmar(EcLentoNostr.curva, "11".repeat(32), 23194, listOf(listOf("p", "ab".repeat(32))), "x\"y\n/é")
        assertEquals(e.id, NostrEvento.leer(e.json(), EcLentoNostr.curva)!!.id)
    }

    @Test fun escapadoNip01() {
        assertEquals("\"a\\\"b\\\\c\\nd/e\\u0001\"", NostrEvento.cadena("a\"b\\c\nd/e\u0001"))
    }
}
