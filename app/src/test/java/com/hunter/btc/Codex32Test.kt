package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los vectores de BIP93 (bips/bip-0093.mediawiki), todos. */
class Codex32Test {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun l(s: String) = Codex32.leer(s)!!

    @Test fun vector1SinPartir() {
        val c = l("ms10testsxxxxxxxxxxxxxxxxxxxxxxxxxx4nzvca9cmczlw")
        assertEquals("test", c.id); assertTrue(c.esSecreto)
        val s = Codex32.semilla(c)!!
        assertEquals("318c6318c6318c6318c6318c6318c631", hex(s))
        assertEquals("xprv9s21ZrQH143K3taPNekMd9oV5K6szJ8ND7vVh6fxicRUMDcChr3bFFzuxY8qP3xFFBL6DWc2uEYCfBFZ2nFWbAqKPhtCLRjgv78EZJDEfpL", Codex32.xprv(s))
    }

    @Test fun vector2RecuperarYDerivar() {
        val a = l("MS12NAMEA320ZYXWVUTSRQPNMLKJHGFEDCAXRPP870HKKQRM"); val c = l("MS12NAMECACDEFGHJKLMNPQRSTUVWXYZ023FTR2GDZMPY6PN")
        // El vector lo da en mayúsculas; la app lo escribe en minúsculas (es la misma cadena).
        assertEquals("ms12namedll4f8jlh4e5vdvuldlfxu2jhdnlsm97xvenrxeg", Codex32.derivar(listOf(a, c), 'd').toString())
        val s = Codex32.recuperar(listOf(a, c))!!
        assertEquals("ms12names6xqguzttxkeqnjsjzv4jv3nz5k3kwgsphuh6evw", s.toString())
        assertEquals("d1808e096b35b209ca12132b264662a5", hex(Codex32.semilla(s)!!))
        assertEquals("xprv9s21ZrQH143K2NkobdHxXeyFDqE44nJYvzLFtsriatJNWMNKznGoGgW5UMTL4fyWtajnMYb5gEc2CgaKhmsKeskoi9eTimpRv2N11THhPTU", Codex32.xprv(Codex32.semilla(s)!!))
    }

    @Test fun vector3PartirUnaSemilla() {
        val seed = Dnssec.hexa("ffeeddccbbaa99887766554433221100")
        assertEquals("ms13cashsllhdmn9m42vcsamx24zrxgs3qqjzqud4m0d6nln", Codex32.deSemilla(seed, 3, "cash"))
        // Con las partes "aleatorias" a y c del vector, salen las derivadas d, e y f del vector.
        val azarDelVector = ArrayDeque(listOf("320zyxwvutsrqpnmlkjhgfedca", "acdefghjklmnpqrstuvwxyz023").map { s -> s.map { Codex32.CS.indexOf(it) } })
        val partes = Codex32.partir(seed, 3, 5, "cash") { azarDelVector.removeFirst() }
        assertEquals(listOf(
            "ms13casha320zyxwvutsrqpnmlkjhgfedca2a8d0zehn8a0t", "ms13cashcacdefghjklmnpqrstuvwxyz023949xq35my48dr",
            "ms13cashd0wsedstcdcts64cd7wvy4m90lm28w4ffupqs7rm", "ms13casheekgpemxzshcrmqhaydlp6yhms3ws7320xyxsar9",
            "ms13cashf8jh6sdrkpyrsp5ut94pj8ktehhw2hfvyrj48704"), partes)
        // Tres cualesquiera de las cinco devuelven la semilla.
        for (trio in listOf(listOf(0, 1, 2), listOf(0, 3, 4), listOf(1, 2, 4), listOf(2, 3, 4)))
            assertEquals(hex(seed), hex(Codex32.semilla(Codex32.recuperar(trio.map { l(partes[it]) })!!)!!))
        assertEquals("xprv9s21ZrQH143K266qUcrDyYJrSG7KA3A7sE5UHndYRkFzsPQ6xwUhEGK1rNuyyA57Vkc1Ma6a8boVqcKqGNximmAe9L65WsYNcNitKRPnABd", Codex32.xprv(seed))
        // Dos no bastan.
        assertTrue(Codex32.problema(listOf(l(partes[0]), l(partes[1]))) != null)
    }

