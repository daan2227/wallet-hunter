package com.hunter.btc

/**
 * Multifirma P2WSH: `wsh(sortedmulti(k, clave1, clave2, …))` (o `multi`, con el orden tal cual), con
 * claves `[huella/ruta]xpub` y las ramas 0 y 1. Es lo que crean Sparrow, Nunchuk, Specter o Bitcoin Core.
 *
 * Las monedas sólo se mueven con k firmas: la PSBT lleva el script y la derivación de TODAS las claves
 * (así cada firmador reconoce la suya), y la app puede poner la firma de su propia clave si está entre
 * ellas (ver firmarPropia). Probado contra Bitcoin Core 30 (MultifirmaTest).
 */
class MotorMultifirma(val k: Int, val claves: List<Descriptor.ClaveX>, val ordenada: Boolean,
                      private val der: Descriptor.Derivador, override val avisos: List<String> = emptyList()) : MotorCartera {

    override val testnet get() = claves[0].testnet
    override val descripcion get() = "Multisig $k-of-${claves.size} · native SegWit (P2WSH)"

    override fun texto(): String {
        val d = "wsh(${if (ordenada) "sortedmulti" else "multi"}($k," + claves.joinToString(",") { Descriptor.claveExprTexto(it) } + "))"
        return d + "#" + Descriptor.checksum(d)
    }

    private val cache = HashMap<Long, Pair<ByteArray, List<ByteArray>>>()

    /** (script, públicas en el orden del script) de (c, i). */
    fun script(c: Int, i: Int): Pair<ByteArray, List<ByteArray>>? {
        val key = (c.toLong() shl 32) or i.toLong()
        cache[key]?.let { return it }
        val pubs = claves.map { ck -> Descriptor.bytes(der.ckd(ck.claveCadenaHex(), "$c/$i").ifEmpty { return null }).also { if (it.size != 33) return null } }
        val orden = if (ordenada) pubs.sortedWith { a, b -> comparar(a, b) } else pubs
        val o = java.io.ByteArrayOutputStream()
        o.write(0x50 + k)
        for (p in orden) { o.write(33); o.write(p) }
        o.write(0x50 + claves.size); o.write(0xae)
        return (o.toByteArray() to orden).also { cache[key] = it }
    }

    override fun spk(c: Int, i: Int): ByteArray? =
        script(c, i)?.let { byteArrayOf(0x00, 0x20) + java.security.MessageDigest.getInstance("SHA-256").digest(it.first) }

    /** vB de una entrada: 41 sin testigo + (vacío + k firmas + script) / 4. */
    override fun vbEntrada(): Int {
        val scriptLen = 3 + 34 * claves.size
        val testigo = 1 + 1 + k * 73 + 3 + scriptLen
        return 41 + (testigo + 3) / 4
    }

    /** Los registros de derivación de todas las claves de (c, i): clave = 0x02/0x06 ‖ pub, valor = huella ‖ ruta. */
    private fun derivaciones(tipo: Int, c: Int, i: Int): List<Pair<ByteArray, ByteArray>> = claves.mapNotNull { ck ->
        val (huella, ruta) = ck.origenDe(c, i) ?: return@mapNotNull null
        val pub = Descriptor.bytes(der.ckd(ck.claveCadenaHex(), "$c/$i").ifEmpty { return@mapNotNull null })
        (byteArrayOf(tipo.toByte()) + pub) to (huella + ruta.fold(ByteArray(0)) { acc, x -> acc + le32(x) })
    }
    private fun le32(x: Long) = byteArrayOf(x.toByte(), (x shr 8).toByte(), (x shr 16).toByte(), (x shr 24).toByte())

    override fun psbt(elegidas: List<Moneda>, destinoSpk: ByteArray, importe: Long, cambio: Long, cambioIndice: Int,
                      locktime: Long, anteriores: (Moneda) -> ByteArray?): ByteArray? {
        val salidas = arrayListOf(destinoSpk to importe)
        val extrasSal = HashMap<Int, List<Pair<ByteArray, ByteArray>>>()
        if (cambio > 0) {
            salidas.add((spk(1, cambioIndice) ?: return null) to cambio)
            val ws = script(1, cambioIndice)!!.first
            extrasSal[1] = listOf(byteArrayOf(0x01) to ws) + derivaciones(0x02, 1, cambioIndice)   // PSBT_OUT_WITNESS_SCRIPT
        }
        val tx = Psbt.sinFirmar(elegidas.map { it.txid to it.vout }, salidas, locktime)
        val ent = elegidas.map { m ->
            val s = spk(m.c, m.i) ?: return null
            Psbt.EntradaPsbt(m.valor, s, null, extras = listOf(byteArrayOf(0x05) to script(m.c, m.i)!!.first) + derivaciones(0x06, m.c, m.i))
        }
        return Psbt.crear(tx, ent, emptyMap(), extrasSal)
    }

    /** Testigo final: vacío (el fallo de CHECKMULTISIG), k firmas en el orden de las públicas del script. */
    override fun testigoWsh(script: ByteArray, firmas: Map<String, ByteArray>): List<ByteArray>? = Companion.testigo(script, firmas)

    companion object {
        fun comparar(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) { val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (d != 0) return d }
            return a.size - b.size
        }

        /** (k, públicas) de un script "OP_k <pub>… OP_n OP_CHECKMULTISIG", o null si no es eso. */
        fun leerScript(s: ByteArray): Pair<Int, List<ByteArray>>? {
            if (s.size < 37 || (s.last().toInt() and 0xff) != 0xae) return null
            val k = (s[0].toInt() and 0xff) - 0x50; val n = (s[s.size - 2].toInt() and 0xff) - 0x50
            if (k !in 1..16 || n !in k..16 || s.size != 3 + 34 * n) return null
            val pubs = (0 until n).map { j -> if (s[1 + 34 * j].toInt() != 33) return null; s.copyOfRange(2 + 34 * j, 35 + 34 * j) }
            return k to pubs
        }

        fun testigo(script: ByteArray, firmas: Map<String, ByteArray>): List<ByteArray>? {
            val (k, pubs) = leerScript(script) ?: return null
            val sigs = pubs.mapNotNull { firmas[Descriptor.hex(it)] }
            if (sigs.size < k) return null
            return listOf(ByteArray(0)) + sigs.take(k)
        }

        /** Lee `wsh(sortedmulti(k,…))` / `wsh(multi(k,…))`; null si no lo es. */
        fun leer(texto0: String, der: Descriptor.Derivador): Pair<MotorMultifirma?, String?>? {
            var d = texto0.trim().lines().first().trim()
            val h = d.indexOf('#')
            if (h >= 0) { val suma = d.substring(h + 1); d = d.substring(0, h); if (Descriptor.checksum(d) != suma) return null to "Wrong descriptor checksum" }
            val ordenada = d.startsWith("wsh(sortedmulti(")
            if (!ordenada && !d.startsWith("wsh(multi(")) return null
            if (!d.endsWith("))")) return null to "Unclosed descriptor"
            val dentro = d.substring(if (ordenada) 16 else 10, d.length - 2)
            val partes = dentro.split(',')
            val k = partes[0].toIntOrNull() ?: return null to "Bad threshold"
            val claves = ArrayList<Descriptor.ClaveX>(); val avisos = ArrayList<String>()
            for (p in partes.drop(1)) {
                val (ck, aviso) = Descriptor.leerClaveExpr(p.trim())
                if (ck == null) return null to (aviso ?: "Bad key")
                aviso?.let { if (it !in avisos) avisos.add(it) }
                claves.add(ck)
            }
            if (claves.size !in 2..15 || k !in 1..claves.size) return null to "A multisig needs 2 to 15 keys and 1..n signatures"
            if (claves.map { it.testnet }.distinct().size != 1) return null to "Keys from different networks"
            if (claves.map { Descriptor.hex(it.clave33) }.distinct().size != claves.size) return null to "The same key appears twice"
            return MotorMultifirma(k, claves, ordenada, der, avisos) to null
        }
    }
}
