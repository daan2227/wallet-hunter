package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hucha con after(T): scripts y direcciones comparados con embit; el gasto se firma y finaliza con la app. */
class HuchaTest {
    private val cuenta = "tprv8hhER7E96fEP1uEfceE422kfzbiKW2jnWv22khwTKuRfJDCPURpT4NUvL7Y83asuUk6n9S6oYHqnjyhUiK22cyNfxrKHdd8zGLuqzdffFks"
    private val desc = "wsh(and_v(v:pk([73c5da0a/48h/1h/2h/2h]tpubDEPGZXGPF2v3uNGTWHteRSQnZdEFfMvh6Dcp3DykkBE48hTA6pe3Es6nWH5dAThPyvqmZGmZqQhTw5kxqgR5uJW6Ei2VWyAAN92yLcYBvzV/<0;1>/*),after(1767225600)))"
    private fun motor() = MotorHucha.leer(desc, EcLento.derivador)!!.first!!

    @Test fun comoEmbit() {
        val m = motor(); assertEquals(1_767_225_600L, m.hasta); assertTrue(m.esFecha)
        val esperado = listOf(
            "00201aa4ee3e6bcd492d8e60c7f3aa095f011efee35dcd66d597b9d3a2c40a9128bd" to "2103b293098ee65db9033cd938c0d82c0efeafc1f16d36a48254aa4d10bb4fd5bae9ad0400b95569b1",
            "002000494e4cde85d34ad00d1500caa55992f06fe22a38ec1de06d5d2bf3efe4172f" to "2103cfb9642b1867d351afc94854c5a217681089cd548ef7df887f4b65bf335f2faead0400b95569b1",
            "00200da81dcb0cae2dff7f2328ad242df32619d73bd57c495c7987e5858986b123e3" to "21025b56ffdf4e39958ba88ac327158e8a3356fb6eb451e30e6cb9c61b3aaabd9672ad0400b95569b1")
        esperado.forEachIndexed { i, (spk, s) ->
            assertEquals(spk, Descriptor.hex(m.spk(0, i)!!)); assertEquals(s, Descriptor.hex(m.script(0, i)!!.first))
        }
        assertEquals(m.texto(), MotorHucha.leer(m.texto(), EcLento.derivador)!!.first!!.texto())
    }

    @Test fun gastoConLocktime() {
        val m = motor()
        val d = Psbt.parse(m.psbt(listOf(Moneda(0, 0, "11".repeat(32), 0, 100_000)), Descriptor.bytes("0014" + "22".repeat(20)),
            60_000L, 39_000L, 0, 0) { null }!!)!!
        assertEquals(1_767_225_600L, d.tx.locktime); assertTrue(d.tx.ins[0].seq < 0xFFFFFFFFL)
        val priv = EcLentoPriv.derivar(cuenta, "0/0")
        val pub = Descriptor.bytes(EcLentoOps.ec.op("pub", priv, ""))
        assertEquals(1, Psbt.firmarWsh(d, pub) { z -> EcLentoPriv.firmar(priv, z) })
        assertEquals(1, Psbt.finalizar(d) { s, f -> m.testigoWsh(s, f) })
        val w = d.inputs[0].first { it.key.size == 1 && it.key[0].toInt() == 0x08 }.value
        assertEquals(2, w[0].toInt())                                   // [firma, script]
    }

    @Test fun bloqueoYLectura() {
        val m = motor()
        assertTrue(m.bloqueada(1_767_225_600L, 0)); assertTrue(!m.bloqueada(1_767_225_600L + 3600, 0))
        val alt = MotorHucha.leer(desc.replace("1767225600", "900000"), EcLento.derivador)!!.first!!
        assertTrue(alt.bloqueada(0, 899_999) && !alt.bloqueada(0, 900_000))
        assertNull(MotorHucha.leer("wsh(multi(1,x))", EcLento.derivador))
        assertTrue(MotorHucha.leer(desc.replace("1767225600", "0"), EcLento.derivador)!!.first == null)
    }
}
