package com.hunter.btc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BIP353 + DNSSEC contra una jerarquía de prueba con raíz propia (resources/bip353_cadena.json, hecha con
 * dnspython por tools/bip353_cadena.py): raíz RSA/SHA-256, tld. ECDSA P-256, example.tld. Ed25519, p384.tld.
 * P-384 y rsa512.tld. RSA/SHA-512, con un CNAME entre zonas. Y los casos que tienen que fallar: TXT cambiado,
 * firma caducada, DS SHA-1, DS de otra clave, sin firma, comodín y dos registros "bitcoin:".
 */
class Bip353Test {
    private val c by lazy { JSONObject(javaClass.classLoader!!.getResourceAsStream("bip353_cadena.json")!!.bufferedReader().readText()) }
    private val ahora get() = c.getLong("ahora")
    private val ancla by lazy {
        val p = c.getString("ancla").split(' ')
        listOf(Dnssec.Ds(p[0].toInt(), p[1].toInt(), p[2].toInt(), Dnssec.hexa(p[3])))
    }
    private val tipos = mapOf(Dns.TXT to "TXT", Dns.DNSKEY to "DNSKEY", Dns.DS to "DS")
    private var consultas = 0
    private val pedir: (String, Int) -> ByteArray? = { n, t ->
        consultas++
        c.getJSONObject("respuestas").optString("${Dns.canonico(n)}|${tipos[t]}", "").takeIf { it.isNotEmpty() }?.let { java.util.Base64.getDecoder().decode(it) }
    }
    private fun resolver(u: String, d: String = "example.tld", t: Long = ahora) = Bip353.resolver(Bip353.Nombre(u, d), pedir, t, ancla)

    @Test fun validosPorCadaAlgoritmo() {
        // alice: TXT en dos cadenas (se juntan) junto a otro TXT que no es de pago (se ignora). Ed25519 → P-256 → RSA.
        assertEquals("bitcoin:bc1qalice?lno=lno1alice", (resolver("alice") as Bip353.Resultado.Instrucciones).uri)
        // bob: CNAME firmado en example.tld. hacia carol en p384.tld. (P-384).
        assertEquals("bitcoin:?lno=lno1carol", (resolver("bob") as Bip353.Resultado.Instrucciones).uri)
        // dave: RSA/SHA-512.
        assertEquals("bitcoin:bc1qdave", (resolver("dave", "rsa512.tld") as Bip353.Resultado.Instrucciones).uri)
    }

    @Test fun loQueTieneQueFallar() {
        // Cada uno tiene que fallar POR SU motivo, no por otro (p. ej. por falta de respuesta).
        for ((u, d, motivo) in listOf(
            Triple("eve", "example.tld", "does not match"),                 // TXT cambiado tras firmar
            Triple("old", "example.tld", "validity period"),                // firma caducada
            Triple("x", "sha1.tld", "keys of sha1.tld"),                    // su zona sólo tiene DS SHA-1
            Triple("x", "falso.tld", "keys of falso.tld"),                  // el DS del padre es de otra clave
            Triple("nosig", "example.tld", "Not signed"),                   // sin firma
            Triple("wild", "example.tld", "Wildcard"),                      // comodín
            Triple("two", "example.tld", "more than one payment record"))) { // dos registros bitcoin:
            val r = resolver(u, d)
            assertTrue("$u@$d: $r", r is Bip353.Resultado.Error && motivo in r.motivo)
        }
        // Con la hora fuera de la validez de TODA la cadena, ni el bueno vale.
        assertTrue(resolver("alice", t = 1_950_000_000) is Bip353.Resultado.Error)
        // Con el ancla verdadera de la raíz (no la de prueba), la cadena de prueba no vale.
        assertTrue(Bip353.resolver(Bip353.Nombre("alice", "example.tld"), pedir, ahora) is Bip353.Resultado.Error)
        // Un nombre sin respuesta: no se inventa nada.
        assertTrue(resolver("nadie") !is Bip353.Resultado.Instrucciones)
    }

    @Test fun nombres() {
        assertEquals(Bip353.Nombre("ana", "x.com"), Bip353.leer("₿Ana@X.com"))
        assertEquals("ana.user._bitcoin-payment.x.com.", Bip353.leer("ana@x.com")!!.consulta)
        assertEquals("₿ana@x.com", Bip353.leer(" ana@x.com ").toString())
        assertNull(Bip353.leer("ana@localhost")); assertNull(Bip353.leer("a na@x.com")); assertNull(Bip353.leer("@x.com"))
        assertNull(Bip353.leer("añá@x.com"))
    }

    @Test fun parametros() {
        val u = "bitcoin:bc1qxyz?amount=0.001&lno=lno1abc&sp=sp1q%2Bx"
        assertEquals("bc1qxyz", Bip353.direccion(u)); assertEquals("lno1abc", Bip353.parametro(u, "LNO"))
        assertEquals("sp1q+x", Bip353.parametro(u, "sp")); assertNull(Bip353.parametro(u, "lightning"))
        assertNull(Bip353.direccion("bitcoin:?lno=lno1"))
    }

    @Test fun consultaDns() {
        // La consulta que se manda: RD+CD y EDNS con DO; y que se lee de vuelta.
        val q = Dns.consulta("Ana.User._bitcoin-payment.X.com", Dns.TXT)
        assertEquals(0x01, q[2].toInt()); assertEquals(0x10, q[3].toInt())
        assertEquals("ana.user._bitcoin-payment.x.com." to 45, Dns.nombre(q, 12))   // 12 de cabecera + 33 del nombre
    }
}
