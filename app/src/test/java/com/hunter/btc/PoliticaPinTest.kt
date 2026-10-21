package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliticaPinTest {
    @Test fun seisDigitosYaNoValen() {
        assertNotNull(PoliticaPin.error("123456"))
        assertNotNull(PoliticaPin.error("482915"))
    }

    @Test fun largoDe8A24() {
        assertNotNull(PoliticaPin.error("aB3\$xY7"))                    // 7
        assertNull(PoliticaPin.error("aB3\$xY7!"))                       // 8
        assertNull(PoliticaPin.error("kQ7#nT2!xLp9@wRz4%vB6&mC"))        // 24
        assertEquals("Use 24 characters at most.", PoliticaPin.error("kQ7#nT2!xLp9@wRz4%vB6&mCx"))   // 25
    }

    @Test fun hacenFaltaLasTresClases() {
        assertEquals("Add at least one letter.", PoliticaPin.error("12345678!@"))
        assertEquals("Add at least one number.", PoliticaPin.error("kQnTxLpw!@#"))
        assertTrue(PoliticaPin.error("kQnTxLp72")!!.contains("symbol"))
    }

    @Test fun lasTrampasNoValen() {
        for (p in listOf("Password1!", "Abcd1234!", "Qwerty123!", "Bitcoin2024!", "Aaaaaaa1!"))
            assertFalse(p, PoliticaPin.vale(p))
    }

    @Test fun controlesNoValen() {
        assertNotNull(PoliticaPin.error("kQ7#nT2!\u0007x"))
        assertNotNull(PoliticaPin.error("kQ7#nT2!\nx"))
    }

    @Test fun unCodigoRazonableVale() {
        for (p in listOf("kQ7#nT2!", "m4P!z9Lq", "tiger-Window-7-orange", "vQ7#nT2!xLp9@wRz"))
            assertTrue(p, PoliticaPin.vale(p))
    }
}
