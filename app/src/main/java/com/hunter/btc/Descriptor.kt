package com.hunter.btc

/**
 * Descriptores de salida (BIP380–386) de una cartera de una sola clave, para vigilarla desde una xpub:
 * `pkh(…)`, `sh(wpkh(…))`, `wpkh(…)` y `tr(…)` con `[huella/ruta]xpub…` seguida de `/<0;1>/` y el
 * comodín (o de las ramas 0 y 1 por separado, en dos líneas).
 * También una xpub/ypub/zpub suelta (el tipo sale del prefijo; una xpub sin más se toma como pkh).
 *
 * La derivación de claves (curva) se inyecta: en la app es HunterEngine.ec("ckd"/"tr"), en las pruebas
 * una implementación lenta con BigInteger. Lo demás es puro y se comprueba contra Bitcoin Core
 * (deriveaddresses y getdescriptorinfo), ver DescriptorTest.
 */
object Descriptor {
    enum class Tipo(val nombre: String) { PKH("pkh"), SH_WPKH("sh(wpkh"), WPKH("wpkh"), TR("tr") }

    /** Clave extendida con su origen. [huella]/[ruta] vacíos si el descriptor no los traía. */
    class ClaveX(val clave33: ByteArray, val cadena: ByteArray, val testnet: Boolean,
                 val huella: ByteArray?, val ruta: List<Long>, val texto: String) {
        /** Huella del maestro y ruta completa hasta (c, i): lo que un firmador necesita en la PSBT. */
        fun origenDe(c: Int, i: Int): Pair<ByteArray, List<Long>>? = huella?.let { it to (ruta + c.toLong() + i.toLong()) }
        fun claveCadenaHex() = hex(clave33) + hex(cadena)
    }

    class Vigilada(val tipo: Tipo, val clave: ClaveX) {
        val testnet get() = clave.testnet
        /** El descriptor canónico, con checksum: lo que se guarda y lo que se exporta. */
        fun texto(): String {
            val k = (clave.huella?.let { "[" + hex(it) + clave.ruta.joinToString("") { r -> "/" + paso(r) } + "]" } ?: "") +
                    clave.texto + "/<0;1>/*"
            val d = if (tipo == Tipo.SH_WPKH) "sh(wpkh($k))" else "${tipo.nombre}($k)"
            return d + "#" + checksum(d)
        }
    }

    /** (pública 33 de c/i). En la app: HunterEngine.ec("ckd", claveCadenaHex, "c/i"). */
    fun interface Derivador { fun ckd(claveCadenaHex: String, ruta: String): String }
    /** (salida BIP86 x-only de una interna x-only). En la app: HunterEngine.ec("tr", x, ""). */
    fun interface Tweaker { fun tr(xonlyHex: String): String }

    fun spk(v: Vigilada, c: Int, i: Int, der: Derivador, tw: Tweaker): ByteArray? {
        val pub = bytes(der.ckd(v.clave.claveCadenaHex(), "$c/$i").ifEmpty { return null })
        if (pub.size != 33) return null
        return when (v.tipo) {
            Tipo.PKH -> byteArrayOf(0x76, 0xa9.toByte(), 0x14) + Ripemd160.hash160(pub) + byteArrayOf(0x88.toByte(), 0xac.toByte())
            Tipo.WPKH -> byteArrayOf(0x00, 0x14) + Ripemd160.hash160(pub)
            Tipo.SH_WPKH -> byteArrayOf(0xa9.toByte(), 0x14) + Ripemd160.hash160(byteArrayOf(0x00, 0x14) + Ripemd160.hash160(pub)) + byteArrayOf(0x87.toByte())
            Tipo.TR -> { val q = bytes(tw.tr(hex(pub.copyOfRange(1, 33))).ifEmpty { return null }); if (q.size != 32) null else byteArrayOf(0x51, 0x20) + q }
        }
    }

