package com.hunter.btc

/**
 * Payjoin (BIP78), lado del que paga. Si la petición de pago trae "pj=<url>", antes de difundir se le
 * manda al cobrador la transacción firmada (como PSBT); él añade una moneda suya y devuelve una
 * propuesta. Si la propuesta pasa TODAS las comprobaciones, se firma y se difunde; si no (o el cobrador
 * no responde), se difunde la original, que es lo que BIP78 prevé. Así una cadena de análisis ya no
 * puede suponer que todas las entradas son del que paga.
 *
 * Aquí lo puro (armar la PSBT original, la URL y comprobar la propuesta), probado en la JVM y contra un
 * cobrador de prueba con Bitcoin Core (PayjoinTest). La red está en [pedir].
 * Sólo entradas P2WPKH: son las que la app sabe volver a firmar en una PSBT (Psbt.sign).
 */
object Payjoin {

    /** La URL de "pj=" de una URI BIP21 (decodificada), y si pide no sustituir la salida (pjos=0); null si no hay. */
    fun endpoint(uri: String): String? {
        val t = uri.trim(); if (!t.startsWith("bitcoin:", ignoreCase = true)) return null
        val pj = t.substringAfter('?', "").split('&').firstOrNull { it.startsWith("pj=", ignoreCase = true) }?.substringAfter('=') ?: return null
        val url = try { java.net.URLDecoder.decode(pj, "UTF-8") } catch (e: Exception) { return null }
        // BIP78: https, o http sólo a un servicio .onion (Tor ya cifra).
        val ok = url.startsWith("https://", ignoreCase = true) ||
            (url.startsWith("http://", ignoreCase = true) && java.net.URI(url).host?.endsWith(".onion") == true)
        return url.takeIf { ok }
    }

