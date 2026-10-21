package com.hunter.btc

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Convierte un snapshot de UTXO de Bitcoin Core (el de `dumptxoutset`, o el que se cargó con `loadtxoutset`)
 * en el índice de 9 bytes que lee el Scanner: `[tipo:1][8 primeros bytes del hash]`, sin cabecera, ordenado y sin
 * repetidos (ver indice9.h). Tipos: 0 P2PKH (y P2PK, que comparte hash de clave), 1 P2WPKH, 2 P2SH, 3 P2WSH/P2TR.
 *
 * Formato leído (Core, versión 2 del snapshot): cabecera de 51 bytes y, por cada txid, `txid(32) · nº de monedas
 * (CompactSize) · por moneda: vout (CompactSize), código altura/coinbase (VARINT), importe comprimido (VARINT) y
 * script comprimido (VARINT nSize + datos)`. El script comprimido trae los estándar como 20 o 32 bytes (nSize 0–5);
 * el resto, el script entero con nSize-6 bytes.
 *
 * Son unos 190 millones de monedas: no caben en memoria. Se hace en dos pasadas con ficheros temporales: primero
 * se reparte cada entrada en uno de 1 024 cajones por (tipo, primer byte del hash); luego cada cajón (unos MB)
 * se ordena en memoria, se quitan los repetidos y se escribe en orden.
 */
object ConversorUtxo {

    class Cancelar { @Volatile var cancelado = false }

    data class Resultado(val monedas: Long, val entradas: Long, val porTipo: LongArray)

    class FormatoInvalido(m: String) : Exception(m)

    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val SIETE = BigInteger.valueOf(7)

    /** Lector con búfer sobre un flujo: lo mínimo que hace falta, sin asignar por moneda. */
    private class Lector(private val ent: InputStream) {
        private val buf = ByteArray(1 shl 20)
        private var pos = 0
        private var lim = 0
        var leidos = 0L; private set

        private fun llenar() {
            val n = ent.read(buf, 0, buf.size)
            if (n <= 0) throw FormatoInvalido("the file ends before the last coin")
            pos = 0; lim = n; leidos += n
        }
        fun u8(): Int { if (pos >= lim) llenar(); return buf[pos++].toInt() and 0xff }
        fun leer(dst: ByteArray, n: Int) {
            var i = 0
            while (i < n) {
                if (pos >= lim) llenar()
                val k = minOf(n - i, lim - pos)
                System.arraycopy(buf, pos, dst, i, k); pos += k; i += k
            }
        }
        fun saltar(n: Long) {
            var r = n
            while (r > 0) {
                if (pos >= lim) llenar()
                val k = minOf(r, (lim - pos).toLong()).toInt(); pos += k; r -= k
            }
        }
        /** VARINT de Core (base 128 con desplazamiento), no el CompactSize. */
        fun varint(): Long {
            var n = 0L
            while (true) {
                val c = u8()
                if (n > (Long.MAX_VALUE shr 7)) throw FormatoInvalido("VARINT too long")
                n = (n shl 7) or (c and 0x7f).toLong()
                if (c and 0x80 != 0) n++ else return n
            }
        }
        fun compactSize(): Long {
            val c = u8()
            return when (c) {
                253 -> (u8() or (u8() shl 8)).toLong()
                254 -> { var v = 0L; for (i in 0 until 4) v = v or (u8().toLong() shl (8 * i)); v }
                255 -> { var v = 0L; for (i in 0 until 8) v = v or (u8().toLong() shl (8 * i)); v }
                else -> c.toLong()
            }
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    fun hash160(b: ByteArray): ByteArray = Ripemd160.digest(sha256(b))

    /** La clave pública sin comprimir (65 bytes) a partir de su x y la paridad de y (2 par, 3 impar). */
    fun descomprimir(prefijo: Int, x: ByteArray): ByteArray? {
        val xi = BigInteger(1, x)
        if (xi >= P) return null
        val y2 = xi.pow(3).add(SIETE).mod(P)
        var y = y2.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.multiply(y).mod(P) != y2) return null          // x no está en la curva
        if (y.testBit(0) != (prefijo == 3)) y = P.subtract(y)
        val out = ByteArray(65); out[0] = 4
        System.arraycopy(x, 0, out, 1, 32)
        val yb = y.toByteArray()                              // puede traer un 0 delante o ser más corto
        val ini = if (yb.size > 32) yb.size - 32 else 0
        System.arraycopy(yb, ini, out, 65 - (yb.size - ini), yb.size - ini)
        return out
    }

    /**
     * Clasifica el script de una moneda. Devuelve (tipo, 8 bytes del hash) o null si no es de los que busca el
     * Scanner. [nSize] y [datos] son los del script comprimido: 0 y 1 (hash de 20 bytes), 2–5 (clave pública
     * comprimida, 32 bytes de x) y, desde 6, el script entero.
     */
    fun clasificar(nSize: Int, datos: ByteArray, largo: Int): Pair<Int, ByteArray>? = when {
        nSize == 0 -> 0 to datos.copyOf(8)
        nSize == 1 -> 2 to datos.copyOf(8)
        nSize == 2 || nSize == 3 -> {
            val k = ByteArray(33); k[0] = nSize.toByte(); System.arraycopy(datos, 0, k, 1, 32)
            0 to hash160(k).copyOf(8)
        }
        nSize == 4 || nSize == 5 -> descomprimir(nSize - 2, datos.copyOf(32))?.let { 0 to hash160(it).copyOf(8) }
        largo == 22 && datos[0].toInt() == 0x00 && datos[1].toInt() == 0x14 -> 1 to datos.copyOfRange(2, 10)
        largo == 34 && datos[0].toInt() == 0x00 && datos[1].toInt() == 0x20 -> 3 to datos.copyOfRange(2, 10)
        largo == 34 && datos[0].toInt() == 0x51 && datos[1].toInt() == 0x20 -> 3 to datos.copyOfRange(2, 10)
        else -> null
    }

