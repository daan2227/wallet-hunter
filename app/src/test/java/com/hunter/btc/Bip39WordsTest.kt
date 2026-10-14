package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/** La lista BIP39 de la app es la oficial: el SHA-256 de english.txt. */
class Bip39WordsTest {
    @Test fun esLaListaOficial() {
        assertEquals(2048, Bip39Words.WORDS.size)
        assertEquals(2048, Bip39Words.WORDS.toSet().size)
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(Bip39Words.WORDS.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", sha)
    }
}
