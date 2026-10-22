package com.hunter.btc

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Desbloqueo con huella, OPCIONAL (ajuste del Perfil, apagado por defecto).
 *
 * Al activarlo, la clave que sale del código se sella con una clave del Keystore que sólo se puede usar
 * tras una huella válida (una por uso, biometría fuerte). Al desbloquear, la huella la devuelve y la sesión
 * se abre igual que con el código. Si cambian las huellas registradas en el móvil, el sistema invalida esa
 * clave: la app lo detecta, vuelve al código y desactiva la opción.
 *
 * Lo que se cede al activarlo: quien pueda poner su dedo (o el tuyo) en el sensor abre la cartera y el baúl
 * sin saber el código. Por eso nace apagado y exige el código para encenderlo.
 */
object HuellaDesbloqueo {

    private fun clave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (ks.containsAlias(WalletManager.ALIAS_HUELLA))
            return (ks.getEntry(WalletManager.ALIAS_HUELLA, null) as KeyStore.SecretKeyEntry).secretKey
        val b = KeyGenParameterSpec.Builder(WalletManager.ALIAS_HUELLA,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)   // una huella por uso
        else {
            @Suppress("DEPRECATION")
            b.setUserAuthenticationValidityDurationSeconds(-1)
        }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(b.build())
        return kg.generateKey()
    }

    /** ¿Hay un sensor de huella (u otra biometría fuerte) con alguna registrada? */
    fun disponible(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun info(subtitulo: String) = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Hunter Suite").setSubtitle(subtitulo)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText("Use the code").build()

    /**
     * Enciende el desbloqueo con huella. Hay que llamarlo con la sesión abierta (el código acabado de teclear):
     * se sella la clave de esta sesión con una huella. [alTerminar] recibe el resultado y, si falló, el motivo.
     */
    fun activar(act: FragmentActivity, alTerminar: (Boolean, String?) -> Unit) {
        val raw = WalletManager.claveSemillaBytes() ?: return alTerminar(false, "Enter your code first")
        if (!disponible(act)) return alTerminar(false, "No fingerprint is enrolled on this phone")
        val cifrador = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, clave()) }
        } catch (e: Exception) { return alTerminar(false, "Could not prepare the key: ${e.javaClass.simpleName}") }
        BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                try {
                    val c = r.cryptoObject!!.cipher!!
                    val enc = c.doFinal(raw)
                    if (WalletManager.huellaGuardar(act, enc, c.iv)) alTerminar(true, null)
                    else alTerminar(false, "Could not save it")
                } catch (e: Exception) { alTerminar(false, "Failed: ${e.javaClass.simpleName}") }
                finally { java.util.Arrays.fill(raw, 0) }
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                java.util.Arrays.fill(raw, 0); alTerminar(false, msg.toString())
            }
        }).authenticate(info("Confirm to turn on fingerprint unlock"), BiometricPrompt.CryptoObject(cifrador))
    }

    /** Pide la huella y abre la sesión con la clave sellada. [alTerminar] recibe true si la sesión quedó abierta. */
    fun desbloquear(act: FragmentActivity, alTerminar: (Boolean) -> Unit) {
        val guardada = WalletManager.huellaClave(act) ?: return alTerminar(false)
        val cifrador = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(128, guardada.second))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Cambiaron las huellas del móvil: la clave ya no existe. Se apaga la opción y se pide el código.
            WalletManager.huellaDesactivar(act)
            Toast.makeText(act, "Your fingerprints changed, so fingerprint unlock was turned off. Use your code.",
                Toast.LENGTH_LONG).show()
            return alTerminar(false)
        } catch (e: Exception) { return alTerminar(false) }
        BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                var raw: ByteArray? = null
                val ok = try {
                    raw = r.cryptoObject!!.cipher!!.doFinal(guardada.first)
                    WalletManager.entrarConClave(act, raw)
                } catch (e: Exception) { false }
                finally { raw?.let { java.util.Arrays.fill(it, 0) } }
                alTerminar(ok)
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {}
            override fun onAuthenticationFailed() {}
        }).authenticate(info("Unlock with your fingerprint"), BiometricPrompt.CryptoObject(cifrador))
    }

    fun desactivar(ctx: Context) = WalletManager.huellaDesactivar(ctx)
}
