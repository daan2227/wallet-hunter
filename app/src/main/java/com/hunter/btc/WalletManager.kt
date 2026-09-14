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
    /* Alias propio para los WIF: clearSeed()/clearSeedOnly() borran KEY_ALIAS,
       y compartirlo dejaría los WIF cifrados irrecuperables. */
    private const val WIF_KEY_ALIAS = "hunter_wif_key"
    private const val WIF_PREFS      = "wallet_wif"
    private const val PREF_WIF_PLAIN = "wif_list"      // legacy, en claro
    private const val PREF_WIF_ENC   = "wif_list_enc"
    private const val PREF_WIF_IV    = "wif_list_iv"
    private const val PREFS_NAME = "wallet_prefs"
    private const val PREF_SEED  = "enc_seed"
    private const val PREF_IV    = "enc_iv"
    private const val PREF_SALT  = "pin_salt"
    private const val PREF_VER   = "pin_verify"
    private const val PREF_VIV   = "pin_verify_iv"
    private const val PREF_ADDRS = "wallet_addrs"
    private const val PBKDF2_ITER = 100000

    /* Keystore key hardware-backed */
    private fun getOrCreateKey(alias: String = KEY_ALIAS): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (ks.containsAlias(alias))
            return (ks.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(alias,
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
    // WIF wallet: id -> "wif|addr|name"
    /* La lista de WIF se cifra con AES-GCM bajo una clave del Keystore. Las
       versiones anteriores la guardaban en claro; listWifs() detecta ese formato,
       lo migra y borra el original. Las firmas públicas no cambian. */

    private fun serializeWifs(list: List<Triple<String,String,String>>) =
        list.joinToString(";;") { "${it.first}~~~${it.second}~~~${it.third}" }

    private fun parseWifs(raw: String): List<Triple<String,String,String>> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(";;").mapNotNull {
            val p = it.split("~~~")
            if (p.size == 3) Triple(p[0], p[1], p[2]) else null
        }
    }

    private fun writeWifs(ctx: Context, list: List<Triple<String,String,String>>) {
        val prefs = ctx.getSharedPreferences(WIF_PREFS, Context.MODE_PRIVATE)
        if (list.isEmpty()) { prefs.edit().clear().apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(WIF_KEY_ALIAS))
        val enc = cipher.doFinal(serializeWifs(list).toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(PREF_WIF_ENC, Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(PREF_WIF_IV,  Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .remove(PREF_WIF_PLAIN)
            .apply()
    }

    fun saveWif(ctx: Context, wif: String, addr: String, name: String = "WIF Wallet") {
        val id = "wif_${System.currentTimeMillis()}"
        writeWifs(ctx, listWifs(ctx) + Triple(id, wif, "$addr|$name"))
    }

    fun listWifs(ctx: Context): List<Triple<String,String,String>> {
        val prefs = ctx.getSharedPreferences(WIF_PREFS, Context.MODE_PRIVATE)
        val encB64 = prefs.getString(PREF_WIF_ENC, null)
        if (encB64 != null) {
            val ivB64 = prefs.getString(PREF_WIF_IV, null) ?: return emptyList()
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(WIF_KEY_ALIAS),
                    GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
                parseWifs(String(cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP)), Charsets.UTF_8))
            } catch (e: Exception) { emptyList() }
        }
        // Migración desde el formato legacy en claro. Si falla, las claves
        // privadas siguen sin cifrar en disco: hay que dejar rastro.
        val legacy = parseWifs(prefs.getString(PREF_WIF_PLAIN, "") ?: "")
        if (legacy.isNotEmpty()) {
            try {
                writeWifs(ctx, legacy)
            } catch (e: Exception) {
                android.util.Log.e("WalletManager",
                    "WIF migration failed — keys remain in cleartext: ${e.javaClass.simpleName}")
            }
        }
        return legacy
    }
    fun removeWif(ctx: Context, id: String) {
        writeWifs(ctx, listWifs(ctx).filter { it.first != id })
    }
    // Legacy single WIF support
    fun loadWif(ctx: Context): Pair<String,String>? {
        val list = listWifs(ctx)
        if (list.isEmpty()) return null
        val last = list.last()
        val addr = last.third.split("|").firstOrNull() ?: ""
        return Pair(last.second, addr)
    }
    fun clearWif(ctx: Context) {
        ctx.getSharedPreferences(WIF_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        try {
            KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }.deleteEntry(WIF_KEY_ALIAS)
        } catch (e: Exception) {}
    }
    fun hasWif(ctx: Context) = listWifs(ctx).isNotEmpty()

    // Watcher wallets: watch-only by address
    fun saveWatcher(ctx: Context, addr: String, label: String) {
        val id = "watch_${System.currentTimeMillis()}"
        val prefs = ctx.getSharedPreferences("wallet_watch", Context.MODE_PRIVATE)
        val raw = prefs.getString("watch_list", "") ?: ""
        val list = if (raw.isEmpty()) mutableListOf() else raw.split(";;").toMutableList()
        list.add("$id~~~$addr~~~$label")
        prefs.edit().putString("watch_list", list.joinToString(";;")).apply()
    }
    fun listWatchers(ctx: Context): List<Triple<String,String,String>> {
        val raw = ctx.getSharedPreferences("wallet_watch", Context.MODE_PRIVATE).getString("watch_list", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";;").mapNotNull {
            val p = it.split("~~~")
            if (p.size == 3) Triple(p[0], p[1], p[2]) else null
        }
    }
    fun removeWatcher(ctx: Context, id: String) {
        val list = listWatchers(ctx).filter { it.first != id }
        ctx.getSharedPreferences("wallet_watch", Context.MODE_PRIVATE).edit()
            .putString("watch_list", list.joinToString(";;") { "${it.first}~~~${it.second}~~~${it.third}" }).apply()
    }

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

    fun encryptData(data: ByteArray, pin: String): Pair<ByteArray, ByteArray> {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val key = pinToKey(pin, salt)
        val (enc, iv) = aesEncrypt(key, data)
        // Prepend salt to encrypted data
        return Pair(salt + enc, iv)
    }

    fun decryptData(data: ByteArray, iv: ByteArray, pin: String): ByteArray? {
        if (data.size < 16) return null
        val salt = data.copyOfRange(0, 16)
        val enc  = data.copyOfRange(16, data.size)
        val key  = pinToKey(pin, salt)
        return aesDecrypt(key, enc, iv)
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

    /* Borra solo la seed principal — preserva PIN y otras wallets */
    fun clearSeedOnly(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pin_salt = prefs.getString(PREF_SALT, null)
        val pin_ver  = prefs.getString(PREF_VER, null)
        val pin_viv  = prefs.getString(PREF_VIV, null)
        prefs.edit().remove(PREF_SEED).remove("seed_iv").apply()
        try { KeyStore.getInstance("AndroidKeyStore").also{it.load(null)}.deleteEntry(KEY_ALIAS) } catch(e: Exception) {}
    }

    // ── Backup cifrado de wallets ─────────────────────────────────────────────
    /**
     * Exporta TODO lo que el usuario tiene guardado.
     *
     * Antes sólo recorría listWallets(), y saveWallet() —lo único que rellena
     * esa lista— se llama exclusivamente desde importBackup(). Es decir: la
     * lista sólo tenía contenido si ya habías restaurado un backup antes, así
     * que en la práctica exportBackup devolvía null y la app respondía "No hay
     * wallets para exportar" por muchas wallets que tuvieras. La seed principal
     * (saveSeed), los WIF y los watchers no se exportaban nunca.
     */
    fun exportBackup(ctx: Context, pin: String): java.io.File? {
        return try {
            val wallets = listWallets(ctx)
            val mainSeed = loadSeed(ctx)
            val wifs = listWifs(ctx)
            val watchers = listWatchers(ctx)
            if (wallets.isEmpty() && mainSeed == null && wifs.isEmpty() && watchers.isEmpty())
                return null

            val backupData = org.json.JSONArray()
            for ((id, name) in wallets) {
                val seed = loadWalletSeed(ctx, id) ?: continue
                backupData.put(org.json.JSONObject().apply {
                    put("id",   id)
                    put("name", name)
                    put("seed", seed)
                })
            }
            val wifArr = org.json.JSONArray()
            for ((id, wif, meta) in wifs) {
                wifArr.put(org.json.JSONObject().apply {
                    put("id", id); put("wif", wif); put("meta", meta)
                })
            }
            val watchArr = org.json.JSONArray()
            for ((id, addr, label) in watchers) {
                watchArr.put(org.json.JSONObject().apply {
                    put("id", id); put("addr", addr); put("label", label)
                })
            }

            val json = org.json.JSONObject().apply {
                put("version",    2)
                put("app",        "WalletHunter")
                put("created_at", System.currentTimeMillis())
                put("wallets",    backupData)
                if (mainSeed != null) put("main_seed", mainSeed)
                if (wifArr.length() > 0)   put("wifs",     wifArr)
                if (watchArr.length() > 0) put("watchers", watchArr)
            }.toString()

            // Derivar clave del PIN con PBKDF2
            val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val key  = deriveKeyFromPin(pin, salt)

            // Cifrar con AES/GCM
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

            // Formato: [4 salt_len][salt][4 iv_len][iv][encrypted]
            val out = java.io.ByteArrayOutputStream()
            val dos = java.io.DataOutputStream(out)
            dos.writeInt(salt.size);      dos.write(salt)
            dos.writeInt(cipher.iv.size); dos.write(cipher.iv)
            dos.write(encrypted)
            dos.flush()

            // Almacenamiento interno, no externo: el fichero lleva todas las
            // seeds y solo lo protege la contraseña. Se comparte vía FileProvider,
            // que ya cubre files-path en res/xml/file_paths.xml.
            val dir = java.io.File(ctx.filesDir, "backups").also { it.mkdirs() }
            dir.listFiles()?.forEach { it.delete() }   // no acumular exports viejos
            val file = java.io.File(dir, "wh_backup_${System.currentTimeMillis()}.whbak")
            file.writeBytes(out.toByteArray())
            file
        } catch (e: Exception) { null }
    }

    fun importBackup(ctx: Context, pin: String, data: ByteArray): Int {
        return try {
            val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(data))
            val saltLen = dis.readInt()
            val salt    = ByteArray(saltLen).also { dis.readFully(it) }
            val ivLen   = dis.readInt()
            val iv      = ByteArray(ivLen).also { dis.readFully(it) }
            val enc     = dis.readBytes()

            val key = deriveKeyFromPin(pin, salt)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key,
                javax.crypto.spec.GCMParameterSpec(128, iv))
            val json = String(cipher.doFinal(enc), Charsets.UTF_8)

            val root  = org.json.JSONObject(json)
            var count = 0

            // v1 sólo traía "wallets"; v2 añade la seed principal, los WIF y los
            // watchers. Se leen con opt* para seguir aceptando backups antiguos.
            val wallets = root.optJSONArray("wallets") ?: org.json.JSONArray()
            for (i in 0 until wallets.length()) {
                val w = wallets.getJSONObject(i)
                saveWallet(ctx, w.getString("id"), w.getString("name"), w.getString("seed"))
                count++
            }

            root.optString("main_seed", "").takeIf { it.isNotEmpty() }?.let {
                saveSeed(ctx, it); count++
            }

            root.optJSONArray("wifs")?.let { arr ->
                val restored = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Triple(o.optString("id"), o.optString("wif"), o.optString("meta"))
                }.filter { it.second.isNotEmpty() }
                if (restored.isNotEmpty()) {
                    writeWifs(ctx, listWifs(ctx) + restored)
                    count += restored.size
                }
            }

            root.optJSONArray("watchers")?.let { arr ->
                val restored = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Triple(o.optString("id"), o.optString("addr"), o.optString("label"))
                }.filter { it.second.isNotEmpty() }
                if (restored.isNotEmpty()) {
                    // Esta rama guarda los watchers con el formato posicional
                    // ";;" / "~~~"; no existe aquí el escritor en JSON.
                    val all = listWatchers(ctx) + restored
                    ctx.getSharedPreferences("wallet_watch", Context.MODE_PRIVATE).edit()
                        .putString("watch_list",
                            all.joinToString(";;") { "${it.first}~~~${it.second}~~~${it.third}" })
                        .apply()
                    count += restored.size
                }
            }

            count
        } catch (e: Exception) { -1 }
    }

    private fun deriveKeyFromPin(pin: String, salt: ByteArray): javax.crypto.SecretKey {
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)
        val tmp  = factory.generateSecret(spec)
        return javax.crypto.spec.SecretKeySpec(tmp.encoded, "AES")
    }


}
