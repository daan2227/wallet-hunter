package com.hunter.btc

/**
 * BIP322: firmar y verificar mensajes con direcciones modernas (bc1q, bc1p, 3…, y también 1…), lo que la
 * firma clásica de "Sign & verify message" no puede: aquella sólo vale para claves P2PKH.
 *
 * La firma es la de una transacción virtual que no se puede difundir: `to_spend` paga 0 a la dirección con
 * el hash etiquetado del mensaje dentro, y `to_sign` la gasta hacia un OP_RETURN. Quien sabe gastar de la
 * dirección sabe firmar `to_sign`. Formatos:
 *   smp…  simple: sólo el testigo (P2WPKH, P2TR), en base64.
 *   ful…  completo: toda `to_sign` (para P2SH-P2WPKH y P2PKH, que necesitan scriptSig).
 *   sin prefijo: firmas simples de antes de que el BIP fijara los prefijos; se aceptan al verificar.
 *
 * Se entienden los scripts de una sola clave que usa la app: P2WPKH, P2TR por clave, P2SH-P2WPKH y P2PKH.
 * Cualquier otro (multifirma, bloqueos de tiempo, ruta de script) da "inconcluso", como pide el BIP para
 * quien no tiene un intérprete de scripts completo; nunca "válido" sin comprobarlo.
 *
 * Puro: las firmas (ECDSA y Schnorr) las hace y comprueba el motor, inyectado. Bip322Test lo prueba con
 * los vectores oficiales.
 */
object Bip322 {

    /** Las comprobaciones criptográficas, que en la app hace el motor nativo (HunterEngine.ec). */
    interface Verificador {
        /** ECDSA DER (sin byte de sighash), DER estricto y s bajo. */
        fun ecdsa(pub: ByteArray, hash: ByteArray, der: ByteArray): Boolean
        /** BIP340 con la x-only. */
        fun schnorr(xonly: ByteArray, hash: ByteArray, firma64: ByteArray): Boolean
    }

    sealed class Veredicto {
        /** Válida. [tiempo] y [edad] son el nLockTime y el nSequence de to_sign (0 = sin bloqueo). */
        data class Valida(val tiempo: Long = 0, val edad: Long = 0) : Veredicto()
        data class Invalida(val motivo: String) : Veredicto()
        /** No se pudo comprobar (un tipo de script que no se entiende aquí). No es ni buena ni mala. */
        data class Inconclusa(val motivo: String) : Veredicto()
    }

    // ── Transacciones ──────────────────────────────────────────────────────────────────────────────

    class Entrada(val txid: ByteArray, val vout: Long, val scriptSig: ByteArray, val secuencia: Long, val testigo: List<ByteArray>)
    class Salida(val valor: Long, val script: ByteArray)
    class Tx(val version: Long, val entradas: List<Entrada>, val salidas: List<Salida>, val bloqueo: Long)

    private val OP_RETURN = byteArrayOf(0x6a)

    fun hashMensaje(m: ByteArray): ByteArray = etiquetado("BIP0322-signed-message", m)

    /** to_spend: paga 0 a [script] con OP_0 PUSH32[hash del mensaje] en su única entrada (que no existe). */
    fun toSpend(script: ByteArray, mensaje: ByteArray): Tx = Tx(0,
        listOf(Entrada(ByteArray(32), 0xffffffffL, byteArrayOf(0x00, 0x20) + hashMensaje(mensaje), 0, emptyList())),
        listOf(Salida(0, script)), 0)

    /** to_sign sin firmar: gasta to_spend:0 hacia un OP_RETURN, todo a cero. */
    fun toSign(spend: Tx, scriptSig: ByteArray = ByteArray(0), testigo: List<ByteArray> = emptyList()): Tx = Tx(0,
        listOf(Entrada(txid(spend), 0, scriptSig, 0, testigo)), listOf(Salida(0, OP_RETURN)), 0)

    /** txid en orden interno (el de los prevout), no el que se muestra. */
    fun txid(tx: Tx): ByteArray = dsha(serializar(tx, false))

