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

    /**
     * La privada (hex) de [direccion] si es una de las primeras [n] de recepción o cambio de la semilla [mn]
     * (BIP44/49/84/86 según su tipo, cuenta 0). null si no está.
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
                    return HunterEngine.deriveRuta(mn, "m/$purpose'/$coin'/0'/$cambio/${o.getInt("i")}").takeIf { it.length == 64 }
            }
        }
        return null
    }
}
