package com.hunter.btc

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ConversorUtxoTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun String.h() = hex(this)

    /** VARINT de Core. */
    private fun varint(n0: Long): ByteArray {
        val t = ArrayList<Int>(); var n = n0; var len = 0
        while (true) {
            t.add((n and 0x7f).toInt() or (if (len != 0) 0x80 else 0))
            if (n <= 0x7f) break
            n = (n shr 7) - 1; len++
        }
        return t.reversed().map { it.toByte() }.toByteArray()
    }

    private val GX = "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798".h()

    private fun moneda(o: ByteArrayOutputStream, vout: Int, nSize: Int, datos: ByteArray) {
        o.write(vout)                         // CompactSize < 253
        o.write(varint(1000L * 2 + 1))        // altura y coinbase
        o.write(varint(5))                    // importe comprimido
        o.write(varint(nSize.toLong())); o.write(datos)
    }

    private fun snapshot(monedas: Long, cuerpo: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf('u'.code.toByte(), 't'.code.toByte(), 'x'.code.toByte(), 'o'.code.toByte(), 0xff.toByte(), 2, 0))
        o.write("f9beb4d9".h()); o.write(ByteArray(32) { 7 })
        for (i in 0 until 8) o.write(((monedas shr (8 * i)) and 0xff).toInt())
        o.write(cuerpo); return o.toByteArray()
    }

    @Test fun convierteYOrdena() {
        val a = ByteArray(20) { (0xA0 + it).toByte() }     // P2PKH
        val b = ByteArray(20) { (0x10 + it).toByte() }     // P2SH
        val wpkh = ByteArray(20) { (0x55 + it).toByte() }
        val tap = ByteArray(32) { (0xC0 + it).toByte() }
        val cuerpo = ByteArrayOutputStream()
        // tx 1: P2PKH, P2SH, P2PK comprimida (clave G)
        cuerpo.write(ByteArray(32) { 1 }); cuerpo.write(3)
        moneda(cuerpo, 0, 0, a); moneda(cuerpo, 1, 1, b); moneda(cuerpo, 2, 2, GX)
        // tx 2: P2PK sin comprimir (G, y par = nSize 4), P2WPKH, P2TR, un P2PKH repetido y un script ajeno
        cuerpo.write(ByteArray(32) { 2 }); cuerpo.write(5)
        moneda(cuerpo, 0, 4, GX)
        moneda(cuerpo, 1, 6 + 22, "0014".h() + wpkh)
        moneda(cuerpo, 2, 6 + 34, "5120".h() + tap)
        moneda(cuerpo, 3, 0, a)
        moneda(cuerpo, 4, 6 + 5, "6a0368656c".h())            // OP_RETURN: no se busca
        val dir = java.nio.file.Files.createTempDirectory("utxo").toFile()
        try {
            val entrada = File(dir, "s.dat"); entrada.writeBytes(snapshot(8, cuerpo.toByteArray()))
            val salida = File(dir, "o.bin")
            val fases = HashSet<Int>()
            val r = ConversorUtxo.convertir(entrada, salida, File(dir, "tmp"), ConversorUtxo.Cancelar()) { f, _, _ -> fases.add(f) }!!
            assertEquals(8L, r.monedas); assertEquals(6L, r.entradas)
            assertEquals(listOf(3L, 1L, 1L, 1L), r.porTipo.toList()); assertEquals(setOf(1, 2), fases)
            val b = salida.readBytes()
            assertEquals(6 * 9, b.size)
            // Ordenado por los 9 bytes, sin repetidos.
            val regs = (0 until 6).map { b.copyOfRange(it * 9, it * 9 + 9) }
            for (i in 1 until regs.size) assertTrue(java.util.Arrays.compareUnsigned(regs[i - 1], regs[i]) < 0)
            fun hay(t: Int, h8: ByteArray) = regs.any { it[0].toInt() == t && it.copyOfRange(1, 9).contentEquals(h8) }
            assertTrue(hay(0, a.copyOf(8)))
            assertTrue(hay(2, b.let { ByteArray(20) { i -> (0x10 + i).toByte() } }.copyOf(8)))
            assertTrue(hay(1, wpkh.copyOf(8))); assertTrue(hay(3, tap.copyOf(8)))
            // Clave G: 751e76e8199196d4… (comprimida) y 91b24bf9f5288532… (sin comprimir)
            assertTrue(hay(0, "751e76e8199196d4".h())); assertTrue(hay(0, "91b24bf9f5288532".h()))
            assertFalse(File(dir, "tmp").exists()); assertFalse(File(dir, "o.bin.part").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rechazaLoQueNoEsUnSnapshot() {
        val dir = java.nio.file.Files.createTempDirectory("utxo").toFile()
        try {
            val e = File(dir, "x.dat"); e.writeBytes(ByteArray(200) { 1 })
            try { ConversorUtxo.convertir(e, File(dir, "o.bin"), File(dir, "t"), ConversorUtxo.Cancelar()) { _, _, _ -> }; fail() }
            catch (x: ConversorUtxo.FormatoInvalido) {}
            // Cortado a mitad: dice 8 monedas y sólo trae una.
            val c = ByteArrayOutputStream(); c.write(ByteArray(32) { 1 }); c.write(1); moneda(c, 0, 0, ByteArray(20))
            e.writeBytes(snapshot(8, c.toByteArray()))
            try { ConversorUtxo.convertir(e, File(dir, "o.bin"), File(dir, "t"), ConversorUtxo.Cancelar()) { _, _, _ -> }; fail() }
            catch (x: ConversorUtxo.FormatoInvalido) {}
            assertFalse(File(dir, "t").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancelar() {
        val dir = java.nio.file.Files.createTempDirectory("utxo").toFile()
        try {
            val c = ByteArrayOutputStream(); c.write(ByteArray(32) { 1 }); c.write(1); moneda(c, 0, 0, ByteArray(20))
            val e = File(dir, "x.dat"); e.writeBytes(snapshot(1, c.toByteArray()))
            val can = ConversorUtxo.Cancelar(); can.cancelado = true
            assertNull(ConversorUtxo.convertir(e, File(dir, "o.bin"), File(dir, "t"), can) { _, _, _ -> })
            assertFalse(File(dir, "o.bin").exists())
        } finally { dir.deleteRecursively() }
    }
}
