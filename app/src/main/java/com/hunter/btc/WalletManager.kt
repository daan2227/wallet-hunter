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
    /** La lista de WIF dentro del sobre del código: Keystore por dentro, clave de datos (DEK) por fuera. */
    private const val PREF_WIF_ENC_P = "wif_list_enc_p"
    private const val PREF_WIF_IV_P  = "wif_list_iv_p"
    private const val PREFS_NAME = "wallet_prefs"
    private const val PREF_SEED  = "enc_seed"
    private const val PREF_IV    = "enc_iv"
    private const val PREF_SALT  = "pin_salt"
    private const val PREF_VER   = "pin_verify"
    private const val PREF_VIV   = "pin_verify_iv"
    // Sobre de cada semilla bajo el PIN (ver SeedEnvelope). PREF_SEED/PREF_IV
    // son las de antes: se leen como legado y desaparecen al envolverlas.
    private const val PREF_SEED_P   = "seed_pin"
    private const val PREF_SEED_PIV = "seed_pin_iv"
    private const val PREF_ADDRS = "wallet_addrs"
    private const val PBKDF2_ITER = 100000
    /** Vueltas de PBKDF2 para un código NUEVO (alfanumérico de 8-24). Los antiguos (seis dígitos) siguen con
     *  PBKDF2_ITER: cada código guarda las suyas en PREF_ITER, y sin esa marca es uno antiguo. */
    private const val PBKDF2_ITER_NUEVO = 600000
    private const val PREF_ITER = "pin_iter"

    /**
     * La clave que YA existe, o excepción. Para LEER: crear una nueva en una ruta de lectura sobrescribe
     * el alias si el Keystore devolvió «no existe» por un fallo pasajero, y lo que se cifró con la clave
     * vieja queda irrecuperable. Crear sólo se hace al escribir (getOrCreateKey).
     */
    private fun claveExistente(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        return (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw java.security.KeyStoreException("la clave $alias no está en el Keystore")
    }

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

    /**
     * Clave del PIN de ESTA sesión, sólo en memoria. Es la que abre el sobre de
     * las semillas: sin ella no se lee ni escribe ninguna. La pone checkPin al
     * acertar el PIN y la borra olvidarClaveSemilla al bloquearse la app.
     */
    @Volatile private var claveSemilla: SecretKey? = null

    fun claveSemillaLista(): Boolean = claveSemilla != null

    /**
     * Clave de DATOS: 32 bytes al azar con los que se cierra el baúl de hallazgos y la lista de WIF, sin
     * que dependan del código directamente. Va guardada sellada con la clave del código (PREF_DEK), así
     * que cambiar el código sólo la vuelve a sellar —en el mismo guardado atómico— y el baúl no se toca.
     * Sólo está en memoria con la sesión abierta; sin ella ni root puede abrir esos dos almacenes.
     */
    @Volatile private var claveBoveda: SecretKey? = null
    internal fun claveDeBoveda(): SecretKey? = claveBoveda
    private const val PREF_DEK = "dek_enc"
    private const val PREF_DEK_IV = "dek_iv"

    fun olvidarClaveSemilla() { claveSemilla = null; claveBoveda = null }

    // ── Desbloqueo con huella (opcional, a elección del usuario) ─────────────
    //
    // La clave del código se guarda cifrada con una clave del Keystore que sólo se puede usar tras una
    // huella válida (HuellaDesbloqueo). Con ella activada, la huella abre la sesión igual que el código;
    // sin ella, no hay nada guardado. Cambiar el código la desactiva: lo guardado era la clave anterior.

    internal const val ALIAS_HUELLA = "wh_bio_v1"
    private const val PREF_HUELLA_ENC = "bio_key_enc"
    private const val PREF_HUELLA_IV = "bio_key_iv"

    fun huellaActivada(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.contains(PREF_HUELLA_ENC) && p.contains(PREF_HUELLA_IV)
    }

    /** Los bytes de la clave del código de esta sesión (para sellarlos con la huella); null si no hay sesión. */
    internal fun claveSemillaBytes(): ByteArray? = claveSemilla?.encoded

    internal fun huellaGuardar(ctx: Context, enc: ByteArray, iv: ByteArray): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_HUELLA_ENC, b64e(enc)).putString(PREF_HUELLA_IV, b64e(iv)).commit()

    internal fun huellaClave(ctx: Context): Pair<ByteArray, ByteArray>? {
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val e = p.getString(PREF_HUELLA_ENC, null) ?: return null
        val i = p.getString(PREF_HUELLA_IV, null) ?: return null
        return try { b64d(e) to b64d(i) } catch (x: IllegalArgumentException) {
            RegistroFallos.anotar("WalletManager: datos de huella corruptos", x); null
        }
    }

    fun huellaDesactivar(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(PREF_HUELLA_ENC).remove(PREF_HUELLA_IV).commit()
        try { KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }.deleteEntry(ALIAS_HUELLA) }
        catch (e: Exception) { android.util.Log.w("WalletManager", "bio key delete: ${e.javaClass.simpleName}") }
    }

    /**
     * Abre la sesión con la clave del código que devolvió la huella. Se comprueba contra el verificador
     * guardado (igual que el código): una clave que no es la vigente no entra. Respeta la espera por intentos.
     */
    fun entrarConClave(ctx: Context, raw: ByteArray): Boolean {
        if (esperaPin(ctx) > 0 || bioBloqueada(ctx)) return false
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enc = b64d(prefs.getString(PREF_VER, null) ?: return false)
        val iv = b64d(prefs.getString(PREF_VIV, null) ?: return false)
        val clave = SecretKeySpec(raw, "AES")
        val dec = aesDecrypt(clave, enc, iv) ?: return false
        if (String(dec) != "wallet_ok") return false
        claveSemilla = clave
        abrirBoveda(ctx, clave)
        return true
    }

    /** Abre la clave de datos con la clave del código y la deja en memoria; luego pasa lo pendiente a los almacenes. */
    private fun abrirBoveda(ctx: Context, clave: SecretKey) {
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val de = p.getString(PREF_DEK, null); val di = p.getString(PREF_DEK_IV, null)
        val dek = if (de != null && di != null) SeedEnvelope.abrir(clave, b64d(de), b64d(di)) else null
        claveBoveda = dek?.let { SecretKeySpec(it, "AES") }
        if (claveBoveda == null) return
        // Migra lo que aún estuviera sólo bajo el Keystore y recoge el buzón. Un fallo aquí no impide entrar.
        try { MatchVault.alDesbloquear(ctx) } catch (e: Throwable) {
            android.util.Log.e("WalletManager", "vault unlock hook: ${e.javaClass.simpleName}") }
        try { alDesbloquearWifs(ctx) } catch (e: Throwable) {
            android.util.Log.e("WalletManager", "WIF unlock hook: ${e.javaClass.simpleName}") }
    }

    private fun b64e(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /* Deriva clave AES-256 del PIN usando PBKDF2 */
    private fun pinToKey(pin: String, salt: ByteArray, iter: Int = PBKDF2_ITER): SecretKey {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iter, 256)
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
        // distinctBy: las repetidas que ya estuvieran guardadas se quedan en una
        // al leer, y desaparecen del disco en la siguiente escritura.
        return raw.split(";;").mapNotNull {
            val p = it.split("~~~")
            if (p.size == 3) Triple(p[0], p[1], p[2]) else null
        }.distinctBy { it.second }
    }

    /**
     * Con código puesto, la lista va en el SOBRE del código (Keystore por dentro, clave de datos por fuera) y
     * escribirla exige la sesión abierta: lanza IllegalStateException si está cerrada. Sin código, sólo Keystore.
     * `conservarAntigua`: durante la migración no se borra la copia vieja hasta comprobar la nueva.
     */
    private fun writeWifs(ctx: Context, list: List<Triple<String,String,String>>, conservarAntigua: Boolean = false) {
        val prefs = ctx.getSharedPreferences(WIF_PREFS, Context.MODE_PRIVATE)
        if (list.isEmpty()) { prefs.edit().clear().apply(); return }
        val conPin = hasPin(ctx)
        val dek = if (conPin) (claveBoveda ?: throw IllegalStateException("WIF list locked")) else null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(WIF_KEY_ALIAS))
        val enc = cipher.doFinal(serializeWifs(list).toByteArray(Charsets.UTF_8))
        val ed = prefs.edit()
        if (dek != null) {
            val (sellado, iv) = SeedEnvelope.sellar(dek, SeedEnvelope.empaquetar(cipher.iv, enc))
            ed.putString(PREF_WIF_ENC_P, Base64.encodeToString(sellado, Base64.NO_WRAP))
              .putString(PREF_WIF_IV_P,  Base64.encodeToString(iv, Base64.NO_WRAP))
            if (!conservarAntigua) ed.remove(PREF_WIF_ENC).remove(PREF_WIF_IV)
        } else {
            ed.putString(PREF_WIF_ENC, Base64.encodeToString(enc, Base64.NO_WRAP))
              .putString(PREF_WIF_IV,  Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        ed.remove(PREF_WIF_PLAIN).commit()
    }

    /** ¿Hay alguna WIF guardada? Sin descifrar nada: vale con la sesión cerrada. */
    fun hayWifs(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(WIF_PREFS, Context.MODE_PRIVATE)
        return p.contains(PREF_WIF_ENC_P) || p.contains(PREF_WIF_ENC) || (p.getString(PREF_WIF_PLAIN, "") ?: "").isNotEmpty()
    }

    /** La lista de WIF está cerrada: hay WIF y falta la sesión del código para abrirla. */
    fun wifsBloqueadas(ctx: Context): Boolean = hasPin(ctx) && claveBoveda == null && hayWifs(ctx)

    /**
     * Al abrir la sesión: si la lista de WIF aún estaba sólo bajo el Keystore (instalación anterior a este sobre), se
     * pasa al sobre. Primero se escribe la nueva SIN borrar la vieja, se vuelve a leer y se compara; sólo si coincide se
     * borra la vieja. Un fallo deja la lista como estaba.
     */
    private fun alDesbloquearWifs(ctx: Context) {
        if (!hasPin(ctx) || claveBoveda == null) return
        val prefs = ctx.getSharedPreferences(WIF_PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(PREF_WIF_ENC_P) || !prefs.contains(PREF_WIF_ENC)) return
        val lista = synchronized(this) { leerWifsAntiguaConSesion(ctx) } ?: return
        synchronized(this) {
            writeWifs(ctx, lista, conservarAntigua = true)
            val vuelta = leerWifs(ctx)
            if (vuelta != null && serializeWifs(vuelta) == serializeWifs(lista)) {
                prefs.edit().remove(PREF_WIF_ENC).remove(PREF_WIF_IV).commit()
            } else {
                android.util.Log.e("WalletManager", "WIF migration did not verify: the old list stays")
                prefs.edit().remove(PREF_WIF_ENC_P).remove(PREF_WIF_IV_P).commit()
            }
        }
    }

    /** Descifra la copia antigua (sólo Keystore) de la lista de WIF. null si no se puede. */
    private fun leerWifsAntiguaConSesion(ctx: Context): List<Triple<String,String,String>>? {
        val prefs = ctx.getSharedPreferences(WIF_PREFS, Context.MODE_PRIVATE)
        val e = prefs.getString(PREF_WIF_ENC, null) ?: return null
        val i = prefs.getString(PREF_WIF_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, claveExistente(WIF_KEY_ALIAS), GCMParameterSpec(128, Base64.decode(i, Base64.NO_WRAP)))
            parseWifs(String(cipher.doFinal(Base64.decode(e, Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (x: Exception) { null }
    }

    /**
     * @return true si la clave queda en la lista (también si ya estaba).
     */
    @Synchronized
    fun saveWif(ctx: Context, wif: String, addr: String, name: String = "WIF Wallet",
                origen: String? = null): Boolean {
        val lista = listWifs(ctx)
        // listWifs() devuelve vacío también cuando NO puede descifrar. Una lista
        // vacía de verdad no deja nada guardado (writeWifs limpia), así que si
        // hay algo cifrado y sale vacía es un fallo del Keystore: escribir
        // encima borraría todas las claves que hay.
        if (lista.isEmpty() && hayWifs(ctx)) return false
        // La misma clave dos veces es la misma cartera dos veces. Pasaba: abrir
        // un hallazgo del puzzle la guardaba cada vez, y la lista acababa con
        // entradas idénticas sin forma de saber que eran la misma.
        if (lista.any { it.second == wif }) return true
        val id = "wif_${System.currentTimeMillis()}"
        val n = limpiarNombre(name).ifEmpty { "WIF Wallet" }
        // Con la sesión cerrada no se puede escribir la lista: el que llama (p. ej. «añadir hallazgos a la cartera»)
        // lo trata como un fallo y lo reintenta al desbloquear.
        try { writeWifs(ctx, lista + Triple(id, wif, "$addr|$n")) } catch (e: IllegalStateException) { return false }
        if (origen != null) setOrigen(ctx, id, origen)
        return true
    }

    fun listWifs(ctx: Context): List<Triple<String,String,String>> = leerWifs(ctx) ?: emptyList()

    /**
     * Las WIF guardadas, o null si HAY algo cifrado pero no se ha podido abrir.
     *
     * listWifs() devuelve vacío en los dos casos, y quien la usaba para leer,
     * cambiar y volver a escribir (borrar, renombrar, restaurar) escribía una
     * lista vacía encima de todas las claves tras un solo fallo del Keystore.
     * Todo lo que reescribe la lista usa esta, y con null no toca nada.
     */
    private fun leerWifs(ctx: Context): List<Triple<String,String,String>>? {
        val prefs = ctx.getSharedPreferences(WIF_PREFS, Context.MODE_PRIVATE)
        val conPin = hasPin(ctx)
        val dek = if (conPin) claveBoveda else null
        // Dentro del sobre del código: sin sesión no se abre (null = «cerrada», que quien lee ya trata como
        // «no se pudo abrir» y no reescribe nada).
        val encP = prefs.getString(PREF_WIF_ENC_P, null); val ivP = prefs.getString(PREF_WIF_IV_P, null)
        if (encP != null && ivP != null) {
            if (dek == null) return null
            return try {
                val par = SeedEnvelope.abrir(dek, Base64.decode(encP, Base64.NO_WRAP), Base64.decode(ivP, Base64.NO_WRAP))
                    ?.let { SeedEnvelope.desempaquetar(it) } ?: return null
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, claveExistente(WIF_KEY_ALIAS), GCMParameterSpec(128, par.first))
                parseWifs(String(cipher.doFinal(par.second), Charsets.UTF_8))
            } catch (e: Exception) {
                android.util.Log.e("WalletManager", "could not open the sealed WIF list: ${e.javaClass.simpleName}")
                null
            }
        }
        val encB64 = prefs.getString(PREF_WIF_ENC, null)
        // Con código puesto y la sesión cerrada, la copia antigua (sólo Keystore) tampoco se enseña.
        if (encB64 != null && conPin && dek == null) return null
        if (encB64 != null) {
            val ivB64 = prefs.getString(PREF_WIF_IV, null) ?: return null
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, claveExistente(WIF_KEY_ALIAS),
                    GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
                parseWifs(String(cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP)), Charsets.UTF_8))
            } catch (e: Exception) {
                android.util.Log.e("WalletManager", "could not open the WIF list: ${e.javaClass.simpleName}")
                null
            }
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
    @Synchronized
    fun removeWif(ctx: Context, id: String) {
        val lista = leerWifs(ctx) ?: return   // sin poder leer, no se reescribe nada
        writeWifs(ctx, lista.filter { it.first != id })
        borrarOrigen(ctx, id)
    }
    // Legacy single WIF support
    fun loadWif(ctx: Context): Pair<String,String>? {
        val list = listWifs(ctx)
        if (list.isEmpty()) return null
        val last = list.last()
        val addr = last.third.split("|").firstOrNull() ?: ""
        return Pair(last.second, addr)
    }

    // Watcher wallets: watch-only by address
    @Synchronized
    fun saveWatcher(ctx: Context, addr: String, label: String, origen: String? = null) {
        if (listWatchers(ctx).any { it.second == addr }) return
        val id = "watch_${System.currentTimeMillis()}"
        if (origen != null) setOrigen(ctx, id, origen)
        val prefs = ctx.getSharedPreferences("wallet_watch", Context.MODE_PRIVATE)
        val raw = prefs.getString("watch_list", "") ?: ""
        val list = if (raw.isEmpty()) mutableListOf() else raw.split(";;").toMutableList()
        list.add("$id~~~$addr~~~${limpiarNombre(label).ifEmpty { "Watch" }}")
        prefs.edit().putString("watch_list", list.joinToString(";;")).apply()
    }
    fun listWatchers(ctx: Context): List<Triple<String,String,String>> {
        val raw = ctx.getSharedPreferences("wallet_watch", Context.MODE_PRIVATE).getString("watch_list", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";;").mapNotNull {
            val p = it.split("~~~")
            if (p.size == 3) Triple(p[0], p[1], p[2]) else null
        }.distinctBy { it.second }
    }
    @Synchronized
    fun removeWatcher(ctx: Context, id: String) {
        borrarOrigen(ctx, id)
        val list = listWatchers(ctx).filter { it.first != id }
        ctx.getSharedPreferences("wallet_watch", Context.MODE_PRIVATE).edit()
            .putString("watch_list", list.joinToString(";;") { "${it.first}~~~${it.second}~~~${it.third}" }).apply()
    }

    /*
     * Semilla principal, en dos capas: dentro el Keystore (como antes, no sale
     * del chip) y fuera el sobre con la clave del PIN de la sesión. Sin esa
     * clave no se escribe ni se lee nada: ver claveSemilla.
     */
    fun saveSeed(ctx: Context, mnemonic: String) {
        val k = claveSemilla ?: throw IllegalStateException("Unlock with the PIN before saving a seed")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val enc = cipher.doFinal(mnemonic.toByteArray(Charsets.UTF_8))
        val (e2, i2) = SeedEnvelope.sellar(k, SeedEnvelope.empaquetar(cipher.iv, enc))
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_SEED_P, b64e(e2))
            .putString(PREF_SEED_PIV, b64e(i2))
            .remove(PREF_SEED).remove(PREF_IV)
            .commit()
    }

    fun loadSeed(ctx: Context): String? {
        val k = claveSemilla ?: return null
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val inner = innerDe(prefs, k, PREF_SEED_P, PREF_SEED_PIV, PREF_SEED, PREF_IV) ?: return null
        val (iv, enc) = SeedEnvelope.desempaquetar(inner) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, claveExistente(KEY_ALIAS), GCMParameterSpec(128, iv))
            String(cipher.doFinal(enc), Charsets.UTF_8)
        } catch(e: Exception) { null }
    }

    /** Blob interior de una semilla: abre el sobre, o usa el formato de antes. */
    private fun innerDe(prefs: android.content.SharedPreferences, k: SecretKey,
                        encK: String, ivK: String, legE: String, legI: String): ByteArray? {
        val pe = prefs.getString(encK, null); val pi = prefs.getString(ivK, null)
        if (pe != null && pi != null) return SeedEnvelope.abrir(k, b64d(pe), b64d(pi))
        val le = prefs.getString(legE, null) ?: return null
        val li = prefs.getString(legI, null) ?: return null
        return SeedEnvelope.empaquetar(b64d(li), b64d(le))
    }

    fun hasSeed(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.contains(PREF_SEED_P) || p.contains(PREF_SEED)
    }

    /* PIN: guarda salt + texto de verificacion cifrado con clave derivada del PIN
       Si el PIN es incorrecto PBKDF2 genera clave diferente -> AES falla -> checkPin devuelve false.
       OJO: esto NO impide un ataque de fuerza bruta offline si alguien saca estos SharedPreferences
       (root, copia ADB): el propio texto cifrado funciona como un "hash" que se puede probar candidata
       a candidata sin el telefono. Lo que de verdad lo hace caro es PoliticaPin (8-24 caracteres, 600 000
       vueltas de PBKDF2), no la ausencia de un hash explicito. */
    /**
     * Fija o cambia el PIN. Las semillas se vuelven a cerrar con la clave nueva
     * en el MISMO commit que el PIN: o cambian las dos cosas, o ninguna. false si
     * no se pudo (con un PIN previo, hace falta la clave vigente para reabrirlas).
     */
    fun savePin(ctx: Context, pin: String): Boolean {
        // La política también aquí, no sólo en la pantalla: nada de fijar un código de seis dígitos por otra vía.
        if (!PoliticaPin.vale(pin)) return false
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hayPin = hasPin(ctx)
        val antigua = claveSemilla
        if (hayPin && antigua == null) return false
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val nueva = pinToKey(pin, salt, PBKDF2_ITER_NUEVO)
        val (enc, iv) = aesEncrypt(nueva, "wallet_ok".toByteArray())
        val ed = prefs.edit()
        if (!reenvolver(ctx, if (hayPin) antigua else null, nueva, ed)) return false
        ed.putString(PREF_SALT, b64e(salt))
          .putInt(PREF_ITER, PBKDF2_ITER_NUEVO)
          .putString(PREF_VER,  b64e(enc))
          .putString(PREF_VIV,  b64e(iv))
          // Un PIN nuevo empieza sin fallos a cuestas (ni veto de huella).
          .remove(PREF_FALLOS).remove(PREF_ESPERA_INI).remove(PREF_ESPERA_BOOT).remove(PREF_ESPERA_FIN_OLD).remove(PREF_ESPERA_MS).remove(PREF_BIO_BLOQ)
        if (!ed.commit()) return false
        // Lo que la huella guardaba era la clave del código anterior: ya no sirve.
        if (huellaActivada(ctx)) huellaDesactivar(ctx)
        claveSemilla = nueva
        abrirBoveda(ctx, nueva)
        return true
    }

    /**
     * Pone cada semilla bajo la clave `nueva`. Con sobre previo lo abre con
     * `antigua` y lo vuelve a cerrar; sin `antigua` da por hecho que ya va con la
     * vigente (checkPin). Las que sólo tienen Keystore se envuelven ahora.
     * Escribe en `ed` sin confirmar: el que llama decide. false si una semilla no
     * se puede abrir con `antigua`; entonces no debe confirmarse nada.
     */
    private fun reenvolver(ctx: Context, antigua: SecretKey?, nueva: SecretKey,
                           ed: android.content.SharedPreferences.Editor): Boolean {
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ranuras = mutableListOf(Ranura(PREF_SEED_P, PREF_SEED_PIV, PREF_SEED, PREF_IV))
        listWallets(ctx).forEach { w ->
            ranuras.add(Ranura("seed_pin_${w.first}", "seed_pin_iv_${w.first}",
                               "seed_enc_${w.first}", "seed_iv_${w.first}"))
        }
        for (r in ranuras) {
            val pe = p.getString(r.encK, null); val pi = p.getString(r.ivK, null)
            if (pe != null && pi != null) {
                if (antigua == null) continue                 // ya va con la clave vigente
                val inner = SeedEnvelope.abrir(antigua, b64d(pe), b64d(pi)) ?: return false
                val (e2, i2) = SeedEnvelope.sellar(nueva, inner)
                ed.putString(r.encK, b64e(e2)).putString(r.ivK, b64e(i2))
            } else {
                val le = p.getString(r.legE, null) ?: continue
                val li = p.getString(r.legI, null) ?: continue
                val (e2, i2) = SeedEnvelope.sellar(nueva, SeedEnvelope.empaquetar(b64d(li), b64d(le)))
                ed.putString(r.encK, b64e(e2)).putString(r.ivK, b64e(i2))
                ed.remove(r.legE).remove(r.legI)
            }
        }
        // La clave de datos: se vuelve a sellar con la clave nueva EN ESTE MISMO guardado (el del cambio de código),
        // o se crea si aún no existía (primer código, o instalación anterior a este sobre).
        val de = p.getString(PREF_DEK, null); val di = p.getString(PREF_DEK_IV, null)
        if (de != null && di != null) {
            if (antigua != null) {
                val dek = SeedEnvelope.abrir(antigua, b64d(de), b64d(di)) ?: return false
                val (e2, i2) = SeedEnvelope.sellar(nueva, dek)
                ed.putString(PREF_DEK, b64e(e2)).putString(PREF_DEK_IV, b64e(i2))
            }
        } else {
            val dek = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val (e2, i2) = SeedEnvelope.sellar(nueva, dek)
            ed.putString(PREF_DEK, b64e(e2)).putString(PREF_DEK_IV, b64e(i2))
        }
        return true
    }

    private data class Ranura(val encK: String, val ivK: String, val legE: String, val legI: String)

    // ── Perfil local (cifrado) ──────────────────────────────────────────────
    // Por privacidad, lo ÚNICO que se guarda del usuario es un nombre, y va
    // cifrado con una clave del Keystore (hardware). Nada de red, nada más.
    private const val PROFILE_ALIAS = "hunter_profile_key"
    private const val PREF_USER_C  = "perfil_user_c"
    private const val PREF_USER_IV = "perfil_user_iv"

    /** Nombre de usuario (descifrado), o "" si no hay. */
    fun getUsername(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val c  = p.getString(PREF_USER_C,  null) ?: return ""
        val iv = p.getString(PREF_USER_IV, null) ?: return ""
        return try {
            val dec = aesDecrypt(claveExistente(PROFILE_ALIAS),
                Base64.decode(c, Base64.NO_WRAP), Base64.decode(iv, Base64.NO_WRAP)) ?: return ""
            String(dec, Charsets.UTF_8)
        } catch (e: Throwable) {
            RegistroFallos.anotar("WalletManager.getUsername", e); ""
        }
    }

    /** Guarda el nombre de usuario cifrado con Keystore. Vacío = lo borra. */
    fun setUsername(ctx: Context, nombre: String) {
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (nombre.isBlank()) { p.edit().remove(PREF_USER_C).remove(PREF_USER_IV).apply(); return }
        val (enc, iv) = aesEncrypt(getOrCreateKey(PROFILE_ALIAS), nombre.trim().toByteArray(Charsets.UTF_8))
        p.edit()
            .putString(PREF_USER_C,  Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(PREF_USER_IV, Base64.encodeToString(iv,  Base64.NO_WRAP))
            .apply()
    }

    // ── Intentos de PIN ──────────────────────────────────────────────────
    //
    // No había ningún límite. Son seis cifras, un millón de combinaciones, y
    // cada intento cuesta una fracción de segundo: se podía automatizar sin
    // freno. Y el PIN es lo único entre quien tenga el móvil desbloqueado y
    // "Show seed / WIF".
    //
    // Tras 5 fallos seguidos, una espera de 30 s que se dobla con cada fallo
    // más, hasta una hora. Va aquí, dentro de checkPin, y no en las pantallas:
    // son tres las que piden el PIN, y una que se olvidara de mirarlo dejaría
    // la puerta abierta. Se guarda en disco, así que cerrar la app no borra la
    // cuenta.

    private const val PREF_FALLOS = "pin_fallos"
    private const val PREF_ESPERA_INI  = "pin_espera_ini"   // reloj monotónico al empezar
    private const val PREF_ESPERA_BOOT = "pin_espera_boot"  // número de arranque al empezar
    private const val PREF_ESPERA_MS   = "pin_espera_ms"    // duración de la espera
    private const val PREF_ESPERA_FIN_OLD = "pin_espera_fin" // de la versión anterior
    private const val PREF_BIO_BLOQ = "pin_bio_bloqueada"
    private const val FALLOS_LIBRES = 5

    /**
     * ¿Está la huella vetada por haber saltado el límite de intentos?
     *
     * Se activa en cuanto empieza la espera por demasiados fallos y NO se
     * quita cuando la cuenta atrás llega a cero: sólo la reactiva un PIN
     * correcto. Así, quien ha agotado los intentos no puede saltarse la espera
     * ni, al acabar, volver a entrar con la huella sin demostrar primero que
     * sabe el código. Va en disco, así que cerrar la app no la reactiva.
     */
    fun bioBloqueada(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_BIO_BLOQ, false)

    /** Segundos que faltan para poder volver a intentarlo; 0 si ya se puede. Ver EsperaPin. */
    fun esperaPin(ctx: Context): Long {
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ms = p.getLong(PREF_ESPERA_MS, 0L)
        if (ms <= 0) return 0
        val ini = if (p.contains(PREF_ESPERA_INI)) p.getLong(PREF_ESPERA_INI, 0L) else null
        val ahora = android.os.SystemClock.elapsedRealtime()
        val arranque = numeroArranque(ctx)
        val (resto, ancla) = EsperaPin.resto(ms, ini, p.getInt(PREF_ESPERA_BOOT, -1), ahora, arranque)
        // Otro arranque (o una espera de la versión anterior): vuelve a contar desde ahora.
        if (ancla != null) p.edit().putLong(PREF_ESPERA_INI, ancla).putInt(PREF_ESPERA_BOOT, arranque)
            .remove(PREF_ESPERA_FIN_OLD).apply()
        // Terminada: se olvida, para que un reinicio posterior no la vuelva a
        // poner entera. Los fallos se quedan: el siguiente error espera el doble.
        else if (resto <= 0) p.edit().remove(PREF_ESPERA_MS).remove(PREF_ESPERA_INI)
            .remove(PREF_ESPERA_BOOT).apply()
        return EsperaPin.segundos(resto)
    }

    /** Cuántas veces ha arrancado el móvil (Android 7+); -1 si no se puede saber. */
    private fun numeroArranque(ctx: Context): Int = try {
        android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
    } catch (e: Throwable) { -1 }

    /**
     * El mensaje para después de un PIN rechazado: cuánto hay que esperar, o
     * cuántos intentos quedan antes de la espera, o sólo que está mal.
     */
    fun avisoPinFallido(ctx: Context): String {
        val espera = esperaPin(ctx)
        if (espera > 0) {
            val cuanto = if (espera >= 60) "${(espera + 59) / 60} min" else "$espera s"
            return "Too many attempts. Try again in $cuanto."
        }
        val fallos = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(PREF_FALLOS, 0)
        val quedan = FALLOS_LIBRES - fallos
        return if (quedan in 1..2) "Wrong code. $quedan more before a wait."
               else "Wrong code. Try again."
    }

    fun checkPin(ctx: Context, pin: String): Boolean {
        // Durante la espera no se comprueba nada: ni el PIN correcto entra.
        // Si no, la espera sería sólo un aviso.
        if (esperaPin(ctx) > 0) return false
        val clave = claveDelPin(ctx, pin)
        val ok = clave != null
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (clave != null) {
            // El PIN correcto es lo ÚNICO que vuelve a habilitar la huella, y lo
            // que abre las semillas de esta sesión. Aprovecha para envolver las
            // que aún estén sólo con el Keystore (las de antes del PIN).
            claveSemilla = clave
            val ed = prefs.edit().remove(PREF_FALLOS).remove(PREF_ESPERA_INI).remove(PREF_ESPERA_BOOT).remove(PREF_ESPERA_FIN_OLD).remove(PREF_ESPERA_MS).remove(PREF_BIO_BLOQ)
            reenvolver(ctx, null, clave, ed)
            ed.commit()
            abrirBoveda(ctx, clave)
        } else {
            val fallos = prefs.getInt(PREF_FALLOS, 0) + 1
            val ed = prefs.edit().putInt(PREF_FALLOS, fallos)
            if (fallos >= FALLOS_LIBRES) {
                val seg = (30L shl (fallos - FALLOS_LIBRES).coerceAtMost(7)).coerceAtMost(3600L)
                ed.putLong(PREF_ESPERA_INI, android.os.SystemClock.elapsedRealtime())
                ed.putInt(PREF_ESPERA_BOOT, numeroArranque(ctx))
                ed.putLong(PREF_ESPERA_MS, seg * 1000)
                // Al saltar el límite, la huella queda vetada hasta un PIN correcto.
                ed.putBoolean(PREF_BIO_BLOQ, true)
            }
            // commit y no apply: matar el proceso justo después de un fallo perdía
            // la cuenta, y con ella el límite de intentos.
            ed.commit()
        }
        return ok
    }

    /** La clave del PIN si es el correcto, o null. Es la que abre las semillas. */
    private fun claveDelPin(ctx: Context, pin: String): SecretKey? {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val salt = b64d(prefs.getString(PREF_SALT, null) ?: return null)
        val enc  = b64d(prefs.getString(PREF_VER,  null) ?: return null)
        val iv   = b64d(prefs.getString(PREF_VIV,  null) ?: return null)
        val key  = pinToKey(pin, salt, prefs.getInt(PREF_ITER, PBKDF2_ITER))
        val dec  = aesDecrypt(key, enc, iv) ?: return null
        return if (String(dec) == "wallet_ok") key else null
    }

    /* encryptData()/decryptData() vivían aquí para el exportador de matches de
       MainActivity, que escribía wh_backup_<fecha>.enc y lo compartía. Nunca
       hubo importador —decryptData() no lo llamaba nadie— así que el formato
       era de ida: se podía guardar y no recuperar. Los hallazgos viajan ahora
       en la copia normal, que sí se restaura, y ese exportador ya no existe. */

    /** ¿El código guardado es del formato antiguo (seis dígitos, 100 000 vueltas)? Tiene que cambiarse. */
    fun pinAntiguo(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.contains(PREF_SALT) && !p.contains(PREF_ITER)
    }

    fun hasPin(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(PREF_SALT)


    fun loadAddresses(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_ADDRS, null)


    /* -- MULTI-WALLET -- */
    private const val PREF_WALLET_LIST = "wallet_list"

    // ── Nombres ──────────────────────────────────────────────────────────
    //
    // Cada tipo de cartera ya guardaba un nombre, pero no había forma de
    // ponerlo ni de cambiarlo: las WIF se llamaban todas "WIF Wallet" y la
    // lista era una columna de entradas iguales.

    private const val PREF_MAIN_NAME = "main_wallet_name"

    /**
     * Un nombre que no rompa el formato en que se guardan las listas: las de
     * carteras separan con '|' y ':', las de WIF y vigiladas con '~~~' y ';;'.
     * Un nombre con cualquiera de esos partiría la entrada en dos al leerla.
     */
    fun limpiarNombre(n: String): String =
        n.replace(Regex("[|:~;\\r\\n]"), " ").trim().take(32)

    fun mainName(ctx: Context): String =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_MAIN_NAME, null)?.takeIf { it.isNotBlank() } ?: "Main wallet"

    fun renameMain(ctx: Context, name: String) {
        val n = limpiarNombre(name); if (n.isEmpty()) return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_MAIN_NAME, n).apply()
    }

    @Synchronized
    fun renameWallet(ctx: Context, id: String, name: String) {
        val n = limpiarNombre(name); if (n.isEmpty()) return
        val list = listWallets(ctx).map { if (it.first == id) Pair(it.first, n) else it }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_WALLET_LIST, list.joinToString("|") { "${it.first}:${it.second}" })
            .apply()
    }

    @Synchronized
    fun renameWif(ctx: Context, id: String, name: String) {
        val n = limpiarNombre(name); if (n.isEmpty()) return
        val lista = leerWifs(ctx) ?: return
        writeWifs(ctx, lista.map {
            if (it.first == id) Triple(it.first, it.second, "${it.third.substringBefore('|')}|$n")
            else it
        })
    }

    @Synchronized
    fun renameWatcher(ctx: Context, id: String, label: String) {
        val n = limpiarNombre(label); if (n.isEmpty()) return
        val list = listWatchers(ctx).map { if (it.first == id) Triple(it.first, it.second, n) else it }
        ctx.getSharedPreferences("wallet_watch", Context.MODE_PRIVATE).edit()
            .putString("watch_list", list.joinToString(";;") { "${it.first}~~~${it.second}~~~${it.third}" })
            .apply()
    }

    fun listWallets(ctx: Context): List<Pair<String,String>> {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_WALLET_LIST, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("|").mapNotNull {
            val parts = it.split(":")
            if (parts.size == 2) Pair(parts[0], parts[1]) else null
        }
    }

    @Synchronized
    fun saveWallet(ctx: Context, id: String, name: String, mnemonic: String) {
        val k = claveSemilla ?: throw IllegalStateException("Unlock with the PIN before saving a seed")
        // Keystore propio de la cartera (como antes), y encima el sobre del PIN.
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
        val (e2, i2) = SeedEnvelope.sellar(k, SeedEnvelope.empaquetar(cipher.iv, enc))
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.putString("seed_pin_$id", b64e(e2))
        prefs.putString("seed_pin_iv_$id", b64e(i2))
        prefs.remove("seed_enc_$id").remove("seed_iv_$id")
        // Add to list
        val list = listWallets(ctx).toMutableList()
        if (list.none { it.first == id }) list.add(Pair(id, name))
        prefs.putString(PREF_WALLET_LIST, list.joinToString("|") { "${it.first}:${it.second}" })
        prefs.commit()
    }

    fun loadWalletSeed(ctx: Context, id: String): String? {
        val k = claveSemilla ?: return null
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val inner = innerDe(prefs, k, "seed_pin_$id", "seed_pin_iv_$id", "seed_enc_$id", "seed_iv_$id") ?: return null
        val (iv, enc) = SeedEnvelope.desempaquetar(inner) ?: return null
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            val key = (ks.getEntry("hunter_wallet_$id", null) as KeyStore.SecretKeyEntry).secretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(enc), Charsets.UTF_8)
        } catch(e: Exception) { null }
    }



    @Synchronized
    fun deleteWallet(ctx: Context, id: String) {
        borrarOrigen(ctx, id)
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.remove("seed_enc_$id"); prefs.remove("seed_iv_$id")
        prefs.remove("seed_pin_$id"); prefs.remove("seed_pin_iv_$id")
        val list = listWallets(ctx).filter { it.first != id }
        prefs.putString(PREF_WALLET_LIST, list.joinToString("|") { "${it.first}:${it.second}" })
        prefs.commit()
        try { KeyStore.getInstance("AndroidKeyStore").also{it.load(null)}.deleteEntry("hunter_wallet_$id") } catch(e: Exception) {}
    }

    // ── Añadir una seed sin pisar nada ──────────────────────────────────

    data class SeedGuardada(val id: String, val nombre: String, val yaEstaba: Boolean)

    /**
     * Guarda una seed: de principal si no hay ninguna, y al lado si ya la hay.
     * Si ya está guardada no la duplica y devuelve la que hay.
     *
     * Antes cada sitio que guardaba una seed lo resolvía a su manera, y dos
     * lo hacían mal: añadir una desde la cartera y "Save wallet" de Recovery
     * llamaban a saveSeed, que escribe la PRINCIPAL. Con una principal ya
     * guardada, la sustituían y la anterior se perdía.
     *
     * @param id el que tendrá si se guarda al lado; "" es la principal.
     */
    @Synchronized
    fun agregarSeed(ctx: Context, frase: String, nombreEscrito: String, origen: String?): SeedGuardada {
        val mn = Frase.canonica(frase)
        val nombre = limpiarNombre(nombreEscrito)
        if (!hasSeed(ctx)) {
            saveSeed(ctx, mn)
            if (nombre.isNotEmpty()) renameMain(ctx, nombre)
            if (origen != null) setOrigen(ctx, CLAVE_PRINCIPAL, origen)
            return SeedGuardada("", mainName(ctx), false)
        }
        if (loadSeed(ctx) == mn) return SeedGuardada("", mainName(ctx), true)
        listWallets(ctx).firstOrNull { loadWalletSeed(ctx, it.first) == mn }?.let {
            return SeedGuardada(it.first, it.second, true)
        }
        val id = "w${System.currentTimeMillis()}"
        val n = nombre.ifEmpty { "Wallet ${listWallets(ctx).size + 2}" }
        saveWallet(ctx, id, n, mn)
        if (origen != null) setOrigen(ctx, id, origen)
        return SeedGuardada(id, n, false)
    }

    /** Todas las seeds guardadas, la principal incluida. */
    fun todasLasSeeds(ctx: Context): Set<String> {
        val r = HashSet<String>()
        loadSeed(ctx)?.let { r.add(it) }
        listWallets(ctx).forEach { w -> loadWalletSeed(ctx, w.first)?.let { r.add(it) } }
        return r
    }

    // ── De dónde viene cada cartera ─────────────────────────────────────
    //
    // La lista enseñaba el tipo (seed, WIF, vigilada) pero no el origen: una
    // WIF que puso el usuario y una que encontró el puzzle se veían igual.
    // Se guarda aparte, por identificador, para no tocar el formato de las
    // listas —que ya tienen datos en móviles—: las carteras de antes
    // simplemente no tienen origen y se enseñan sin él.

    private const val ORIGEN_PREFS = "wallet_origen"

    /** La clave de la seed principal, que no tiene id propio. */
    const val CLAVE_PRINCIPAL = "main"

    const val O_CREADA    = "created"
    const val O_IMPORTADA = "imported"
    const val O_BACKUP    = "backup"

    fun setOrigen(ctx: Context, clave: String, origen: String) {
        ctx.getSharedPreferences(ORIGEN_PREFS, Context.MODE_PRIVATE).edit()
            .putString(clave, origen).apply()
    }

    fun origen(ctx: Context, clave: String): String? =
        ctx.getSharedPreferences(ORIGEN_PREFS, Context.MODE_PRIVATE).getString(clave, null)

    private fun borrarOrigen(ctx: Context, clave: String) {
        ctx.getSharedPreferences(ORIGEN_PREFS, Context.MODE_PRIVATE).edit()
            .remove(clave).apply()
    }

    private fun origenes(ctx: Context): Map<String, String> =
        ctx.getSharedPreferences(ORIGEN_PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    /** Cómo se dice en pantalla. null si no se sabe (carteras de antes). */
    fun textoOrigen(origen: String?): String? = when (origen) {
        O_CREADA    -> "Created in this app"
        O_IMPORTADA -> "Added by you"
        O_BACKUP    -> "Restored from a backup"
        "puzzle"    -> "Found by the puzzle"
        "scanner"   -> "Found by the scanner"
        "kangaroo"  -> "Found by Kangaroo"
        "recovery"  -> "Recovered with Recovery"
        else        -> null
    }

    /**
     * ¿La puso el usuario? Las de antes, sin origen, cuentan como suyas: los
     * hallazgos sólo entraban en la cartera abriéndolos a mano.
     */
    fun esDelUsuario(origen: String?): Boolean =
        origen == null || origen == O_CREADA || origen == O_IMPORTADA || origen == O_BACKUP

    // Aqui estaba clearSeed(), sin una sola llamada. Hacia prefs.clear(): la
    // seed, TODAS las demas carteras, los WIF, los watchers y el PIN. Se va
    // porque no es solo codigo muerto, es un pie de plomo: se llama igual que
    // clearSeedOnly() menos una palabra, y esa si se usa —es la que corre al
    // pulsar "Delete wallet" sin wallet-id—. Equivocarse de nombre al
    // completar borraba la cartera entera del usuario en vez de una seed.

    /* Borra solo la seed principal — preserva PIN y otras wallets */
    fun clearSeedOnly(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Aqui se leian pin_salt, pin_ver y pin_viv a tres locales que no se
        // usaban para nada. Son de cuando esto hacia prefs.clear() y habia que
        // volver a escribir el PIN despues. Ya no: se quitan las dos claves de
        // la seed y ya esta, asi que el PIN ni se toca. Guardar a un lado algo
        // que nadie restaura solo sirve para que el siguiente que lo lea crea
        // que esta funcion borra mas de lo que borra.
        // Antes se borraba "seed_iv", que no es la clave del IV (PREF_IV): el IV
        // se quedaba huérfano. Ahora se quitan todas las claves de la semilla.
        prefs.edit().remove(PREF_SEED).remove(PREF_IV).remove(PREF_SEED_P).remove(PREF_SEED_PIV).commit()
        borrarOrigen(ctx, CLAVE_PRINCIPAL)
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
     *
     * Incluye también el baúl de hallazgos (MatchVault): las claves que
     * encuentran el puzzle y el escáner vivían sólo en un fichero en claro
     * (coincidencias.txt, ya retirado) que no entraba en ningún backup, así
     * que un acierto se perdía al desinstalar.
     */
    fun exportBackup(ctx: Context, pin: String): java.io.File? {
        // Sin la clave del PIN de la sesión, loadSeed devuelve null y la copia
        // saldría sin semillas sin avisar. Mejor no hacerla.
        // También con sólo carteras secundarias (antes sólo miraba la principal).
        if ((hasSeed(ctx) || listWallets(ctx).isNotEmpty()) && !claveSemillaLista()) return null
        // "pin" es ya la contraseña de la copia: ver cifrarCopia.
        return try {
            // Recoge lo que el motor no haya podido entregar al baúl todavía,
            // para que un acierto reciente no se quede fuera del backup.
            try { MatchVault.recoger(ctx) } catch (e: Exception) {
                RegistroFallos.anotar("WalletManager.exportBackup: recoger (un acierto puede faltar en la copia)", e)
            }

            val wallets = listWallets(ctx)
            val mainSeed = loadSeed(ctx)
            // Hay semilla principal guardada pero no se puede leer (pantalla
            // bloqueada a mitad, clave perdida): una copia sin ella es una copia
            // que parece buena y no lo es. Se aborta, como con las demás claves.
            if (mainSeed == null && hasSeed(ctx)) return null
            // Lo que no se pueda leer aborta la copia: una copia a la que le faltan
            // claves sin avisar es peor que ninguna, y además desplaza (prune) a
            // una buena de las que se guardan.
            val wifs = leerWifs(ctx) ?: return null
            val watchers = listWatchers(ctx)
            val matches = MatchVault.listaONull(ctx) ?: return null
            if (wallets.isEmpty() && mainSeed == null && wifs.isEmpty() &&
                watchers.isEmpty() && matches.isEmpty())
                return null

            val backupData = org.json.JSONArray()
            for ((id, name) in wallets) {
                // Si se bloquea la app a mitad (pantalla apagada), la clave se borra
                // y esto da null: antes se saltaba la cartera y la copia salía sin ella.
                val seed = loadWalletSeed(ctx, id) ?: return null
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
                if (matches.isNotEmpty())  put("matches",  MatchVault.toJson(matches))
                val ors = origenes(ctx)
                if (ors.isNotEmpty()) put("origins", org.json.JSONObject(ors))
            }.toString()

            val bytes = cifrarCopia(pin, json)

            // Almacenamiento interno, no externo: el fichero lleva todas las
            // seeds y solo lo protege la contraseña. Se comparte vía FileProvider,
            // que ya cubre files-path en res/xml/file_paths.xml.
            // Antes se borraba todo lo anterior antes de escribir, así que sólo
            // existía la última copia y no había manera de volver a una previa.
            // Ahora las gestiona BackupStore, que conserva las más recientes.
            val dir = BackupStore.dir(ctx)
            val file = java.io.File(dir, "wh_backup_${System.currentTimeMillis()}${BackupStore.EXT}")
            // Escritura atómica: primero a un temporal (que BackupStore no lista),
            // se fuerza a disco, se vuelve a leer y sólo entonces se da el nombre
            // definitivo. Así un corte a mitad no deja una copia truncada como la
            // más reciente, y la poda sólo borra copias que ya están bien.
            val tmp = java.io.File(dir, file.name + ".tmp")
            java.io.FileOutputStream(tmp).use { it.write(bytes); it.flush(); it.fd.sync() }
            if (!tmp.readBytes().contentEquals(bytes) || !tmp.renameTo(file)) {
                tmp.delete(); return null
            }
            // Si el baúl tenía una copia que no se pudo descifrar, las copias viejas pueden ser lo único que
            // conserva esos hallazgos: no se poda ninguna.
            if (!MatchVault.hayCopiaIlegible(ctx)) BackupStore.prune(ctx)
            file
        } catch (e: Exception) { null }
    }

    /** importBackup: la copia trae semillas y no hay sesión abierta con el PIN. */
    const val RESTAURAR_SIN_SESION = -2

    /** La copia trae WIF y no se pueden leer las que hay: no se escribe nada. */
    const val RESTAURAR_SIN_LEER_WIFS = -3

    fun importBackup(ctx: Context, pin: String, data: ByteArray): Int {
        return try {
            val json = descifrarCopia(pin, data)
            val root  = org.json.JSONObject(json)
            var count = 0

            // v1 sólo traía "wallets"; v2 añade la seed principal, los WIF, los
            // watchers y los hallazgos del baúl. Se leen con opt* para seguir
            // aceptando backups antiguos.
            // El origen que traiga la copia; si no trae, "de una copia". No
            // pisa el que ya haya en este móvil.
            val ors = root.optJSONObject("origins")
            fun origenDe(clave: String, enCopia: String = clave) {
                if (origen(ctx, clave) == null)
                    setOrigen(ctx, clave, ors?.optString(enCopia, "")?.ifEmpty { null } ?: O_BACKUP)
            }

            val wallets = root.optJSONArray("wallets") ?: org.json.JSONArray()
            // Las semillas sólo se pueden guardar con el PIN de la sesión. Se mira
            // ANTES de tocar nada, para no dejar una restauración a medias.
            if ((wallets.length() > 0 || root.optString("main_seed", "").isNotEmpty()) &&
                !claveSemillaLista()) return RESTAURAR_SIN_SESION
            // Las WIF se escriben reescribiendo la lista entera: si no se pueden
            // leer las que hay, se para aquí, antes de tocar nada. Antes se
            // saltaban a mitad de la restauración y se devolvía "restaurado".
            val wifsCopia = root.optJSONArray("wifs")
            if (wifsCopia != null && wifsCopia.length() > 0 && leerWifs(ctx) == null)
                return RESTAURAR_SIN_LEER_WIFS
            for (i in 0 until wallets.length()) {
                val w = wallets.getJSONObject(i)
                val mn = w.optString("seed")
                // Una copia con palabras que no son BIP39 no se restaura: sería otra cartera.
                if (mn.isNotEmpty() && Bip39.validate(Frase.palabras(mn)) !is Bip39.Result.Valid) continue
                if (mn.isEmpty()) continue
                // agregarSeed: no pisa una cartera que ya haya con el mismo id, no
                // duplica la misma semilla, y limpia el nombre (un ":" o "|" de la
                // copia rompía la lista y dejaba la cartera invisible).
                val g = agregarSeed(ctx, mn, w.optString("name"), null)
                if (!g.yaEstaba) { origenDe(g.id.ifEmpty { CLAVE_PRINCIPAL }, w.optString("id")); count++ }
            }

            // Con agregarSeed y no saveSeed: restaurar una copia con otra seed
            // principal sustituía la que hubiera en el móvil.
            root.optString("main_seed", "").takeIf { it.isNotEmpty() && Bip39.validate(Frase.palabras(it)) is Bip39.Result.Valid }?.let {
                val g = agregarSeed(ctx, it, "", null)
                // La copia guarda el origen de la principal con la clave CLAVE_PRINCIPAL;
                // el id nuevo de esta cartera no existe en ella.
                if (!g.yaEstaba) origenDe(g.id.ifEmpty { CLAVE_PRINCIPAL }, CLAVE_PRINCIPAL)
                count++
            }

            root.optJSONArray("wifs")?.let { arr ->
                val restored = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Triple(o.optString("id"), o.optString("wif"), o.optString("meta"))
                }.filter { it.second.isNotEmpty() }
                // Sin poder leer las que hay, escribir taparía todas: se omiten las
                // de la copia (no cuentan como restauradas) y sigue con lo demás.
                synchronized(WalletManager) {
                    val actuales = if (restored.isNotEmpty()) leerWifs(ctx) else null
                    if (actuales != null) {
                        writeWifs(ctx, (actuales + restored).distinctBy { it.second })
                        restored.forEach { origenDe(it.first) }
                        count += restored.size
                    }
                }
            }

            root.optJSONArray("watchers")?.let { arr ->
                val restored = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Triple(o.optString("id"), o.optString("addr"), o.optString("label"))
                }.filter { it.second.isNotEmpty() }
                if (restored.isNotEmpty()) synchronized(WalletManager) {
                    // Esta rama guarda los watchers con el formato posicional
                    // ";;" / "~~~"; no existe aquí el escritor en JSON.
                    val actuales = listWatchers(ctx)
                    val nuevos = restored.filter { r -> actuales.none { it.second == r.second } }   // sin duplicar
                    val all = actuales + nuevos
                    ctx.getSharedPreferences("wallet_watch", Context.MODE_PRIVATE).edit()
                        .putString("watch_list",
                            all.joinToString(";;") { "${it.first}~~~${it.second}~~~${it.third}" })
                        .commit()
                    nuevos.forEach { origenDe(it.first) }
                    count += nuevos.size
                }
            }

            root.optJSONArray("matches")?.let { arr ->
                count += MatchVault.add(ctx, MatchVault.fromJson(arr))
            }

            count
        } catch (e: Exception) { -1 }
    }

    /** Qué lleva dentro una copia, sin revelar ningún secreto. */
    data class BackupSummary(
        val version:   Int,
        val createdAt: Long,
        val wallets:   Int,
        val hasMainSeed: Boolean,
        val wifs:      Int,
        val watchers:  Int,
        val matches:   Int
    )

    /**
     * Descifra una copia y cuenta lo que trae, sin devolver seeds ni claves.
     *
     * Es lo que hace falta para poder mirar una copia antes de restaurarla:
     * saber si es la que buscas sin tener que sobrescribir lo que ya tienes, y
     * de paso comprobar que el PIN es el correcto. Las copias antiguas se abren
     * con el PIN que tuvieras al crearlas, no con el actual.
     *
     * @return null si el PIN no es válido o el fichero no lo es.
     */
    fun inspectBackup(pin: String, data: ByteArray): BackupSummary? {
        return try {
            val root = org.json.JSONObject(descifrarCopia(pin, data))

            BackupSummary(
                version     = root.optInt("version", 1),
                createdAt   = root.optLong("created_at", 0L),
                wallets     = root.optJSONArray("wallets")?.length() ?: 0,
                hasMainSeed = root.optString("main_seed", "").isNotEmpty(),
                wifs        = root.optJSONArray("wifs")?.length() ?: 0,
                watchers    = root.optJSONArray("watchers")?.length() ?: 0,
                matches     = root.optJSONArray("matches")?.length() ?: 0
            )
        } catch (e: Exception) { null }
    }

    // ── Cifrado de las copias ────────────────────────────────────────────
    //
    // Las copias se cifraban con el PIN de 6 dígitos y 100 000 vueltas de
    // PBKDF2. Un millón de PIN posibles por 100 000 vueltas cada uno es poco
    // para un ordenador: quien consiguiera el fichero —y es un fichero que se
    // comparte, se sube a la nube, se manda por correo— sacaba el PIN en
    // horas, y con él todas las seeds y el baúl.
    //
    // Ahora la copia lleva su propia contraseña, larga, y 600 000 vueltas (lo
    // que recomienda OWASP para PBKDF2-SHA256). El formato nuevo empieza por
    // "WHB2" y dice cuántas vueltas usa, para poder subirlas sin romper nada.
    // Las copias de antes, sin esa marca, se siguen abriendo con su PIN.

    private val MAGIA_COPIA = "WHB2".toByteArray(Charsets.US_ASCII)
    private const val VUELTAS_COPIA = 600_000
    private const val VUELTAS_COPIA_V1 = 100_000

    /** Longitud mínima de la contraseña de una copia nueva. */
    const val MIN_CONTRASENA_COPIA = 12

    private fun claveCopia(secreto: String, salt: ByteArray, vueltas: Int): javax.crypto.SecretKey {
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(secreto.toCharArray(), salt, vueltas, 256)
        return javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** [WHB2][vueltas][salt][iv][cifrado], cada trozo con su longitud delante. */
    private fun cifrarCopia(secreto: String, json: String): ByteArray {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, claveCopia(secreto, salt, VUELTAS_COPIA))
        val enc = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        val out = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(out).apply {
            write(MAGIA_COPIA); writeInt(VUELTAS_COPIA)
            writeInt(salt.size); write(salt)
            writeInt(cipher.iv.size); write(cipher.iv)
            write(enc); flush()
        }
        return out.toByteArray()
    }

    /** El JSON de dentro. Lanza excepción si la contraseña no es o el fichero no vale. */
    private fun descifrarCopia(secreto: String, data: ByteArray): String {
        val nueva = data.size > 8 && data.copyOfRange(0, 4).contentEquals(MAGIA_COPIA)
        val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(data))
        val vueltas = if (nueva) { dis.skipBytes(4); dis.readInt() } else VUELTAS_COPIA_V1
        require(vueltas in 1..10_000_000)
        // Tamaños acotados: un fichero manipulado con un entero enorme pedía gigas y tumbaba la app con un
        // OutOfMemoryError (que no es una Exception y no lo recogía nadie).
        val nSalt = dis.readInt(); require(nSalt in 8..64)
        val salt = ByteArray(nSalt).also { dis.readFully(it) }
        val nIv = dis.readInt(); require(nIv in 12..16)
        val iv   = ByteArray(nIv).also { dis.readFully(it) }
        val enc  = dis.readBytes()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, claveCopia(secreto, salt, vueltas),
            javax.crypto.spec.GCMParameterSpec(128, iv))
        return String(cipher.doFinal(enc), Charsets.UTF_8)
    }

    /** ¿Es del formato viejo, cifrado con el PIN? */
    fun copiaConPin(data: ByteArray): Boolean =
        !(data.size > 8 && data.copyOfRange(0, 4).contentEquals(MAGIA_COPIA))


}
