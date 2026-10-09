package com.hunter.btc

/**
 * Lee una transacción cruda (hex) y la explica: versión, entradas, salidas con
 * importe y tipo, locktime, tamaño y vsize. Todo en Kotlin, sin red: es un
 * descodificador, no valida firmas ni consulta nada.
 */
object TxDecoder {

    class Cursor(val b: ByteArray) {
        var p = 0
        fun u8(): Int { val v = b[p].toInt() and 0xFF; p++; return v }
        fun bytes(n: Int): ByteArray { val o = b.copyOfRange(p, p + n); p += n; return o }
        fun u32(): Long { var v = 0L; for (i in 0 until 4) v = v or ((b[p+i].toLong() and 0xFF) shl (8*i)); p += 4; return v }
        fun u64(): Long { var v = 0L; for (i in 0 until 8) v = v or ((b[p+i].toLong() and 0xFF) shl (8*i)); p += 8; return v }
        fun varint(): Long { val f = u8(); return when (f) {
            0xfd -> { var v = 0L; for (i in 0 until 2) v = v or ((b[p+i].toLong() and 0xFF) shl (8*i)); p += 2; v }
            0xfe -> { var v = 0L; for (i in 0 until 4) v = v or ((b[p+i].toLong() and 0xFF) shl (8*i)); p += 4; v }
            0xff -> { var v = 0L; for (i in 0 until 8) v = v or ((b[p+i].toLong() and 0xFF) shl (8*i)); p += 8; v }
            else -> f.toLong() } }
        fun queda() = p < b.size
    }

    private fun hexToBytes(h: String): ByteArray? {
        val s = h.trim().removePrefix("0x")
        if (s.length % 2 != 0 || s.isEmpty()) return null
        val o = ByteArray(s.length / 2)
        for (i in o.indices) { val v = s.substring(i*2, i*2+2).toIntOrNull(16) ?: return null; o[i] = v.toByte() }
        return o
    }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun satsABtc(s: Long) = "%.8f".format(s / 1e8).trimEnd('0').trimEnd('.')

    private fun tipoSpk(spk: String): String = when {
        spk.startsWith("76a914") && spk.endsWith("88ac") && spk.length == 50 -> "P2PKH"
        spk.startsWith("a914") && spk.endsWith("87") && spk.length == 46 -> "P2SH"
        spk.startsWith("0014") && spk.length == 44 -> "P2WPKH"
        spk.startsWith("0020") && spk.length == 68 -> "P2WSH"
        spk.startsWith("5120") && spk.length == 68 -> "P2TR"
        spk.startsWith("6a") -> "OP_RETURN"
        else -> "script"
    }

    /** Devuelve filas (etiqueta, valor) o null si el hex no es una tx válida. */
    fun decode(hexStr: String): List<Pair<String, String>>? {
        val b = hexToBytes(hexStr) ?: return null
        return try {
            val c = Cursor(b)
            val total = b.size
            val version = c.u32()
            var segwit = false
            if (c.b.size > c.p + 1 && c.b[c.p].toInt() == 0x00 && c.b[c.p+1].toInt() == 0x01) { segwit = true; c.p += 2 }
            val nIn = c.varint().toInt()
            data class In(val txid: String, val vout: Long, val seq: Long)
            val ins = ArrayList<In>()
            for (i in 0 until nIn) {
                val txidLe = c.bytes(32)
                val txid = hex(txidLe.reversedArray())
                val vout = c.u32()
                val sl = c.varint().toInt(); c.bytes(sl)
                val seq = c.u32()
                ins.add(In(txid, vout, seq))
            }
            val nOut = c.varint().toInt()
            data class Out(val value: Long, val tipo: String, val spk: String)
            val outs = ArrayList<Out>()
            for (j in 0 until nOut) {
                val value = c.u64()
                val sl = c.varint().toInt(); val spk = hex(c.bytes(sl))
                outs.add(Out(value, tipoSpk(spk), spk))
            }
            var witBytes = 0
            if (segwit) {
                val antes = c.p
                for (i in 0 until nIn) {
                    val items = c.varint().toInt()
                    for (k in 0 until items) { val l = c.varint().toInt(); c.bytes(l) }
                }
                witBytes = c.p - antes
            }
            val locktime = c.u32()

            val base = total - (if (segwit) witBytes + 2 else 0)
            val weight = base * 3 + total
            val vsize = (weight + 3) / 4

            val filas = ArrayList<Pair<String, String>>()
            filas.add("Version" to "$version" + (if (segwit) " · segwit" else " · legacy"))
            filas.add("Inputs" to "$nIn")
            ins.forEachIndexed { i, x -> filas.add("in #$i" to "${x.txid}:${x.vout}") }
            filas.add("Outputs" to "$nOut")
            outs.forEachIndexed { j, x -> filas.add("out #$j · ${x.tipo}" to "${satsABtc(x.value)} BTC") }
            filas.add("Locktime" to "$locktime")
            filas.add("Size" to "$total bytes · vsize $vsize")
            filas
        } catch (e: Throwable) { null }
    }
}