    fun serializar(tx: Tx, conTestigo: Boolean = true): ByteArray {
        val t = conTestigo && tx.entradas.any { it.testigo.isNotEmpty() }
        val o = java.io.ByteArrayOutputStream()
        o.write(le(tx.version, 4))
        if (t) { o.write(0); o.write(1) }
        o.write(varint(tx.entradas.size.toLong()))
        for (e in tx.entradas) { o.write(e.txid); o.write(le(e.vout, 4)); o.write(vstr(e.scriptSig)); o.write(le(e.secuencia, 4)) }
        o.write(varint(tx.salidas.size.toLong()))
        for (s in tx.salidas) { o.write(le(s.valor, 8)); o.write(vstr(s.script)) }
        if (t) for (e in tx.entradas) o.write(pilaSerializada(e.testigo))
        o.write(le(tx.bloqueo, 4))
        return o.toByteArray()
    }

    /** Lee una transacción (con o sin testigo). null si no es una transacción bien formada y entera. */
    fun leer(b: ByteArray): Tx? = try {
        val c = Lector(b)
        val version = c.u32()
        var segwit = false
        if (c.quedan() >= 2 && b[c.p].toInt() == 0 && b[c.p + 1].toInt() == 1) { segwit = true; c.p += 2 }
        val nin = c.varint(); require(nin in 1..10_000)
        val ins = ArrayList<Entrada>()
        repeat(nin.toInt()) { ins.add(Entrada(c.bytes(32), c.u32(), c.bytes(c.varint().toInt()), c.u32(), emptyList())) }
        val nout = c.varint(); require(nout in 0..10_000)
        val outs = ArrayList<Salida>()
        repeat(nout.toInt()) { outs.add(Salida(c.u64(), c.bytes(c.varint().toInt()))) }
        val ins2 = if (!segwit) ins else ins.map { e ->
            val n = c.varint(); require(n <= 10_000)
            Entrada(e.txid, e.vout, e.scriptSig, e.secuencia, (0 until n).map { c.bytes(c.varint().toInt()) })
        }
        val bloqueo = c.u32()
        require(c.quedan() == 0)
        Tx(version, ins2, outs, bloqueo)
    } catch (e: Exception) { null }

    // ── Hash de firma ──────────────────────────────────────────────────────────────────────────────

    /** Lo que gasta una entrada: su importe y su scriptPubKey (de la salida previa). */
    class Previa(val valor: Long, val script: ByteArray)

    /** BIP143 de la entrada [i] que gasta [valor], SIGHASH_ALL. */
    fun sighashV0(tx: Tx, i: Int, scriptCode: ByteArray, valor: Long): ByteArray {
        val prev = java.io.ByteArrayOutputStream(); val seq = java.io.ByteArrayOutputStream(); val outs = java.io.ByteArrayOutputStream()
        for (e in tx.entradas) { prev.write(e.txid); prev.write(le(e.vout, 4)); seq.write(le(e.secuencia, 4)) }
        for (s in tx.salidas) { outs.write(le(s.valor, 8)); outs.write(vstr(s.script)) }
        val ei = tx.entradas[i]
        val o = java.io.ByteArrayOutputStream()
        o.write(le(tx.version, 4)); o.write(dsha(prev.toByteArray())); o.write(dsha(seq.toByteArray()))
        o.write(ei.txid); o.write(le(ei.vout, 4)); o.write(vstr(scriptCode)); o.write(le(valor, 8)); o.write(le(ei.secuencia, 4))
        o.write(dsha(outs.toByteArray())); o.write(le(tx.bloqueo, 4)); o.write(le(1, 4))
        return dsha(o.toByteArray())
    }
    /** BIP143 de la entrada 0 (importe 0, que es lo que paga to_spend). */
    fun sighashV0(tx: Tx, scriptCode: ByteArray): ByteArray = sighashV0(tx, 0, scriptCode, 0)

    /** El sighash clásico de la entrada [i] con [scriptCode], SIGHASH_ALL. */
    fun sighashLegado(tx: Tx, i: Int, scriptCode: ByteArray): ByteArray {
        val t = Tx(tx.version, tx.entradas.mapIndexed { k, e -> Entrada(e.txid, e.vout, if (k == i) scriptCode else ByteArray(0), e.secuencia, emptyList()) },
                   tx.salidas, tx.bloqueo)
        return dsha(serializar(t, false) + le(1, 4))
    }
    fun sighashLegado(tx: Tx, scriptCode: ByteArray): ByteArray = sighashLegado(tx, 0, scriptCode)

