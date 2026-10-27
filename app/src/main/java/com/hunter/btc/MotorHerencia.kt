package com.hunter.btc

/**
 * Herencia con bloqueo de tiempo: `wsh(or_d(pk(DUEÑO),and_v(v:pk(HEREDERO),older(N))))`, la plantilla de
 * Miniscript que usan Liana y Nunchuk. El dueño gasta cuando quiere; el heredero sólo puede cuando una
 * moneda lleva N bloques sin moverse (CSV, BIP112). Mientras el dueño viva, basta con mover las monedas
 * (a la misma cartera) antes de que pasen N bloques para que el reloj vuelva a cero.
 *
 * Script: <DUEÑO> CHECKSIG IFDUP NOTIF <HEREDERO> CHECKSIGVERIFY <N> CHECKSEQUENCEVERIFY ENDIF.
 * Testigo: [firma del dueño] o [firma del heredero, vacío]. Probado contra Bitcoin Core 30 (HerenciaTest).
 */
class MotorHerencia(val dueno: Descriptor.ClaveX, val heredero: Descriptor.ClaveX, val bloques: Int,
                    private val der: Descriptor.Derivador, override val avisos: List<String> = emptyList()) : MotorCartera {

    /** Si la PSBT es para el heredero: versión 2 y secuencia N en cada entrada (si no, CSV la rechaza). */
    var modoHeredero = false

    override val testnet get() = dueno.testnet
    override val descripcion get() = "Inheritance · heir can spend after $bloques blocks (~${bloques / 144} days) without movement"

    override fun texto(): String {
        val d = "wsh(or_d(pk(${Descriptor.claveExprTexto(dueno)}),and_v(v:pk(${Descriptor.claveExprTexto(heredero)}),older($bloques))))"
        return d + "#" + Descriptor.checksum(d)
    }

    private val cache = HashMap<Long, Triple<ByteArray, ByteArray, ByteArray>>()

    /** (script, pública del dueño, pública del heredero) de (c, i). */
    fun script(c: Int, i: Int): Triple<ByteArray, ByteArray, ByteArray>? {
        val k = (c.toLong() shl 32) or i.toLong()
        cache[k]?.let { return it }
        val a = Descriptor.bytes(der.ckd(dueno.claveCadenaHex(), "$c/$i").ifEmpty { return null })
        val b = Descriptor.bytes(der.ckd(heredero.claveCadenaHex(), "$c/$i").ifEmpty { return null })
        if (a.size != 33 || b.size != 33) return null
        val s = byteArrayOf(33) + a + byteArrayOf(0xac.toByte(), 0x73, 0x64, 33) + b +
                byteArrayOf(0xad.toByte()) + numero(bloques.toLong()) + byteArrayOf(0xb2.toByte(), 0x68)
        return Triple(s, a, b).also { cache[k] = it }
    }

    override fun spk(c: Int, i: Int): ByteArray? =
        script(c, i)?.let { byteArrayOf(0x00, 0x20) + java.security.MessageDigest.getInstance("SHA-256").digest(it.first) }

    /** vB: del heredero (el peor caso: firma + vacío + script de 81–83 bytes). */
    override fun vbEntrada(): Int = 41 + (1 + 73 + 1 + 1 + 84 + 3) / 4

    private fun le32(x: Long) = byteArrayOf(x.toByte(), (x shr 8).toByte(), (x shr 16).toByte(), (x shr 24).toByte())

    private fun derivaciones(tipo: Int, c: Int, i: Int): List<Pair<ByteArray, ByteArray>> = listOf(dueno, heredero).mapNotNull { ck ->
        val (h, r) = ck.origenDe(c, i) ?: return@mapNotNull null
        val pub = Descriptor.bytes(der.ckd(ck.claveCadenaHex(), "$c/$i").ifEmpty { return@mapNotNull null })
        (byteArrayOf(tipo.toByte()) + pub) to (h + r.fold(ByteArray(0)) { acc, x -> acc + le32(x) })
    }

    override fun psbt(elegidas: List<Moneda>, destinoSpk: ByteArray, importe: Long, cambio: Long, cambioIndice: Int,
                      locktime: Long, anteriores: (Moneda) -> ByteArray?): ByteArray? {
        val salidas = arrayListOf(destinoSpk to importe)
        val extrasSal = HashMap<Int, List<Pair<ByteArray, ByteArray>>>()
        if (cambio > 0) {
            salidas.add((spk(1, cambioIndice) ?: return null) to cambio)
            extrasSal[1] = listOf(byteArrayOf(0x01) to script(1, cambioIndice)!!.first) + derivaciones(0x02, 1, cambioIndice)
        }
        val tx = if (modoHeredero) Psbt.sinFirmar(elegidas.map { it.txid to it.vout }, salidas, locktime, version = 2, secuencia = bloques.toLong())
                 else Psbt.sinFirmar(elegidas.map { it.txid to it.vout }, salidas, locktime)
        val ent = elegidas.map { m ->
            val s = spk(m.c, m.i) ?: return null
            Psbt.EntradaPsbt(m.valor, s, null, extras = listOf(byteArrayOf(0x05) to script(m.c, m.i)!!.first) + derivaciones(0x06, m.c, m.i))
        }
        return Psbt.crear(tx, ent, emptyMap(), extrasSal)
    }

    override fun testigoWsh(script: ByteArray, firmas: Map<String, ByteArray>): List<ByteArray>? = Companion.testigo(script, firmas)

    companion object {
        /** Número de script mínimo (BIP62) de un entero positivo, empujado. */
        fun numero(n: Long): ByteArray {
            require(n in 1..0xFFFFFFFFL)
            if (n <= 16) return byteArrayOf((0x50 + n).toByte())
            val b = ArrayList<Byte>(); var x = n
            while (x > 0) { b.add((x and 0xff).toByte()); x = x shr 8 }
            if ((b.last().toInt() and 0x80) != 0) b.add(0)
            return byteArrayOf(b.size.toByte()) + b.toByteArray()
        }

        /** (dueño, heredero) del script de la plantilla, o null si no lo es. */
        fun claves(s: ByteArray): Pair<ByteArray, ByteArray>? {
            if (s.size < 74 || s[0].toInt() != 33 || s[34] != 0xac.toByte() || s[35].toInt() != 0x73 || s[36].toInt() != 0x64 ||
                s[37].toInt() != 33 || s[71] != 0xad.toByte() || s[s.size - 2] != 0xb2.toByte() || s[s.size - 1].toInt() != 0x68) return null
            return s.copyOfRange(1, 34) to s.copyOfRange(38, 71)
        }

        /** Con la firma del dueño basta; si no, la del heredero (y la entrada tiene que llevar ya su secuencia). */
        fun testigo(s: ByteArray, firmas: Map<String, ByteArray>): List<ByteArray>? {
            val (a, b) = claves(s) ?: return null
            firmas[Descriptor.hex(a)]?.let { return listOf(it) }
            firmas[Descriptor.hex(b)]?.let { return listOf(it, ByteArray(0)) }
            return null
        }

        /** Lee la plantilla; null si no es esta, (null, motivo) si lo es pero algo falla. */
        fun leer(texto0: String, der: Descriptor.Derivador): Pair<MotorHerencia?, String?>? {
            var d = texto0.trim().lines().first().trim()
            val h = d.indexOf('#')
            if (h >= 0) { val suma = d.substring(h + 1); d = d.substring(0, h); if (Descriptor.checksum(d) != suma) return null to "Wrong descriptor checksum" }
            if (!d.startsWith("wsh(or_d(pk(") || !d.endsWith("))))")) return null
            val r = Regex("^wsh\\(or_d\\(pk\\(([^)]+)\\),and_v\\(v:pk\\(([^)]+)\\),older\\((\\d+)\\)\\)\\)\\)$").find(d)
                ?: return null to "Only the inheritance template or_d(pk(owner),and_v(v:pk(heir),older(N))) is supported"
            val (a, av) = Descriptor.leerClaveExpr(r.groupValues[1]); if (a == null) return null to (av ?: "Bad owner key")
            val (b, bv) = Descriptor.leerClaveExpr(r.groupValues[2]); if (b == null) return null to (bv ?: "Bad heir key")
            val n = r.groupValues[3].toIntOrNull() ?: return null to "Bad timelock"
            // older(N) con N < 65536 son bloques; por encima, BIP68 lo leería como tiempo (bit 22): no se admite.
            if (n !in 1..65535) return null to "The timelock must be 1–65535 blocks"
            if (a.testnet != b.testnet) return null to "Keys from different networks"
            return MotorHerencia(a, b, n, der, listOfNotNull(av, bv).distinct()) to null
        }
    }
}
