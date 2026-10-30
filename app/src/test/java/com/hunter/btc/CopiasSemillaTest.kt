package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SeedQR con los vectores de SeedSigner (docs/seed_qr) y Seed XOR con los ejemplos de Coldcard (docs/seed-xor.md). */
class CopiasSemillaTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun seedQrVectores() {
        val f1 = "attack pizza motion avocado network gather crop fresh patrol unusual wild holiday candy pony ranch winter theme error hybrid van cereal salon goddess expire"
        assertEquals("011513251154012711900771041507421289190620080870026613431420201617920614089619290300152408010643", SeedQr.estandar(f1))
        assertEquals("0e74b64107f94cc0ccfae6a13dcbec3662154fec67e0e00999c07892597d190a", hex(SeedQr.compacto(f1)!!))
        // El vector 2 lleva un byte 0x00 en medio: se codifica y se lee entero.
        val f2 = "atom solve joy ugly ankle message setup typical bean era cactus various odor refuse element afraid meadow quick medal plate wisdom swap noble shallow"
        assertEquals("011416550964188800731119157218870156061002561932122514430573003611011405110613292018175411971576", SeedQr.estandar(f2))
        assertEquals("0e59dde27600931 7f1275f13898880 78c99368d1e82489b5f629531fc5b6a56e".replace(" ", ""), hex(SeedQr.compacto(f2)!!))
        val f4 = "forum undo fragile fade shy sign arrest garment culture tube off merit"
        assertEquals("073318950739065415961602009907670428187212261116", SeedQr.estandar(f4))
        for (f in listOf(f1, f2, f4)) {
            assertEquals(f, SeedQr.deEstandar(SeedQr.estandar(f)!!)); assertEquals(f, SeedQr.deCompacto(SeedQr.compacto(f)!!))
        }
        assertNull(SeedQr.deEstandar("0733189507390654159616020099076704281872122611"))      // corto
        assertNull(SeedQr.deEstandar("073318950739065415961602009907670428187212261117"))    // checksum
    }

    @Test fun seedXorEjemplosColdcard() {
        val a24 = "romance wink lottery autumn shop bring dawn tongue range crater truth ability miss spice fitness easy legal release recall obey exchange recycle dragon room"
        val b24 = "lion misery divide hurry latin fluid camp advance illegal lab pyramid unaware eager fringe sick camera series noodle toy crowd jeans select depth lounge"
        val c24 = "vault nominee cradle silk own frown throw leg cactus recall talent worry gadget surface shy planet purpose coffee drip few seven term squeeze educate"
        assertEquals("silent toe meat possible chair blossom wait occur this worth option bag nurse find fish scene bench asthma bike wage world quit primary indoor",
            SeedXor.juntar(listOf(a24, b24, c24)))
        val a12 = "romance wink lottery autumn shop bring dawn tongue range crater truth ability"
        val b12 = "boat unfair shell violin tree robust open ride visual forest vintage approve"
        val c12 = "lion misery divide hurry latin fluid camp advance illegal lab pyramid unhappy"
        assertEquals("cannon opinion leader nephew found yard metal galaxy crouch between real trade", SeedXor.juntar(listOf(a12, b12, c12)))
        assertNull(SeedXor.juntar(listOf(a12, b24)))
    }

    @Test fun seedXorPartirYJuntar() {
        for (f in listOf("cannon opinion leader nephew found yard metal galaxy crouch between real trade", Bip39.generate(24))) for (n in 2..4) {
            val partes = SeedXor.partir(f, n)!!
            assertEquals(n, partes.size)
            partes.forEach { assertEquals(Bip39.Result.Valid, Bip39.validate(it)) }
            assertEquals(f, SeedXor.juntar(partes))
        }
    }
}