    /** BIP341, entrada [i] por clave, sin anejo; [tipo] 0 (DEFAULT) o 1 (ALL). Firma los importes y scripts de TODAS. */
    fun sighashTaproot(tx: Tx, i: Int, previas: List<Previa>, tipo: Int): ByteArray {
        require(previas.size == tx.entradas.size)
        val prev = java.io.ByteArrayOutputStream(); val imp = java.io.ByteArrayOutputStream()
        val spks = java.io.ByteArrayOutputStream(); val seq = java.io.ByteArrayOutputStream(); val outs = java.io.ByteArrayOutputStream()
        for ((k, e) in tx.entradas.withIndex()) {
            prev.write(e.txid); prev.write(le(e.vout, 4)); imp.write(le(previas[k].valor, 8)); spks.write(vstr(previas[k].script)); seq.write(le(e.secuencia, 4))
        }
        for (s in tx.salidas) { outs.write(le(s.valor, 8)); outs.write(vstr(s.script)) }
        val o = java.io.ByteArrayOutputStream()
        o.write(0); o.write(tipo); o.write(le(tx.version, 4)); o.write(le(tx.bloqueo, 4))
        o.write(sha(prev.toByteArray())); o.write(sha(imp.toByteArray())); o.write(sha(spks.toByteArray())); o.write(sha(seq.toByteArray()))
        o.write(sha(outs.toByteArray()))
        o.write(0); o.write(le(i.toLong(), 4))
        return etiquetado("TapSighash", o.toByteArray())
    }
    /** La entrada 0 de una to_sign de una sola entrada (gasta 0 de [spk]). */
    fun sighashTaproot(tx: Tx, spk: ByteArray, tipo: Int): ByteArray = sighashTaproot(tx, 0, tx.entradas.map { Previa(0, spk) }, tipo)

    /** El ajuste BIP341/BIP86 de una x-only sin árbol de scripts: t = hash etiquetado "TapTweak". */
    fun tapTweak(xonly: ByteArray): ByteArray = etiquetado("TapTweak", xonly)

    // ── Firmar ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Firma [mensaje] para la dirección [info] con la pública [pub33] de su clave. [ecdsa] firma un hash de 32
     * con la privada (DER, s bajo); [schnorr] firma con la privada YA AJUSTADA de Taproot (64 bytes).
     * Devuelve la firma con su prefijo, o null si la clave no es la de la dirección.
     */
    fun firmar(info: BtcAddress.Info, mensaje: ByteArray, pub33: ByteArray,
               ecdsa: (ByteArray) -> ByteArray?, schnorr: (ByteArray) -> ByteArray?): String? {
        val spk = BtcAddress.scriptPubKey(info)
        val spend = toSpend(spk, mensaje)
        val h160 = Ripemd160.hash160(pub33)
        return when (info.type) {
            BtcAddress.Type.P2WPKH -> {
                if (!h160.contentEquals(info.program)) return null
                val der = ecdsa(sighashV0(toSign(spend), p2pkh(h160))) ?: return null
                "smp" + b64(pilaSerializada(listOf(der + 0x01.toByte(), pub33)))
            }
            BtcAddress.Type.P2TR -> {
                val sig = schnorr(sighashTaproot(toSign(spend), spk, 0)) ?: return null
                if (sig.size != 64) return null
                "smp" + b64(pilaSerializada(listOf(sig)))
            }
            BtcAddress.Type.P2SH -> {
                val redeem = byteArrayOf(0x00, 0x14) + h160
                if (!Ripemd160.hash160(redeem).contentEquals(info.program)) return null
                val scriptSig = empuje(redeem)
                val der = ecdsa(sighashV0(toSign(spend, scriptSig), p2pkh(h160))) ?: return null
                "ful" + b64(serializar(toSign(spend, scriptSig, listOf(der + 0x01.toByte(), pub33))))
            }
            BtcAddress.Type.P2PKH -> {
                if (!h160.contentEquals(info.program)) return null
                val der = ecdsa(sighashLegado(toSign(spend), spk)) ?: return null
                "ful" + b64(serializar(toSign(spend, empuje(der + 0x01.toByte()) + empuje(pub33))))
            }
            BtcAddress.Type.P2WSH -> null
        }
    }

    // ── Verificar ──────────────────────────────────────────────────────────────────────────────────

