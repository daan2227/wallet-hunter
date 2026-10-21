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
        assertEquals(4, CoreConfig.SNAPSHOTS_ACEPTADOS.size)
        assertNull(CoreConfig.alturaAceptada("00".repeat(32)))
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
