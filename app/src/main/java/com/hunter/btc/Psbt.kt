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
    class TxIn(val txid: ByteArray, val vout: Long, val seq: Long, val scriptSig: ByteArray = ByteArray(0))
    class TxOut(val value: Long, val spk: ByteArray)
    class Tx(val version: Long, val ins: List<TxIn>, val outs: List<TxOut>, val locktime: Long)
    class Doc(val globals: MutableList<Rec>, val inputs: List<MutableList<Rec>>,
              val outputs: List<MutableList<Rec>>, val tx: Tx)

    /**
     * Transacción en formato de red, con o sin testigos (BIP144). Antes no
     * entendía el marcador segwit (00 01): la transacción anterior de una entrada
     * legacy —hoy casi siempre segwit— se leía con cero entradas y salidas basura.
     */
    internal fun parseTx(b: ByteArray): Tx {
        val c = TxDecoder.Cursor(b)
        val version = c.u32()
        val segwit = b.size > c.p + 1 && b[c.p].toInt() == 0x00 && b[c.p + 1].toInt() == 0x01
        if (segwit) c.p += 2
        val nin = c.varint().toInt()
        require(nin in 0..100_000)
        val ins = ArrayList<TxIn>()
        for (i in 0 until nin) { val txid = c.bytes(32); val vout = c.u32(); val sl = c.varint().toInt(); val ss = c.bytes(sl); val seq = c.u32(); ins.add(TxIn(txid, vout, seq, ss)) }
        val nout = c.varint().toInt()
        require(nout in 0..100_000)
        val outs = ArrayList<TxOut>()
        for (j in 0 until nout) { val v = c.u64(); val sl = c.varint().toInt(); outs.add(TxOut(v, c.bytes(sl))) }
        if (segwit) for (i in 0 until nin) { val n = c.varint().toInt(); for (k in 0 until n) c.bytes(c.varint().toInt()) }
        return Tx(version, ins, outs, c.u32())
    }

    /** Serialización SIN testigos: la que define el txid. */
    internal fun sinTestigos(tx: Tx): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(le(tx.version, 4)); o.write(varint(tx.ins.size.toLong()))
        for (i in tx.ins) { o.write(i.txid); o.write(le(i.vout, 4)); o.write(varint(i.scriptSig.size.toLong())); o.write(i.scriptSig); o.write(le(i.seq, 4)) }
        o.write(varint(tx.outs.size.toLong()))
        for (x in tx.outs) { o.write(le(x.value, 8)); o.write(varint(x.spk.size.toLong())); o.write(x.spk) }
        o.write(le(tx.locktime, 4))
        return o.toByteArray()
    }

    /** txid en el orden en que se escribe (big-endian, como en los exploradores). */
    internal fun txidHex(tx: Tx): String = hex(rev(dsha(sinTestigos(tx))))

    /** Lo que hay que enseñar antes de firmar, y lo que impide firmar. */
    class Revision(val salidas: List<Pair<String, Long>>, val totalEntradas: Long?,
                   val comision: Long?, val problemas: List<String>)

    private fun esP2pkh(spk: ByteArray): Boolean {
        // Los bytes son con signo en Kotlin: 0x88 y 0xac salen negativos.
        fun b(i: Int) = spk[i].toInt() and 0xFF
        return spk.size == 25 && b(0) == 0x76 && b(1) == 0xa9 && b(2) == 0x14 && b(23) == 0x88 && b(24) == 0xac
    }

    private fun importe(d: Doc, i: Int): Pair<Long, ByteArray>? {
        val recs = d.inputs[i]
        val wu = recs.firstOrNull { it.key.size == 1 && it.key[0].toInt() == 0x01 }
        if (wu != null) { val c = TxDecoder.Cursor(wu.value); val a = c.u64(); return a to c.bytes(c.varint().toInt()) }
        val nwu = recs.firstOrNull { it.key.size == 1 && it.key[0].toInt() == 0x00 } ?: return null
        val prev = parseTx(nwu.value); val vo = d.tx.ins[i].vout.toInt()
        return if (vo < prev.outs.size) prev.outs[vo].value to prev.outs[vo].spk else null
    }

    /**
     * Revisa la PSBT antes de firmar. Antes se firmaba a ciegas: sin enseñar a
     * quién paga ni cuánto se va en comisión. Y una entrada P2WPKH se firmaba sólo
     * con witness_utxo, aunque viniera también la transacción anterior completa
     * diciendo otro importe (el patrón de CVE-2020-14199: se paga de comisión lo
     * que el atacante quiera).
     */
    fun revisar(d: Doc, testnet: Boolean = false): Revision {
        val problemas = ArrayList<String>()
        var total = 0L; var conocido = true
        for (i in d.tx.ins.indices) {
            val recs = d.inputs[i]
            val nwu = recs.firstOrNull { it.key.size == 1 && it.key[0].toInt() == 0x00 }
            val wu = recs.firstOrNull { it.key.size == 1 && it.key[0].toInt() == 0x01 }
            if (nwu != null) {
                val prev = try { parseTx(nwu.value) } catch (e: Throwable) { null }
                if (prev == null) { problemas.add("Input #$i: its previous transaction can't be read."); continue }
                if (txidHex(prev) != hex(rev(d.tx.ins[i].txid)))
                    problemas.add("Input #$i: the previous transaction attached is not the one it spends.")
                val vo = d.tx.ins[i].vout.toInt()
                if (vo >= prev.outs.size) problemas.add("Input #$i: the output it spends doesn't exist.")
                else if (wu != null) {
                    val c = TxDecoder.Cursor(wu.value); val a = c.u64(); val spk = c.bytes(c.varint().toInt())
                    if (a != prev.outs[vo].value || !spk.contentEquals(prev.outs[vo].spk))
                        problemas.add("Input #$i: witness_utxo and the previous transaction disagree on the amount.")
                }
            }
            // Una entrada legacy (P2PKH) firmada sólo con witness_utxo: su firma no
            // cubre el importe, así que el importe que dice el PSBT no se puede
            // comprobar. Sin la transacción anterior completa no se firma.
            if (nwu == null && wu != null) {
                val c = TxDecoder.Cursor(wu.value); c.u64(); val spk = c.bytes(c.varint().toInt())
                if (esP2pkh(spk))
                    problemas.add("Input #$i is a legacy coin with no previous transaction attached. " +
                        "Its signature does not cover the amount, so the amount can't be checked. " +
                        "Attach the full previous transaction.")
            }
            val imp = try { importe(d, i) } catch (e: Throwable) { null }
            if (imp == null) conocido = false else total += imp.first
        }
        val salidas = d.tx.outs.map { (SpkDireccion.de(it.spk, testnet) ?: "script ${hex(it.spk).take(20)}…") to it.value }
        val sumaSal = d.tx.outs.sumOf { it.value }
        val comision = if (conocido) total - sumaSal else null
        if (comision != null && comision < 0) problemas.add("The outputs spend more than the inputs hold.")
        return Revision(salidas, if (conocido) total else null, comision, problemas)
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
        // Nunca se firma lo que no pasa la revisión (ver revisar).
        if (revisar(d).problemas.isNotEmpty()) return 0
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