    fun verificar(info: BtcAddress.Info, mensaje: ByteArray, firmaTexto: String, v: Verificador): Veredicto {
        val f = firmaTexto.filterNot { it.isWhitespace() }
        if (f.isEmpty()) return Veredicto.Invalida("The signature is empty.")
        val spk = BtcAddress.scriptPubKey(info)
        val spend = toSpend(spk, mensaje)
        val prefijo = f.take(3)
        return when (prefijo) {
            "smp" -> simple(info, spk, spend, f.drop(3), v)
            "ful" -> {
                val raw = deB64(f.drop(3)) ?: return Veredicto.Invalida("The signature is not valid base64.")
                val tx = leer(raw) ?: return Veredicto.Invalida("A 'ful' signature must be a whole transaction, and this is not.")
                completa(info, spk, spend, tx, v)
            }
            "pof" -> Veredicto.Inconclusa("This is a proof of funds (it also shows coins). Checking those needs the chain; not done here.")
            else -> {
                val raw = deB64(f) ?: return Veredicto.Invalida("The signature is not valid base64.")
                // 65 bytes con cabecera 27..42: una firma clásica, no BIP322.
                if (raw.size == 65 && (raw[0].toInt() and 0xff) in 27..42)
                    return Veredicto.Inconclusa("This is a classic (legacy) signature. Check it with 'Sign & verify message'.")
                simple(info, spk, spend, f, v)   // firmas de antes de los prefijos
            }
        }
    }

    private fun simple(info: BtcAddress.Info, spk: ByteArray, spend: Tx, b64: String, v: Verificador): Veredicto {
        val raw = deB64(b64) ?: return Veredicto.Invalida("The signature is not valid base64.")
        if (info.type != BtcAddress.Type.P2WPKH && info.type != BtcAddress.Type.P2TR && info.type != BtcAddress.Type.P2WSH)
            return Veredicto.Invalida("A simple signature only exists for native SegWit addresses (bc1…).")
        val pila = leerPila(raw) ?: return Veredicto.Invalida("The signature is not a valid witness.")
        return comprobar(info, spk, toSign(spend, ByteArray(0), pila), v)
    }

    private fun completa(info: BtcAddress.Info, spk: ByteArray, spend: Tx, tx: Tx, v: Verificador): Veredicto {
        if (tx.entradas.size != 1) return Veredicto.Invalida("A 'ful' signature must have exactly one input.")
        val e = tx.entradas[0]
        if (!e.txid.contentEquals(txid(spend)) || e.vout != 0L)
            return Veredicto.Invalida("The signature does not commit to this message and address.")
        if (tx.salidas.size != 1 || tx.salidas[0].valor != 0L || !tx.salidas[0].script.contentEquals(OP_RETURN))
            return Veredicto.Invalida("The signed transaction must have a single empty OP_RETURN output.")
        if (tx.version != 0L && tx.version != 2L) return Veredicto.Inconclusa("Unknown transaction version ${tx.version}.")
        return comprobar(info, spk, tx, v)
    }

    /** Evalúa el gasto de to_spend:0 por to_sign (de una sola entrada) para los scripts de una clave. */
    private fun comprobar(info: BtcAddress.Info, spk: ByteArray, tx: Tx, v: Verificador): Veredicto =
        comprobarEntrada(tx, 0, listOf(Previa(0, spk)), v)

