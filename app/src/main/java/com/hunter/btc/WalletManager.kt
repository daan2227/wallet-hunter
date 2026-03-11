package com.hunter.btc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

object WalletManager {
    private const val KEY_ALIAS   = "hunter_wallet_key"
    private const val PREFS_NAME  = "wallet_prefs"
    private const val PREF_SEED   = "enc_seed"
    private const val PREF_IV     = "enc_iv"
    private const val PREF_ADDRS  = "wallet_addrs"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (ks.containsAlias(KEY_ALIAS))
            return (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return kg.generateKey()
    }

    fun saveSeed(ctx: Context, mnemonic: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val enc = cipher.doFinal(mnemonic.toByteArray(Charsets.UTF_8))
        val iv  = cipher.iv
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_SEED, Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(PREF_IV,   Base64.encodeToString(iv,  Base64.NO_WRAP))
            .apply()
    }

    fun loadSeed(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encB64 = prefs.getString(PREF_SEED, null) ?: return null
        val ivB64  = prefs.getString(PREF_IV,   null) ?: return null
        return try {
            val enc = Base64.decode(encB64, Base64.NO_WRAP)
            val iv  = Base64.decode(ivB64,  Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(enc), Charsets.UTF_8)
        } catch(e: Exception) { null }
    }

    fun hasSeed(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(PREF_SEED)

    fun clearSeed(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            ks.deleteEntry(KEY_ALIAS)
        } catch(e: Exception) {}
    }

    fun saveAddresses(ctx: Context, json: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_ADDRS, json).apply()
    }

    fun loadAddresses(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_ADDRS, null)
}
