package com.hunter.btc

import org.json.JSONObject

/**
 * El hash de mensaje (z) que firmó un input, a partir del JSON de la
 * transacción que da el explorador (esplora). Hace falta para recuperar una
 * clave por nonce reutilizado: d = (s·k − z)/r.
 *
 * Cubre los dos tipos que son casi todo lo que hay: P2PKH heredado (sighash
 * clásico) y P2WPKH segwit v0 (BIP-143), y sólo SIGHASH_ALL (0x01). Para el
 * resto devuelve null, y el que llama lo verifica igualmente comprobando que
 * d·G sea la pública — así nunca se da por buena una clave mal calculada.
 */
object FirmaTx {

    private fun hex(b: ByteArray): String { val s = StringBuilder(b.size * 2); for (x in b) s.append("%02x".format(x.toInt() and 0xFF)); return s.toString() }
    private fun bytes(h: String): ByteArray { val o = ByteArray(h.length / 2); for (i in o.indices) o[i] = h.substring(i*2, i*2+2).toInt(16).toByte(); return o }
    private fun le(v: Long, n: Int): ByteArray { val o = ByteArray(n); var x = v; for (i in 0 until n) { o[i] = (x and 0xFF).toByte(); x = x shr 8 }; return o }
    private fun revb(b: ByteArray): ByteArray { val o = ByteArray(b.size); for (i in b.indices) o[i] = b[b.size-1-i]; return o }
    private fun varint(n: Long): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte())
        n <= 0xffff -> byteArrayOf(0xfd.toByte()) + le(n, 2)
        n <= 0xffffffffL -> byteArrayOf(0xfe.toByte()) + le(n, 4)
        else -> byteArrayOf(0xff.toByte()) + le(n, 8)
    }
    private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b)
    private fun dsha(b: ByteArray) = sha256(sha256(b))

    private fun varStr(scriptHex: String): ByteArray { val s = bytes(scriptHex); return varint(s.size.toLong()) + s }

    /** Devuelve z en hex (64) para el input [vin], o null si no se puede. */
    fun z(tx: JSONObject, vin: Int, htype: Int): String? {
        if (htype != 0x01) return null   // sólo SIGHASH_ALL
        return try {
            val vins = tx.getJSONArray("vin")
            val vouts = tx.getJSONArray("vout")
            val version = tx.optLong("version", 1)
            val locktime = tx.optLong("locktime", 0)
            val thisIn = vins.getJSONObject(vin)
            val prevSpk = thisIn.getJSONObject("prevout").getString("scriptpubkey").lowercase()
            val esSegwit = prevSpk.startsWith("0014") && prevSpk.length == 44
            val esLegacy = prevSpk.startsWith("76a914") && prevSpk.endsWith("88ac") && prevSpk.length == 50
            // P2SH-P2WPKH: el prevout es P2SH, pero si el input lleva witness es
            // un segwit envuelto y se firma con BIP-143.
            val esP2sh = prevSpk.startsWith("a914") && prevSpk.endsWith("87") && prevSpk.length == 46 &&
                         (thisIn.optJSONArray("witness")?.length() ?: 0) >= 2
            if (!esSegwit && !esLegacy && !esP2sh) return null

            if (esLegacy) {
                val b = java.io.ByteArrayOutputStream()
                b.write(le(version, 4))
                b.write(varint(vins.length().toLong()))
                for (i in 0 until vins.length()) {
                    val vi = vins.getJSONObject(i)
                    b.write(revb(bytes(vi.getString("txid"))))
                    b.write(le(vi.getLong("vout"), 4))
                    if (i == vin) b.write(varStr(prevSpk)) else b.write(0)   // scriptCode / vacío
                    b.write(le(vi.optLong("sequence", 0xffffffffL), 4))
                }
                b.write(varint(vouts.length().toLong()))
                for (i in 0 until vouts.length()) {
                    val vo = vouts.getJSONObject(i)
                    b.write(le(vo.getLong("value"), 8))
                    b.write(varStr(vo.getString("scriptpubkey")))
                }
                b.write(le(locktime, 4))
                b.write(le(htype.toLong(), 4))
                hex(dsha(b.toByteArray()))
            } else {
                // BIP-143
                val prevouts = java.io.ByteArrayOutputStream()
                val seqs = java.io.ByteArrayOutputStream()
                for (i in 0 until vins.length()) {
                    val vi = vins.getJSONObject(i)
                    prevouts.write(revb(bytes(vi.getString("txid"))))
                    prevouts.write(le(vi.getLong("vout"), 4))
                    seqs.write(le(vi.optLong("sequence", 0xffffffffL), 4))
                }
                val outs = java.io.ByteArrayOutputStream()
                for (i in 0 until vouts.length()) {
                    val vo = vouts.getJSONObject(i)
                    outs.write(le(vo.getLong("value"), 8))
                    outs.write(varStr(vo.getString("scriptpubkey")))
                }
                // h160 del scriptCode: del propio scriptPubKey en P2WPKH nativo,
                // o del hash160 de la pública del witness en P2SH-P2WPKH.
                val h160 = if (esSegwit) prevSpk.substring(4) else {
                    val w = thisIn.optJSONArray("witness") ?: return null
                    val pub = w.optString(w.length() - 1, "")
                    if (pub.length != 66 && pub.length != 130) return null
                    hex(Ripemd160.hash160(bytes(pub)))
                }
                val scriptCode = "1976a914" + h160 + "88ac"
                val amount = thisIn.getJSONObject("prevout").getLong("value")
                val b = java.io.ByteArrayOutputStream()
                b.write(le(version, 4))
                b.write(dsha(prevouts.toByteArray()))
                b.write(dsha(seqs.toByteArray()))
                b.write(revb(bytes(thisIn.getString("txid"))))
                b.write(le(thisIn.getLong("vout"), 4))
                b.write(bytes(scriptCode))
                b.write(le(amount, 8))
                b.write(le(thisIn.optLong("sequence", 0xffffffffL), 4))
                b.write(dsha(outs.toByteArray()))
                b.write(le(locktime, 4))
                b.write(le(htype.toLong(), 4))
                hex(dsha(b.toByteArray()))
            }
        } catch (e: Throwable) { null }
    }
}