    /**
     * Evalúa el gasto de la entrada [i] de [tx], que gasta [previas][i], según el tipo de su script: P2WPKH,
     * P2TR por clave, P2SH-P2WPKH o P2PKH. Otro script → inconcluso. [previas] son las de TODAS las entradas
     * (Taproot las firma todas).
     */
    fun comprobarEntrada(tx: Tx, i: Int, previas: List<Previa>, v: Verificador): Veredicto {
        val e = tx.entradas[i]; val w = e.testigo
        val spk = previas[i].script; val valor = previas[i].valor
        val mal = Veredicto.Invalida(if (i == 0) "The signature does not match this message and address." else "Coin #$i is not signed by its owner.")
        val ok = Veredicto.Valida(tx.bloqueo, e.secuencia)
        fun v0(h160: ByteArray): Veredicto {
            if (w.size != 2) return mal
            val sig = w[0]; val pub = w[1]
            if (pub.size != 33 || (pub[0].toInt() != 2 && pub[0].toInt() != 3)) return mal   // segwit: sólo comprimidas
            if (!Ripemd160.hash160(pub).contentEquals(h160)) return mal
            if (sig.isEmpty() || sig.last().toInt() != 0x01) return mal                        // SIGHASH_ALL
            return if (v.ecdsa(pub, sighashV0(tx, i, p2pkh(h160), valor), sig.copyOfRange(0, sig.size - 1))) ok else mal
        }
        return when (tipoDe(spk)) {
            BtcAddress.Type.P2WPKH -> if (e.scriptSig.isNotEmpty()) mal else v0(spk.copyOfRange(2, 22))
            BtcAddress.Type.P2TR -> {
                if (e.scriptSig.isNotEmpty() || w.isEmpty()) return mal
                if (w.size >= 2 && w.last().isNotEmpty() && w.last()[0].toInt() == 0x50) return Veredicto.Inconclusa("Taproot annex is not allowed.")
                if (w.size != 1) return Veredicto.Inconclusa("Taproot script-path signatures (e.g. time locks) can't be checked here.")
                val sig = w[0]
                val tipo = when (sig.size) { 64 -> 0; 65 -> sig[64].toInt() and 0xff; else -> return mal }
                if (sig.size == 65 && tipo != 0x01) return mal
                if (v.schnorr(spk.copyOfRange(2, 34), sighashTaproot(tx, i, previas, tipo), sig.copyOfRange(0, 64))) ok else mal
            }
            BtcAddress.Type.P2SH -> {
                // El último empuje es el script que se canjea; tiene que ser el de la dirección.
                val pushes = empujes(e.scriptSig) ?: return Veredicto.Inconclusa("This P2SH script can't be checked here.")
                val redeem = pushes.lastOrNull() ?: return mal
                if (!Ripemd160.hash160(redeem).contentEquals(spk.copyOfRange(2, 22))) return mal
                if (pushes.size == 1 && redeem.size == 22 && redeem[0].toInt() == 0 && redeem[1].toInt() == 0x14) v0(redeem.copyOfRange(2, 22))
                else Veredicto.Inconclusa("This P2SH script (multisig or wrapped script) can't be checked here.")
            }
            BtcAddress.Type.P2PKH -> {
                if (w.isNotEmpty()) return mal
                val pushes = empujes(e.scriptSig) ?: return mal
                if (pushes.size != 2) return mal
                val sig = pushes[0]; val pub = pushes[1]
                if (!Ripemd160.hash160(pub).contentEquals(spk.copyOfRange(3, 23))) return mal
                if (sig.isEmpty() || sig.last().toInt() != 0x01) return mal
                if (v.ecdsa(pub, sighashLegado(tx, i, spk), sig.copyOfRange(0, sig.size - 1))) ok else mal
            }
            BtcAddress.Type.P2WSH -> Veredicto.Inconclusa("Script (P2WSH) signatures, such as multisig, can't be checked here.")
            null -> Veredicto.Inconclusa("This kind of script can't be checked here.")
        }
    }

    /** El tipo de un scriptPubKey estándar, o null. */
    fun tipoDe(s: ByteArray): BtcAddress.Type? = when {
        s.size == 22 && s[0].toInt() == 0x00 && s[1].toInt() == 0x14 -> BtcAddress.Type.P2WPKH
        s.size == 34 && s[0].toInt() == 0x00 && s[1].toInt() == 0x20 -> BtcAddress.Type.P2WSH
        s.size == 34 && s[0].toInt() == 0x51 && s[1].toInt() == 0x20 -> BtcAddress.Type.P2TR
        s.size == 23 && (s[0].toInt() and 0xff) == 0xa9 && s[1].toInt() == 0x14 && (s[22].toInt() and 0xff) == 0x87 -> BtcAddress.Type.P2SH
        s.size == 25 && s[0].toInt() == 0x76 && (s[1].toInt() and 0xff) == 0xa9 && s[2].toInt() == 0x14 &&
            (s[23].toInt() and 0xff) == 0x88 && (s[24].toInt() and 0xff) == 0xac -> BtcAddress.Type.P2PKH
        else -> null
    }

    // ── Prueba de fondos ("pof") ───────────────────────────────────────────────────────────────────
    //
    // La misma firma, pero to_sign gasta ADEMÁS las monedas que se quieren enseñar, cada una firmada por su
    // dueño, y se entrega como PSBT finalizada. Prueba control de esas monedas sin moverlas (la transacción
    // gasta to_spend, que no existe, así que nunca es válida en la red). Que sigan sin gastar y su importe
    // real los dice la cadena, no la firma: eso lo mira quien verifica (FirmaBip322.comprobarEnCadena).

    /** Una moneda: su outpoint (txid en orden interno) y lo que gasta; [txPrevia] entera si es P2PKH. */
    class Moneda(val txid: ByteArray, val vout: Long, val previa: Previa, val txPrevia: ByteArray? = null) {
        val txidTexto: String get() = txid.reversedArray().joinToString("") { "%02x".format(it) }
    }

