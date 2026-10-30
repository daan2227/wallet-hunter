package com.hunter.btc

import java.math.BigInteger

/**
 * codex32 (BIP93): una semilla BIP32 escrita en caracteres bech32 con una suma de comprobación fuerte (detecta
 * cualquier error de hasta 8 caracteres) y, si se quiere, partida en k-de-n partes (Shamir sobre GF(32)) que se
 * pueden comprobar e incluso calcular a mano.
 *
 * OJO: codifica la SEMILLA MAESTRA BIP32 (16–64 bytes), no una frase BIP39; una cartera BIP39 no se convierte
 * a codex32 sin cambiar de cartera. Esto sirve para comprobar, recuperar y partir códigos codex32, y enseña la
 * semilla y su xprv para llevarla a una cartera que lo admita.
 *
 * Copiado del código de referencia del BIP. Puro: Codex32Test con todos sus vectores.
 */
object Codex32 {
    const val CS = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val LARGOS = setOf(48, 54, 61, 67, 74, 127)
    private const val HRP_EXP = 5

    private val CONST = BigInteger("10ce0795c2fd1e62a", 16)
    private val GEN = listOf("19dc500ce73fde210", "1bfae00def77fe529", "1fbd920fffe7bee52", "1739640bdeee3fdad", "07729a039cfc75f5a").map { BigInteger(it, 16) }
    private val CONST_L = BigInteger("43381e570bf4798ab26", 16)
    private val GEN_L = listOf("3d59d273535ea62d897", "7a9becb6361c6c51507", "543f9b7e6c38d8a2a0e", "0c577eaeccf1990d13c", "1887f74f8dc71b10651").map { BigInteger(it, 16) }
    private val INICIO = BigInteger("23181b3", 16)

    private fun polymod(v: List<Int>, gen: List<BigInteger>, alto: Int): BigInteger {
        val mascara = BigInteger.ONE.shiftLeft(alto).subtract(BigInteger.ONE)
        var r = INICIO
        for (x in v) {
            val b = r.shiftRight(alto).toInt()
            r = r.and(mascara).shiftLeft(5).xor(BigInteger.valueOf(x.toLong()))
            for (i in 0 until 5) if ((b shr i) and 1 == 1) r = r.xor(gen[i])
        }
        return r
    }

    private fun verificar(d: List<Int>): Boolean = when {
        HRP_EXP + d.size >= 96 -> HRP_EXP + d.size <= 1023 && polymod(d, GEN_L, 70) == CONST_L
        else -> HRP_EXP + d.size <= 93 && polymod(d, GEN, 60) == CONST
    }

    private fun checksum(d: List<Int>): List<Int> =
        if (HRP_EXP + d.size + 13 > 93) {
            val p = polymod(d + List(15) { 0 }, GEN_L, 70).xor(CONST_L)
            (0 until 15).map { p.shiftRight(5 * (14 - it)).toInt() and 31 }
        } else {
            val p = polymod(d + List(13) { 0 }, GEN, 60).xor(CONST)
            (0 until 13).map { p.shiftRight(5 * (12 - it)).toInt() and 31 }
        }

    /** Una cadena válida: [datos] es la parte de datos SIN la suma (umbral, identificador, índice y carga). */
    class Cadena(val datos: List<Int>) {
        /** El carácter del umbral, como valor bech32 (para comparar); [k] es el número que representa. */
        val umbral: Int get() = datos[0]
        val k: Int get() = CS[datos[0]].digitToInt()
        val id: String get() = datos.subList(1, 5).joinToString("") { CS[it].toString() }
        val indice: Char get() = CS[datos[5]]
        val esSecreto: Boolean get() = indice == 's'
        override fun toString() = codificar(datos)
    }

    fun codificar(d: List<Int>): String = "ms1" + (d + checksum(d)).joinToString("") { CS[it].toString() }

    /** ms32_decode del BIP: null si no es una cadena codex32 válida. */
    fun leer(s0: String): Cadena? {
        val s = s0.trim()
        if (s.any { it.code < 33 || it.code > 126 } || (s.lowercase() != s && s.uppercase() != s)) return null
        val c = s.lowercase(); val pos = c.lastIndexOf('1')
        if (pos < 2 || c.length !in LARGOS) return null
        if (!c.substring(pos + 1).all { it in CS }) return null
        if (c.substring(0, pos) != "ms" || c[pos + 1].isLetter() || (c[pos + 1] == '0' && c[pos + 6] != 's')) return null
        val d = c.substring(pos + 1).map { CS.indexOf(it) }
        if (!verificar(d)) return null
        return Cadena(d.dropLast(if (HRP_EXP + d.size < 94) 13 else 15))
    }

    /** La semilla maestra de un secreto (índice s): 16–64 bytes; null si su carga no es un tamaño válido. */
    fun semilla(c: Cadena): ByteArray? {
        val carga = c.datos.drop(6)
        val bits = carga.size * 5; val n = bits / 8
        if (bits - n * 8 > 4 || n !in setOf(16, 20, 24, 28, 32, 64)) return null
        var acc = 0L; var b = 0; val out = java.io.ByteArrayOutputStream()
        for (v in carga) { acc = (acc shl 5) or v.toLong(); b += 5; if (b >= 8) { b -= 8; out.write(((acc shr b) and 0xff).toInt()); acc = acc and ((1L shl b) - 1) } }
        return out.toByteArray().copyOf(n)
    }

