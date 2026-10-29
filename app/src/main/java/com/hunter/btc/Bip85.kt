package com.hunter.btc

/**
 * BIP85: de UNA semilla salen otras independientes (frases hijas, claves WIF, contraseñas), cada una por
 * su índice. Sirve para tener una cartera caliente, una de regalo o una de prueba sin más copias: basta la
 * semilla madre y el número. Filtrar una hija no compromete a la madre ni a sus hermanas; perder la madre,
 * en cambio, lo pierde todo.
 *
 * La clave k de la ruta (toda endurecida) la deriva el motor (HunterEngine.deriveRuta); aquí sólo va lo que
 * se hace con ella: HMAC-SHA512(clave = "bip-entropy-from-k", mensaje = k) y cortar lo que pida cada uso.
 * Puro: se prueba en la JVM con los vectores oficiales (Bip85Test).
 */
object Bip85 {

    const val RAIZ = "m/83696968'"

    /** BIP39 en inglés (idioma 0'): 12, 15, 18, 21 o 24 palabras. */
    fun rutaFrase(palabras: Int, indice: Int): String {
        require(palabras in setOf(12, 15, 18, 21, 24)) { "BIP85: $palabras palabras" }
        return "$RAIZ/39'/0'/$palabras'/${idx(indice)}'"
    }
    /** WIF comprimida para usar como hdseed de Bitcoin Core (aplicación 2'). */
    fun rutaWif(indice: Int) = "$RAIZ/2'/${idx(indice)}'"
    /** Bytes en hex (aplicación 128169'), 16..64. */
    fun rutaHex(bytes: Int, indice: Int): String {
        require(bytes in 16..64) { "BIP85: $bytes bytes" }
        return "$RAIZ/128169'/$bytes'/${idx(indice)}'"
    }
    /** Contraseña en Base64 (aplicación 707764'), 20..86 caracteres. */
    fun rutaClave(largo: Int, indice: Int): String {
        require(largo in 20..86) { "BIP85: $largo caracteres" }
        return "$RAIZ/707764'/$largo'/${idx(indice)}'"
    }

    private fun idx(i: Int): Int { require(i >= 0) { "BIP85: índice negativo" }; return i }

    /** Los 64 bytes de entropía de la clave k (32 bytes) de la ruta. */
    fun entropia(k: ByteArray): ByteArray {
        require(k.size == 32) { "BIP85: la clave tiene que tener 32 bytes" }
        val mac = javax.crypto.Mac.getInstance("HmacSHA512")
        mac.init(javax.crypto.spec.SecretKeySpec("bip-entropy-from-k".toByteArray(Charsets.US_ASCII), "HmacSHA512"))
        return mac.doFinal(k)
    }

    /** La frase hija: los primeros bytes que pide su largo (16 para 12 palabras … 32 para 24). */
    fun frase(ent: ByteArray, palabras: Int): String = Bip39.deEntropia(ent.copyOfRange(0, palabras * 4 / 3))

    /** Los [n] primeros bytes, en hex. */
    fun hex(ent: ByteArray, n: Int): String = ent.copyOfRange(0, n).joinToString("") { "%02x".format(it) }

    /** Los 32 primeros bytes como WIF comprimida (mainnet). null si no es una clave válida (1 entre 2^127). */
    fun wif(ent: ByteArray): String? {
        val d = java.math.BigInteger(1, ent.copyOfRange(0, 32))
        if (d.signum() == 0 || d >= N) return null
        return base58check(byteArrayOf(0x80.toByte()) + ent.copyOfRange(0, 32) + byteArrayOf(1))
    }

    /** La contraseña: Base64 de los 64 bytes, cortado a [largo] (sin relleno: largo ≤ 86). */
    fun clave(ent: ByteArray, largo: Int): String = java.util.Base64.getEncoder().encodeToString(ent).substring(0, largo)

    private val N = java.math.BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    private fun base58check(datos: ByteArray): String {
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        val b = datos + sha.digest(sha.digest(datos)).copyOfRange(0, 4)
        val a = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var n = java.math.BigInteger(1, b); val b58 = java.math.BigInteger.valueOf(58)
        val sb = StringBuilder()
        while (n.signum() > 0) { val qr = n.divideAndRemainder(b58); sb.append(a[qr[1].toInt()]); n = qr[0] }
        for (x in b) { if (x.toInt() != 0) break; sb.append('1') }
        return sb.reverse().toString()
    }
}