    /** Una clave que firma una entrada: su pública, y cómo firmar con ella (ECDSA o Schnorr ya ajustada). */
    class Clave(val pub33: ByteArray, val ecdsa: (ByteArray) -> ByteArray?, val schnorr: (ByteArray) -> ByteArray?)

    class Fondos(val veredicto: Veredicto, val monedas: List<Moneda>)

    /**
     * Firma una prueba de fondos de [mensaje] por [info] que enseña [monedas]. [claves][0] es la de la
     * dirección; [claves][k] la de la moneda k-1. null si alguna clave no es la de lo que firma.
     */
    fun firmarFondos(info: BtcAddress.Info, mensaje: ByteArray, monedas: List<Moneda>, claves: List<Clave>): String? {
        require(claves.size == monedas.size + 1 && monedas.isNotEmpty())
        val spkA = BtcAddress.scriptPubKey(info)
        val spend = toSpend(spkA, mensaje)
        val ins = listOf(Entrada(txid(spend), 0, ByteArray(0), 0, emptyList())) +
                  monedas.map { Entrada(it.txid, it.vout, ByteArray(0), 0, emptyList()) }
        val sinFirmar = Tx(0, ins, listOf(Salida(0, OP_RETURN)), 0)
        val previas = listOf(Previa(0, spkA)) + monedas.map { it.previa }
        val firmadas = ArrayList<Entrada>()
        for ((i, e) in ins.withIndex()) {
            val c = claves[i]; val spk = previas[i].script; val h160 = Ripemd160.hash160(c.pub33)
            val (ss, w) = when (tipoDe(spk)) {
                BtcAddress.Type.P2WPKH -> {
                    if (!h160.contentEquals(spk.copyOfRange(2, 22))) return null
                    val der = c.ecdsa(sighashV0(sinFirmar, i, p2pkh(h160), previas[i].valor)) ?: return null
                    ByteArray(0) to listOf(der + 0x01.toByte(), c.pub33)
                }
                BtcAddress.Type.P2SH -> {
                    val redeem = byteArrayOf(0x00, 0x14) + h160
                    if (!Ripemd160.hash160(redeem).contentEquals(spk.copyOfRange(2, 22))) return null
                    val der = c.ecdsa(sighashV0(sinFirmar, i, p2pkh(h160), previas[i].valor)) ?: return null
                    empuje(redeem) to listOf(der + 0x01.toByte(), c.pub33)
                }
                BtcAddress.Type.P2PKH -> {
                    if (!h160.contentEquals(spk.copyOfRange(3, 23))) return null
                    val der = c.ecdsa(sighashLegado(sinFirmar, i, spk)) ?: return null
                    (empuje(der + 0x01.toByte()) + empuje(c.pub33)) to emptyList<ByteArray>()
                }
                BtcAddress.Type.P2TR -> {
                    val sig = c.schnorr(sighashTaproot(sinFirmar, i, previas, 0))?.takeIf { it.size == 64 } ?: return null
                    ByteArray(0) to listOf(sig)
                }
                else -> return null
            }
            firmadas.add(Entrada(e.txid, e.vout, ss, e.secuencia, w))
        }
        // PSBT finalizada: la transacción sin firmar y, por entrada, lo que gasta y su firma final.
        val o = java.io.ByteArrayOutputStream()
        fun par(k: Int, v: ByteArray) { o.write(varint(1)); o.write(k); o.write(vstr(v)) }
        o.write("psbt".toByteArray()); o.write(0xff)
        par(0x00, serializar(sinFirmar, false)); o.write(0)
        for ((i, e) in firmadas.withIndex()) {
            val p = previas[i]
            if (tipoDe(p.script) == BtcAddress.Type.P2PKH) par(0x00, if (i == 0) serializar(spend, false) else monedas[i - 1].txPrevia ?: return null)
            else par(0x01, le(p.valor, 8) + vstr(p.script))
            if (e.scriptSig.isNotEmpty()) par(0x07, e.scriptSig)
            if (e.testigo.isNotEmpty()) par(0x08, pilaSerializada(e.testigo))
            o.write(0)
        }
        o.write(0)   // la única salida, sin campos
        return "pof" + b64(o.toByteArray())
    }

