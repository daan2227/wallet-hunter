package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LnurlTest {
    // El ejemplo de LUD-01: el LNURL bech32 y la URL que codifica.
    private val EJEMPLO = "LNURL1DP68GURN8GHJ7UM9WFMXJCM99E3K7MF0V9CXJ0M385EKVCENXC6R2C35XVUKXEFCV5MKVV34X5EKZD3EV56NYD3HXQURZEPEXEJXXEPNXSCRVWFNV9NXZCN9XQ6XYEFHVGCXXCMYXYMNSERXFQ5FNS"
    private val URL = "https://service.com/api?q=3fc3645b439ce8e7f2553a69e5267081d96dcd340693afabe04be7b0ccd178df"

    @Test fun lud01() {
        assertEquals(Lnurl.Destino.Url(URL), Lnurl.leer(EJEMPLO))
        assertEquals(Lnurl.Destino.Url(URL), Lnurl.leer("lightning:" + EJEMPLO.lowercase()))
        assertEquals(Lnurl.Destino.Url(URL), Lnurl.leer("https://service.com/giftcard/redeem?id=123&lightning=$EJEMPLO"))
        // Un carácter cambiado rompe la suma de comprobación: no es nada.
        assertNull(Lnurl.leer(EJEMPLO.dropLast(1) + "T"))
        assertNull(Lnurl.leer(EJEMPLO.substring(0, 20) + EJEMPLO.substring(20).lowercase()))   // mayúsculas y minúsculas mezcladas
    }

    @Test fun lud17yLud16() {
        assertEquals(Lnurl.Destino.Url("https://domain.com/path", "withdrawRequest"), Lnurl.leer("lnurlw://domain.com/path"))
        assertEquals(Lnurl.Destino.Url("http://abcdefgh.onion/p", "payRequest"), Lnurl.leer("lnurlp://abcdefgh.onion/p"))
        val d = Lnurl.leer("Satoshi@Bitcoin.org") as Lnurl.Destino.Direccion
        assertEquals("https://bitcoin.org/.well-known/lnurlp/satoshi", Lnurl.urlDe(d))
        assertEquals("https://x.io/.well-known/lnurlp/al+tag", Lnurl.urlDe(Lnurl.direccion("al+tag@x.io")!!))
        assertNull(Lnurl.direccion("con espacio@x.io")); assertNull(Lnurl.direccion("a@localhost")); assertNull(Lnurl.direccion("@x.io"))
    }

    @Test fun soloHttpsOOnion() {
        assertTrue(Lnurl.urlValida("https://a.com/x")); assertTrue(Lnurl.urlValida("http://zz.onion/x"))
        assertTrue(!Lnurl.urlValida("http://a.com/x")); assertTrue(!Lnurl.urlValida("https://user:pw@a.com/x")); assertTrue(!Lnurl.urlValida("ftp://a.com"))
    }

    private val PAGO = """{"tag":"payRequest","callback":"https://a.com/cb?id=7","minSendable":1000,"maxSendable":5000000,
        "metadata":"[[\"text/plain\",\"Coffee\"],[\"text/identifier\",\"ana@a.com\"]]","commentAllowed":50}"""

    @Test fun pagoCompleto() {
        val p = (Lnurl.leerRespuesta(PAGO) as Lnurl.Respuesta.EsPago).p
        assertEquals("Coffee", p.descripcion); assertEquals("ana@a.com", p.identificador); assertEquals(50, p.comentarioMax)
        assertEquals("https://a.com/cb?id=7&amount=21000&comment=hola+qu%C3%A9+tal", Lnurl.urlFactura(p, 21000, "hola qué tal"))
        assertTrue(runCatching { Lnurl.urlFactura(p, 999, null) }.isFailure)          // por debajo del mínimo
        val f = Lnurl.leerFactura("""{"pr":"lnbc210n1xyz","routes":[],"successAction":{"tag":"url","description":"Order","url":"https://a.com/o/1"}}""", p)
        assertEquals("lnbc210n1xyz", f.pr); assertEquals(Lnurl.Accion.Enlace("Order", "https://a.com/o/1"), f.accion)
        // Un enlace a otro dominio no se ofrece (LUD-09).
        assertNull(Lnurl.leerFactura("""{"pr":"lnbc1","successAction":{"tag":"url","description":"x","url":"https://evil.com/"}}""", p).accion)
        assertEquals("No", Lnurl.leerFactura("""{"status":"ERROR","reason":"No"}""", p).error)
        assertNull(Lnurl.comprobarImporte(21000, 21000))
        assertTrue(Lnurl.comprobarImporte(21000, 22000) != null); assertTrue(Lnurl.comprobarImporte(21000, null) != null)
    }

    @Test fun respuestasMalas() {
        assertTrue(Lnurl.leerRespuesta("""{"status":"ERROR","reason":"Nope"}""") == Lnurl.Respuesta.Error("Nope"))
        assertTrue(Lnurl.leerRespuesta(PAGO.replace("https://a.com/cb", "http://a.com/cb")) is Lnurl.Respuesta.Error)   // callback no seguro
        assertTrue(Lnurl.leerRespuesta(PAGO.replace("\"minSendable\":1000", "\"minSendable\":9000000")) is Lnurl.Respuesta.Error)
        assertTrue(Lnurl.leerRespuesta(PAGO.replace("text/plain", "text/other")) is Lnurl.Respuesta.Error)
        assertTrue(Lnurl.leerRespuesta("<html>") is Lnurl.Respuesta.Error)
    }

    private fun hexa(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun lud05VectorOficial() {
        // LUD-05: hashingKey y dominio → los cuatro índices; los ≥ 2^31 van endurecidos.
        val idx = Lnurl.indicesAuth(hexa("7d417a6a5e9a6a4a879aeaba11a11838764c8fa2b959c242d43dea682b3e409b"), "site.com")
        assertEquals(listOf(1588488367L, 2659270754L, 38110259L, 4136336762L), idx)
        assertEquals("m/138'/1588488367/511787106'/38110259/1988853114'", Lnurl.rutaAuth(idx))
    }

    @Test fun lud04FirmaSobreK1TalCual() {
        // El ejemplo de LUD-04: la firma DER verifica sobre k1 (32 bytes) SIN volver a hacer hash, con s bajo.
        val k1 = java.math.BigInteger(1, hexa("e2af6254a8df433264fa23f67eb8188635d15ce883e8fc020989d5f82ae6f11e"))
        val pub = EcLento.parse(hexa("02c3b844b8104f0c1b15c507774c9ba7fc609f58f343b9b149122e944dd20c9362"))
        val der = hexa("304402203767faf494f110b139293d9bab3c50e07b3bf33c463d4aa767256cd09132dc5102205821f8efacdb5c595b92ada255876d9201e126e2f31a140d44561cc1f7e9e43d")
        val r = java.math.BigInteger(1, der.copyOfRange(4, 4 + der[3])); val sIni = 4 + der[3] + 2
        val s = java.math.BigInteger(1, der.copyOfRange(sIni, sIni + der[sIni - 1]))
        val n = EcLento.N; val w = s.modInverse(n)
        assertTrue(s <= n.shiftRight(1))
        assertEquals(r, EcLento.add(EcLento.mul(k1 * w % n), EcLento.mul(r * w % n, pub))!!.first.mod(n))
    }

    @Test fun enlaceDeLogin() {
        val k1 = "e2af6254a8df433264fa23f67eb8188635d15ce883e8fc020989d5f82ae6f11e"
        val l = Lnurl.login("https://Auth.Site.com/lnurl?tag=login&k1=$k1&action=register")!!
        assertEquals(k1, l.k1); assertEquals("register", l.accion); assertEquals("auth.site.com", l.dominio)
        assertEquals("https://Auth.Site.com/lnurl?tag=login&k1=$k1&action=register&sig=3044&key=02ab", Lnurl.urlLogin(l, "3044", "02ab"))
        assertNull(Lnurl.login("https://a.com/x?tag=payRequest&k1=$k1"))
        assertNull(Lnurl.login("https://a.com/x?tag=login&k1=abc"))
        assertEquals("login", Lnurl.leer("keyauth://a.com/x?tag=login&k1=$k1").let { (it as Lnurl.Destino.Url).pista })
    }

    @Test fun retiro() {
        val r = (Lnurl.leerRespuesta("""{"tag":"withdrawRequest","callback":"https://w.com/cb","k1":"abc 1","minWithdrawable":0,"maxWithdrawable":100000,"defaultDescription":"Gift"}""")
            as Lnurl.Respuesta.EsRetiro).r
        assertEquals("https://w.com/cb?k1=abc+1&pr=lnbc1%2B", Lnurl.urlRetiro(r, "lnbc1+"))
        assertNull(Lnurl.leerOk("""{"status":"OK"}""")); assertEquals("bad", Lnurl.leerOk("""{"status":"ERROR","reason":"bad"}"""))
    }
}