    /**
     * @param progreso (fase 1 o 2, parte, total)
     * @return null si se canceló
     */
    fun convertir(entrada: File, salida: File, tmp: File, c: Cancelar,
                  progreso: (Int, Long, Long) -> Unit): Resultado? {
        tmp.deleteRecursively(); tmp.mkdirs()
        var monedasTotal = 0L
        val porTipo = LongArray(4)
        var monedasLeidas = 0L
        val cajonesBuf = Array(1024) { ByteArray(8192) }
        val cajonesPos = IntArray(1024)
        val cajonesOut = arrayOfNulls<FileOutputStream>(1024)
        fun vaciar(k: Int) {
            if (cajonesPos[k] == 0) return
            val o = cajonesOut[k] ?: FileOutputStream(File(tmp, "c%04d".format(k)), true).also { cajonesOut[k] = it }
            o.write(cajonesBuf[k], 0, cajonesPos[k]); cajonesPos[k] = 0
        }
        try {
            entrada.inputStream().use { fin ->
                val l = Lector(fin)
                val cab = ByteArray(51); l.leer(cab, 51)
                val h = CoreConfig.leerCabeceraSnapshot(cab) ?: throw FormatoInvalido("that is not a UTXO snapshot")
                monedasTotal = h.monedas
                val datos = ByteArray(1 shl 14)
                val hash20 = ByteArray(32)
                var siguienteAviso = 0L
                while (monedasLeidas < monedasTotal) {
                    l.saltar(32)
                    val n = l.compactSize()
                    if (n <= 0 || n > monedasTotal - monedasLeidas) throw FormatoInvalido("bad coin count for a transaction")
                    for (i in 0 until n) {
                        l.compactSize(); l.varint(); l.varint()
                        val ns = l.varint()
                        if (ns < 0 || ns > 10_000_000L) throw FormatoInvalido("bad script size")
                        val nSize = ns.toInt()
                        var clase: Pair<Int, ByteArray>? = null
                        when {
                            nSize < 2 -> { l.leer(hash20, 20); clase = clasificar(nSize, hash20, 20) }
                            nSize < 6 -> { l.leer(hash20, 32); clase = clasificar(nSize, hash20, 32) }
                            else -> {
                                val len = nSize - 6
                                if (len <= datos.size) { l.leer(datos, len); clase = clasificar(nSize, datos, len) }
                                else l.saltar(len.toLong())
                            }
                        }
                        monedasLeidas++
                        if (clase != null) {
                            val (t, hb) = clase
                            val k = t * 256 + (hb[0].toInt() and 0xff)
                            if (cajonesPos[k] + 7 > cajonesBuf[k].size) vaciar(k)
                            System.arraycopy(hb, 1, cajonesBuf[k], cajonesPos[k], 7); cajonesPos[k] += 7
                        }
                    }
                    if (monedasLeidas >= siguienteAviso) {
                        if (c.cancelado) return null
                        progreso(1, monedasLeidas, monedasTotal); siguienteAviso = monedasLeidas + 500_000
                    }
                }
            }
            for (k in 0 until 1024) { vaciar(k); cajonesOut[k]?.close() }
        } catch (e: Exception) {
            for (o in cajonesOut) try { o?.close() } catch (x: Exception) {}
            tmp.deleteRecursively()
            throw e
        }
        progreso(1, monedasTotal, monedasTotal)

        // Pasada 2: cada cajón se ordena y se escribe en orden de (tipo, primer byte, resto).
        var entradas = 0L
        val parcial = File(salida.path + ".part")
        try {
            BufferedOutputStream(FileOutputStream(parcial), 1 shl 20).use { out ->
                for (k in 0 until 1024) {
                    if (c.cancelado) { parcial.delete(); tmp.deleteRecursively(); return null }
                    progreso(2, k.toLong(), 1024)
                    val f = File(tmp, "c%04d".format(k))
                    if (!f.isFile) continue
                    val bytes = f.readBytes(); f.delete()
                    val n = bytes.size / 7
                    val v = LongArray(n)
                    for (i in 0 until n) {
                        var x = 0L
                        for (j in 0 until 7) x = (x shl 8) or (bytes[i * 7 + j].toLong() and 0xff)
                        v[i] = x
                    }
                    v.sort()
                    val tipo = k / 256; val b0 = k % 256
                    var previo = -1L
                    val reg = ByteArray(9); reg[0] = tipo.toByte(); reg[1] = b0.toByte()
                    for (x in v) {
                        if (x == previo) continue
                        previo = x
                        for (j in 0 until 7) reg[2 + j] = (x shr (8 * (6 - j))).toByte()
                        out.write(reg); entradas++; porTipo[tipo]++
                    }
                }
            }
            if (!parcial.renameTo(salida)) throw FormatoInvalido("could not move the dataset into place")
        } catch (e: Exception) {
            parcial.delete(); throw e
        } finally { tmp.deleteRecursively() }
        progreso(2, 1024, 1024)
        return Resultado(monedasLeidas, entradas, porTipo)
    }
}