    /**
     * Verifica una prueba de fondos: la firma de la dirección y la de cada moneda. Devuelve el veredicto y las
     * monedas que enseña (sin mirar la cadena: si existen, su importe real y si siguen sin gastar, aparte).
     */
    fun verificarFondos(info: BtcAddress.Info, mensaje: ByteArray, firmaTexto: String, v: Verificador): Fondos {
        fun mal(m: String) = Fondos(Veredicto.Invalida(m), emptyList())
        val f = firmaTexto.filterNot { it.isWhitespace() }
        if (!f.startsWith("pof")) return mal("A proof of funds starts with 'pof'.")
        val b = deB64(f.drop(3)) ?: return mal("The proof is not valid base64.")
        val psbt = leerPsbt(b) ?: return mal("The proof is not a valid PSBT.")
        val (sinFirmar, mapas) = psbt
        if (sinFirmar.entradas.size < 2) return mal("A proof of funds must include at least one coin.")
        val spkA = BtcAddress.scriptPubKey(info)
        val spend = toSpend(spkA, mensaje)
        val e0 = sinFirmar.entradas[0]
        if (!e0.txid.contentEquals(txid(spend)) || e0.vout != 0L) return mal("The proof does not commit to this message and address.")
        if (sinFirmar.salidas.size != 1 || sinFirmar.salidas[0].valor != 0L || !sinFirmar.salidas[0].script.contentEquals(OP_RETURN))
            return mal("The signed transaction must have a single empty OP_RETURN output.")
        if (sinFirmar.version != 0L && sinFirmar.version != 2L) return Fondos(Veredicto.Inconclusa("Unknown transaction version."), emptyList())
        // Lo que gasta cada entrada: la 0 es to_spend; las demás, su witness_utxo o la salida de su tx previa
        // (que tiene que ser la del outpoint: se comprueba su txid). Una tx previa puede venir sólo la primera vez.
        val previasTx = HashMap<String, ByteArray>()
        val previas = ArrayList<Previa>(); val monedas = ArrayList<Moneda>()
        for ((i, e) in sinFirmar.entradas.withIndex()) {
            val m = mapas[i]
            if (i == 0) { previas.add(Previa(0, spkA)); continue }
            val clave = e.txid.joinToString("") { "%02x".format(it) }
            val cruda = m[0x00]?.also { if (!dsha(it).contentEquals(e.txid)) return mal("Coin #$i: its previous transaction does not match.") ; previasTx[clave] = it }
                ?: previasTx[clave]
            val p = when {
                m[0x01] != null -> {
                    val wu = m[0x01]!!; if (wu.size < 9) return mal("Coin #$i: bad data.")
                    val l = Lector(wu); val valor = l.u64(); val s = l.bytes(l.varint().toInt()); if (l.quedan() != 0) return mal("Coin #$i: bad data.")
                    Previa(valor, s)
                }
                cruda != null -> {
                    val tx = leer(cruda) ?: return mal("Coin #$i: bad previous transaction.")
                    val sal = tx.salidas.getOrNull(e.vout.toInt()) ?: return mal("Coin #$i: that output does not exist.")
                    Previa(sal.valor, sal.script)
                }
                else -> return mal("Coin #$i has no data about what it spends.")
            }
            previas.add(p); monedas.add(Moneda(e.txid, e.vout, p, cruda))
        }
        val firmada = Tx(sinFirmar.version, sinFirmar.entradas.mapIndexed { i, e ->
            val w = mapas[i][0x08]?.let { leerPila(it) ?: return mal("Input #$i: bad witness.") } ?: emptyList()
            Entrada(e.txid, e.vout, mapas[i][0x07] ?: ByteArray(0), e.secuencia, w)
        }, sinFirmar.salidas, sinFirmar.bloqueo)
        for (i in firmada.entradas.indices) {
            when (val r = comprobarEntrada(firmada, i, previas, v)) {
                is Veredicto.Valida -> {}
                else -> return Fondos(r, monedas)
            }
        }
        return Fondos(Veredicto.Valida(firmada.bloqueo, firmada.entradas[0].secuencia), monedas)
    }

