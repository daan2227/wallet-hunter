package com.hunter.btc

import org.junit.Assert.*
import org.junit.Test

class InstantaneaUtxoTest {
    @Test fun tipos() {
        assertEquals("P2PKH (Legacy)", InstantaneaUtxo.tipoDeDireccion("1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH"))
        assertEquals("P2SH (SegWit wrapped)", InstantaneaUtxo.tipoDeDireccion("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"))
        assertEquals("P2WPKH (Native SegWit)", InstantaneaUtxo.tipoDeDireccion("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
        assertEquals("P2TR (Taproot)", InstantaneaUtxo.tipoDeDireccion("bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297"))
        assertEquals("P2WSH (SegWit script)", InstantaneaUtxo.tipoDeDireccion("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"))
        assertEquals("Unknown", InstantaneaUtxo.tipoDeDireccion("zzz"))
    }
    @Test fun fechas() {
        assertEquals("unconfirmed", InstantaneaUtxo.fecha(false, 0, 0))
        assertEquals("2021-01-01 00:00 UTC", InstantaneaUtxo.fecha(true, 1609459200L, 0))
        assertEquals("block 700000", InstantaneaUtxo.fecha(true, 0, 700000))
        assertEquals("", InstantaneaUtxo.fecha(true, 0, 0))
    }
    @Test fun taproot() {
        val s = ByteArray(34); s[0] = 0x51; s[1] = 0x20; for (i in 2 until 34) s[i] = i.toByte()
        assertEquals("02030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f2021", InstantaneaUtxo.claveTaproot(s))
        assertNull(InstantaneaUtxo.claveTaproot(ByteArray(22)))
    }
    @Test fun csvConEscapes() {
        val c = InstantaneaUtxo.csv(listOf(
            InstantaneaUtxo.Fila("Mi, \"cartera\"", "ab:1", 123_456_789L, "P2WPKH (Native SegWit)", "02ff", "2021-01-01 00:00 UTC"),
            InstantaneaUtxo.Fila("=HYPERLINK(\"x\")", "cd:0", 5L, "P2TR (Taproot)", "", "unconfirmed")))
        val l = c.trim().lines()
        assertEquals("wallet,utxo,funds_btc,wallet_type,public_key,tx_date", l[0])
        assertEquals("\"Mi, \"\"cartera\"\"\",ab:1,1.23456789,P2WPKH (Native SegWit),02ff,2021-01-01 00:00 UTC", l[1])
        assertTrue(l[2].startsWith("\"'=HYPERLINK(\"\"x\"\")\",cd:0,0.00000005,"))
        assertEquals(3, l.size)
    }
}
