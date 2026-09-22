package com.hunter.btc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Baúl cifrado de hallazgos (puzzle y escáner).
 *
 * El motor nativo escribe cada acierto en files/coincidencias.txt como texto
 * plano, con la clave privada dentro. Ese fichero no entraba en el backup, así
 * que un hallazgo se perdía al desinstalar y no había forma de llevárselo.
 *
 * Aquí se guardan cifrados con AES-GCM bajo una clave propia del Keystore, y
 * el fichero en claro se ingiere y se borra. La clave es distinta de la de las
 * seeds y de la de los WIF: borrar una wallet no debe llevarse los hallazgos,
 * ni al revés.
 */
object MatchVault {

    private const val KEY_ALIAS = "hunter_vault_key"
    private const val PREFS     = "match_vault"
    private const val PREF_ENC  = "vault_enc"
    private const val PREF_IV   = "vault_iv"
    private const val MAX_ENTRIES = 500

    data class Entry(
        val ts: Long,
        val source: String,   // "puzzle" | "scanner" | "recovery"
        val addr: String,
        val wif: String,
        val privHex: String,
        val btc: Double,
        val extra: String,
        /** Cuándo se consultó el saldo en la cadena. 0 = nunca. */
        val checkedTs: Long = 0L
    )

    private fun key(): SecretKey {
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

    // ── Persistencia ──────────────────────────────────────────────────────────

    fun list(ctx: Context): List<Entry> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encB64 = prefs.getString(PREF_ENC, null) ?: return emptyList()
        val ivB64  = prefs.getString(PREF_IV, null) ?: return emptyList()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            val json = String(cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP)), Charsets.UTF_8)
            fromJson(JSONArray(json))
        } catch (e: Exception) {
            android.util.Log.e("MatchVault", "no se pudo abrir el baúl: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun write(ctx: Context, entries: List<Entry>) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (entries.isEmpty()) { prefs.edit().clear().apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val enc = cipher.doFinal(toJson(entries).toString().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(PREF_ENC, Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(PREF_IV,  Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    /** Añade evitando duplicados por dirección; los más recientes primero. */
    @Synchronized
    fun add(ctx: Context, entries: List<Entry>): Int {
        if (entries.isEmpty()) return 0
        val current = list(ctx)
        val known = current.mapTo(HashSet()) { it.addr }
        val nuevos = entries.filter { it.addr.isNotEmpty() && it.addr !in known }
        if (nuevos.isEmpty()) return 0
        write(ctx, (nuevos + current).take(MAX_ENTRIES))
        return nuevos.size
    }

    fun add(ctx: Context, e: Entry): Int = add(ctx, listOf(e))

    /**
     * Rellena la dirección y el WIF de los hallazgos que sólo tengan la clave.
     *
     * Kangaroo devolvía la clave privada y nada más, y así se guardaban: el
     * baúl lista POR DIRECCIÓN, así que esas entradas salían en blanco y
     * parecía que no se había guardado nada. Eso ya no pasa al guardar, pero
     * las que quedaron de antes siguen ahí — y son justo las que más importa
     * poder ver.
     *
     * Se deriva de la clave, que es lo único que hace falta: la dirección y el
     * WIF salen de ella, no al revés.
     *
     * @return cuántas se han podido completar.
     */
    fun completarClaves(ctx: Context): Int {
        val todas = list(ctx)
        var tocadas = 0
        val nuevas = todas.map { e ->
            if (e.privHex.length != 64 || (e.addr.isNotEmpty() && e.wif.isNotEmpty())) e
            else {
                val d = try { HunterEngine.datosDeClave(e.privHex) } catch (t: Throwable) { "" }
                if (!d.contains("|")) e
                else {
                    tocadas++
                    e.copy(wif = d.substringBefore("|"), addr = d.substringAfter("|"))
                }
            }
        }
        if (tocadas > 0) write(ctx, nuevas)
        return tocadas
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        try {
            KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {}
    }

    fun count(ctx: Context): Int = list(ctx).size

    /** Hallazgos cuyo saldo aún no se ha consultado en la cadena. */
    fun pendingBalance(ctx: Context): Int = list(ctx).count { it.checkedTs == 0L }

    /**
     * Consulta en la cadena el saldo de los hallazgos que aún no se han
     * comprobado y lo guarda en el baúl.
     *
     * save_match() en C++ escribe siempre BTC:0.00000000 —el formato .bin sólo
     * lleva hash160, no saldos—, así que hasta ahora todo acierto se guardaba
     * como 0 aunque la dirección tuviera fondos.
     *
     * HACE RED: llamar sólo desde un hilo secundario. La red se hace fuera del
     * cerrojo para no bloquear a add() mientras se espera al servidor.
     *
     * @return número de entradas actualizadas.
     */
    fun resolvePendingBalances(ctx: Context, max: Int = 25): Int {
        val pendientes = list(ctx).filter { it.checkedTs == 0L && it.addr.isNotEmpty() }.take(max)
        if (pendientes.isEmpty()) return 0

        val saldos = HashMap<String, Long>()
        // Sin esto, una consulta automática sin cobertura encadena 25 esperas
        // completas —cada una probando dos APIs web y diez servidores Electrum—
        // y el hilo se queda minutos dando vueltas para acabar sin nada.
        var fallosSeguidos = 0
        for (e in pendientes) {
            val r = BalanceLookup.query(e.addr)
            if (r == null) {
                // Tres seguidas es que no hay ruta a la cadena, no que esas tres
                // direcciones tengan mala suerte. Las que queden se reintentan
                // la próxima vez, que es lo que ya hacía checkedTs.
                if (++fallosSeguidos >= 3) break
                continue
            }
            fallosSeguidos = 0
            saldos[e.addr] = r.sat
        }
        if (saldos.isEmpty()) return 0

        val now = System.currentTimeMillis()
        synchronized(this) {
            // Se relee dentro del cerrojo: mientras se consultaba la red pudo
            // entrar un hallazgo nuevo, y escribir la lista vieja lo perdería.
            write(ctx, list(ctx).map { e ->
                val sat = saldos[e.addr]
                if (sat == null) e else e.copy(btc = sat / 1e8, checkedTs = now)
            })
        }
        return saldos.size
    }

    // ── Serialización, compartida con el backup ───────────────────────────────

    fun toJson(entries: List<Entry>): JSONArray {
        val arr = JSONArray()
        entries.forEach {
            arr.put(JSONObject().apply {
                put("ts", it.ts); put("source", it.source); put("addr", it.addr)
                put("wif", it.wif); put("hex", it.privHex); put("btc", it.btc)
                put("extra", it.extra); put("checked", it.checkedTs)
            })
        }
        return arr
    }

    fun fromJson(arr: JSONArray): List<Entry> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                ts      = o.optLong("ts", 0L),
                source  = o.optString("source", "?"),
                addr    = o.optString("addr", ""),
                wif     = o.optString("wif", ""),
                privHex = o.optString("hex", ""),
                btc       = o.optDouble("btc", 0.0),
                extra     = o.optString("extra", ""),
                checkedTs = o.optLong("checked", 0L)
            )
        }.filter { it.addr.isNotEmpty() }

    // ── Ingesta del fichero en claro que escribe el motor nativo ──────────────

    /**
     * Lee files/coincidencias.txt, incorpora lo que haya al baúl y borra el
     * fichero. El motor en C++ no puede cifrar por sí mismo —el Keystore es
     * de la capa Java— así que sigue escribiendo en claro y se recoge aquí.
     *
     * save_match() en C++ escribe "<extra> ADDR:.. BTC:.. WIF:..", y extra
     * cambia según el modo:
     *   BIP39   SEED:<mnemónico> PATH:<ruta> PRIV:<hex>
     *   puzzle  PRIV:<hex>
     *   raw     RAW:<hex>
     */
    @Synchronized
    fun ingestPlaintextFile(ctx: Context): Int {
        val f = java.io.File(ctx.filesDir, "coincidencias.txt")
        if (!f.exists() || f.length() == 0L) return 0
        return try {
            val now = System.currentTimeMillis()
            val parsed = f.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                fun field(name: String): String =
                    Regex("""\b$name:(\S+)""").find(line)?.groupValues?.get(1) ?: ""
                val addr = field("ADDR")
                if (addr.isEmpty()) return@mapNotNull null
                Entry(
                    ts      = now,
                    source  = when {
                        line.contains("SEED:") -> "scanner"   // BIP39
                        line.contains("RAW:")  -> "scanner"   // raw keys
                        else                   -> "puzzle"
                    },
                    addr    = addr,
                    wif     = field("WIF"),
                    privHex = field("PRIV").ifEmpty { field("RAW") }.ifEmpty { field("HEX") },
                    btc     = field("BTC").toDoubleOrNull() ?: 0.0,
                    extra   = line.trim()
                )
            }
            val added = add(ctx, parsed)
            // Sólo se borra el fichero en claro si el baúl quedó escrito.
            if (parsed.isNotEmpty() && count(ctx) > 0) f.delete()
            added
        } catch (e: Exception) {
            android.util.Log.e("MatchVault", "ingesta falló: ${e.message}")
            0
        }
    }
}