    /** Una PSBT: la transacción sin firmar y, por entrada, sus campos (tipo de clave de 1 byte → valor). */
    private fun leerPsbt(b: ByteArray): Pair<Tx, List<Map<Int, ByteArray>>>? = try {
        require(b.size > 5 && String(b, 0, 4, Charsets.US_ASCII) == "psbt" && (b[4].toInt() and 0xff) == 0xff)
        val c = Lector(b); c.p = 5
        fun mapa(): Map<Int, ByteArray> {
            val m = HashMap<Int, ByteArray>()
            while (true) {
                val kl = c.varint().toInt(); if (kl == 0) return m
                val k = c.bytes(kl); val vl = c.varint().toInt(); val v = c.bytes(vl)
                if (kl == 1) { require((k[0].toInt() and 0xff) !in m) { "clave repetida" }; m[k[0].toInt() and 0xff] = v }
            }
        }
        val g = mapa()
        val tx = leer(g[0x00]!!)!!
        require(tx.entradas.all { it.scriptSig.isEmpty() && it.testigo.isEmpty() })
        val ins = tx.entradas.map { mapa() }
        repeat(tx.salidas.size) { mapa() }
        require(c.quedan() == 0)
        tx to ins
    } catch (e: Exception) { null }

    // ── Piezas ─────────────────────────────────────────────────────────────────────────────────────

    private fun p2pkh(h160: ByteArray) = byteArrayOf(0x76, 0xa9.toByte(), 0x14) + h160 + byteArrayOf(0x88.toByte(), 0xac.toByte())

    /** Un empuje mínimo de [d] (hasta 75 bytes directo; más, con PUSHDATA1). */
    private fun empuje(d: ByteArray): ByteArray = when {
        d.size <= 75 -> byteArrayOf(d.size.toByte()) + d
        d.size <= 255 -> byteArrayOf(0x4c, d.size.toByte()) + d
        else -> throw IllegalArgumentException("empuje demasiado largo")
    }

    /** Los datos de un script hecho sólo de empujes MÍNIMOS (MINIMALDATA); null si hay otra cosa. */
    private fun empujes(s: ByteArray): List<ByteArray>? {
        val out = ArrayList<ByteArray>(); var i = 0
        while (i < s.size) {
            val op = s[i].toInt() and 0xff; i++
            val n = when {
                op in 1..75 -> op
                op == 0x4c -> { if (i >= s.size) return null; (s[i].toInt() and 0xff).also { i++; if (it <= 75) return null } }
                else -> return null
            }
            if (i + n > s.size) return null
            out.add(s.copyOfRange(i, i + n)); i += n
        }
        return out
    }

    fun pilaSerializada(p: List<ByteArray>): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        o.write(varint(p.size.toLong())); for (x in p) o.write(vstr(x))
        return o.toByteArray()
    }

    private fun leerPila(b: ByteArray): List<ByteArray>? = try {
        val c = Lector(b); val n = c.varint(); require(n in 0..10_000)
        val p = (0 until n).map { c.bytes(c.varint().toInt()) }
        require(c.quedan() == 0); p
    } catch (e: Exception) { null }

    private class Lector(val b: ByteArray) {
        var p = 0
        fun quedan() = b.size - p
        fun u8(): Int { require(p < b.size); return b[p++].toInt() and 0xff }
        fun u32(): Long { var v = 0L; for (i in 0 until 4) v = v or (u8().toLong() shl (8 * i)); return v }
        fun u64(): Long { var v = 0L; for (i in 0 until 8) v = v or (u8().toLong() shl (8 * i)); return v }
        fun varint(): Long = when (val x = u8()) { 0xfd -> (u8() or (u8() shl 8)).toLong(); 0xfe -> u32(); 0xff -> u64(); else -> x.toLong() }
        fun bytes(n: Int): ByteArray { require(n >= 0 && p + n <= b.size); return b.copyOfRange(p, p + n).also { p += n } }
    }

    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
    private fun deB64(s: String): ByteArray? = try { java.util.Base64.getDecoder().decode(s) } catch (e: IllegalArgumentException) { null }
    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b)
    private fun dsha(b: ByteArray) = sha(sha(b))
    private fun etiquetado(tag: String, m: ByteArray): ByteArray { val t = sha(tag.toByteArray()); return sha(t + t + m) }
    private fun le(v: Long, n: Int) = ByteArray(n) { ((v ushr (8 * it)) and 0xff).toByte() }
    private fun varint(n: Long): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte())
        n <= 0xffff -> byteArrayOf(0xfd.toByte()) + le(n, 2)
        n <= 0xffffffffL -> byteArrayOf(0xfe.toByte()) + le(n, 4)
        else -> byteArrayOf(0xff.toByte()) + le(n, 8)
    }
    private fun vstr(b: ByteArray) = varint(b.size.toLong()) + b
}