    private fun le(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * it)).toByte() }
    private fun varint(n: Long): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte()); n <= 0xffff -> byteArrayOf(0xfd.toByte()) + le(n, 2)
        else -> byteArrayOf(0xfe.toByte()) + le(n, 4)
    }

    /**
     * La PSBT original (v0) de una transacción ya firmada: la transacción sin firmas en el global y, en
     * cada entrada, su UTXO y su scriptSig/testigo finales. [utxos] = (importe, script) de cada entrada.
     */
    fun psbtOriginal(raw: ByteArray, utxos: List<Pair<Long, ByteArray>>): ByteArray {
        val tx = Psbt.parseTx(raw)
        require(tx.ins.size == utxos.size)
        val testigos = testigosDe(raw, tx.ins.size)
        val sin = Psbt.Tx(tx.version, tx.ins.map { Psbt.TxIn(it.txid, it.vout, it.seq) }, tx.outs, tx.locktime)
        val o = java.io.ByteArrayOutputStream()
        o.write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))
        fun rec(k: ByteArray, v: ByteArray) { o.write(varint(k.size.toLong())); o.write(k); o.write(varint(v.size.toLong())); o.write(v) }
        rec(byteArrayOf(0), Psbt.sinTestigos(sin)); o.write(0)
        tx.ins.forEachIndexed { i, x ->
            val (v, spk) = utxos[i]
            rec(byteArrayOf(0x01), le(v, 8) + varint(spk.size.toLong()) + spk)
            if (x.scriptSig.isNotEmpty()) rec(byteArrayOf(0x07), x.scriptSig)
            testigos[i]?.let { rec(byteArrayOf(0x08), it) }
            o.write(0)
        }
        repeat(tx.outs.size) { o.write(0) }
        return o.toByteArray()
    }

    /** Los testigos serializados (n.º de elementos + elementos) de cada entrada; null si no tiene. */
    private fun testigosDe(raw: ByteArray, n: Int): List<ByteArray?> {
        if (raw.size < 6 || raw[4].toInt() != 0 || raw[5].toInt() != 1) return List(n) { null }
        val c = TxDecoder.Cursor(raw); c.u32(); c.p += 2
        repeat(c.varint().toInt()) { c.bytes(36); c.bytes(c.varint().toInt()); c.u32() }
        repeat(c.varint().toInt()) { c.u64(); c.bytes(c.varint().toInt()) }
        return (0 until n).map {
            val ini = c.p
            repeat(c.varint().toInt()) { c.bytes(c.varint().toInt()) }
            raw.copyOfRange(ini, c.p).takeIf { b -> b.size > 1 }
        }
    }

    /** La URL con los parámetros de BIP78 (versión 1, sin sustituir la salida del cobrador). */
    fun url(endpoint: String, indiceCambio: Int?, maxComision: Long, tasaMin: Long): String {
        val sep = if (endpoint.contains('?')) "&" else "?"
        return endpoint + sep + "v=1&disableoutputsubstitution=true&minfeerate=$tasaMin" +
            (if (indiceCambio != null && maxComision > 0) "&additionalfeeoutputindex=$indiceCambio&maxadditionalfeecontribution=$maxComision" else "")
    }

    private fun clave(i: Psbt.TxIn) = Psbt.hexRev(i.txid) + ":" + i.vout

    /**
     * Las comprobaciones de BIP78 para el que paga. null si la propuesta se puede firmar; si no, el motivo.
     * [propios] son los scripts de la cartera (entradas y cambio); [maxComision], lo más que se acepta
     * pagar de más (sale del cambio).
     */
    fun comprobar(original: Psbt.Doc, prop: Psbt.Doc, utxosOrig: List<Pair<Long, ByteArray>>,
                  propios: Set<String>, maxComision: Long): String? {
        val o = original.tx; val p = prop.tx
        if (o.version != p.version || o.locktime != p.locktime) return "version or locktime changed"
        val nuestras = o.ins.associateBy { clave(it) }
        val idxP = p.ins.map { clave(it) }
        if (!nuestras.keys.all { it in idxP }) return "one of our inputs is missing"
        val seq = o.ins[0].seq
        if (p.ins.any { it.seq != seq }) return "input sequence numbers differ"
        val tipo = utxosOrig[0].second.let { if (it.size == 22 && it[0].toInt() == 0 && it[1].toInt() == 0x14) "wpkh" else "otro" }
        if (tipo != "wpkh") return "payjoin only for native SegWit (bc1q) inputs"
        var entradaAjena = 0L; var ajenas = 0
        for ((i, x) in p.ins.withIndex()) {
            if (clave(x) in nuestras) continue
            ajenas++
            val recs = prop.inputs[i]
            fun f(t: Int) = recs.firstOrNull { it.key.size == 1 && it.key[0].toInt() == t }?.value
            val wu = f(0x01) ?: return "a receiver input has no UTXO information"
            val c = TxDecoder.Cursor(wu); val v = c.u64(); val spk = c.bytes(c.varint().toInt())
            if (spk.size != 22 || spk[0].toInt() != 0 || spk[1].toInt() != 0x14) return "a receiver input has another script type"
            if (Descriptor.hex(spk) in propios) return "the receiver added one of OUR coins"
            if (f(0x07) == null && f(0x08) == null) return "a receiver input is not signed"
            entradaAjena += v
        }
        if (ajenas == 0) return "the receiver added no input"
        // Salidas: todas las originales siguen (mismo script); la del cobrador no baja; la nuestra baja lo permitido.
        val sumaNuestrasO = utxosOrig.sumOf { it.first }
        val nuestrasSalO = o.outs.filter { Descriptor.hex(it.spk) in propios }.sumOf { it.value }
        val nuestrasSalP = p.outs.filter { Descriptor.hex(it.spk) in propios }.sumOf { it.value }
        for (x in o.outs) {
            val y = p.outs.firstOrNull { it.spk.contentEquals(x.spk) } ?: return "an original output is missing"
            if (Descriptor.hex(x.spk) !in propios && y.value < x.value) return "the payment output was lowered"
        }
        val gastoO = sumaNuestrasO - nuestrasSalO
        val gastoP = sumaNuestrasO - nuestrasSalP
        if (gastoP - gastoO > maxComision) return "the receiver takes more fee from us than allowed"
        if (gastoP < gastoO) return "inconsistent amounts"
        // La comisión total no baja (el cobrador no puede abaratarla a nuestra costa ni dejarla atascada).
        val comisionO = sumaNuestrasO - o.outs.sumOf { it.value }
        val comisionP = sumaNuestrasO + entradaAjena - p.outs.sumOf { it.value }
        if (comisionP < comisionO) return "the proposal pays less fee than the original"
        return null
    }

    /**
     * Deja la propuesta lista para que firme el que paga: en sus entradas, el UTXO de la original y sin
     * firmas ni finales (el cobrador no debe ponerlos, pero si los pone se quitan).
     */
    fun prepararParaFirmar(original: Psbt.Doc, prop: Psbt.Doc, utxosOrig: List<Pair<Long, ByteArray>>) {
        val idx = original.tx.ins.mapIndexed { i, x -> clave(x) to i }.toMap()
        for ((i, x) in prop.tx.ins.withIndex()) {
            val j = idx[clave(x)] ?: continue
            val (v, spk) = utxosOrig[j]
            prop.inputs[i].clear()
            prop.inputs[i].add(Psbt.Rec(byteArrayOf(0x01), le(v, 8) + varint(spk.size.toLong()) + spk))
        }
    }

    /** POST de la original al cobrador (por Tor si está activado). La respuesta (base64) o null. */
    fun pedir(url: String, psbtOriginal: ByteArray, timeoutMs: Int = 30_000): ByteArray? = try {
        val c = RedPrivada.abrir(url)
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = timeoutMs
        c.setRequestProperty("Content-Type", "text/plain")
        c.outputStream.use { it.write(android.util.Base64.encodeToString(psbtOriginal, android.util.Base64.NO_WRAP).toByteArray()) }
        try {
            if (c.responseCode != 200) null
            else c.inputStream.use { ins -> ins.readBytes().takeIf { it.size < 1_000_000 } }
                ?.let { android.util.Base64.decode(String(it).trim(), android.util.Base64.DEFAULT) }
        } finally { c.disconnect() }
    } catch (e: Exception) { RegistroFallos.anotar("Payjoin.pedir", e); null }
}
