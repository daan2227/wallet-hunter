package com.hunter.btc

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * El «buzón» del baúl: cómo guardar un hallazgo cuando el código está cerrado.
 *
 * El baúl va cerrado con una clave que sale del código del usuario, así que ni quien tenga root puede
 * leerlo sin él. Pero el motor encuentra claves con la pantalla apagada, cuando esa clave NO está en
 * memoria, y no puede esperar a que el usuario desbloquee: se perdería. Solución: cifrado de clave pública
 * (ECIES sobre P-256 + AES-GCM). Cualquiera puede ESCRIBIR en el buzón con la parte pública, que no es
 * secreta; sólo se LEE con la privada, que va guardada bajo la clave del código. Al desbloquear, el
 * contenido del buzón pasa al baúl.
 *
 * Pura (sólo java.security / javax.crypto): se prueba en la JVM.
 *
 * Paquete: [longitud de la pública efímera (2 bytes)][pública efímera X.509][iv 12][cifrado].
 */
object BuzonCifrado {
    private const val CURVA = "secp256r1"
    private val rnd = SecureRandom()

    /** (pública X.509, privada PKCS#8). */
    fun generarPar(): Pair<ByteArray, ByteArray> {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVA), rnd) }
        val kp = g.generateKeyPair()
        return kp.public.encoded to kp.private.encoded
    }

    private fun clave(compartido: ByteArray, efimera: ByteArray, destino: ByteArray): SecretKeySpec {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("WHBUZON1".toByteArray()); md.update(compartido); md.update(efimera); md.update(destino)
        return SecretKeySpec(md.digest(), "AES")
    }

    /** Cifra para el dueño de la pública [pub]. Sólo con la privada se abre. */
    fun cifrar(pub: ByteArray, datos: ByteArray): ByteArray {
        val destino = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub))
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVA), rnd) }
        val eph = g.generateKeyPair()
        val ka = KeyAgreement.getInstance("ECDH").apply { init(eph.private); doPhase(destino, true) }
        val ephPub = eph.public.encoded
        val k = clave(ka.generateSecret(), ephPub, pub)
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv)) }
        val enc = c.doFinal(datos)
        return byteArrayOf((ephPub.size shr 8).toByte(), ephPub.size.toByte()) + ephPub + iv + enc
    }

    /** null si la privada no es la del destinatario o el paquete está dañado. */
    fun descifrar(priv: ByteArray, paquete: ByteArray, pub: ByteArray): ByteArray? = try {
        val n = ((paquete[0].toInt() and 0xff) shl 8) or (paquete[1].toInt() and 0xff)
        require(n in 60..200 && paquete.size > 2 + n + 12 + 16)
        val ephPub = paquete.copyOfRange(2, 2 + n)
        val iv = paquete.copyOfRange(2 + n, 2 + n + 12)
        val enc = paquete.copyOfRange(2 + n + 12, paquete.size)
        val kf = KeyFactory.getInstance("EC")
        val ka = KeyAgreement.getInstance("ECDH").apply {
            init(kf.generatePrivate(PKCS8EncodedKeySpec(priv)))
            doPhase(kf.generatePublic(X509EncodedKeySpec(ephPub)), true)
        }
        val k = clave(ka.generateSecret(), ephPub, pub)
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)) }.doFinal(enc)
    } catch (e: Exception) { null }
}
