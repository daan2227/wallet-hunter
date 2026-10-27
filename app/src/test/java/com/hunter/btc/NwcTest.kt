package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** La URI de conexión. El intercambio completo se probó contra un servicio NWC con nostr-sdk (ver PENDIENTES.md). */
class NwcTest {
    private val pk = "b889ff5b1513b641e2a139f661a661364979c5beee91842f8f0ef42ab558e9d4"
    private val sec = "71a8c14c1407c113601079c4302dab36460f0ccd0ad506f1f2dc73b5100e4f3c"

    @Test fun leeLaUri() {
        val n = Nwc.leer("nostr+walletconnect://$pk?relay=wss%3A%2F%2Frelay.damus.io&relay=wss://nos.lol&secret=$sec&lud16=a@b.c", EcLentoNostr.curva)!!
        assertEquals(pk, n.servicio); assertEquals(listOf("wss://relay.damus.io", "wss://nos.lol"), n.relays)
    }

    @Test fun rechazaLasMalas() {
        assertNull(Nwc.leer("nostr+walletconnect://$pk?relay=wss://r&secret=abc", EcLentoNostr.curva))
        assertNull(Nwc.leer("nostr+walletconnect://$pk?secret=$sec", EcLentoNostr.curva))
        assertNull(Nwc.leer("https://$pk?relay=wss://r&secret=$sec", EcLentoNostr.curva))
        assertNotNull(Nwc.leer("nostr+walletconnect://$pk?relay=wss://r&secret=$sec", EcLentoNostr.curva))
    }
}
