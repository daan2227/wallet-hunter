package com.hunter.btc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PoolProtocoloTest {
    private val bueno = """{"id": 7, "puzzle": 20, "inicio": "${"0".repeat(59)}80000", "fin": "${"0".repeat(59)}80fff",
        "objetivo": "1HsMJxNiV7TLxmoF6uJNkydxPFDog4NQum", "testigos": ["${"ab".repeat(20)}", "${"cd".repeat(20)}"]}"""

    @Test fun leeUnTrabajo() {
        val t = PoolProtocolo.leerTrabajo(bueno)!!
        assertEquals(7L, t.id); assertEquals(2, t.testigos.size)
    }

    @Test fun rechazaLoRaro() {
        assertNull(PoolProtocolo.leerTrabajo(bueno.replace("80fff", "7ffff")))                              // al revés
        assertNull(PoolProtocolo.leerTrabajo(bueno.replace("\"${"0".repeat(59)}80fff\"", "\"${"f".repeat(64)}\"")))  // enorme
        assertNull(PoolProtocolo.leerTrabajo(bueno.replace("1HsMJxNiV7TLxmoF6uJNkydxPFDog4NQum", "bc1qxyz")))
        assertNull(PoolProtocolo.leerTrabajo("no json"))
    }

    @Test fun testigosYResultado() {
        val t = PoolProtocolo.leerTrabajo(bueno)!!
        val m = PoolProtocolo.testigosHallados("${"ab".repeat(20)}:${"11".repeat(32)},${"ee".repeat(20)}:${"22".repeat(32)}", t)
        assertEquals(mapOf("ab".repeat(20) to "11".repeat(32)), m)
        val r = JSONObject(PoolProtocolo.resultado(t, "yo", m, null))
        assertEquals(7L, r.getLong("id")); assertEquals("11".repeat(32), r.getJSONObject("testigos").getString("ab".repeat(20)))
        assertNotNull(PoolProtocolo.claveDe("x\n1HsMJxNiV7TLxmoF6uJNkydxPFDog4NQum PRIV:${"0".repeat(59)}d2c55 y", t.objetivo))
    }
}
