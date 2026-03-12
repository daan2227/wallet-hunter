package com.hunter.btc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object WalletManager {
    private const val KEY_ALIAS  = "hunter_wallet_key"
    private const val PREFS_NAME = "wallet_prefs"
    private const val PREF_SEED  = "enc_seed"
    private const val PREF_IV    = "enc_iv"
    private const val PREF_SALT  = "pin_salt"
    private const val PREF_VER   = "pin_verify"
    private const val PREF_VIV   = "pin_verify_iv"
    private const val PREF_ADDRS = "wallet_addrs"
    private const val PBKDF2_ITER = 100000

    /* Keystore key solo para seed (hardware-backed) */
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (ks.containsAlias(KEY_ALIAS))
            return (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return kg.generateKey()
    }

    /* Deriva clave AES-256 del PIN usando PBKDF2 */
    private fun pinToKey(pin: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITER, 256)
        val raw  = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun aesEncrypt(key: SecretKey, data: ByteArray): Pair<ByteArray,ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Pair(cipher.doFinal(data), cipher.iv)
    }

    private fun aesDecrypt(key: SecretKey, data: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(data)
        } catch(e: Exception) { null }
    }

    /* Guarda seed cifrada con Keystore (hardware) */
    fun saveWif(ctx: Context, wif: String, addr: String) {
        val prefs = ctx.getSharedPreferences("wallet_wif", Context.MODE_PRIVATE)
        // Save single WIF (latest puzzle match or manual import)
        prefs.edit().putString("wif", wif).putString("addr", addr).apply()
    }
    fun loadWif(ctx: Context): Pair<String,String>? {
        val prefs = ctx.getSharedPreferences("wallet_wif", Context.MODE_PRIVATE)
        val w = prefs.getString("wif", null) ?: return null
        val a = prefs.getString("addr", "") ?: ""
        return Pair(w, a)
    }
    fun clearWif(ctx: Context) {
        ctx.getSharedPreferences("wallet_wif", Context.MODE_PRIVATE).edit().clear().apply()
    }
    fun hasWif(ctx: Context) = ctx.getSharedPreferences("wallet_wif", Context.MODE_PRIVATE).contains("wif")

    fun saveSeed(ctx: Context, mnemonic: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val enc = cipher.doFinal(mnemonic.toByteArray(Charsets.UTF_8))
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_SEED, Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(PREF_IV,   Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun loadSeed(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enc = Base64.decode(prefs.getString(PREF_SEED, null) ?: return null, Base64.NO_WRAP)
        val iv  = Base64.decode(prefs.getString(PREF_IV,   null) ?: return null, Base64.NO_WRAP)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(enc), Charsets.UTF_8)
        } catch(e: Exception) { null }
    }

    fun hasSeed(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(PREF_SEED)

    /* PIN: guarda salt + texto de verificacion cifrado con clave derivada del PIN
       Si el PIN es incorrecto PBKDF2 genera clave diferente -> AES falla -> checkPin devuelve false
       No hay hash almacenado -> no hay brute-force offline directo */
    fun savePin(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val key  = pinToKey(pin, salt)
        val (enc, iv) = aesEncrypt(key, "wallet_ok".toByteArray())
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(PREF_VER,  Base64.encodeToString(enc,  Base64.NO_WRAP))
            .putString(PREF_VIV,  Base64.encodeToString(iv,   Base64.NO_WRAP))
            .apply()
    }

    fun checkPin(ctx: Context, pin: String): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val salt = Base64.decode(prefs.getString(PREF_SALT, null) ?: return false, Base64.NO_WRAP)
        val enc  = Base64.decode(prefs.getString(PREF_VER,  null) ?: return false, Base64.NO_WRAP)
        val iv   = Base64.decode(prefs.getString(PREF_VIV,  null) ?: return false, Base64.NO_WRAP)
        val key  = pinToKey(pin, salt)
        val dec  = aesDecrypt(key, enc, iv) ?: return false
        return String(dec) == "wallet_ok"
    }

    fun hasPin(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(PREF_SALT)

    fun saveAddresses(ctx: Context, json: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_ADDRS, json).apply()
    }

    fun loadAddresses(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_ADDRS, null)


    /* -- MULTI-WALLET -- */
    private const val PREF_WALLET_LIST = "wallet_list"
    private const val PREF_ACTIVE_ID   = "active_wallet_id"

    fun listWallets(ctx: Context): List<Pair<String,String>> {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_WALLET_LIST, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("|").mapNotNull {
            val parts = it.split(":")
            if (parts.size == 2) Pair(parts[0], parts[1]) else null
        }
    }

    fun saveWallet(ctx: Context, id: String, name: String, mnemonic: String) {
        // Encrypt seed under id-specific key
        val alias = "hunter_wallet_$id"
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (!ks.containsAlias(alias)) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            kg.generateKey()
        }
        val key = (ks.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val enc = cipher.doFinal(mnemonic.toByteArray(Charsets.UTF_8))
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.putString("seed_enc_$id", Base64.encodeToString(enc, Base64.NO_WRAP))
        prefs.putString("seed_iv_$id",  Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        // Add to list
        val list = listWallets(ctx).toMutableList()
        if (list.none { it.first == id }) list.add(Pair(id, name))
        prefs.putString(PREF_WALLET_LIST, list.joinToString("|") { "${it.first}:${it.second}" })
        prefs.apply()
    }

    fun loadWalletSeed(ctx: Context, id: String): String? {
        val alias = "hunter_wallet_$id"
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enc = Base64.decode(prefs.getString("seed_enc_$id", null) ?: return null, Base64.NO_WRAP)
        val iv  = Base64.decode(prefs.getString("seed_iv_$id",  null) ?: return null, Base64.NO_WRAP)
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            val key = (ks.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(enc), Charsets.UTF_8)
        } catch(e: Exception) { null }
    }

    fun setActiveWallet(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_ACTIVE_ID, id).apply()
    }

    fun getActiveWalletId(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_ACTIVE_ID, null)

    fun deleteWallet(ctx: Context, id: String) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.remove("seed_enc_$id"); prefs.remove("seed_iv_$id")
        val list = listWallets(ctx).filter { it.first != id }
        prefs.putString(PREF_WALLET_LIST, list.joinToString("|") { "${it.first}:${it.second}" })
        prefs.apply()
        try { KeyStore.getInstance("AndroidKeyStore").also{it.load(null)}.deleteEntry("hunter_wallet_$id") } catch(e: Exception) {}
    }

    fun clearSeed(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        try { KeyStore.getInstance("AndroidKeyStore").also{it.load(null)}.deleteEntry(KEY_ALIAS) } catch(e: Exception) {}
    }
}