    @Test fun vectores4a8() {
        val s256 = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"
        assertEquals("ms10leetsllhdmn9m42vcsamx24zrxgs3qrl7ahwvhw4fnzrhve25gvezzyqqtum9pgv99ycma", Codex32.deSemilla(Dnssec.hexa(s256), 0, "leet"))
        assertEquals("xprv9s21ZrQH143K3s41UCWxXTsU4TRrhkpD1t21QJETan3hjo8DP5LFdFcB5eaFtV8x6Y9aZotQyP8KByUjgLTbXCUjfu2iosTbMv98g8EQoqr", Codex32.xprv(Dnssec.hexa(s256)))
        // Cualquiera de los 16 rellenos del vector 4 es válida y da la misma semilla.
        assertEquals(s256, hex(Codex32.semilla(l("ms10leetsllhdmn9m42vcsamx24zrxgs3qrl7ahwvhw4fnzrhve25gvezzyq0pgjxpzx0ysaam"))!!))
        // Vector 5: codex32 largo, semilla de 512 bits.
        val v5 = l("MS100C8VSM32ZXFGUHPCHTLUPZRY9X8GF2TVDW0S3JN54KHCE6MUA7LQPZYGSFJD6AN074RXVCEMLH8WU3TK925ACDEFGHJKLMNPQRSTUVWXY06FHPV80UNDVARHRAK")
        assertEquals("dc5423251cb87175ff8110c8531d0952d8d73e1194e95b5f19d6f9df7c01111104c9baecdfea8cccc677fb9ddc8aec5553b86e528bcadfdcc201c17c638c47e9", hex(Codex32.semilla(v5)!!))
        assertEquals("xprv9s21ZrQH143K4UYT4rP3TZVKKbmRVmfRqTx9mG2xCy2JYipZbkLV8rwvBXsUbEv9KQiUD7oED1Wyi9evZzUn2rqK9skRgPkNaAzyw3YrpJN", Codex32.xprv(Codex32.semilla(v5)!!))
        // 6, 7 y 8: 160, 192 y 224 bits (con bits sobrantes que no son cero).
        for ((h, c) in listOf("000102030405060708090a0b0c0d0e0f10111213" to "ms10seedsqqqsyqcyq5rqwzqfpg9scrgwpugpzysn9vaqzzvs20xnl",
                              "202122232425262728292a2b2c2d2e2f3031323334353637" to "ms10seedsyqsjygeyy5nzw2pf9g4jctfw9ucrzv3nxs6nvdau84gz0632s0xs",
                              "404142434445464748494a4b4c4d4e4f505152535455565758595a5b" to "ms10seedsgpq5ys6yg4rywjzfff95cn2wfag9z5jn2324v46ct9d9hrcduqw8c3lccl"))
            assertEquals(h, hex(Codex32.semilla(l(c))!!))
        // El de 160 bits se reescribe igual (relleno con ceros).
        assertEquals("ms10seedsqqqsyqcyq5rqwzqfpg9scrgwpugpzysn9vaqzzvs20xnl", Codex32.deSemilla(Dnssec.hexa("000102030405060708090a0b0c0d0e0f10111213"), 0, "seed"))
    }

    @Test fun ningunInvalidoPasa() {
        val inv = javaClass.classLoader!!.getResourceAsStream("codex32_invalidos.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(55, inv.size)
        for (s in inv) {
            val c = Codex32.leer(s)
            assertTrue("pasa: $s", c == null || (c.esSecreto && Codex32.semilla(c) == null))
        }
        assertNull(Codex32.leer("ms10testsxxxxxxxxxxxxxxxxxxxxxxxxxx4nzvca9cmczlx"))
    }
}
