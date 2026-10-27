package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionRemotaTest {
    private val sha = "ab".repeat(32)

    @Test fun leeLoQueEscribeLaCi() {
        val v = VersionRemota.leer(
            """{"versionCode":1090,"versionName":"1.0.1090","sha256":"${sha.uppercase()}","size":21000000,"commit":"abc"}""")!!
        assertEquals(1090L, v.codigo)
        assertEquals("1.0.1090", v.nombre)
        assertEquals(sha, v.sha256)
        assertEquals(21000000L, v.bytes)
        assertTrue(v.masNuevaQue(1084))
        assertFalse(v.masNuevaQue(1090))
        assertFalse(v.masNuevaQue(2000))
    }

    @Test fun sinNombreLoDeduce() {
        assertEquals("1.0.7", VersionRemota.leer("""{"versionCode":7,"sha256":"$sha","size":5}""")!!.nombre)
    }

    @Test fun rechazaLoRaro() {
        for (j in listOf(
            "", "no es json", "{}",
            """{"versionCode":0,"sha256":"$sha","size":5}""",
            """{"versionCode":5,"sha256":"abc","size":5}""",
            """{"versionCode":5,"sha256":"${"zz".repeat(32)}","size":5}""",
            """{"versionCode":5,"sha256":"$sha"}""",
            """{"versionCode":5,"sha256":"$sha","size":${VersionRemota.MAX_BYTES + 1}}""",
        )) assertNull(j, VersionRemota.leer(j))
    }
}
