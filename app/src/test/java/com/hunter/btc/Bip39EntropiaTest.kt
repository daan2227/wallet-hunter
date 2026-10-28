package com.hunter.btc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
class Bip39EntropiaTest {
    @Test fun entropiaDeMilkSad() {
        val milksad = "milk sad wage cup reward umbrella raven visa give list decorate broccoli"
        assertEquals("8c97b7d89adb8bd86c9fa562704ce40e",
            Bip39.entropia(milksad)!!.joinToString("") { "%02x".format(it) })
    }
    @Test fun fraseMalaDaNull() {
        assertNull(Bip39.entropia("milk sad wage cup reward umbrella raven visa give list decorate zebra"))
        assertNull(Bip39.entropia("not words here"))
    }
}
