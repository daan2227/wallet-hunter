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
    /** Un txid tal como va en la transacción, en el orden en que lo enseñan los exploradores. */
    fun hexRev(b: ByteArray): String = hex(rev(b))

    internal fun txidHex(tx: Tx): String = hex(rev(dsha(sinTestigos(tx))))

    /** txid de una transacción firmada en hex (con o sin testigos), o null si no se puede leer. */
    fun txidDeRaw(rawHex: String): String? = try {
        txidHex(parseTx(bytes(rawHex.trim().lowercase())))
    } catch (e: Throwable) { null }

    /** Lo que hay que enseñar antes de firmar, y lo que impide firmar. */
    class Revision(val salidas: List<Pair<String, Long>>, val totalEntradas: Long?,
                   val comision: Long?, val problemas: List<String>)

    /** Programa segwit (v0..v16): OP_n + un push de 2..40 bytes que ocupa el resto del script. */
    private fun esPrograma(spk: ByteArray): Boolean {
        if (spk.size < 4 || spk.size > 42) return false
        val v = spk[0].toInt() and 0xFF
        return (v == 0x00 || v in 0x51..0x60) && (spk[1].toInt() and 0xFF) == spk.size - 2
    }

    private fun esP2sh(spk: ByteArray): Boolean {
        fun b(i: Int) = spk[i].toInt() and 0xFF
        return spk.size == 23 && b(0) == 0xa9 && b(1) == 0x14 && b(22) == 0x87
    }

    /** ¿Firmar esta entrada compromete su importe? Sólo si se gasta por segwit (BIP143/341). */
    private fun comprometeImporte(spk: ByteArray, redeem: ByteArray?): Boolean =
        esPrograma(spk) || (esP2sh(spk) && redeem != null && esPrograma(redeem))

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
                val redeem = recs.firstOrNull { it.key.size == 1 && it.key[0].toInt() == 0x04 }?.value
                if (!comprometeImporte(spk, redeem))
                    problemas.add("Input #$i is a legacy coin with no previous transaction attached. " +
                        "Its signature does not cover the amount, so the amount can't be checked. " +
                        "Attach the full previous transaction.")
            }
            val imp = try { importe(d, i) } catch (e: Throwable) { RegistroFallos.anotar("Psbt.revisar: importe", e); null }
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
            val txRec = globals.firstOrNull { it.key.size == 1 && it.key[0].toInt() == 0x00 }
            if (txRec != null) {
                val tx = parseTx(txRec.value)
                val inputs = ArrayList<MutableList<Rec>>(); for (i in tx.ins.indices) inputs.add(readMap(c))
                val outputs = ArrayList<MutableList<Rec>>(); for (j in tx.outs.indices) outputs.add(readMap(c))
                Doc(globals, inputs, outputs, tx)
            } else leerV2(c, globals)
        }
    } catch (e: Throwable) { null }

    /**
     * PSBT versión 2 (BIP370): la transacción no va entera en el global, sino repartida por entradas y
     * salidas. Bitcoin Core 30 las crea así por defecto, y Sparrow las acepta. Se reconstruye la [Tx]
     * para que todo lo demás (revisar, firmar, finalizar, extraer) funcione igual; al volver a
     * serializar los campos se escriben tal como vinieron.
     */
    private fun leerV2(c: TxDecoder.Cursor, globals: MutableList<Rec>): Doc? {
        fun g(t: Int) = globals.firstOrNull { it.key.size == 1 && (it.key[0].toInt() and 0xff) == t }?.value
        fun leU(b: ByteArray): Long { var v = 0L; for (i in b.indices.reversed()) v = (v shl 8) or (b[i].toLong() and 0xff); return v }
        if (g(0xfb)?.let { leU(it) } != 2L) return null
        val nIn = g(0x04)?.let { TxDecoder.Cursor(it).varint().toInt() } ?: return null
        val nOut = g(0x05)?.let { TxDecoder.Cursor(it).varint().toInt() } ?: return null
        require(nIn in 0..100_000 && nOut in 0..100_000)
        val version = g(0x02)?.let { leU(it) } ?: return null
        val inputs = ArrayList<MutableList<Rec>>(); repeat(nIn) { inputs.add(readMap(c)) }
        val outputs = ArrayList<MutableList<Rec>>(); repeat(nOut) { outputs.add(readMap(c)) }
        fun f(m: List<Rec>, t: Int) = m.firstOrNull { it.key.size == 1 && it.key[0].toInt() == t }?.value
        val ins = inputs.map { m ->
            TxIn(f(m, 0x0e)?.takeIf { it.size == 32 } ?: return null, f(m, 0x0f)?.let { leU(it) } ?: return null,
                 f(m, 0x10)?.let { leU(it) } ?: 0xFFFFFFFFL)
        }
        val outs = outputs.map { m -> TxOut(f(m, 0x03)?.let { leU(it) } ?: return null, f(m, 0x04) ?: return null) }
        // nLockTime (BIP370): por altura si todas las que piden algo lo admiten así, si no por tiempo; si ninguna, el de reserva.
        val alturas = inputs.mapNotNull { f(it, 0x12)?.let { b -> leU(b) } }
        val tiempos = inputs.mapNotNull { f(it, 0x11)?.let { b -> leU(b) } }
        val porAltura = alturas.isNotEmpty() && inputs.all { f(it, 0x11) == null || f(it, 0x12) != null }
        val locktime = when {
            porAltura -> alturas.max()
            tiempos.isNotEmpty() -> tiempos.max()
            else -> g(0x03)?.let { leU(it) } ?: 0L
        }
        return Doc(globals, inputs, outputs, Tx(version, ins, outs, locktime))
    }

    fun serialize(d: Doc): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte()))
        fun wm(recs: List<Rec>) { for (r in recs) { o.write(varint(r.key.size.toLong())); o.write(r.key); o.write(varint(r.value.size.toLong())); o.write(r.value) }; o.write(0x00) }
        wm(d.globals); for (m in d.inputs) wm(m); for (m in d.outputs) wm(m)
        return o.toByteArray()
    }

    /**
     * Una entrada para crear una PSBT: su importe y su script (witness_utxo), o
     * la transacción anterior entera si la entrada es legacy.
     */
    class EntradaPsbt(val importe: Long, val spk: ByteArray, val txAnterior: ByteArray?,
                      val derivacion: Derivacion? = null,
                      /** Registros (clave, valor) que se añaden tal cual: script de testigo, derivaciones de una multifirma… */
                      val extras: List<Pair<ByteArray, ByteArray>> = emptyList())

    /**
     * Lo que un firmador externo necesita para reconocer la entrada como suya:
     * la clave pública y la ruta BIP32 (con la huella del maestro), el script de
     * rescate si es P2SH-P2WPKH, o la clave interna si es P2TR.
     */
    class Derivacion(val pub33: ByteArray, val huella: ByteArray, val ruta: List<Long>,
                     val rescate: ByteArray? = null, val tapInterna: ByteArray? = null)

    /** "m/86'/0'/0'/0/3" → [0x80000056, 0x80000000, 0x80000000, 0, 3] (endurecidos con el bit alto). */
    fun rutaAIndices(ruta: String): List<Long> = ruta.removePrefix("m/").split('/').map { c ->
        val duro = c.endsWith("'") || c.endsWith("h")
        val n = c.trimEnd('\'', 'h').toLong()
        if (duro) n + 0x80000000L else n
    }

    /** Secuencia de las entradas: la misma del motor nativo (RBF activado, nLockTime válido). */
    const val SEQUENCIA = 0xFFFFFFFDL

    /**
     * La transacción sin firmar (versión 1, sin scriptSig ni testigos) que el motor nativo
     * construiría con la opción "unsigned", pero sin necesitar ninguna clave: para una
     * dirección vigilada, que sólo tiene el script. [entradas] son (txid como lo muestra
     * un explorador, vout); [salidas], (script, importe) en el orden en que van.
     */
    fun sinFirmar(entradas: List<Pair<String, Int>>, salidas: List<Pair<ByteArray, Long>>, locktime: Long,
                  version: Long = 1, secuencia: Long = SEQUENCIA): String {
        require(entradas.isNotEmpty() && salidas.isNotEmpty()) { "sin entradas o sin salidas" }
        // Un bloqueo relativo (OP_CHECKSEQUENCEVERIFY) sólo cuenta con versión 2 y la secuencia de la entrada (BIP68/112).
        require(version >= 1 && secuencia in 0..0xFFFFFFFFL)
        val o = ByteArrayOutputStream()
        o.write(le(version, 4)); o.write(varint(entradas.size.toLong()))
        for ((txid, vout) in entradas) {
            require(txid.length == 64) { "txid" }
            o.write(rev(bytes(txid))); o.write(le(vout.toLong(), 4)); o.write(0); o.write(le(secuencia, 4))
        }
        o.write(varint(salidas.size.toLong()))
        for ((spk, v) in salidas) { o.write(le(v, 8)); o.write(varint(spk.size.toLong())); o.write(spk) }
        o.write(le(locktime, 4))
        return hex(o.toByteArray())
    }

    /** Script de un OP_RETURN con [datos] (hex), como lo escribe el motor: 6a + push. */
    fun scriptDatos(datosHex: String): ByteArray {
        val d = bytes(datosHex)
        require(d.size <= 80) { "OP_RETURN de más de 80 bytes" }
        return if (d.size <= 75) byteArrayOf(0x6a, d.size.toByte()) + d else byteArrayOf(0x6a, 0x4c, d.size.toByte()) + d
    }

    /**
     * PSBT (BIP174) sin firmar de la transacción [txHex] (sin scriptSig ni
     * testigos). Cada entrada lleva su UTXO: witness_utxo, o la transacción
     * anterior si es legacy, que es lo que su firmante necesita para comprobar
     * el importe.
     */
    fun crear(txHex: String, entradas: List<EntradaPsbt>, salidasPropias: Map<Int, Derivacion> = emptyMap(),
              salidasExtras: Map<Int, List<Pair<ByteArray, ByteArray>>> = emptyMap()): ByteArray {
        val raw = bytes(txHex)
        val tx = parseTx(raw)
        require(tx.ins.size == entradas.size) { "una entrada por cada UTXO" }
        val globals = mutableListOf(Rec(byteArrayOf(0x00), raw))
        val inputs = entradas.map { e ->
            val recs = mutableListOf<Rec>()
            if (e.txAnterior != null) recs.add(Rec(byteArrayOf(0x00), e.txAnterior))
            else recs.add(Rec(byteArrayOf(0x01), le(e.importe, 8) + varint(e.spk.size.toLong()) + e.spk))
            e.derivacion?.let { d ->
                val indices = d.ruta.fold(ByteArray(0)) { acc, i -> acc + le(i, 4) }
                if (d.rescate != null) recs.add(Rec(byteArrayOf(0x04), d.rescate))           // PSBT_IN_REDEEM_SCRIPT
                if (d.tapInterna != null) {                                                  // Taproot
                    recs.add(Rec(byteArrayOf(0x17), d.tapInterna))                           // PSBT_IN_TAP_INTERNAL_KEY
                    recs.add(Rec(byteArrayOf(0x16) + d.tapInterna,                           // PSBT_IN_TAP_BIP32_DERIVATION
                        byteArrayOf(0) + d.huella + indices))                                // 0 hojas de tapscript
                } else {
                    recs.add(Rec(byteArrayOf(0x06) + d.pub33, d.huella + indices))           // PSBT_IN_BIP32_DERIVATION
                }
            }
            e.extras.forEach { (k, v) -> recs.add(Rec(k, v)) }
            recs
        }
        // Las salidas propias (el cambio) llevan su derivación: así el firmador sabe que vuelven a la cartera
        // y no las enseña como un pago a un tercero.
        val outputs = tx.outs.indices.map { j ->
            val recs = mutableListOf<Rec>()
            salidasPropias[j]?.let { d ->
                val indices = d.ruta.fold(ByteArray(0)) { acc, i -> acc + le(i, 4) }
                if (d.rescate != null) recs.add(Rec(byteArrayOf(0x00), d.rescate))                // PSBT_OUT_REDEEM_SCRIPT
                if (d.tapInterna != null) {
                    recs.add(Rec(byteArrayOf(0x05), d.tapInterna))                               // PSBT_OUT_TAP_INTERNAL_KEY
                    recs.add(Rec(byteArrayOf(0x07) + d.tapInterna, byteArrayOf(0) + d.huella + indices))  // PSBT_OUT_TAP_BIP32_DERIVATION
                } else recs.add(Rec(byteArrayOf(0x02) + d.pub33, d.huella + indices))           // PSBT_OUT_BIP32_DERIVATION
            }
            salidasExtras[j]?.forEach { (k, v) -> recs.add(Rec(k, v)) }
            recs
        }
        return serialize(Doc(globals, inputs, outputs, tx))
    }

    /** Filas (etiqueta, valor) para mostrar el contenido. */
    fun resumen(d: Doc): List<Pair<String, String>> {
        val f = ArrayList<Pair<String, String>>()
        f.add("Version" to "${d.tx.version}")
        f.add("Inputs" to "${d.tx.ins.size}")
        d.tx.ins.forEachIndexed { i, inp ->
            val recs = d.inputs[i]
            val wu = recs.firstOrNull { it.key.size == 1 && it.key[0].toInt() == 0x01 }
            val amt = if (wu != null && wu.value.size >= 8) { var v = 0L; for (k in 0 until 8) v = v or ((wu.value[k].toLong() and 0xFF) shl (8*k)); v } else -1L
            val sigs = recs.count { it.key.isNotEmpty() && it.key[0].toInt() == 0x02 }
            val det = (if (amt >= 0) "${btc(amt)} · " else "") + "$sigs sig"
            f.add("in #$i" to "${hex(rev(inp.txid))}:${inp.vout} · $det")
        }
        f.add("Outputs" to "${d.tx.outs.size}")
        d.tx.outs.forEachIndexed { j, out -> f.add("out #$j" to btc(out.value)) }
        f.add("Locktime" to "${d.tx.locktime}")
        return f
    }
    private fun btc(s: Long) = "%.8f".format(java.util.Locale.US, s / 1e8).trimEnd('0').trimEnd('.') + " BTC"

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

    /**
     * Pone la firma de [pub33] en las entradas P2WSH (multifirma, herencia) cuyo script de testigo la
     * contiene. [firma] recibe el hash (hex) y devuelve la firma DER (hex, sin el byte de tipo): en la app,
     * HunterEngine.firmarHash con la privada, que no sale del motor. Devuelve cuántas firmó.
     */
    fun firmarWsh(d: Doc, pub33: ByteArray, firma: (String) -> String): Int {
        if (revisar(d).problemas.isNotEmpty()) return 0
        var n = 0
        for (i in d.tx.ins.indices) {
            val recs = d.inputs[i]
            if (recs.any { it.key.size == 34 && it.key[0].toInt() == 0x02 && it.key.copyOfRange(1, 34).contentEquals(pub33) }) continue
            val ws = recs.firstOrNull { it.key.size == 1 && it.key[0].toInt() == 0x05 }?.value ?: continue
            val (importe, spk) = importe(d, i) ?: continue
            if (spk.size != 34 || spk[0].toInt() != 0 || spk[1].toInt() != 0x20 || !sha(ws).contentEquals(spk.copyOfRange(2, 34))) continue
            if (!contiene(ws, pub33)) continue
            val z = sighashBip143(d.tx, i, hex(varint(ws.size.toLong()) + ws), importe)
            val der = firma(hex(z)); if (der.isBlank()) continue
            recs.add(Rec(byteArrayOf(0x02) + pub33, bytes(der + "01")))
            n++
        }
        return n
    }

    /** Las claves de [huella] que piden firma en las entradas (PSBT_IN_BIP32_DERIVATION): (pública 33, ruta). */
    fun clavesDe(d: Doc, huella: ByteArray): List<Pair<ByteArray, List<Long>>> {
        val out = LinkedHashMap<String, Pair<ByteArray, List<Long>>>()
        for (recs in d.inputs) for (r in recs) {
            if (r.key.size != 34 || r.key[0].toInt() != 0x06 || r.value.size < 4 || (r.value.size - 4) % 4 != 0) continue
            if (!r.value.copyOfRange(0, 4).contentEquals(huella)) continue
            val ruta = (4 until r.value.size step 4).map { j ->
                (r.value[j].toLong() and 0xff) or ((r.value[j + 1].toLong() and 0xff) shl 8) or
                ((r.value[j + 2].toLong() and 0xff) shl 16) or ((r.value[j + 3].toLong() and 0xff) shl 24) }
            out[hex(r.key)] = r.key.copyOfRange(1, 34) to ruta
        }
        return out.values.toList()
    }

    /** [0x80000030, 0x80000000, 0, 5] → "m/48'/0'/0/5". */
    fun rutaTexto(r: List<Long>) = "m" + r.joinToString("") { "/" + if (it >= 0x80000000L) "${it - 0x80000000L}'" else "$it" }

    private fun contiene(h: ByteArray, n: ByteArray): Boolean {
        outer@ for (i in 0..h.size - n.size) { for (j in n.indices) if (h[i + j] != n[j]) continue@outer; return true }
        return false
    }

    /** Firma las entradas que controla [privHex]. Devuelve cuántas firmó. */
    fun sign(d: Doc, privHex: String): Int {
        // Nunca se firma lo que no pasa la revisión (ver revisar).
        if (revisar(d).problemas.isNotEmpty()) return 0
        val pub = try { HunterEngine.pubDeHex(privHex) } catch (e: Throwable) { RegistroFallos.anotar("Psbt.sign: pubDeHex", e); "" }
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
            val der = try { HunterEngine.firmarHash(privHex, hex(z)) } catch (e: Throwable) { RegistroFallos.anotar("Psbt.sign: firmarHash input #$i", e); "" }
            if (der.isBlank()) continue
            recs.add(Rec(byteArrayOf(0x02) + pubBytes, bytes(der + "01")))
            firmados++
        }
        return firmados
    }

    // ── Finalizar y extraer (BIP174: Input Finalizer y Transaction Extractor) ───────────────────────

    /**
     * Para las entradas que trae otro aparato con sus firmas parciales: arma el scriptSig/testigo final
     * de las de una sola clave (P2PKH, P2SH-P2WPKH, P2WPKH, P2TR por clave) y, si se pasa [wsh], el de
     * las P2WSH (multifirma, herencia): recibe el script y las firmas, devuelve la pila del testigo o null.
     * Como dice BIP174, al finalizar una entrada se quitan sus demás campos salvo los UTXO (y los de v2).
     * Devuelve cuántas entradas quedaron finalizadas (contando las que ya venían así).
     */
    fun finalizar(d: Doc, wsh: ((script: ByteArray, firmas: Map<String, ByteArray>) -> List<ByteArray>?)? = null): Int {
        var hechas = 0
        for (i in d.tx.ins.indices) {
            val recs = d.inputs[i]
            fun campo(t: Int) = recs.firstOrNull { it.key.size == 1 && it.key[0].toInt() == t }
            if (campo(0x07) != null || campo(0x08) != null) { hechas++; continue }
            val (_, spk) = importe(d, i) ?: continue
            val firmas = recs.filter { it.key.size == 34 && it.key[0].toInt() == 0x02 }
                .associate { hex(it.key.copyOfRange(1, 34)) to it.value }
            var scriptSig: ByteArray? = null; var testigo: List<ByteArray>? = null
            fun firmaDe(h160: ByteArray) = firmas.entries.firstOrNull { Ripemd160.hash160(bytes(it.key)).contentEquals(h160) }
            when {
                spk.size == 22 && spk[0].toInt() == 0 && spk[1].toInt() == 0x14 ->
                    firmaDe(spk.copyOfRange(2, 22))?.let { testigo = listOf(it.value, bytes(it.key)) }
                spk.size == 25 && spk[0] == 0x76.toByte() && spk[1] == 0xa9.toByte() ->
                    firmaDe(spk.copyOfRange(3, 23))?.let { scriptSig = empujar(it.value) + empujar(bytes(it.key)) }
                spk.size == 23 && spk[0] == 0xa9.toByte() -> {
                    val rd = campo(0x04)?.value
                    if (rd != null && rd.size == 22 && rd[0].toInt() == 0 && rd[1].toInt() == 0x14 &&
                        Ripemd160.hash160(rd).contentEquals(spk.copyOfRange(2, 22)))
                        firmaDe(rd.copyOfRange(2, 22))?.let { testigo = listOf(it.value, bytes(it.key)); scriptSig = empujar(rd) }
                }
                spk.size == 34 && spk[0].toInt() == 0x51 && spk[1].toInt() == 0x20 ->
                    campo(0x13)?.let { testigo = listOf(it.value) }
                spk.size == 34 && spk[0].toInt() == 0 && spk[1].toInt() == 0x20 && wsh != null -> {
                    val ws = campo(0x05)?.value
                    if (ws != null && sha(ws).contentEquals(spk.copyOfRange(2, 34)))
                        wsh(ws, firmas)?.let { testigo = it + listOf(ws) }
                }
            }
            if (scriptSig == null && testigo == null) continue
            // Se quedan los UTXO y, en una PSBT v2, los campos que SON la transacción (txid, vout, secuencia, bloqueos).
            val utxos = recs.filter { it.key.size == 1 && (it.key[0].toInt() and 0xff) in setOf(0x00, 0x01, 0x0e, 0x0f, 0x10, 0x11, 0x12) }
            recs.clear(); recs.addAll(utxos)
            scriptSig?.let { recs.add(Rec(byteArrayOf(0x07), it)) }
            testigo?.let { t ->
                val o = ByteArrayOutputStream(); o.write(varint(t.size.toLong()))
                for (e in t) { o.write(varint(e.size.toLong())); o.write(e) }
                recs.add(Rec(byteArrayOf(0x08), o.toByteArray()))
            }
            hechas++
        }
        return hechas
    }

    /** La transacción lista para difundir (con testigos si hace falta), o null si falta alguna entrada por finalizar. */
    fun extraer(d: Doc): ByteArray? {
        val ss = ArrayList<ByteArray>(); val ws = ArrayList<ByteArray?>()
        for (recs in d.inputs) {
            val a = recs.firstOrNull { it.key.size == 1 && it.key[0].toInt() == 0x07 }?.value
            val w = recs.firstOrNull { it.key.size == 1 && it.key[0].toInt() == 0x08 }?.value
            if (a == null && w == null) return null
            ss.add(a ?: ByteArray(0)); ws.add(w)
        }
        val conTestigo = ws.any { it != null }
        val o = ByteArrayOutputStream()
        o.write(le(d.tx.version, 4))
        if (conTestigo) { o.write(0); o.write(1) }
        o.write(varint(d.tx.ins.size.toLong()))
        d.tx.ins.forEachIndexed { i, x -> o.write(x.txid); o.write(le(x.vout, 4)); o.write(varint(ss[i].size.toLong())); o.write(ss[i]); o.write(le(x.seq, 4)) }
        o.write(varint(d.tx.outs.size.toLong()))
        for (x in d.tx.outs) { o.write(le(x.value, 8)); o.write(varint(x.spk.size.toLong())); o.write(x.spk) }
        if (conTestigo) for (w in ws) o.write(w ?: byteArrayOf(0))
        o.write(le(d.tx.locktime, 4))
        return o.toByteArray()
    }

    /** Empuje mínimo de [b] en un script. */
    internal fun empujar(b: ByteArray): ByteArray = when {
        b.isEmpty() -> byteArrayOf(0)
        b.size < 0x4c -> byteArrayOf(b.size.toByte()) + b
        b.size <= 0xff -> byteArrayOf(0x4c, b.size.toByte()) + b
        else -> byteArrayOf(0x4d, (b.size and 0xff).toByte(), (b.size shr 8).toByte()) + b
    }
}
