package com.hunter.btc

/**
 * Silent Payments (BIP352), lado del que envía: pagar a una dirección "sp1…" estática sin que la
 * cadena lo delate. Con las claves de las entradas y la del destinatario se calcula una dirección
 * Taproot de un solo uso que sólo él sabe reconocer.
 *
 * La aritmética de curva es la del motor (HunterEngine.ec: "pub", "priv_sum", "sp_send"), probada
 * contra los vectores oficiales en tools/ec-harness. Aquí: leer la dirección, preparar las claves de
 * las entradas (con el ajuste Taproot) y el menor outpoint. Se inyecta para probarlo en la JVM.
 */
object SilentPayments {
    fun interface Ec { fun op(op: String, a: String, b: String): String }

    private const val CARS = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32M = 0x2bc830a3

    fun esDireccion(a: String) = a.lowercase().let { it.startsWith("sp1") || it.startsWith("tsp1") }

    /** (pública de escaneo, pública de gasto), 33 bytes cada una; null si no es una sp1/tsp1 válida de esa red. */
    fun decodificar(a0: String, testnet: Boolean): Pair<ByteArray, ByteArray>? {
        val a = a0.trim()
        if (a != a.lowercase() && a != a.uppercase()) return null
        val s = a.lowercase()
        val sep = s.lastIndexOf('1'); if (sep < 1) return null
        val hrp = s.substring(0, sep)
        if (hrp != (if (testnet) "tsp" else "sp")) return null
        val datos = s.substring(sep + 1).map { CARS.indexOf(it).also { v -> if (v < 0) return null } }
        if (datos.size < 7 || s.length > 1023) return null
        if (polymod(expandir(hrp) + datos) != BECH32M) return null
        val ver = datos[0]; if (ver == 31) return null
        val prog = convertir(datos.subList(1, datos.size - 6), 5, 8) ?: return null
        // v0: exactamente 66 bytes; versiones futuras: se leen los 66 primeros (lo dice BIP352).
        if (ver == 0 && prog.size != 66) return null
        if (prog.size < 66) return null
        val scan = prog.copyOfRange(0, 33); val spend = prog.copyOfRange(33, 66)
        if (scan[0] !in byteArrayOf(2, 3) || spend[0] !in byteArrayOf(2, 3)) return null
        return scan to spend
    }

    private fun polymod(v: List<Int>): Int {
        val g = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (x in v) { val b = chk ushr 25; chk = ((chk and 0x1ffffff) shl 5) xor x; for (i in 0 until 5) if ((b ushr i) and 1 == 1) chk = chk xor g[i] }
        return chk
    }
    private fun expandir(h: String) = h.map { it.code shr 5 } + listOf(0) + h.map { it.code and 31 }
    private fun convertir(d: List<Int>, de: Int, a: Int): ByteArray? {
        var acc = 0; var bits = 0; val out = java.io.ByteArrayOutputStream(); val max = (1 shl a) - 1
        for (v in d) { acc = (acc shl de) or v; bits += de; while (bits >= a) { bits -= a; out.write((acc shr bits) and max) } }
        if (bits >= de || ((acc shl (a - bits)) and max) != 0) return null
        return out.toByteArray()
    }

    /**
     * Una entrada que se gasta: su privada (hex) y si es Taproot. En Taproot es la INTERNA (BIP86), salvo
     * que [yaAjustada]: entonces ya es la de la clave de salida (como en los vectores de BIP352).
     */
    class Entrada(val privHex: String, val taproot: Boolean, val txid: String, val vout: Int, val yaAjustada: Boolean = false)

    private fun hx(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bs(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun tagged(tag: String, m: ByteArray): ByteArray {
        val sha = java.security.MessageDigest.getInstance("SHA-256"); val t = sha.digest(tag.toByteArray())
        sha.reset(); sha.update(t); sha.update(t); sha.update(m); return sha.digest()
    }

    /** La privada con la que firma una Taproot de clave (BIP86/BIP341): d' = ±d + t, y si Q sale impar, BIP352 la niega. */
    private fun claveTaproot(ec: Ec, d: String): Pair<String, Boolean>? {
        val p = ec.op("pub", d, "").ifEmpty { return null }
        val t = tagged("TapTweak", bs(p.substring(2)))
        val dd = ec.op("priv_sum", "$d,${hx(t)}", if (p.startsWith("03")) "1,0" else "0,0").ifEmpty { return null }
        val q = ec.op("pub", dd, "").ifEmpty { return null }
        return dd to q.startsWith("03")
    }

    /** El menor outpoint (txid en el orden de la transacción ‖ vout LE), comparado byte a byte sin signo. */
    fun outpointMenor(entradas: List<Pair<String, Int>>): ByteArray = entradas.map { (txid, vout) ->
        bs(txid).reversedArray() + byteArrayOf(vout.toByte(), (vout shr 8).toByte(), (vout shr 16).toByte(), (vout ushr 24).toByte())
    }.minWithOrNull { a, b -> var r = 0; for (i in a.indices) { r = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (r != 0) break }; r }!!

    /**
     * El scriptPubKey P2TR (5120 ‖ x) que hay que poner como salida para pagar a [direccion], o null si
     * no se puede (dirección mala, suma de claves nula…). [entradas] son TODAS las de la transacción.
     */
    fun salida(ec: Ec, direccion: String, testnet: Boolean, entradas: List<Entrada>): ByteArray? {
        val (scan, spend) = decodificar(direccion, testnet) ?: return null
        if (entradas.isEmpty()) return null
        val ks = ArrayList<String>(); val neg = ArrayList<String>()
        for (e in entradas) {
            if (e.taproot && e.yaAjustada) { val q = ec.op("pub", e.privHex, "").ifEmpty { return null }; ks.add(e.privHex); neg.add(if (q.startsWith("03")) "1" else "0") }
            else if (e.taproot) { val (d, n) = claveTaproot(ec, e.privHex) ?: return null; ks.add(d); neg.add(if (n) "1" else "0") }
            else { ks.add(e.privHex); neg.add("0") }
        }
        val suma = ec.op("priv_sum", ks.joinToString(","), neg.joinToString(",")).ifEmpty { return null }
        val op = hx(outpointMenor(entradas.map { it.txid to it.vout }))
        val x = ec.op("sp_send", "$suma|$op", "${hx(scan)}:${hx(spend)}").ifEmpty { return null }
        if (x.length != 64) return null
        return byteArrayOf(0x51, 0x20) + bs(x)
    }
}
