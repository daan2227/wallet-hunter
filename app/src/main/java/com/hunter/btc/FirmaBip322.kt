package com.hunter.btc

/**
 * Bip322 (puro) conectado al motor: firmas ECDSA y Schnorr de secp256k1, y la búsqueda de la clave de una
 * dirección propia dentro de una semilla del teléfono. Las privadas se usan aquí y no se guardan.
 */
object FirmaBip322 {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Las comprobaciones con el motor: ECDSA con DER estricto y s bajo, y BIP340. */
    val verificador = object : Bip322.Verificador {
        override fun ecdsa(pub: ByteArray, hash: ByteArray, der: ByteArray) =
            HunterEngine.ec("ecdsa_ok", hex(pub), "${hex(hash)}|${hex(der)}") == "1"
        override fun schnorr(xonly: ByteArray, hash: ByteArray, firma64: ByteArray) =
            HunterEngine.ec("schnorr_ok", hex(xonly), "${hex(hash)}|${hex(firma64)}") == "1"
    }

    /** La dirección como Info, en mainnet o testnet; null si no es una dirección. */
    fun info(direccion: String): BtcAddress.Info? =
        (BtcAddress.validate(direccion, false) as? BtcAddress.Result.Valid)?.info
            ?: (BtcAddress.validate(direccion, true) as? BtcAddress.Result.Valid)?.info

    /**
     * Firma [mensaje] por [info] con la privada [privHex]. null si esa clave no es la de la dirección.
     * Taproot: firma con la privada ajustada (BIP86, sin árbol de scripts) y comprueba antes que su pública
     * es la clave de salida de la dirección; si no, no firma.
     */
    fun firmar(privHex: String, info: BtcAddress.Info, mensaje: ByteArray): String? {
        val pub = HunterEngine.ec("pub", privHex, "")
        if (pub.length != 66) return null
        val pub33 = bytes(pub)
        val ecdsa = { h: ByteArray -> HunterEngine.firmarHash(privHex, hex(h)).takeIf { it.isNotEmpty() }?.let { bytes(it) } }
        val schnorr = schnorr@{ h: ByteArray ->
            val neg = if (pub33[0].toInt() == 3) "1" else "0"
            val t = hex(Bip322.tapTweak(pub33.copyOfRange(1, 33)))
            val ajustada = HunterEngine.ec("priv_sum", "$privHex,$t", "$neg,0")
            if (ajustada.length != 64 || HunterEngine.ec("xonly", ajustada, "") != hex(info.program)) return@schnorr null
            val aux = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            HunterEngine.ec("schnorr", ajustada, "${hex(h)}|${hex(aux)}").takeIf { it.length == 128 }?.let { bytes(it) }
        }
        return Bip322.firmar(info, mensaje, pub33, ecdsa, schnorr)
    }

    // ── Prueba de fondos ───────────────────────────────────────────────────────────────────────────

    /** Una moneda de la cartera, con la ruta de su clave y su dirección. */
    class MonedaPropia(val moneda: Bip322.Moneda, val ruta: String, val direccion: String)

    /**
     * Las monedas sin gastar de la semilla [mn]: las cuatro familias, recepción y cambio, en la cuenta de la
     * frase. Las P2PKH llevan su transacción previa entera (la pide el formato). El Boolean dice si alguna
     * consulta falló (entonces la lista puede estar incompleta).
     */
    fun monedasDe(mn: String, testnet: Boolean = false): Pair<List<MonedaPropia>, Boolean> {
        val out = ArrayList<MonedaPropia>(); var incompleto = false
        val coin = if (testnet) 1 else 0; val cta = Frase.cuenta(mn)
        BalanceLookup.iniciarMemo()
        try {
            for (purpose in HdScanner.PURPOSES) for (cambio in 0..1) {
                val (usadas, _, inc) = HdScanner.scanBranchInformado(mn, purpose, cambio, testnet)
                if (inc) incompleto = true
                for (f in usadas) {
                    val inf = info(f.addr)
                    val js = ChainApi.get("/address/${f.addr}/utxo", testnet)
                    if (inf == null || js == null) { incompleto = true; continue }
                    val spk = BtcAddress.scriptPubKey(inf)
                    val a = org.json.JSONArray(js)
                    for (k in 0 until a.length()) {
                        val u = a.getJSONObject(k)
                        val txid = u.getString("txid"); val vout = u.getLong("vout"); val valor = u.getLong("value")
                        var previa: ByteArray? = null
                        if (inf.type == BtcAddress.Type.P2PKH) {
                            val h = ChainApi.get("/tx/$txid/hex", testnet)?.trim()
                            if (h == null || h.length % 2 != 0) { incompleto = true; continue }
                            previa = bytes(h)
                        }
                        out.add(MonedaPropia(Bip322.Moneda(bytes(txid).reversedArray(), vout, Bip322.Previa(valor, spk), previa),
                            "m/$purpose'/$coin'/$cta'/$cambio/${f.index}", f.addr))
                    }
                }
            }
        } finally { BalanceLookup.terminarMemo() }
        return out to incompleto
    }

