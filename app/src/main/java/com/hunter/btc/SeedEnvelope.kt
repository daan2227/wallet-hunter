package com.hunter.btc

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Sobre de una semilla: una capa AES-GCM con la clave que deriva del PIN, por
 * FUERA de la capa del Keystore que ya la protegía.
 *
 * Por qué dos capas: la del Keystore no sale nunca del chip, así que el PIN
 * sólo no basta para leer nada aunque se copien los ficheros. La del PIN hace
 * que, aun teniendo el chip, no se abra sin el código. Las dos juntas: hace
 * falta el móvil Y el PIN.
 *
 * Esta parte es pura (sólo javax.crypto), así que se prueba en la JVM. Lo que
 * depende del Keystore queda en WalletManager.
 */
object SeedEnvelope {

    /** Junta el blob interior del Keystore: [longitud del iv][iv][cifrado]. */
    fun empaquetar(iv: ByteArray, enc: ByteArray): ByteArray =
        byteArrayOf(iv.size.toByte()) + iv + enc

    /** Inverso de [empaquetar]. null si el blob no tiene esa forma. */
    fun desempaquetar(b: ByteArray): Pair<ByteArray, ByteArray>? {
        if (b.isEmpty()) return null
        val n = b[0].toInt() and 0xff
        if (n <= 0 || b.size < 1 + n) return null
        return b.copyOfRange(1, 1 + n) to b.copyOfRange(1 + n, b.size)
    }

    /** Cifra con la clave del PIN. Devuelve (cifrado, iv). */
    fun sellar(clave: SecretKey, datos: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, clave)
        return c.doFinal(datos) to c.iv
    }

    /** Descifra. null si la clave no es la del PIN o el dato se ha tocado. */
    fun abrir(clave: SecretKey, enc: ByteArray, iv: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, clave, GCMParameterSpec(128, iv))
        c.doFinal(enc)
    } catch (e: Exception) { null }
}
