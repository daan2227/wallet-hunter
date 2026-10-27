package com.hunter.btc

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectores de Bitcoin Core 30 (deriveaddresses y getdescriptorinfo sobre un monedero nuevo). */
class DescriptorTest {
    private val vec by lazy { JSONArray(javaClass.classLoader!!.getResourceAsStream("descriptores_core.json")!!.bufferedReader().readText()) }

    @Test fun checksumIgualQueCore() {
        for (i in 0 until vec.length()) {
            val o = vec.getJSONObject(i)
            val d = o.getString("desc").substringBefore('#')
            assertEquals(d, o.getString("checksum"), Descriptor.checksum(d))
        }
    }

    @Test fun direccionesIgualQueCore() {
        for (i in 0 until vec.length()) {
            val o = vec.getJSONObject(i); val addrs = o.getJSONArray("addrs"); if (addrs.length() == 0) continue
            val desc = o.getString("desc")
            val r = Descriptor.leer(desc) as Descriptor.R.Ok
            val rama = if (desc.contains("/1/*")) 1 else 0
            for (k in 0 until addrs.length()) {
                val spk = Descriptor.spk(r.v, rama, k, EcLento.derivador, EcLento.tweaker)!!
                assertEquals("$desc #$k", addrs.getString(k), SpkDireccion.de(spk, false))
            }
        }
    }

    @Test fun recepcionYCambioEnDosLineasYTextoCanonico() {
        val recep = vec.getJSONObject(6).getString("desc"); val cambio = vec.getJSONObject(7).getString("desc")
        val r = Descriptor.leer("$recep\n$cambio") as Descriptor.R.Ok
        assertEquals(Descriptor.Tipo.WPKH, r.v.tipo)
        assertTrue(r.avisos.isEmpty())
        val t = r.v.texto()
        assertTrue(t, t.startsWith("wpkh([aec58d11/84h/0h/0h]xpub") && t.contains("/<0;1>/*)#"))
        // El canónico vuelve a leerse igual y su checksum cuadra.
        assertEquals(t, (Descriptor.leer(t) as Descriptor.R.Ok).v.texto())
    }

    @Test fun rechazaChecksumMaloYTiposRaros() {
        val d = vec.getJSONObject(6).getString("desc")
        assertTrue(Descriptor.leer(d.dropLast(1) + "q") is Descriptor.R.Mal)
        assertTrue(Descriptor.leer("wsh(multi(1,xpub))") is Descriptor.R.Mal)
        assertTrue(Descriptor.leer("${vec.getJSONObject(0).getString("desc")}\n${vec.getJSONObject(6).getString("desc")}") is Descriptor.R.Mal)
    }

    @Test fun zpubSueltaEsWpkhSinOrigen() {
        // La xpub de la línea 6 (84h) re-codificada como zpub: mismo contenido, otra versión.
        val xpub = vec.getJSONObject(6).getString("desc").substringAfter("]").substringBefore("/")
        val p = Descriptor.b58check(xpub)!!
        val z = Descriptor.bytes("04b24746") + p.copyOfRange(4, 78)
        val zpub = base58check(z)
        val r = Descriptor.leer(zpub) as Descriptor.R.Ok
        assertEquals(Descriptor.Tipo.WPKH, r.v.tipo); assertTrue(r.avisos.isNotEmpty())
        val spk = Descriptor.spk(r.v, 0, 0, EcLento.derivador, EcLento.tweaker)!!
        assertEquals(vec.getJSONObject(6).getJSONArray("addrs").getString(0), SpkDireccion.de(spk, false))
    }

    private fun base58check(b: ByteArray): String {
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        val c = sha.digest(sha.digest(b)).copyOfRange(0, 4)
        var n = java.math.BigInteger(1, b + c); val a = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val sb = StringBuilder(); val b58 = java.math.BigInteger.valueOf(58)
        while (n.signum() > 0) { val qr = n.divideAndRemainder(b58); sb.append(a[qr[1].toInt()]); n = qr[0] }
        for (x in b + c) { if (x.toInt() == 0) sb.append('1') else break }
        return sb.reverse().toString()
    }
}