    /** La clave de una privada, para firmar con ella ECDSA o Schnorr (ésta con la privada ajustada de BIP86). */
    private fun claveDe(privHex: String): Bip322.Clave? {
        val pub = HunterEngine.ec("pub", privHex, "")
        if (pub.length != 66) return null
        val pub33 = bytes(pub)
        return Bip322.Clave(pub33,
            { h -> HunterEngine.firmarHash(privHex, hex(h)).takeIf { it.isNotEmpty() }?.let { bytes(it) } },
            { h ->
                val neg = if (pub33[0].toInt() == 3) "1" else "0"
                val ajustada = HunterEngine.ec("priv_sum", "$privHex,${hex(Bip322.tapTweak(pub33.copyOfRange(1, 33)))}", "$neg,0")
                if (ajustada.length != 64) null else {
                    val aux = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                    HunterEngine.ec("schnorr", ajustada, "${hex(h)}|${hex(aux)}").takeIf { it.length == 128 }?.let { bytes(it) }
                }
            })
    }

    /**
     * Firma una prueba de fondos de la semilla [mn]: firma [dir] (una dirección de esa semilla) y todas las
     * [monedas]. Se verifica antes de devolverla; null si algo no cuadra.
     */
    fun firmarFondos(mn: String, dir: String, mensaje: ByteArray, monedas: List<MonedaPropia>): String? {
        val inf = info(dir) ?: return null
        if (monedas.isEmpty()) return null
        val privs = listOf(privadaDe(mn, inf, dir) ?: return null) + monedas.map { HunterEngine.deriveRuta(mn, it.ruta) }
        val claves = privs.map { if (it.length != 64) return null else claveDe(it) ?: return null }
        val f = Bip322.firmarFondos(inf, mensaje, monedas.map { it.moneda }, claves) ?: return null
        return f.takeIf { Bip322.verificarFondos(inf, mensaje, it, verificador).veredicto is Bip322.Veredicto.Valida }
    }

    /** Cómo está una moneda de una prueba en la cadena ahora mismo. */
    class EnCadena(val moneda: Bip322.Moneda, val existe: Boolean, val sinGastar: Boolean?, val nota: String)

    /**
     * Mira cada moneda en TODOS los servidores (tienen que coincidir): que exista con ese importe y ese script,
     * y si sigue sin gastar. La firma prueba el control; esto, que el dinero sigue ahí.
     */
    fun comprobarEnCadena(monedas: List<Bip322.Moneda>, testnet: Boolean = false): List<EnCadena> = monedas.map { m ->
        try {
            val txs = ChainApi.getDeTodos("/tx/${m.txidTexto}", testnet).mapNotNull { try { org.json.JSONObject(it) } catch (e: Exception) { null } }
            val coincide = txs.isNotEmpty() && txs.all { o ->
                o.optJSONArray("vout")?.optJSONObject(m.vout.toInt())?.let { it.optLong("value", -1) == m.previa.valor && it.optString("scriptpubkey") == hex(m.previa.script) } == true
            }
            if (!coincide) EnCadena(m, false, null, if (txs.isEmpty()) "No server knows this transaction." else "The chain does not show this coin with this amount.")
            else {
                val gastos = ChainApi.getDeTodos("/tx/${m.txidTexto}/outspend/${m.vout}", testnet)
                    .mapNotNull { try { org.json.JSONObject(it).optBoolean("spent") } catch (e: Exception) { null } }
                when {
                    gastos.isEmpty() -> EnCadena(m, true, null, "Exists; could not check whether it is spent.")
                    gastos.any { it } -> EnCadena(m, true, false, "Already spent: it no longer counts.")
                    else -> EnCadena(m, true, true, "Unspent.")
                }
            }
        } catch (e: Exception) { RegistroFallos.anotar("FirmaBip322.comprobarEnCadena", e); EnCadena(m, false, null, "Network error.") }
    }

    /**
     * La privada (hex) de [direccion] si es una de las primeras [n] de recepción o cambio de la semilla [mn]
     * (BIP44/49/84/86 según su tipo, en la cuenta de la frase, "#n"). null si no está.
     */
    fun privadaDe(mn: String, info: BtcAddress.Info, direccion: String, n: Int = 200): String? {
        val purpose = when (info.type) {
            BtcAddress.Type.P2PKH -> 44; BtcAddress.Type.P2SH -> 49
            BtcAddress.Type.P2WPKH -> 84; BtcAddress.Type.P2TR -> 86
            BtcAddress.Type.P2WSH -> return null
        }
        val coin = if (info.testnet) 1 else 0
        for (cambio in 0..1) {
            val a = try { org.json.JSONArray(HunterEngine.deriveAddresses(mn, purpose, cambio, 0, n, info.testnet)) } catch (e: Exception) { continue }
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                if (o.optString("addr").equals(direccion, ignoreCase = direccion.lowercase().startsWith("bc1") || direccion.lowercase().startsWith("tb1")))
                    return HunterEngine.deriveRuta(mn, "m/$purpose'/$coin'/${Frase.cuenta(mn)}'/$cambio/${o.getInt("i")}").takeIf { it.length == 64 }
            }
        }
        return null
    }
}
