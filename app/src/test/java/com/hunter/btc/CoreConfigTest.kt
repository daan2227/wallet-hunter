package com.hunter.btc

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CoreConfigTest {
    @Test fun confLlevaPodaYSoloLocal() {
        val c = CoreConfig.conf(CoreConfig.Ajustes(pruneMiB = 100), "/data/x/.cookie")
        assertTrue("prune=550" in c)               // por debajo del mínimo se sube
        assertTrue("rpcbind=127.0.0.1" in c && "rpcallowip=127.0.0.1" in c)
        assertTrue("rpccookiefile=/data/x/.cookie" in c)
        assertTrue("listen=0" in c && "txindex=0" in c && "blocksonly=1" in c)
        assertFalse("rpcpassword" in c)
    }
    @Test fun rutaConSaltoDeLineaSeRechaza() {
        try { CoreConfig.conf(CoreConfig.Ajustes(), "/a\nrpcallowip=0.0.0.0/0"); fail() }
        catch (e: IllegalArgumentException) {}
    }
    @Test fun alturaDePoda() {
        assertEquals(-1, CoreConfig.alturaPoda(100))
        assertEquals(-1, CoreConfig.alturaPoda(288))
        assertEquals(712, CoreConfig.alturaPoda(1000))
    }
    @Test fun interpretaRespuestas() {
        assertEquals(5, CoreRpc.interpretar("""{"result":5,"error":null,"id":"wh"}"""))
        assertNull(CoreRpc.interpretar("""{"result":null,"error":null,"id":"wh"}"""))
        try { CoreRpc.interpretar("""{"result":null,"error":{"code":-28,"message":"Loading block index…"},"id":"wh"}""")
              fail() } catch (e: CoreRpcError) { assertTrue(e.arrancando); assertEquals(-28, e.code) }
    }
    @Test fun leeElEstado() {
        val ch = JSONObject("""{"chain":"main","blocks":880000,"headers":900000,"verificationprogress":0.97,
            "initialblockdownload":true,"pruned":true,"pruneheight":879000,"size_on_disk":123456789}""")
        val red = JSONObject("""{"connections":8,"subversion":"/Satoshi:29.0.0/"}""")
        val cs = JSONObject("""{"headers":900000,"chainstates":[
            {"blocks":450000,"bestblockhash":"aa"},
            {"blocks":880000,"bestblockhash":"bb","snapshot_blockhash":"cc","validated":false}]}""")
        val e = CoreConfig.leerEstado(ch, red, cs)
        assertEquals("main", e.cadena); assertEquals(880000L, e.bloques); assertEquals(8, e.pares)
        assertTrue(e.podado); assertTrue(e.validandoAtras); assertEquals(0.5, e.progresoAtras, 1e-9)
        val solo = CoreConfig.leerEstado(ch, null, null)
        assertFalse(solo.validandoAtras); assertEquals(0, solo.pares)
    }
    @Test fun cabeceraDeSnapshot() {
        val b = ByteArray(51)
        "utxo".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        b[4] = 0xff.toByte(); b[5] = 2
        byteArrayOf(0xf9.toByte(), 0xbe.toByte(), 0xb4.toByte(), 0xd9.toByte()).copyInto(b, 7)
        for (i in 0 until 32) b[11 + i] = i.toByte()
        b[43] = 0x10; b[44] = 0x27           // 10000 monedas
        val h = CoreConfig.leerCabeceraSnapshot(b)!!
        assertEquals(2, h.version); assertEquals("main", h.red); assertEquals(10000L, h.monedas)
        assertTrue(h.base.startsWith("1f1e1d")); assertTrue(h.base.endsWith("0100"))
        b[0] = 'x'.code.toByte(); assertNull(CoreConfig.leerCabeceraSnapshot(b))
        assertNull(CoreConfig.leerCabeceraSnapshot(ByteArray(10)))
    }
    @Test fun snapshotsAceptados() {
        assertEquals(910_000, CoreConfig.alturaAceptada("0000000000000000000108970ACB9522ffd516eae17acddcb1bd16469194a821"))
        assertEquals(5, CoreConfig.SNAPSHOTS_ACEPTADOS.size)
        assertEquals(965_000, CoreConfig.alturaAceptada("00000000000000000001595977e6000ce56129f5c9b4073e31ccc30b90b97da9"))
        assertNull(CoreConfig.alturaAceptada("00".repeat(32)))
    }
    @Test fun progresoDeLaCarga() {
        val t = "2026-10-21T12:00:00Z"
        val l = listOf(
            "2026-10-21T11:59:00Z Synchronizing blockheaders, height: 965000",
            "$t [snapshot] loading 100000000 coins from snapshot 0000abc",
            "2026-10-21T12:10:00Z [snapshot] 1000000 coins loaded (1.00%, 200.00 MB)",
            "2026-10-21T12:20:00Z [snapshot] 2000000 coins loaded (2.00%, 400.00 MB)")
        val ahora = java.time.Instant.parse("2026-10-21T12:21:00Z").epochSecond
        val c = CoreConfig.leerCargaSnapshot(l, true, ahora)!!
        assertTrue(c.enCurso); assertEquals(2.0, c.porcentaje, 1e-9); assertEquals(1260L, c.transcurridoS)
        assertEquals(98L * 600, c.etaS)            // 2 M en 20 min -> 98 M restantes a 600 s por millón
        assertFalse(CoreConfig.leerCargaSnapshot(l, false, ahora)!!.enCurso)      // el nodo ya no corre
        val fin = l + "2026-10-21T13:00:00Z [snapshot] loaded 100000000 (7000.00 MB) coins from snapshot 0000abc"
        assertFalse(CoreConfig.leerCargaSnapshot(fin, true, ahora)!!.enCurso)
        // Una carga nueva después de una abortada: manda la última.
        val dos = l + listOf("2026-10-21T12:30:00Z Aborting after an interrupt was requested",
            "2026-10-21T14:00:00Z [snapshot] loading 100000000 coins from snapshot 0000abc")
        val d = CoreConfig.leerCargaSnapshot(dos, true, ahora)!!
        assertTrue(d.enCurso); assertEquals(0L, d.cargados); assertNull(d.etaS)
        // El porcentaje que imprime Core manda sobre el calculado.
        val conPct = listOf("$t [snapshot] loading 200000000 coins from snapshot 0000abc",
            "2026-10-21T12:10:00Z [snapshot] 114000000 coins loaded (57.00%, 9000.00 MB)")
        assertEquals(57.0, CoreConfig.leerCargaSnapshot(conPct, true, ahora)!!.porcentaje, 1e-9)
        assertEquals(114_000_000L, CoreConfig.leerCargaSnapshot(conPct, true, ahora)!!.cargados)
        assertNull(CoreConfig.leerCargaSnapshot(listOf("nada"), true, ahora))
        assertEquals("1 h 12 min", CoreConfig.duracion(4320)); assertEquals("8 min", CoreConfig.duracion(500))
    }
    @Test fun leeEscaneoYTarifas() {
        val o = JSONObject("""{"success":true,"txouts":5,"height":965010,"bestblock":"aa","total_amount":0.0123,
            "unspents":[{"txid":"11","vout":0,"scriptPubKey":"00","desc":"raw(00)","amount":0.01,"coinbase":false,"height":960000},
                        {"txid":"22","vout":3,"scriptPubKey":"00","desc":"raw(00)","amount":0.0023,"coinbase":false,"height":961000}]}""")
        val e = CoreConfig.leerEscaneo(o)!!
        assertEquals(1_230_000L, e.sat); assertEquals(2, e.monedas.size); assertEquals(965010, e.altura)
        assertEquals(230_000L, e.monedas[1].sat)
        assertNull(CoreConfig.leerEscaneo(JSONObject("""{"success":false}""")))
        assertEquals(12.0, CoreConfig.satPorVb(JSONObject("""{"feerate":0.00012,"blocks":2}"""))!!, 1e-9)
        assertNull(CoreConfig.satPorVb(JSONObject("""{"errors":["Insufficient data or no feerate found"],"blocks":2}""")))
    }
    @Test fun comprobaMonedas() {
        val ok = JSONObject("""{"bestblock":"aa","confirmations":5,"value":0.0123,"coinbase":false}""")
        assertNull(CoreConfig.comprobarMoneda(ok, 1_230_000L, "ab".repeat(32), 1))
        assertTrue(CoreConfig.comprobarMoneda(ok, 1_000_000L, "ab".repeat(32), 1)!!.contains("1230000"))
        assertTrue(CoreConfig.comprobarMoneda(null, 1L, "ab".repeat(32), 0)!!.contains("spent"))
    }
    @Test fun panelDeLaRed() {
        val h = CoreConfig.proximoHalving(965_000L)
        assertEquals(1_050_000L, h.altura); assertEquals(85_000L, h.faltan); assertEquals(590L, h.dias)
        assertEquals("1050000".length, "%d".format(h.altura).length)
        assertEquals("812.5 EH/s", CoreConfig.hashrate(8.125e20)); assertEquals("–", CoreConfig.hashrate(0.0))
        assertEquals("111.23 T", CoreConfig.dificultad(1.1123e14))
        val m = CoreConfig.leerMempool(JSONObject("""{"size":12000,"bytes":4000000,"mempoolminfee":0.00001}"""))
        assertEquals(12000L, m.tx); assertEquals(1.0, m.minimaSatVb, 1e-9)
        val u = CoreConfig.leerUtxoStats(JSONObject("""{"height":965360,"txouts":190000000,"total_amount":20000000.5}"""))!!
        assertEquals(190_000_000L, u.monedas); assertEquals(965_360L, u.altura)
        assertNull(CoreConfig.leerUtxoStats(JSONObject("""{"height":1}""")))
        assertEquals("just now", CoreConfig.hace(10)); assertEquals("12 min ago", CoreConfig.hace(720)); assertEquals("3 h ago", CoreConfig.hace(11000))
    }
    @Test fun herramientas() {
        assertEquals(0 to 4, CoreConfig.rango("0-4")); assertEquals(7 to 7, CoreConfig.rango(" 7 ")); assertNull(CoreConfig.rango(""))
        for (m in listOf("4-0", "a-b", "0-5000", "1-2-3", "-1"))
            try { CoreConfig.rango(m); fail(m) } catch (e: IllegalArgumentException) {}
        assertEquals(listOf("AAA", "BBB", "CCC"), CoreConfig.partirPsbts("AAA\nBBB  CCC\n"))
    }
    @Test fun redYFiltros() {
        val base = CoreConfig.conf(CoreConfig.Ajustes(), "/x/.cookie")
        assertTrue("listen=0" in base); assertFalse("proxy=" in base); assertFalse("blockfilterindex" in base)
        val tor = CoreConfig.conf(CoreConfig.Ajustes(red = 1, difusionPrivada = true), "/x/.cookie")
        assertTrue("proxy=127.0.0.1:9050" in tor && "dnsseed=0" in tor && "privatebroadcast=1" in tor)
        assertFalse("onlynet=onion" in tor)
        assertTrue("onlynet=onion" in CoreConfig.conf(CoreConfig.Ajustes(red = 2), "/x/.cookie"))
        // La difusión privada sin Tor se ignora: Core se negaría a arrancar.
        assertFalse("privatebroadcast" in CoreConfig.conf(CoreConfig.Ajustes(red = 0, difusionPrivada = true), "/x/.cookie"))
        val f = CoreConfig.conf(CoreConfig.Ajustes(servirFiltros = true), "/x/.cookie")
        assertTrue("bind=127.0.0.1:8333" in f && "peerblockfilters=1" in f && "blockfilterindex=basic" in f && "listen=1" in f)
        assertFalse("listen=0" in f)
        assertTrue(CoreConfig.proxyValido("127.0.0.1:9050")); assertTrue(CoreConfig.proxyValido("localhost:9150"))
        assertFalse(CoreConfig.proxyValido("127.0.0.1")); assertFalse(CoreConfig.proxyValido("a b:1"))
        assertFalse(CoreConfig.proxyValido("x:99999")); assertFalse(CoreConfig.proxyValido("x:1\nrpcallowip=0.0.0.0/0"))
        assertEquals("127.0.0.1:9050", CoreConfig.Ajustes(proxy = "mal").normalizado().proxy)
    }
    @Test fun volcadoYFicha() {
        val o = JSONObject("""{"coins_written":190000000,"base_hash":"00000000000000000001595977e6000ce56129f5c9b4073e31ccc30b90b97da9",
            "base_height":965000,"path":"/x/utxo.dat","txoutset_hash":"4a8d794337118c0c615b574f817c7306c687584a537184b8d233df42bf477ec2","nchaintx":1429611231}""")
        val v = CoreConfig.leerVolcado(o)!!
        assertEquals(965_000L, v.baseAltura); assertEquals(190_000_000L, v.monedas); assertEquals("mainnet-965000-utxos.dat", CoreConfig.nombreSnapshot(v.baseAltura))
        val f = CoreConfig.fichaSnapshot(v, "/Satoshi:32.0.0/", "2026-10-22")
        assertTrue(f.contains("base block height: 965000") && f.contains("yes, the block is in its list") && f.contains("hash_serialized"))
        val otro = CoreConfig.fichaSnapshot(v.copy(baseHash = "00".repeat(32)), "", "x")
        assertTrue(otro.contains("no, Core only loads snapshots at 840000"))
        assertNull(CoreConfig.leerVolcado(JSONObject("""{"x":1}""")))
    }
    @Test fun urls() {
        assertTrue(CoreConfig.urlValida("https://ejemplo.org/utxo-880000.dat"))
        assertFalse(CoreConfig.urlValida("http://ejemplo.org/x.dat"))
        assertFalse(CoreConfig.urlValida("https://a b.org/x"))
        assertFalse(CoreConfig.urlValida("file:///sdcard/x"))
    }
    @Test fun bytesLegibles() {
        assertEquals("512 B", CoreConfig.bytes(512)); assertEquals("1.50 GB", CoreConfig.bytes(1610612736L))
    }
}