    fun pubDe(v: Vigilada, c: Int, i: Int, der: Derivador): ByteArray? =
        bytes(der.ckd(v.clave.claveCadenaHex(), "$c/$i").ifEmpty { return null }).takeIf { it.size == 33 }

    // ── Lectura ──────────────────────────────────────────────────────────────────────────────────

    sealed class R {
        class Ok(val v: Vigilada, val avisos: List<String>) : R()
        class Mal(val motivo: String) : R()
    }

    private val VERSIONES = mapOf(
        "0488b21e" to (Tipo.PKH to false), "049d7cb2" to (Tipo.SH_WPKH to false), "04b24746" to (Tipo.WPKH to false),
        "043587cf" to (Tipo.PKH to true), "044a5262" to (Tipo.SH_WPKH to true), "045f1cf6" to (Tipo.WPKH to true))

    /** Lee una o dos líneas (recepción y cambio de la misma clave). */
    fun leer(texto0: String): R {
        val lineas = texto0.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lineas.isEmpty()) return R.Mal("Empty")
        val rs = lineas.map { leerUna(it) }
        rs.firstOrNull { it is R.Mal }?.let { return it }
        val oks = rs.map { it as R.Ok }
        val a = oks[0].v
        if (oks.any { it.v.tipo != a.tipo || !it.v.clave.clave33.contentEquals(a.clave.clave33) })
            return R.Mal("The lines describe different wallets")
        return R.Ok(a, oks.flatMap { it.avisos }.distinct())
    }

    private fun leerUna(s: String): R {
        val avisos = ArrayList<String>()
        var d = s
        val almohadilla = d.indexOf('#')
        if (almohadilla >= 0) {
            val suma = d.substring(almohadilla + 1); d = d.substring(0, almohadilla)
            if (checksum(d) != suma) return R.Mal("Wrong descriptor checksum (#$suma): it was mistyped or cut")
        }
        // xpub/ypub/zpub suelta
        if (!d.contains('(')) {
            val ck = leerClave(d) ?: return R.Mal("Not a valid xpub/ypub/zpub")
            val tipo = VERSIONES[hex(b58check(ck.texto)!!.copyOfRange(0, 4))]!!.first
            avisos.add("No key origin: an external signer may not recognise the inputs (use a descriptor with [fingerprint/path])")
            if (tipo == Tipo.PKH) avisos.add("A plain xpub is read as legacy (1…). For SegWit or Taproot paste the descriptor")
            return R.Ok(Vigilada(tipo, ck), avisos)
        }
        val (tipo, dentro) = when {
            d.startsWith("sh(wpkh(") && d.endsWith("))") -> Tipo.SH_WPKH to d.substring(8, d.length - 2)
            d.startsWith("wpkh(") && d.endsWith(")") -> Tipo.WPKH to d.substring(5, d.length - 1)
            d.startsWith("pkh(") && d.endsWith(")") -> Tipo.PKH to d.substring(4, d.length - 1)
            d.startsWith("tr(") && d.endsWith(")") && !d.contains(',') -> Tipo.TR to d.substring(3, d.length - 1)
            else -> return R.Mal("Only pkh, sh(wpkh), wpkh and tr (key path) descriptors are supported")
        }
        // Origen
        var resto = dentro
        var huella: ByteArray? = null; val ruta = ArrayList<Long>()
        if (resto.startsWith("[")) {
            val fin = resto.indexOf(']'); if (fin < 0) return R.Mal("Unclosed key origin")
            val partes = resto.substring(1, fin).split('/')
            if (partes[0].length != 8 || !partes[0].all { it in "0123456789abcdefABCDEF" }) return R.Mal("Bad fingerprint")
            huella = bytes(partes[0].lowercase())
            for (p in partes.drop(1)) ruta.add(pasoDe(p) ?: return R.Mal("Bad origin path step: $p"))
            resto = resto.substring(fin + 1)
        } else avisos.add("No key origin: an external signer may not recognise the inputs")
        // Clave y derivación posterior
        val barra = resto.indexOf('/')
        val claveTxt = if (barra < 0) resto else resto.substring(0, barra)
        val deriv = if (barra < 0) "" else resto.substring(barra)
        if (deriv !in setOf("", "/<0;1>/*", "/0/*", "/1/*")) return R.Mal("Unsupported derivation after the key: $deriv")
        val ck0 = leerClave(claveTxt) ?: return R.Mal("Not a valid extended public key")
        val ck = ClaveX(ck0.clave33, ck0.cadena, ck0.testnet, huella, ruta, ck0.texto)
        return R.Ok(Vigilada(tipo, ck), avisos)
    }

    /** xpub/tpub o sus variantes SLIP-132; null si no vale o es privada. */
    private fun leerClave(t: String): ClaveX? {
        val p = b58check(t) ?: return null
        if (p.size != 78) return null
        val ver = VERSIONES[hex(p.copyOfRange(0, 4))] ?: return null
        val clave = p.copyOfRange(45, 78)
        if (clave[0] != 0x02.toByte() && clave[0] != 0x03.toByte()) return null
        return ClaveX(clave, p.copyOfRange(13, 45), ver.second, null, emptyList(), t)
    }

    private fun pasoDe(p: String): Long? {
        val duro = p.endsWith("'") || p.endsWith("h") || p.endsWith("H")
        val n = p.trimEnd('\'', 'h', 'H').toLongOrNull() ?: return null
        if (n !in 0..0x7fffffffL) return null
        return if (duro) n + 0x80000000L else n
    }
    private fun paso(r: Long) = if (r >= 0x80000000L) "${r - 0x80000000L}h" else "$r"

    // ── Checksum (BIP380) ────────────────────────────────────────────────────────────────────────

    private const val ENTRADA = "0123456789()[],'/*abcdefgh@:$%{}IJKLMNOPQRSTUVWXYZ&+-.;<=>?!^_|~ijklmnopqrstuvwxyzABCDEFGH`#\"\\ "
    private const val SUMA = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = longArrayOf(0xf5dee51989L, 0xa9fdca3312L, 0x1bab10e32dL, 0x3706b1677aL, 0x644d626ffdL)

    fun checksum(d: String): String {
        var c = 1L; var cls = 0; var clsCount = 0
        fun paso(v: Int) {
            val top = c ushr 35
            c = ((c and 0x7ffffffffL) shl 5) xor v.toLong()
            for (i in 0 until 5) if ((top ushr i) and 1L == 1L) c = c xor GEN[i]
        }
        for (ch in d) {
            val pos = ENTRADA.indexOf(ch); require(pos >= 0) { "invalid character" }
            paso(pos and 31)
            cls = cls * 3 + (pos shr 5)
            if (++clsCount == 3) { paso(cls); cls = 0; clsCount = 0 }
        }
        if (clsCount > 0) paso(cls)
        repeat(8) { paso(0) }
        c = c xor 1
        return (0 until 8).map { SUMA[((c ushr (5 * (7 - it))) and 31).toInt()] }.joinToString("")
    }

    /** Base58Check de una clave extendida (111–112 caracteres: BtcAddress.base58CheckDecode corta en 100). */
    internal fun b58check(t: String): ByteArray? {
        if (t.length !in 100..120) return null
        val a = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var n = java.math.BigInteger.ZERO; val b58 = java.math.BigInteger.valueOf(58)
        for (c in t) { val v = a.indexOf(c); if (v < 0) return null; n = n.multiply(b58).add(java.math.BigInteger.valueOf(v.toLong())) }
        val raw = n.toByteArray().let { if (it.size > 82 && it[0].toInt() == 0) it.copyOfRange(it.size - 82, it.size) else it }
        if (raw.size != 82) return null
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        val c = sha.digest(sha.digest(raw.copyOfRange(0, 78)))
        return if (c.copyOfRange(0, 4).contentEquals(raw.copyOfRange(78, 82))) raw.copyOfRange(0, 78) else null
    }

    internal fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    internal fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
