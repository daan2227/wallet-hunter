package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenTimestamps contra los ejemplos oficiales del cliente (resources/ots). Lo esperado (qué atestaciones
 * hay y con qué mensaje) lo dio la biblioteca de referencia, python-opentimestamps.
 */
class OtsTest {
    private fun rec(n: String) = javaClass.classLoader!!.getResourceAsStream("ots/$n")!!.readBytes()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b)

    /** nombre → (digest del archivo, atestaciones como "BTC altura msg" / "PENDING uri msg"). */
    private val esperado = mapOf(
        "hello-world.txt" to ("03ba204e50d126e4674c005e04d82e84c21366780af1f43bd54a37816b6ab340" to
            listOf("BTC 358391 007ee445d23ad061af4a36b809501fab1ac4f2d7e7a739817dd0cbb7ec661b8a")),
        "incomplete.txt" to ("05c4f616a8e5310d19d938cfd769864d7f4ccdc2ca8b479b10af83564b097af9" to
            listOf("PENDING https://alice.btc.calendar.opentimestamps.org 57cfa5c46716df9bd9e83595bce439c58108d8fcc1678f30d4c6731c3f1fa6c79ed712c66fb1ac8d4e4eb0e7")),
        "bad-stamp.txt" to ("7e3717bbe020f53cdc6c40154a1a8e55bddc13a28c8bb3c82e9ee64b81b44872" to
            listOf("BTC 358391 921f81b9147c9aebe712d7805d810cf0f762479967e4c26008178277b89db41b")),
        "two-calendars.txt" to ("efaa174f68e59705757460f4f7d204bd2b535cfd194d9d945418732129404ddb" to listOf(
            "PENDING https://alice.btc.calendar.opentimestamps.org 57d46515fdd8c2334c77b1f204338bb2178d73e523988d7dcda13259d3a099f31623755deadd66b1688d5574",
            "PENDING https://bob.btc.calendar.opentimestamps.org 57d4651676e9ac3b3b57c1584828ea5162979c96483a03c87c69a1b8614d1cbaf387e0f5647b90ea1b270a97")),
        "merkle1.txt" to ("d32fee9a827f5a0d580f80beb7edce662dd99fcd6591e4ef8a6244403df0b7c9" to listOf(
            "PENDING https://alice.btc.calendar.opentimestamps.org 57d982df8b35bc0a91a93d6d17e0162868dc123bc8b1bbfab22af9268f7fea70376b5cb0b1f26e2e55590477",
            "PENDING https://bob.btc.calendar.opentimestamps.org 57d982e0a2181eb7fd470600d82119b73d7ae5da2d8c84db7d55fd45b568fac036d86f906644ef713071762a")),
    )

    private fun texto(m: ByteArray, a: Ots.Atestacion) = when (a) {
        is Ots.Atestacion.Bitcoin -> "BTC ${a.altura} ${hex(m)}"
        is Ots.Atestacion.Pendiente -> "PENDING ${a.uri} ${hex(m)}"
        is Ots.Atestacion.Otra -> "OTHER"
    }

    @Test fun ejemplosOficiales() {
        for ((n, e) in esperado) {
            val bruto = rec("$n.ots")
            val a = Ots.leer(bruto)
            assertEquals(n, e.first, hex(a.digest))
            assertEquals(n, hex(a.digest), hex(Ots.hashArchivo(a.hashTag, rec(n))))   // el archivo es el del sello
            assertEquals(n, e.second.sorted(), a.sello.todas().map { (m, at) -> texto(m, at) }.sorted())
            assertTrue("$n: se reescribe idéntico", Ots.escribir(a).contentEquals(bruto))
        }
    }

    @Test fun anclaSoloLaRaizDeMerkle() {
        // Cabecera hecha a mano con la raíz de Merkle que da hello-world y una hora conocida: ancla; la de
        // bad-stamp (mismo bloque, otro mensaje) no. En la app la cabecera viene del explorador.
        val bueno = Ots.leer(rec("hello-world.txt.ots")).sello.todas().single().first
        val malo = Ots.leer(rec("bad-stamp.txt.ots")).sello.todas().single().first
        val cab = ByteArray(80).also { System.arraycopy(bueno, 0, it, 36, 32); it[68] = 0x10; it[69] = 0x20; it[70] = 0x30; it[71] = 0x40 }
        assertEquals(0x40302010L, Ots.horaSiAncla(bueno, cab))
        assertNull(Ots.horaSiAncla(malo, cab))
        assertNull(Ots.horaSiAncla(bueno, cab.copyOf(79)))
    }

    @Test fun selloNuevoYRespuestaDeCalendario() {
        val datos = "Mi contrato, firmado el 29 de octubre".toByteArray()
        val nonce = ByteArray(16) { (it * 7).toByte() }
        val (arch, comp) = Ots.nuevo(sha(datos), nonce)
        assertEquals(hex(sha(sha(datos) + nonce)), hex(comp.msg))
        // Lo que devolvería un calendario: prepend + sha256 hasta una atestación pendiente.
        val cal = Ots.Sello(comp.msg)
        cal.rama(Ots.Op(0xf1, ByteArray(8) { 1 })).rama(Ots.Op.SHA256).atestaciones.add(Ots.Atestacion.Pendiente("https://a.pool.opentimestamps.org"))
        comp.fusionar(Ots.leerSello(Ots.escribirSello(cal), comp.msg))
        val bytes = Ots.escribir(arch)
        val otra = Ots.leer(bytes)
        assertEquals(hex(sha(datos)), hex(otra.digest))
        assertEquals(listOf("https://a.pool.opentimestamps.org"), otra.sello.pendientes().map { it.second })
        assertTrue(Ots.escribir(otra).contentEquals(bytes))
    }

    @Test fun actualizarCompletaLaRama() {
        // Simula la actualización: al nodo pendiente se le une la rama que acaba en un bloque.
        val a = Ots.leer(rec("incomplete.txt.ots"))
        val (nodo, uri) = a.sello.pendientes().single()
        assertTrue(Ots.calendarioConocido(uri))
        val resp = Ots.Sello(nodo.msg)
        resp.rama(Ots.Op.SHA256).atestaciones.add(Ots.Atestacion.Bitcoin(800000))
        nodo.fusionar(Ots.leerSello(Ots.escribirSello(resp), nodo.msg))
        val alturas = Ots.leer(Ots.escribir(a)).sello.todas().map { it.second }.filterIsInstance<Ots.Atestacion.Bitcoin>().map { it.altura }
        assertEquals(listOf(800000L), alturas)
    }

    @Test fun cabeceraDelBloqueGenesis() {
        // La cabecera del bloque 0, que es pública e inmutable: su hash, su prueba de trabajo, su raíz y su hora.
        val g = ByteArray(80) { "0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c".substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        assertEquals("000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", Ots.hashCabecera(g))
        assertTrue(Ots.trabajoValido(g))
        val raiz = "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b"   // como se muestra
        val raizInterna = ByteArray(32) { raiz.substring(it * 2, it * 2 + 2).toInt(16).toByte() }.reversedArray()
        assertEquals(1231006505L, Ots.horaSiAncla(raizInterna, g))
        // Con otro nonce ya no tiene la prueba de trabajo; y una dificultad por debajo de la mínima no vale.
        assertTrue(!Ots.trabajoValido(g.copyOf().also { it[76] = (it[76] + 1).toByte() }))
        assertTrue(!Ots.trabajoValido(g.copyOf().also { it[75] = 0x20 }))
    }

    @Test fun rechazaLoQueNoEs() {
        val bruto = rec("hello-world.txt.ots")
        for (malo in listOf(ByteArray(0), bruto.copyOf(40), bruto.copyOf(bruto.size - 3), bruto + byteArrayOf(0))) {
            assertTrue(runCatching { Ots.leer(malo) }.isFailure)
        }
        assertTrue(!Ots.calendarioConocido("https://evil.example.com/opentimestamps.org"))
        assertTrue(!Ots.calendarioConocido("http://a.pool.opentimestamps.org"))
        assertNotNull(Ots.leer(bruto))
    }
}
