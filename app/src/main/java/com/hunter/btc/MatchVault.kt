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
 * El motor nativo entrega cada acierto aquí en el momento, por
 * HunterEngine.alHallar(). Antes lo escribía en files/coincidencias.txt, en
 * claro y con la clave privada, y ahí se quedaba hasta la siguiente vez que se
 * abría la app. Ya no se escribe nunca; sólo se recogen y se borran los que
 * dejaran versiones anteriores.
 *
 * Aquí se guardan cifrados con AES-GCM bajo una clave propia del Keystore.
 * La clave es distinta de la de las
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

    fun list(ctx: Context): List<Entry> = leer(ctx) ?: emptyList()

    /**
     * Lo que hay en el baúl, o null si HAY algo pero no se ha podido abrir.
     *
     * list() devuelve vacío en los dos casos, y para enseñar da igual. Para
     * AÑADIR no: add() leía "vacío", le sumaba el hallazgo nuevo y escribía
     * eso encima, así que un fallo pasajero del Keystore al abrir el baúl lo
     * dejaba con una sola entrada y el resto perdido.
     */
    private fun leer(ctx: Context): List<Entry>? {
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
            android.util.Log.e("MatchVault", "could not open the vault: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * @param seguro escribe a disco antes de volver (commit) en vez de en
     *   segundo plano (apply). Para un hallazgo recién encontrado: si el
     *   proceso muere justo después, con apply() se habría perdido.
     */
    private fun write(ctx: Context, entries: List<Entry>, seguro: Boolean = false) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (entries.isEmpty()) { prefs.edit().clear().apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val enc = cipher.doFinal(toJson(entries).toString().toByteArray(Charsets.UTF_8))
        val ed = prefs.edit()
            .putString(PREF_ENC, Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(PREF_IV,  Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        if (seguro) ed.commit() else ed.apply()
    }

    /**
     * Añade evitando duplicados por dirección; los más recientes primero.
     *
     * @throws IllegalStateException si el baúl tiene contenido y no se puede
     *   abrir: escribir encima lo borraría.
     */
    @Synchronized
    fun add(ctx: Context, entries: List<Entry>, seguro: Boolean = false): Int {
        if (entries.isEmpty()) return 0
        val current = leer(ctx) ?: throw IllegalStateException("vault locked")
        val known = current.mapTo(HashSet()) { it.addr }
        val nuevos = entries.filter { it.addr.isNotEmpty() && it.addr !in known }
        if (nuevos.isEmpty()) return 0
        write(ctx, (nuevos + current).take(MAX_ENTRIES), seguro)
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

    // ── Hallazgos del motor nativo ───────────────────────────────────────────

    /**
     * Una línea del motor como entrada del baúl, o null si no trae dirección.
     *
     * save_match() en C++ manda "<extra> ADDR:.. BTC:.. WIF:..", y extra
     * cambia según el modo:
     *   BIP39   SEED:<mnemónico> PATH:<ruta> PRIV:<hex>
     *   puzzle  PRIV:<hex>
     *   raw     RAW:<hex>
     */
    fun deLinea(line: String, ts: Long = System.currentTimeMillis()): Entry? {
        if (line.isBlank()) return null
        fun field(name: String): String =
            Regex("""\b$name:(\S+)""").find(line)?.groupValues?.get(1) ?: ""
        val addr = field("ADDR")
        if (addr.isEmpty()) return null
        return Entry(
            ts      = ts,
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

    /**
     * Guarda en el baúl un hallazgo recién salido del motor, escrito a disco
     * antes de volver.
     *
     * @return true si queda guardado (también si ya lo estaba).
     */
    fun guardarHallazgo(ctx: Context, linea: String): Boolean {
        val e = deLinea(linea) ?: return false
        return try {
            add(ctx, listOf(e), seguro = true)
            val ok = leer(ctx)?.any { it.addr == e.addr } == true
            if (ok) alGuardar(ctx, e)
            ok
        } catch (t: Throwable) {
            android.util.Log.e("MatchVault", "could not store a find: ${t.javaClass.simpleName}")
            false
        }
    }

    // ── Pasar hallazgos a la cartera ─────────────────────────────────────────

    private const val PREFS_CFG = "match_vault_cfg"
    private const val PREF_AUTO_CARTERA = "auto_cartera"

    /**
     * ¿Cada hallazgo nuevo se añade también a la cartera, como clave WIF?
     *
     * En preferencias aparte de las del baúl: write() las limpia enteras
     * cuando el baúl se queda vacío, y el ajuste no debe irse con él.
     */
    fun autoCartera(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_CFG, Context.MODE_PRIVATE)
            .getBoolean(PREF_AUTO_CARTERA, false)

    fun setAutoCartera(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS_CFG, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_AUTO_CARTERA, v).apply()
    }

    private fun alGuardar(ctx: Context, e: Entry) {
        if (autoCartera(ctx)) try { aCartera(ctx, e) } catch (t: Throwable) {}
    }

    /**
     * El WIF de un hallazgo, sacándolo de la clave si la entrada no lo trae
     * (los de Kangaroo antiguos sólo guardaban la clave).
     */
    private fun wifDe(e: Entry): Pair<String, String>? {
        if (e.wif.isNotEmpty()) return e.wif to e.addr
        if (e.privHex.length != 64) return null
        val d = try { HunterEngine.datosDeClave(e.privHex) } catch (t: Throwable) { "" }
        if (!d.contains("|")) return null
        return d.substringBefore("|") to e.addr.ifEmpty { d.substringAfter("|") }
    }

    /** ¿Este hallazgo ya está en la cartera? */
    fun enCartera(e: Entry, wifsCartera: Set<String>): Boolean =
        e.wif.isNotEmpty() && e.wif in wifsCartera

    /**
     * Añade el hallazgo a la cartera como clave WIF, con un nombre que dice de
     * dónde sale. Si ya estaba no la duplica.
     *
     * @return true si queda en la cartera.
     */
    fun aCartera(ctx: Context, e: Entry): Boolean {
        val (wif, addr) = wifDe(e) ?: return false
        val origen = when (e.source) {
            "puzzle"   -> "Puzzle"
            "scanner"  -> "Scanner"
            "kangaroo" -> "Kangaroo"
            "recovery" -> "Recovery"
            else       -> "Find"
        }
        return WalletManager.saveWif(ctx, wif, addr, "$origen find ${addr.take(8)}")
    }

    /** Añade a la cartera todos los hallazgos que tengan clave. @return cuántos. */
    fun todosACartera(ctx: Context): Int =
        list(ctx).count { try { aCartera(ctx, it) } catch (t: Throwable) { false } }

    /**
     * Recoge lo que no haya llegado al baúl todavía.
     *
     *   - Lo que el motor no pudo entregar al encontrarlo y guarda en memoria
     *     (Keystore sin abrir, app aún sin conectar). Nunca en disco.
     *   - Los coincidencias.txt en claro que dejaron versiones anteriores, en
     *     almacenamiento interno o externo. Se pasan al baúl y se borran.
     *
     * @return cuántos hallazgos nuevos han entrado.
     */
    @Synchronized
    fun recoger(ctx: Context): Int {
        var nuevos = 0
        while (true) {
            val l = try { HunterEngine.popPorGuardar() } catch (t: Throwable) { "" }
            if (l.isEmpty()) break
            val e = deLinea(l) ?: continue
            try {
                nuevos += add(ctx, listOf(e), seguro = true)
                alGuardar(ctx, e)
            } catch (t: Throwable) {
                // El baúl no se abre: se devuelve al motor y se reintenta luego.
                try { HunterEngine.devolverPorGuardar(l) } catch (t2: Throwable) {}
                break
            }
        }
        val viejos = listOfNotNull(
            java.io.File(ctx.filesDir, "coincidencias.txt"),
            ctx.getExternalFilesDir(null)?.let { java.io.File(it, "coincidencias.txt") }
        )
        for (f in viejos) {
            if (!f.exists()) continue
            try {
                val now = System.currentTimeMillis()
                val entradas = f.useLines { ls -> ls.mapNotNull { deLinea(it, now) }.toList() }
                nuevos += add(ctx, entradas, seguro = true)
                // Sólo se borra si todo lo que traía está ya en el baúl.
                val dentro = leer(ctx)?.mapTo(HashSet()) { it.addr } ?: continue
                if (entradas.all { it.addr in dentro }) f.delete()
            } catch (e: Exception) {
                android.util.Log.e("MatchVault", "legacy file: ${e.javaClass.simpleName}")
            }
        }
        return nuevos
    }
}
