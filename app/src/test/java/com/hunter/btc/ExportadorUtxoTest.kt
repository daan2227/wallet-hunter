package com.hunter.btc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import com.hunter.btc.InstantaneaUtxo.Col
import com.hunter.btc.ExportadorUtxo.FormatoClave as F

class ExportadorUtxoTest {

    private fun String.h() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun varint(n0: Long): ByteArray {
        val t = ArrayList<Int>(); var n = n0; var len = 0
        while (true) { t.add((n and 0x7f).toInt() or (if (len != 0) 0x80 else 0)); if (n <= 0x7f) break; n = (n shr 7) - 1; len++ }
        return t.reversed().map { it.toByte() }.toByteArray()
    }

    private val GX = "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798".h()

    private fun moneda(o: ByteArrayOutputStream, vout: Int, altura: Long, sat: Long, nSize: Int, datos: ByteArray) {
        o.write(vout); o.write(varint(altura * 2)); o.write(varint(ExportadorUtxo.comprimir(sat)))
        o.write(varint(nSize.toLong())); o.write(datos)
    }

    private fun snapshot(monedas: Long, cuerpo: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf('u'.code.toByte(), 't'.code.toByte(), 'x'.code.toByte(), 'o'.code.toByte(), 0xff.toByte(), 2, 0))
        o.write("f9beb4d9".h()); o.write(ByteArray(32) { 7 })
        for (i in 0 until 8) o.write(((monedas shr (8 * i)) and 0xff).toInt())
        o.write(cuerpo); return o.toByteArray()
    }

    @Test fun importeIdaYVuelta() {
        for (n in listOf(0L, 1L, 5L, 546L, 1000L, 123_456_789L, 100_000_000L, 2_100_000_000_000_000L, 5_000_000_000L))
            assertEquals(n, ExportadorUtxo.importe(ExportadorUtxo.comprimir(n)))
        assertEquals(9L, ExportadorUtxo.comprimir(100_000_000L))
    }

    @Test fun tiempoInterpola() {
        // Un bloque cada 600 s desde el 2009-01-03 (1231006505); anclas cada 100 bloques hasta el 250.
        val t0 = 1231006505L
        val t = ExportadorUtxo.TiempoDeBloques(longArrayOf(t0, t0 + 60_000, t0 + 120_000), 250, t0 + 150_000)
        assertEquals(t0, t.segundos(0)); assertEquals(t0 + 30_000, t.segundos(50)); assertEquals(t0 + 60_000, t.segundos(100))
        assertEquals(t0 + 135_000, t.segundos(225)); assertEquals("2009-01-03", t.dia(0))
        assertEquals("2009-01-04", t.dia(100)); assertEquals("2009-01-05", t.dia(200))
    }

    @Test fun lotes() {
        val r = CoreRpc.interpretarLote("""[{"result":"b","error":null,"id":1},{"result":"a","error":null,"id":0},{"result":null,"error":null,"id":2}]""", 3)
        assertEquals(listOf<Any?>("a", "b", null), r)
        try { CoreRpc.interpretarLote("""[{"result":null,"error":{"code":-8,"message":"Block height out of range"},"id":0}]""", 1); fail() }
        catch (e: CoreRpcError) { assertEquals(-8, e.code) }
        try { CoreRpc.interpretarLote("""{"result":null,"error":{"code":-28,"message":"loading"},"id":"wh"}""", 1); fail() }
        catch (e: CoreRpcError) { assertTrue(e.arrancando) }
    }

    @Test fun exportaTodoElConjunto() {
        val a = ByteArray(20) { (0xA0 + it).toByte() }; val b = ByteArray(20) { (0x10 + it).toByte() }
        val wpkh = "751e76e8199196d454941c45d1b3a323f1433bd6".h()
        val cuerpo = ByteArrayOutputStream()
        val txid1 = ByteArray(32) { (it + 1).toByte() }
        cuerpo.write(txid1); cuerpo.write(4)
        moneda(cuerpo, 0, 0, 5_000_000_000L, 0, a)               // P2PKH, 50 BTC
        moneda(cuerpo, 1, 100, 100_000L, 1, b)                    // P2SH, 0,001
        moneda(cuerpo, 2, 100, 5L, 2, GX)                         // P2PK comprimida, polvo
        moneda(cuerpo, 3, 200, 150_000L, 4, GX)                   // P2PK sin comprimir
        cuerpo.write(ByteArray(32) { 9 }); cuerpo.write(3)
        moneda(cuerpo, 0, 200, 250_000L, 6 + 22, "0014".h() + wpkh)     // P2WPKH
        moneda(cuerpo, 1, 200, 1_000_000L, 6 + 34, "5120".h() + GX)     // P2TR
        moneda(cuerpo, 2, 200, 1_000_000L, 6 + 5, "6a0368656c".h())     // OP_RETURN raro: no estándar
        val dir = java.nio.file.Files.createTempDirectory("utxocsv").toFile()
        try {
            val e = File(dir, "s.dat"); e.writeBytes(snapshot(7, cuerpo.toByteArray()))
            val t0 = 1231006505L
            val t = ExportadorUtxo.TiempoDeBloques(longArrayOf(t0, t0 + 60_000, t0 + 120_000), 250, t0 + 150_000)
            val s = File(dir, "o.csv")
            var avisos = 0
            val r = ExportadorUtxo.exportar(e, s, FiltroUtxo(minSat = 100_000L), t, ConversorUtxo.Cancelar()) { _, _, _ -> avisos++ }!!
            assertEquals(7L, r.monedas); assertEquals(6L, r.filas); assertTrue(avisos >= 1)
            val l = s.readLines()
            assertEquals("wallet,utxo,funds_btc,wallet_type,public_key,tx_date", l[0])
            val txid1Hex = txid1.reversedArray().joinToString("") { "%02x".format(it) }
            assertEquals("${SpkDireccion.p2pkh(a)},$txid1Hex:0,50.00000000,P2PKH,,2009-01-03", l[1])
            assertEquals("${SpkDireccion.p2sh(b)},$txid1Hex:1,0.00100000,P2SH,,2009-01-04", l[2])
            // P2PK sin comprimir: dirección 1EHNa6…, clave de 65 bytes.
            assertTrue(l[3].startsWith("1EHNa6Q4Jz2uvNExL497mE43ikXhwF6kZm,$txid1Hex:3,0.00150000,P2PK,04" + "79be667e"))
            assertTrue(l[4].startsWith("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4,"))
            assertTrue(l[4].contains(",0.00250000,P2WPKH,,"))
            assertTrue(l[5].startsWith("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0,"))
            assertTrue(l[5].contains(",P2TR,79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798,"))
            assertTrue(l[6].startsWith(",") && l[6].contains(",Non-standard,"))
            assertEquals(7, l.size)
            // Sin mínimo entra también el polvo (la P2PK comprimida de 5 sat).
            val todas = ExportadorUtxo.exportar(e, File(dir, "t.csv"), FiltroUtxo(), t, ConversorUtxo.Cancelar()) { _, _, _ -> }!!
            assertEquals(7L, todas.filas)
            assertTrue(File(dir, "t.csv").readLines().any { it.startsWith("1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH,") && it.contains(",0.00000005,P2PK,02") })
            assertFalse(File(dir, "o.csv.part").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun columnasAMedida() {
        val cuerpo = ByteArrayOutputStream()
        val txid = ByteArray(32) { (it + 1).toByte() }
        cuerpo.write(txid); cuerpo.write(3)
        moneda(cuerpo, 0, 0, 5_000_000_000L, 0, ByteArray(20) { (0xA0 + it).toByte() })     // P2PKH
        moneda(cuerpo, 1, 200, 150_000L, 4, GX)                                              // P2PK sin comprimir
        moneda(cuerpo, 2, 200, 1_000_000L, 6 + 34, "5120".h() + GX)                           // P2TR
        val dir = java.nio.file.Files.createTempDirectory("utxocsv").toFile()
        try {
            val e = File(dir, "s.dat"); e.writeBytes(snapshot(3, cuerpo.toByteArray()))
            val t0 = 1231006505L
            val t = ExportadorUtxo.TiempoDeBloques(longArrayOf(t0, t0 + 60_000, t0 + 120_000), 250, t0 + 150_000)
            // Sólo wallet y utxo.
            val a = File(dir, "a.csv")
            ExportadorUtxo.exportar(e, a, FiltroUtxo(), t, ConversorUtxo.Cancelar(), setOf(Col.WALLET, Col.UTXO)) { _, _, _ -> }!!
            val la = a.readLines(); assertEquals("wallet,utxo", la[0]); assertEquals(4, la.size)
            assertTrue(la[1].startsWith("1") && la[1].contains(":0") && la[1].count { it == ',' } == 1)
            // Sólo claves públicas, y sólo monedas que la tengan.
            val b = File(dir, "b.csv")
            val rb = ExportadorUtxo.exportar(e, b, FiltroUtxo(clave = 1), t, ConversorUtxo.Cancelar(), setOf(Col.CLAVE)) { _, _, _ -> }!!
            val lb = b.readLines(); assertEquals(2L, rb.filas); assertEquals(3, lb.size); assertEquals("public_key", lb[0])
            assertTrue(lb[1].startsWith("04") && lb[1].length == 130); assertEquals("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", lb[2])
            // Datos que no estaban entre las seis: altura, coinbase, confirmaciones, hash, script, importe en sat.
            val d = File(dir, "d.csv")
            ExportadorUtxo.exportar(e, d, FiltroUtxo(), t, ConversorUtxo.Cancelar(), setOf(Col.FONDO_SAT, Col.ALTURA, Col.COINBASE, Col.CONFIRMACIONES, Col.HASH, Col.SCRIPT, Col.VOUT)) { _, _, _ -> }!!
            val ld = d.readLines()
            assertEquals("vout,funds_sat,key_hash,script_hex,height,coinbase,confirmations", ld[0])
            val hA = ByteArray(20) { (0xA0 + it).toByte() }.joinToString("") { "%02x".format(it) }
            assertEquals("0,5000000000,$hA,76a914${hA}88ac,0,no,251", ld[1])
        } finally { dir.deleteRecursively() }
    }

    @Test fun filtrosPorCampo() {
        val cuerpo = ByteArrayOutputStream()
        cuerpo.write(ByteArray(32) { (it + 1).toByte() }); cuerpo.write(4)
        moneda(cuerpo, 0, 0, 5_000_000_000L, 0, ByteArray(20) { (0xA0 + it).toByte() })     // P2PKH, altura 0
        moneda(cuerpo, 1, 100, 100_000L, 1, ByteArray(20) { (0x10 + it).toByte() })         // P2SH, altura 100
        moneda(cuerpo, 2, 200, 150_000L, 4, GX)                                              // P2PK sin comprimir
        moneda(cuerpo, 3, 200, 1_000_000L, 6 + 34, "5120".h() + GX)                           // P2TR
        val dir = java.nio.file.Files.createTempDirectory("utxocsv").toFile()
        try {
            val e = File(dir, "s.dat"); e.writeBytes(snapshot(4, cuerpo.toByteArray()))
            val t0 = 1231006505L
            val t = ExportadorUtxo.TiempoDeBloques(longArrayOf(t0, t0 + 60_000, t0 + 120_000), 250, t0 + 150_000)
            fun filas(fl: FiltroUtxo): Int {
                val o = File(dir, "f.csv")
                val r = ExportadorUtxo.exportar(e, o, fl, t, ConversorUtxo.Cancelar(), setOf(Col.UTXO)) { _, _, _ -> }!!
                assertEquals(r.filas.toInt() + 1, o.readLines().size); return r.filas.toInt()
            }
            assertEquals(4, filas(FiltroUtxo()))
            assertEquals(3, filas(FiltroUtxo(minSat = 150_000L)))                       // la de 50 BTC, la P2PK y la P2TR
            assertEquals(1, filas(FiltroUtxo(maxSat = 100_000L)))                       // P2SH
            assertEquals(1, filas(FiltroUtxo(tipos = setOf("P2TR"))))
            assertEquals(2, filas(FiltroUtxo(tipos = setOf("P2TR", "P2PKH"))))
            assertEquals(2, filas(FiltroUtxo(clave = 1))); assertEquals(2, filas(FiltroUtxo(clave = 2)))
            assertEquals(1, filas(FiltroUtxo(clave = 1, prefijoClave = "04")))           // la P2PK sin comprimir
            assertEquals(2, filas(FiltroUtxo(alturaMin = 150)))                          // las de altura 200
            assertEquals(1, filas(FiltroUtxo(alturaMax = 50)))
            assertEquals(2, filas(FiltroUtxo(fechaDesde = "2009-01-05")))                // altura 200 = 5 de enero
            assertEquals(2, filas(FiltroUtxo(fechaHasta = "2009-01-04")))                // alturas 0 y 100
            assertEquals(1, filas(FiltroUtxo(voutMin = 3)))
            assertEquals(1, filas(FiltroUtxo(direccionContiene = "1EHNa6")))
            assertEquals(4, filas(FiltroUtxo(coinbase = 2))); assertEquals(0, filas(FiltroUtxo(coinbase = 1)))
            assertEquals(2, filas(FiltroUtxo(confMax = 51)))                             // altura 200 -> 51 confirmaciones
            assertEquals(1, filas(FiltroUtxo(scriptContiene = "5120")))
            assertEquals(1, filas(FiltroUtxo(hashEmpieza = "79be667e")))                 // el hash de la P2TR es su clave
            assertEquals(4, filas(FiltroUtxo(txidEmpieza = "20")))                       // el txid empieza por 20 (al revés)
            assertEquals(0, filas(FiltroUtxo(txidEmpieza = "01")))
            // Combinados
            assertEquals(1, filas(FiltroUtxo(minSat = 150_000L, tipos = setOf("P2TR"), alturaMin = 200)))
        } finally { dir.deleteRecursively() }
    }

    @Test fun conviertePublicas() {
        val x = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        val y = "483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8"
        // x-only -> comprimida (Y par por BIP340) y sin comprimir
        assertEquals("02$x", ExportadorUtxo.convertirClave(x, F.COMPRIMIDA))
        assertEquals("04$x$y", ExportadorUtxo.convertirClave(x, F.SIN_COMPRIMIR))
        assertEquals(x, ExportadorUtxo.convertirClave(x, F.X_ONLY)); assertEquals(x, ExportadorUtxo.convertirClave(x, F.TAL_CUAL))
        // comprimida <-> sin comprimir <-> x-only
        assertEquals("04$x$y", ExportadorUtxo.convertirClave("02$x", F.SIN_COMPRIMIR))
        assertEquals("02$x", ExportadorUtxo.convertirClave("04$x$y", F.COMPRIMIDA))
        assertEquals(x, ExportadorUtxo.convertirClave("04$x$y", F.X_ONLY)); assertEquals(x, ExportadorUtxo.convertirClave("02$x", F.X_ONLY))
        // La 03 es la clave negada: misma x, Y impar (p - y)
        val yNeg = java.math.BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)
            .subtract(java.math.BigInteger(y, 16)).toString(16).padStart(64, '0')
        assertEquals("04$x$yNeg", ExportadorUtxo.convertirClave("03$x", F.SIN_COMPRIMIR))
        assertEquals("03$x", ExportadorUtxo.convertirClave("04$x$yNeg", F.COMPRIMIDA))
        // Una x que no está en la curva no se puede pasar a comprimida ni a sin comprimir.
        var malo: String? = null
        for (k in 1..50) { val h = k.toString(16).padStart(64, '0'); if (ConversorUtxo.descomprimir(2, h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) == null) { malo = h; break } }
        assertNotNull(malo)
        assertEquals("", ExportadorUtxo.convertirClave(malo!!, F.COMPRIMIDA)); assertEquals("", ExportadorUtxo.convertirClave(malo, F.SIN_COMPRIMIR))
        assertEquals(malo, ExportadorUtxo.convertirClave(malo, F.X_ONLY))
        assertEquals("", ExportadorUtxo.convertirClave("zz", F.COMPRIMIDA)); assertEquals("", ExportadorUtxo.convertirClave("0102", F.COMPRIMIDA))
        assertEquals("", ExportadorUtxo.convertirClave("", F.COMPRIMIDA))
    }

    @Test fun exportaConFormatoDeClave() {
        val cuerpo = ByteArrayOutputStream(); cuerpo.write(ByteArray(32) { 3 }); cuerpo.write(2)
        moneda(cuerpo, 0, 10, 1000, 6 + 34, "5120".h() + GX)        // Taproot con la x de G
        moneda(cuerpo, 1, 10, 1000, 4, GX)                          // P2PK sin comprimir
        val dir = java.nio.file.Files.createTempDirectory("utxocsv").toFile()
        try {
            val e = File(dir, "s.dat"); e.writeBytes(snapshot(2, cuerpo.toByteArray()))
            val t = ExportadorUtxo.TiempoDeBloques(longArrayOf(0L, 100L), 100, 100)
            val o = File(dir, "o.csv")
            ExportadorUtxo.exportar(e, o, FiltroUtxo(clave = 1), t, ConversorUtxo.Cancelar(), setOf(Col.CLAVE), ExportadorUtxo.FormatoClave.COMPRIMIDA) { _, _, _ -> }!!
            val l = o.readLines()
            assertEquals(listOf("public_key", "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
                                              "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"), l)
            // Con el filtro por prefijo, se aplica sobre la clave ya convertida.
            val o2 = File(dir, "o2.csv")
            val r = ExportadorUtxo.exportar(e, o2, FiltroUtxo(prefijoClave = "04"), t, ConversorUtxo.Cancelar(), setOf(Col.CLAVE), ExportadorUtxo.FormatoClave.SIN_COMPRIMIR) { _, _, _ -> }!!
            assertEquals(2L, r.filas)
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancelaYBorraElParcial() {
        val cuerpo = ByteArrayOutputStream(); cuerpo.write(ByteArray(32) { 1 }); cuerpo.write(1)
        moneda(cuerpo, 0, 5, 1000, 0, ByteArray(20))
        val dir = java.nio.file.Files.createTempDirectory("utxocsv").toFile()
        try {
            val e = File(dir, "s.dat"); e.writeBytes(snapshot(1, cuerpo.toByteArray()))
            val c = ConversorUtxo.Cancelar(); c.cancelado = true
            val t = ExportadorUtxo.TiempoDeBloques(longArrayOf(0L), 5, 3000)
            assertNull(ExportadorUtxo.exportar(e, File(dir, "o.csv"), FiltroUtxo(), t, c) { _, _, _ -> })
            assertFalse(File(dir, "o.csv").exists()); assertFalse(File(dir, "o.csv.part").exists())
        } finally { dir.deleteRecursively() }
    }
}