    /** Un secreto codex32 de [semilla] con umbral [k] (0 = sin partir) e [id] de 4 caracteres; relleno con ceros. */
    fun deSemilla(semilla: ByteArray, k: Int, id: String, indice: Char = 's'): String {
        require(semilla.size in setOf(16, 20, 24, 28, 32, 64)) { "tamaño de semilla" }
        require(k == 0 || k in 2..9) { "umbral" }
        require(id.length == 4 && id.lowercase().all { it in CS }) { "identificador" }
        val carga = ArrayList<Int>(); var acc = 0; var b = 0
        for (x in semilla) { acc = (acc shl 8) or (x.toInt() and 0xff); b += 8; while (b >= 5) { b -= 5; carga.add((acc shr b) and 31) }; acc = acc and ((1 shl b) - 1) }
        if (b > 0) carga.add((acc shl (5 - b)) and 31)
        val cab = listOf(CS.indexOf(('0' + k))) + id.lowercase().map { CS.indexOf(it) } + CS.indexOf(indice)
        return codificar(cab + carga)
    }

    // ── Aritmética de GF(32) y Lagrange, tal cual el BIP ──

    private val INV = intArrayOf(0, 1, 20, 24, 10, 8, 12, 29, 5, 11, 4, 9, 6, 28, 26, 31, 22, 18, 17, 23, 2, 25, 16, 19, 3, 21, 14, 30, 13, 7, 27, 15)
    private fun mul(a0: Int, b: Int): Int { var a = a0; var r = 0; for (i in 0 until 5) { if ((b shr i) and 1 == 1) r = r xor a; a = a shl 1; if (a >= 32) a = a xor 41 }; return r }
    private fun lagrange(l: List<Int>, x: Int): List<Int> {
        var n = 1; val c = ArrayList<Int>()
        for (i in l) { n = mul(n, i xor x); var m = 1; for (j in l) m = mul(m, (if (i == j) x else i) xor j); c.add(m) }
        return c.map { mul(n, INV[it]) }
    }
    private fun interpolar(l: List<List<Int>>, x: Int): List<Int> {
        val w = lagrange(l.map { it[5] }, x)
        return (l[0].indices).map { i -> l.indices.fold(0) { n, j -> n xor mul(w[j], l[j][i]) } }
    }

    /** Por qué un conjunto de partes no vale; null si vale. */
    fun problema(partes: List<Cadena>): String? = when {
        partes.isEmpty() -> "No shares."
        partes.any { it.umbral == CS.indexOf('0') } -> "A threshold-0 string is already the secret."
        partes.map { Triple(it.umbral, it.id, it.datos.size) }.toSet().size != 1 -> "The shares must have the same threshold, identifier and length."
        partes.map { it.indice }.toSet().size != partes.size -> "Two shares have the same index."
        partes.size != partes[0].k -> "This set needs exactly ${partes[0].k} shares (it has ${partes.size})."
        else -> null
    }

    /** El secreto (índice s) de k partes; null si el conjunto no vale. */
    fun recuperar(partes: List<Cadena>): Cadena? =
        if (problema(partes) != null) null else Cadena(interpolar(partes.map { it.datos }, CS.indexOf('s')))

    /** La parte de índice [indice] a partir de k cadenas del conjunto (partes o el secreto). */
    fun derivar(k: List<Cadena>, indice: Char): Cadena = Cadena(interpolar(k.map { it.datos }, CS.indexOf(indice)))

    /**
     * Parte [semilla] en [n] partes con umbral [k] (BIP93, "For an existing secret"): el secreto y k−1 partes al
     * azar (índices a, c, d…) fijan el polinomio; de ahí salen las n partes. [azar] da los caracteres aleatorios.
     */
    fun partir(semilla: ByteArray, k: Int, n: Int, id: String,
               azar: (Int) -> List<Int> = { t -> val r = java.security.SecureRandom(); List(t) { r.nextInt(32) } }): List<String> {
        require(k in 2..9 && n in k..31) { "k de 2 a 9 y n ≥ k" }
        val secreto = leer(deSemilla(semilla, k, id))!!
        val indices = CS.filter { it.isLetter() && it != 's' }.toList().sorted()
        val iniciales = indices.take(k - 1).map { ix -> Cadena(secreto.datos.subList(0, 5) + CS.indexOf(ix) + azar(secreto.datos.size - 6)) }
        val base = listOf(secreto) + iniciales
        return (iniciales + indices.drop(k - 1).take(n - (k - 1)).map { derivar(base, it) }).map { it.toString() }
    }

    /** La xprv maestra (BIP32) de [semilla]. */
    fun xprv(semilla: ByteArray): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA512")
        mac.init(javax.crypto.spec.SecretKeySpec("Bitcoin seed".toByteArray(), "HmacSHA512"))
        val i = mac.doFinal(semilla)
        val d = byteArrayOf(0x04, 0x88.toByte(), 0xAD.toByte(), 0xE4.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0) + i.copyOfRange(32, 64) + byteArrayOf(0) + i.copyOfRange(0, 32)
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        val b = d + sha.digest(sha.digest(d)).copyOfRange(0, 4)
        val a = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var x = BigInteger(1, b); val sb = StringBuilder(); val b58 = BigInteger.valueOf(58)
        while (x.signum() > 0) { val qr = x.divideAndRemainder(b58); sb.append(a[qr[1].toInt()]); x = qr[0] }
        for (y in b) { if (y.toInt() != 0) break; sb.append('1') }
        return sb.reverse().toString()
    }
}
