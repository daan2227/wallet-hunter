package com.hunter.btc

/**
 * Hucha con fecha: `wsh(and_v(v:pk(CLAVE),after(T)))`. Lo que entra no se puede gastar —ni siquiera con la
 * clave— hasta T (una fecha Unix si T ≥ 500 000 000, o una altura de bloque si no): la regla la impone la red
 * (OP_CHECKLOCKTIMEVERIFY, BIP65), no la app. Para ahorrar a largo plazo sin poder tocarlo en un mal momento.
 *
 * Script: <CLAVE> CHECKSIGVERIFY <T> CHECKLOCKTIMEVERIFY. Testigo: [firma]. Para gastar, la transacción lleva
 * nLockTime = T y las entradas una secuencia menor que 0xffffffff (si no, el nLockTime no cuenta).
 * Con fecha, la red compara con la mediana de los últimos 11 bloques: se puede gastar ~1 hora después de T.
 * Script y direcciones comprobados con embit (HuchaTest).
 */
class MotorHucha(val clave: Descriptor.ClaveX, val hasta: Long, private val der: Descriptor.Derivador,
                 override val avisos: List<String> = emptyList()) : MotorCartera {

    val esFecha get() = hasta >= 500_000_000L
    override val testnet get() = clave.testnet
    override val descripcion get() = "Time-locked savings · spendable from " +
        (if (esFecha) java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(hasta * 1000)) else "block %,d".format(hasta))

    override fun texto(): String {
        val d = "wsh(and_v(v:pk(${Descriptor.claveExprTexto(clave)}),after($hasta)))"
        return d + "#" + Descriptor.checksum(d)
    }

    private val cache = HashMap<Long, Pair<ByteArray, ByteArray>>()

    /** (script, pública) de (c, i). */
    fun script(c: Int, i: Int): Pair<ByteArray, ByteArray>? {
        val k = (c.toLong() shl 32) or i.toLong()
        cache[k]?.let { return it }
        val pub = Descriptor.bytes(der.ckd(clave.claveCadenaHex(), "$c/$i").ifEmpty { return null })
        if (pub.size != 33) return null
        val s = byteArrayOf(33) + pub + byteArrayOf(0xad.toByte()) + MotorHerencia.numero(hasta) + byteArrayOf(0xb1.toByte())
        return (s to pub).also { cache[k] = it }
    }

    override fun spk(c: Int, i: Int): ByteArray? =
        script(c, i)?.let { byteArrayOf(0x00, 0x20) + java.security.MessageDigest.getInstance("SHA-256").digest(it.first) }

    /** vB de una entrada: 41 + testigo (contador, firma, script de ~41 bytes) / 4. */
    override fun vbEntrada(): Int = 41 + (1 + 1 + 73 + 1 + 42 + 3) / 4

    /** ¿Sigue bloqueada? Con fecha, la red mira la mediana de 11 bloques (~1 h por detrás de la hora real). */
    fun bloqueada(ahoraSeg: Long, altura: Long): Boolean = if (esFecha) ahoraSeg < hasta + 3600 else altura < hasta

    private fun le32(x: Long) = byteArrayOf(x.toByte(), (x shr 8).toByte(), (x shr 16).toByte(), (x shr 24).toByte())

    private fun derivacion(tipo: Int, c: Int, i: Int): List<Pair<ByteArray, ByteArray>> {
        val (h, r) = clave.origenDe(c, i) ?: return emptyList()
        val pub = script(c, i)?.second ?: return emptyList()
        return listOf((byteArrayOf(tipo.toByte()) + pub) to (h + r.fold(ByteArray(0)) { acc, x -> acc + le32(x) }))
    }

    /** [locktime] no se usa: el de una hucha tiene que ser T (del mismo tipo que T), o el script la rechaza. */
    override fun psbt(elegidas: List<Moneda>, destinoSpk: ByteArray, importe: Long, cambio: Long, cambioIndice: Int,
                      locktime: Long, anteriores: (Moneda) -> ByteArray?): ByteArray? {
        val salidas = arrayListOf(destinoSpk to importe)
        val extrasSal = HashMap<Int, List<Pair<ByteArray, ByteArray>>>()
        if (cambio > 0) {
            salidas.add((spk(1, cambioIndice) ?: return null) to cambio)
            extrasSal[1] = listOf(byteArrayOf(0x01) to script(1, cambioIndice)!!.first) + derivacion(0x02, 1, cambioIndice)
        }
        val tx = Psbt.sinFirmar(elegidas.map { it.txid to it.vout }, salidas, hasta)     // secuencia < 0xffffffff por defecto
        val ent = elegidas.map { m ->
            val s = spk(m.c, m.i) ?: return null
            Psbt.EntradaPsbt(m.valor, s, null, extras = listOf(byteArrayOf(0x05) to script(m.c, m.i)!!.first) + derivacion(0x06, m.c, m.i))
        }
        return Psbt.crear(tx, ent, emptyMap(), extrasSal)
    }

    override fun testigoWsh(script: ByteArray, firmas: Map<String, ByteArray>): List<ByteArray>? {
        if (script.size < 37 || script[0].toInt() != 33 || script[34] != 0xad.toByte() || script.last() != 0xb1.toByte()) return null
        return firmas[Descriptor.hex(script.copyOfRange(1, 34))]?.let { listOf(it) }
    }

    companion object {
        /** Lee la plantilla; null si no es ésta, (null, motivo) si lo es pero algo falla. */
        fun leer(texto0: String, der: Descriptor.Derivador): Pair<MotorHucha?, String?>? {
            var d = texto0.trim().lines().first().trim()
            val h = d.indexOf('#')
            if (h >= 0) { val suma = d.substring(h + 1); d = d.substring(0, h); if (Descriptor.checksum(d) != suma) return null to "Wrong descriptor checksum" }
            val r = Regex("^wsh\\(and_v\\(v:pk\\(([^)]+)\\),after\\((\\d+)\\)\\)\\)$").find(d) ?: return null
            val (k, aviso) = Descriptor.leerClaveExpr(r.groupValues[1]); if (k == null) return null to (aviso ?: "Bad key")
            val t = r.groupValues[2].toLongOrNull() ?: return null to "Bad time lock"
            if (t !in 1..0xFFFFFFFFL) return null to "The time lock is out of range"
            return MotorHucha(k, t, der, listOfNotNull(aviso)) to null
        }
    }
}
