package com.hunter.btc

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectores oficiales de BIP352 con un destinatario (la aritmética, con BigInteger: EcLentoOps). */
class SilentPaymentsTest {
    private val vec by lazy { JSONArray(javaClass.classLoader!!.getResourceAsStream("sp_envio.json")!!.bufferedReader().readText()) }

    @Test fun vectoresOficiales() {
        var n = 0
        for (k in 0 until vec.length()) {
            val o = vec.getJSONObject(k)
            val ins = o.getJSONArray("ins")
            val es = (0 until ins.length()).map { val a = ins.getJSONArray(it)
                // El 4.º campo es "negar" (P2TR de y impar). Las P2TR de los vectores ya traen la privada de la
                // clave de salida: pasarlas como Taproot "ya ajustada" hace que se niegue por la paridad; una
                // de y par da lo mismo como Taproot que como no Taproot.
                SilentPayments.Entrada(a.getString(0), a.getString(3) == "1", a.getString(1), a.getInt(2), yaAjustada = true) }
            val spk = SilentPayments.salida(EcLentoOps.ec, o.getString("addr"), false, es)
            val exp = o.getJSONArray("exp").getString(0)
            if (exp == "-") assertNull(o.getString("nombre"), spk)
            else { assertNotNull(o.getString("nombre"), spk); assertEquals(o.getString("nombre"), exp, Descriptor.hex(spk!!.copyOfRange(2, 34))) }
            n++
        }
        assertTrue(n >= 10)
    }

    @Test fun direcciones() {
        val a = vec.getJSONObject(0).getString("addr")
        assertNotNull(SilentPayments.decodificar(a, false))
        assertNotNull(SilentPayments.decodificar(a.uppercase(), false))
        assertNull(SilentPayments.decodificar(a, true))                 // red equivocada
        assertNull(SilentPayments.decodificar(a.dropLast(1) + "q", false)) // checksum
        assertTrue(SilentPayments.esDireccion(a))
    }

    @Test fun claveTaprootCuadraConBip86() {
        // Una privada interna cualquiera: la salida calculada con la clave ajustada tiene que ser la misma
        // que pagar a esa entrada como "ya ajustada" con la privada d' = ±d + t.
        val d = "c90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b14e5c9"
        val txid = "aa".repeat(32)
        val dir = vec.getJSONObject(0).getString("addr")
        val a = SilentPayments.salida(EcLentoOps.ec, dir, false, listOf(SilentPayments.Entrada(d, true, txid, 0)))!!
        // d' a mano
        val p = EcLentoOps.ec.op("pub", d, "")
        val t = Descriptor.hex(EcLento.tagged("TapTweak", Descriptor.bytes(p.substring(2))))
        val dd = EcLentoOps.ec.op("priv_sum", "$d,$t", if (p.startsWith("03")) "1,0" else "0,0")
        val b = SilentPayments.salida(EcLentoOps.ec, dir, false, listOf(SilentPayments.Entrada(dd, true, txid, 0, yaAjustada = true)))!!
        assertEquals(Descriptor.hex(a), Descriptor.hex(b))
        // y la x de pub(d') es la salida BIP86 de la interna
        assertEquals(EcLento.tweaker.tr(p.substring(2)), EcLentoOps.ec.op("pub", dd, "").substring(2))
    }
}
