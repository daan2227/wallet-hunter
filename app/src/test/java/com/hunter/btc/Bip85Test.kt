package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** Los vectores oficiales de BIP85 (bips/bip-0085.mediawiki), todos con la misma raíz xprv. */
class Bip85Test {
    private val XPRV = "xprv9s21ZrQH143K2LBWUUQRFXhucrQqBpKdRRxNVq2zBqsx8HVqFk2uYo8kmbaLLHRdqtQpUm98uKfu3vca1LqdGhUtyoFnCNkfmXRyPXLjbKb"

    private fun hmac512(k: ByteArray, d: ByteArray) =
        javax.crypto.Mac.getInstance("HmacSHA512").apply { init(javax.crypto.spec.SecretKeySpec(k, "HmacSHA512")) }.doFinal(d)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** BIP32 privada desde la xprv, sólo niveles endurecidos (todos los de BIP85): en la app lo hace el motor. */
    private fun derivar(ruta: String): ByteArray {
        val raw = Descriptor.b58check(XPRV)!!
        var c = raw.copyOfRange(13, 45); var k = raw.copyOfRange(46, 78)
        for (s in ruta.removePrefix("m/").split('/')) {
            require(s.endsWith("'"))
            val i = s.dropLast(1).toLong() + 0x80000000L
            val I = hmac512(c, byteArrayOf(0) + k + byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            k = EcLento.b32((BigInteger(1, I.copyOfRange(0, 32)) + BigInteger(1, k)).mod(EcLento.N)); c = I.copyOfRange(32, 64)
        }
        return k
    }
    private fun ent(ruta: String) = Bip85.entropia(derivar(ruta))

    @Test fun casosBase() {
        assertEquals("cca20ccb0e9a90feb0912870c3323b24874b0ca3d8018c4b96d0b97c0e82ded0", hex(derivar("m/83696968'/0'/0'")))
        assertEquals("efecfbccffea313214232d29e71563d941229afb4338c21f9517c41aaa0d16f00b83d2a09ef747e7a64e8e2bd5a14869e693da66ce94ac2da570ab7ee48618f7",
            hex(ent("m/83696968'/0'/0'")))
        assertEquals("503776919131758bb7de7beb6c0ae24894f4ec042c26032890c29359216e21ba", hex(derivar("m/83696968'/0'/1'")))
        assertEquals("70c6e3e8ebee8dc4c0dbba66076819bb8c09672527c4277ca8729532ad711872218f826919f6b67218adde99018a6df9095ab2b58d803b5b93ec9802085a690e",
            hex(ent("m/83696968'/0'/1'")))
    }

    @Test fun frases() {
        assertEquals("m/83696968'/39'/0'/12'/0'", Bip85.rutaFrase(12, 0))
        val e12 = ent(Bip85.rutaFrase(12, 0))
        assertEquals("6250b68daf746d12a24d58b4787a714b", Bip85.hex(e12, 16))
        assertEquals("girl mad pet galaxy egg matter matrix prison refuse sense ordinary nose", Bip85.frase(e12, 12))
        assertEquals("near account window bike charge season chef number sketch tomorrow excuse sniff circle vital hockey outdoor supply token",
            Bip85.frase(ent(Bip85.rutaFrase(18, 0)), 18))
        assertEquals("puppy ocean match cereal symbol another shed magic wrap hammer bulb intact gadget divorce twin tonight reason outdoor destroy simple truth cigar social volcano",
            Bip85.frase(ent(Bip85.rutaFrase(24, 0)), 24))
        // Toda hija es una frase BIP39 válida, que cualquier cartera importa.
        for (n in listOf(12, 15, 18, 21, 24)) for (i in 0..3)
            assertEquals(Bip39.Result.Valid, Bip39.validate(Bip85.frase(ent(Bip85.rutaFrase(n, i)), n)))
    }

    @Test fun indicesDistintosDanHijasDistintas() {
        val a = Bip85.frase(ent(Bip85.rutaFrase(12, 0)), 12); val b = Bip85.frase(ent(Bip85.rutaFrase(12, 1)), 12)
        assertTrue(a != b)
    }

    @Test fun wifHexYClave() {
        assertEquals("m/83696968'/2'/0'", Bip85.rutaWif(0))
        val ew = ent(Bip85.rutaWif(0))
        assertEquals("7040bb53104f27367f317558e78a994ada7296c6fde36a364e5baf206e502bb1", Bip85.hex(ew, 32))
        assertEquals("Kzyv4uF39d4Jrw2W7UryTHwZr1zQVNk4dAFyqE6BuMrMh1Za7uhp", Bip85.wif(ew))
        assertEquals("m/83696968'/128169'/64'/0'", Bip85.rutaHex(64, 0))
        assertEquals("492db4698cf3b73a5a24998aa3e9d7fa96275d85724a91e71aa2d645442f878555d078fd1f1f67e368976f04137b1f7a0d19232136ca50c44614af72b5582a5c",
            Bip85.hex(ent(Bip85.rutaHex(64, 0)), 64))
        assertEquals("m/83696968'/707764'/21'/0'", Bip85.rutaClave(21, 0))
        assertEquals("dKLoepugzdVJvdL56ogNV", Bip85.clave(ent(Bip85.rutaClave(21, 0)), 21))
    }

    @Test fun deEntropiaCoincideConGenerate() {
        // El refactor de Bip39: lo que genera la app sigue siendo una frase válida de su largo.
        for (n in listOf(12, 15, 18, 21, 24)) {
            val f = Bip39.generate(n)
            assertEquals(n, f.split(' ').size)
            assertEquals(f, Bip39.deEntropia(Bip39.entropia(f)!!))
        }
    }
}
