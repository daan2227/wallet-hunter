package com.hunter.btc

import java.io.ByteArrayOutputStream

/**
 * PSBT (BIP-174): leer y firmar transacciones parcialmente firmadas.
 *
 * Parsea el formato (mapas global / por entrada / por salida), resume lo que
 * hay, y firma las entradas P2WPKH y P2PKH cuya clave controlas, añadiendo la
 * firma parcial. El sighash se calcula aquí (legacy y BIP-143); la firma ECDSA
 * la hace el motor nativo (firmarHash). No finaliza la tx: deja las firmas
 * parciales, como cualquier firmante PSBT.
 */
object Psbt {
    private fun le(v: Long, n: Int): ByteArray { val o = ByteArray(n); var x = v; for (i in 0 until n) { o[i] = (x and 0xFF).toByte(); x = x shr 8 }; return o }
    private fun varint(n: Long): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte())
        n <= 0xffff -> byteArrayOf(0xfd.toByte()) + le(n, 2)
        n <= 0xffffffffL -> byteArrayOf(0xfe.toByte()) + le(n, 4)
        else -> byteArrayOf(0xff.toByte()) + le(n, 8)
    }
    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b)
    private fun dsha(b: ByteArray) = sha(sha(b))
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(it*2, it*2+2).toInt(16).toByte() }
    private fun rev(b: ByteArray) = b.reversedArray()
    private fun varStr(h: String): ByteArray { val s = bytes(h); return varint(s.size.toLong()) + s }

    class Rec(val key: ByteArray, val value: ByteArray)
    class TxIn(val txid: ByteArray, val vout: Long, val seq: Long)
    class TxOut(val value: Long, val spk: ByteArray)
    class Tx(val version: Long, val ins: List<TxIn>, val outs: List<TxOut>, val locktime: Long)
    class Doc(val globals: MutableList<Rec>, val inputs: List<MutableList<Rec>>,
              val outputs: List<MutableList<Rec>>, val tx: Tx)

    private fun parseTx(b: ByteArray): Tx {
        val c = TxDecoder.Cursor(b)
        val version = c.u32()
        val nin = c.varint().toInt()
        val ins = ArrayList<TxIn>()
        for (i in 0 until nin) { val txid = c.bytes(32); val vout = c.u32(); val sl = c.varint().toInt(); c.bytes(sl); val seq = c.u32(); ins.add(TxIn(txid, vout, seq)) }
        val nout = c.varint().toInt()
        val outs = ArrayList<TxOut>()
        for (j in 0 until nout) { val v = c.u64(); val sl = c.varint().toInt(); outs.add(TxOut(v, c.bytes(sl))) }
        return Tx(version, ins, outs, c.u32())
    }

    private fun readMap(c: TxDecoder.Cursor): MutableList<Rec> {
        val recs = ArrayList<Rec>()
        while (c.queda()) { val kl = c.varint().toInt(); if (kl == 0) break; val key = c.bytes(kl); val vl = c.varint().toInt(); recs.add(Rec(key, c.bytes(vl))) }
        return recs
    }

    fun parse(data: ByteArray): Doc? = try {
        val c = TxDecoder.Cursor(data)
        val m = c.bytes(5)
        if (!(m[0].toInt() == 0x70 && m[1].toInt() == 0x73 && m[2].toInt() == 0x62 && m[3].toInt() == 0x74 && (m[4].toInt() and 0xFF) == 0xFF)) null
        else {
            val globals = readMap(c)
            val txRec = globals.firstOrNull { it.key.isNotEmpty() && it.key[0].toInt() == 0x00 }
            if (txRec == null) null else {
                val tx = parseTx(txRec.value)
                val inputs = ArrayList<MutableList<Rec>>(); for (i in tx.ins.indices) inputs.add(readMap(c))
                val outputs = ArrayList<MutableList<Rec>>(); for (j in tx.outs.indices) outputs.add(readMap(c))
                Doc(globals, inputs, outputs, tx)
            }
        }
    } catch (e: Throwable) { null }

    fun serialize(d: Doc): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte()))
        fun wm(recs: List<Rec>) { for (r in recs) { o.write(varint(r.key.size.toLong())); o.write(r.key); o.write(varint(r.value.size.toLong())); o.write(r.value) }; o.write(0x00) }
        wm(d.globals); for (m in d.inputs) wm(m); for (m in d.outputs) wm(m)
        return o.toByteArray()
    }

    /** Filas (etiqueta, valor) para mostrar el contenido. */
    fun resumen(d: Doc): List<Pair<String, String>> {
        val f = ArrayList<Pair<String, String>>()
        f.add("Version" to "${d.tx.version}")
        f.add("Inputs" to "${d.tx.ins.size}")
        d.tx.ins.forEachIndexed { i, inp ->
            val recs = d.inputs[i]
            val wu = recs.firstOrNull { it.key.size == 1 && it.key[0].toInt() == 0x01 }
            val amt = if (wu != null) { var v = 0L; for (k in 0 until 8) v = v or ((wu.value[k].toLong() and 0xFF) shl (8*k)); v } else -1L
            val sigs = recs.count { it.key.isNotEmpty() && it.key[0].toInt() == 0x02 }
            val det = (if (amt >= 0) "${btc(amt)} · " else "") + "$sigs sig"
            f.add("in #$i" to "${hex(rev(inp.txid))}:${inp.vout} · $det")
        }
        f.add("Outputs" to "${d.tx.outs.size}")
        d.tx.outs.forEachIndexed { j, out -> f.add("out #$j" to btc(out.value)) }
        f.add("Locktime" to "${d.tx.locktime}")
        return f
    }
    private fun btc(s: Long) = "%.8f".format(s / 1e8).trimEnd('0').trimEnd('.') + " BTC"

    private fun sighashLegacy(tx: Tx, idx: Int, scriptCodeHex: String): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(le(tx.version, 4)); b.write(varint(tx.ins.size.toLong()))
        tx.ins.forEachIndexed { i, inp -> b.write(inp.txid); b.write(le(inp.vout, 4))
            if (i == idx) b.write(varStr(scriptCodeHex)) else b.write(0); b.write(le(inp.seq, 4)) }
        b.write(varint(tx.outs.size.toLong()))
        tx.outs.forEach { o -> b.write(le(o.value, 8)); b.write(varint(o.spk.size.toLong())); b.write(o.spk) }
        b.write(le(tx.locktime, 4)); b.write(le(1, 4))
        return dsha(b.toByteArray())
    }
    private fun sighashBip143(tx: Tx, idx: Int, scriptCodeHex: String, amount: Long): ByteArray {
        val pv = ByteArrayOutputStream(); val sq = ByteArrayOutputStream(); val op = ByteArrayOutputStream()
        tx.ins.forEach { inp -> pv.write(inp.txid); pv.write(le(inp.vout, 4)); sq.write(le(inp.seq, 4)) }
        tx.outs.forEach { o -> op.write(le(o.value, 8)); op.write(varint(o.spk.size.toLong())); op.write(o.spk) }
        val b = ByteArrayOutputStream()
        b.write(le(tx.version, 4)); b.write(dsha(pv.toByteArray())); b.write(dsha(sq.toByteArray()))
        b.write(tx.ins[idx].txid); b.write(le(tx.ins[idx].vout, 4)); b.write(bytes(scriptCodeHex))
        b.write(le(amount, 8)); b.write(le(tx.ins[idx].seq, 4)); b.write(dsha(op.toByteArray()))
        b.write(le(tx.locktime, 4)); b.write(le(1, 4))
        return dsha(b.toByteArray())
    }

    /** Firma las entradas que controla [privHex]. Devuelve cuántas firmó. */
    fun sign(d: Doc, privHex: String): Int {
        val pub = try { HunterEngine.pubDeHex(privHex) } catch (e: Throwable) { "" }
        if (pub.isBlank()) return 0
        val pubBytes = bytes(pub)
        val miH160 = hex(Ripemd160.hash160(pubBytes))
        var firmados = 0
        for (i in d.tx.ins.indices) {
            val recs = d.inputs[i]
            if (recs.any { it.key.size >= 1 && it.key[0].toInt() == 0x02 && it.key.copyOfRange(1, it.key.size).contentEquals(pubBytes) }) continue
            val wu = recs.firstOrNull { it.key.size == 1 && it.key[0].toInt() == 0x01 }
            val nwu = recs.firstOrNull { it.key.size == 1 && it.key[0].toInt() == 0x00 }
            var spk: ByteArray? = null; var amount = 0L
            if (wu != null) { val c = TxDecoder.Cursor(wu.value); amount = c.u64(); spk = c.bytes(c.varint().toInt()) }
            else if (nwu != null) { val prev = parseTx(nwu.value); val vo = d.tx.ins[i].vout.toInt(); if (vo < prev.outs.size) { amount = prev.outs[vo].value; spk = prev.outs[vo].spk } }
            if (spk == null) continue
            val spkHex = hex(spk)
            val z: ByteArray = when {
                spkHex.startsWith("0014") && spkHex.length == 44 && spkHex.substring(4) == miH160 ->
                    sighashBip143(d.tx, i, "1976a914$miH160" + "88ac", amount)
                spkHex.startsWith("76a914") && spkHex.endsWith("88ac") && spkHex.length == 50 && spkHex.substring(6, 46) == miH160 ->
                    sighashLegacy(d.tx, i, spkHex)
                else -> continue
            }
            val der = try { HunterEngine.firmarHash(privHex, hex(z)) } catch (e: Throwable) { "" }
            if (der.isBlank()) continue
            recs.add(Rec(byteArrayOf(0x02) + pubBytes, bytes(der + "01")))
            firmados++
        }
        return firmados
    }
}
