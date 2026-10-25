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
    /** El baúl dentro del SOBRE del código: Keystore por dentro, clave de datos de WalletManager por fuera. */
    private const val PREF_ENC_P = "vault_enc_p"
    private const val PREF_IV_P  = "vault_iv_p"
    /** El buzón: hallazgos que llegan con el código cerrado (ver BuzonCifrado). La privada va sellada con la clave de datos. */
    private const val BUZON_PUB     = "buzon_pub"
    private const val BUZON_PRIV    = "buzon_priv"
    private const val BUZON_PRIV_IV = "buzon_priv_iv"
    private const val BUZON_ITEMS   = "buzon_items"
    private const val BUZON_MAX     = 2000
    /** Copias que no se pudieron descifrar (la clave del Keystore ya no es la misma). Se apartan, no se borran. */
    private const val PREF_ENC_PERDIDO = "vault_enc_perdido"
    private const val PREF_IV_PERDIDO  = "vault_iv_perdido"
    private const val MAX_ENTRIES = 2000

    private fun tieneClave(e: Entry) = e.privHex.isNotEmpty() || e.wif.isNotEmpty() || e.extra.contains("SEED:")

    /**
     * Tope de entradas sin perder claves. Antes se cortaba a 500 por el final y
     * los hallazgos más antiguos —con su clave privada— desaparecían sin aviso.
     * Ahora sólo se descartan, de las más viejas a las más nuevas, las que no
     * traen ninguna clave.
     */
    private fun recortar(lista: List<Entry>): List<Entry> {
        if (lista.size <= MAX_ENTRIES) return lista
        var sobran = lista.size - MAX_ENTRIES
        val fuera = HashSet<Int>()
        for (i in lista.indices.reversed()) {
            if (sobran == 0) break
            if (!tieneClave(lista[i])) { fuera.add(i); sobran-- }
        }
        return lista.filterIndexed { i, _ -> i !in fuera }
    }

    data class Entry(
        val ts: Long,
        /** "puzzle" | "scanner" | "kangaroo" | "recovery", o "created" /
         *  "imported" para una cartera del usuario guardada aquí a mano. */
        val source: String,
        val addr: String,
        val wif: String,
        val privHex: String,
        val btc: Double,
        val extra: String,
        /** Cuándo se consultó el saldo en la cadena. 0 = nunca. */
        val checkedTs: Long = 0L
    )

    /** La clave si existe; null si no. Leer nunca la crea (ver WalletManager.claveExistente). */
    private fun keyExistente(): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        return (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

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

    /** Como [list], pero null si hay baúl y no se pudo abrir (para no hacer copias incompletas). */
    fun listaONull(ctx: Context): List<Entry>? = leer(ctx)

    /**
     * Lo que hay en el baúl, o null si HAY algo pero no se ha podido abrir.
     *
     * list() devuelve vacío en los dos casos, y para enseñar da igual. Para
     * AÑADIR no: add() leía "vacío", le sumaba el hallazgo nuevo y escribía
     * eso encima, así que un fallo pasajero del Keystore al abrir el baúl lo
     * dejaba con una sola entrada y el resto perdido.
     */
    private class Abierto(val lista: List<Entry>, val enSobre: Boolean)

    private fun b64e(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64d(s: String) = Base64.decode(s, Base64.NO_WRAP)

    /** Con código puesto y la sesión cerrada: el baúl no se abre (ni se enseña la copia antigua). */
    fun bloqueado(ctx: Context): Boolean = WalletManager.hasPin(ctx) && WalletManager.claveDeBoveda() == null

    /**
     * Abre el baúl tal como está en disco. null = no se puede ahora: cerrado (falta la sesión) o ilegible.
     * No migra ni toca el buzón: eso lo hace [integrar] cuando hay sesión.
     */
    private fun abrir(ctx: Context): Abierto? {
        // Bajo el mismo cerrojo que las escrituras: el texto cifrado y su IV se leen siempre del
        // mismo guardado. Si no, una lectura que cayera entre dos escrituras vería un IV que no es
        // el suyo y parecería un baúl estropeado.
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val conPin = WalletManager.hasPin(ctx)
        val dek = if (conPin) WalletManager.claveDeBoveda() else null
        val encP = prefs.getString(PREF_ENC_P, null); val ivP = prefs.getString(PREF_IV_P, null)
        val enSobre = encP != null && ivP != null
        val enc: ByteArray; val iv: ByteArray
        if (enSobre) {
            if (dek == null) return null
            val par = SeedEnvelope.abrir(dek, b64d(encP!!), b64d(ivP!!))?.let { SeedEnvelope.desempaquetar(it) }
            if (par == null) {
                android.util.Log.e("MatchVault", "the sealed vault does not open with the data key")
                return null
            }
            iv = par.first; enc = par.second
        } else {
            val e = prefs.getString(PREF_ENC, null); val i = prefs.getString(PREF_IV, null)
            if (e == null || i == null) return Abierto(emptyList(), false)
            if (conPin && dek == null) return null
            enc = b64d(e); iv = b64d(i)
        }
        // «La clave no existe» puede ser un fallo pasajero del Keystore: se insiste un poco antes de darlo por
        // perdido (y entonces se aparta la copia, igual que si la etiqueta no cuadra).
        var k = try { keyExistente() } catch (e: Exception) { null }
        var intentos = 0
        while (k == null && intentos++ < 3) {
            try { Thread.sleep(150) } catch (e: InterruptedException) { break }
            k = try { keyExistente() } catch (e: Exception) { null }
        }
        return try {
            if (k == null) throw javax.crypto.AEADBadTagException("clave del baúl ausente")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
            val json = String(cipher.doFinal(enc), Charsets.UTF_8)
            Abierto(fromJson(JSONArray(json)), enSobre)
        } catch (e: javax.crypto.AEADBadTagException) {
            // La etiqueta no cuadra con esta clave: no es un fallo pasajero. O el Keystore ya no
            // tiene la clave con que se cifró (copia restaurada en otro móvil, Keystore reiniciado)
            // y key() ha creado otra, o el contenido está dañado. Dejar el baúl así impedía guardar
            // nada más PARA SIEMPRE: cada hallazgo nuevo se quedaba en memoria. Se aparta la copia
            // ilegible (sin borrarla) y se sigue con un baúl nuevo.
            android.util.Log.e("MatchVault", "the vault cannot be decrypted with the current key: set aside")
            val marca = System.currentTimeMillis()    // la misma para texto cifrado e IV: si no, el par no se reconstruye
            val ed = prefs.edit()
            if (enSobre) ed.putString("${PREF_ENC_PERDIDO}_$marca", encP).putString("${PREF_IV_PERDIDO}_$marca", ivP)
            else ed.putString("${PREF_ENC_PERDIDO}_$marca", b64e(enc)).putString("${PREF_IV_PERDIDO}_$marca", b64e(iv))
            ed.remove(PREF_ENC).remove(PREF_IV).remove(PREF_ENC_P).remove(PREF_IV_P).commit()
            Abierto(emptyList(), false)
        } catch (e: Exception) {
            android.util.Log.e("MatchVault", "could not open the vault: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Lo que hay en el baúl, o null si HAY algo pero no se ha podido abrir (o está cerrado por falta del código).
     *
     * list() devuelve vacío en los dos casos, y para enseñar da igual. Para
     * AÑADIR no: add() leía "vacío", le sumaba el hallazgo nuevo y escribía
     * eso encima, así que un fallo pasajero del Keystore al abrir el baúl lo
     * dejaba con una sola entrada y el resto perdido.
     */
    private fun leer(ctx: Context): List<Entry>? = synchronized(this) {
        val a = abrir(ctx) ?: return null
        if (!WalletManager.hasPin(ctx) || WalletManager.claveDeBoveda() == null) return a.lista
        integrar(ctx, a).first
    }

    /**
     * Con la sesión abierta: pasa el baúl al sobre del código si aún estaba sólo bajo el Keystore, y recoge el
     * buzón. Devuelve (la lista final, lo que venía del buzón).
     *
     * Migración sin riesgo: primero se escribe la forma nueva SIN borrar la vieja, se vuelve a abrir y se
     * compara; sólo si coincide se borra la vieja. Si no, se deja todo como estaba.
     * El buzón sólo se vacía DESPUÉS de que el baúl con sus entradas quede escrito.
     */
    private fun integrar(ctx: Context, a: Abierto): Pair<List<Entry>, List<Entry>> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var lista = a.lista
        val (delBuzon, fallidos) = leerBuzon(ctx)
        if (delBuzon.isNotEmpty()) {
            val conocidas = lista.mapTo(HashSet()) { it.addr }
            val claves = lista.mapNotNullTo(HashSet()) { it.privHex.ifEmpty { null } }
            val nuevas = delBuzon.filter { e ->
                if (e.addr.isNotEmpty()) e.addr !in conocidas else e.privHex.isNotEmpty() && e.privHex !in claves
            }.distinctBy { it.addr.ifEmpty { it.privHex } }
            lista = recortar(nuevas + lista)
        }
        val hayViejo = prefs.contains(PREF_ENC) || prefs.contains(PREF_IV)
        if (!a.enSobre && lista.isNotEmpty()) {
            try {
                write(ctx, lista, seguro = true, conservarAntigua = true)
                val vuelta = abrir(ctx)
                if (vuelta != null && vuelta.enSobre && toJson(vuelta.lista).toString() == toJson(lista).toString()) {
                    prefs.edit().remove(PREF_ENC).remove(PREF_IV).commit()
                } else {
                    android.util.Log.e("MatchVault", "vault migration did not verify: the old copy stays")
                    prefs.edit().remove(PREF_ENC_P).remove(PREF_IV_P).commit()
                    return lista to emptyList()      // el buzón se queda como estaba
                }
            } catch (e: Exception) {
                android.util.Log.e("MatchVault", "vault migration failed: ${e.javaClass.simpleName}")
                prefs.edit().remove(PREF_ENC_P).remove(PREF_IV_P).commit()
                return a.lista to emptyList()
            }
        } else if (delBuzon.isNotEmpty()) {
            try { write(ctx, lista, seguro = true) } catch (e: Exception) {
                android.util.Log.e("MatchVault", "mailbox merge failed: ${e.javaClass.simpleName}")
                return a.lista to emptyList()
            }
        } else if (hayViejo && a.enSobre) {
            prefs.edit().remove(PREF_ENC).remove(PREF_IV).commit()   // sobras de una migración a medias
        }
        if (delBuzon.isNotEmpty() || fallidos.size != contarBuzon(prefs)) {
            val ed = prefs.edit()
            if (fallidos.isEmpty()) ed.remove(BUZON_ITEMS) else ed.putString(BUZON_ITEMS, JSONArray(fallidos).toString())
            ed.commit()
        }
        return lista to delBuzon
    }

    private fun contarBuzon(prefs: android.content.SharedPreferences): Int =
        try { JSONArray(prefs.getString(BUZON_ITEMS, "[]")).length() } catch (e: Exception) {
            RegistroFallos.anotar("MatchVault.contarBuzon", e); 0
        }

    // ── Buzón: hallazgos que llegan con el código cerrado ───────────────────────

    /** Crea el par del buzón si falta (primera vez, o instalación anterior). Sólo con sesión. */
    private fun asegurarBuzon(ctx: Context) {
        val dek = WalletManager.claveDeBoveda() ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(BUZON_PUB) && prefs.contains(BUZON_PRIV)) return
        val (pub, priv) = BuzonCifrado.generarPar()
        val (sellada, iv) = SeedEnvelope.sellar(dek, priv)
        prefs.edit().putString(BUZON_PUB, b64e(pub)).putString(BUZON_PRIV, b64e(sellada))
            .putString(BUZON_PRIV_IV, b64e(iv)).commit()
    }

    /** (entradas descifradas, paquetes que no se pudieron abrir y se conservan). Sólo con sesión. */
    private fun leerBuzon(ctx: Context): Pair<List<Entry>, List<String>> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val crudo = prefs.getString(BUZON_ITEMS, null) ?: return emptyList<Entry>() to emptyList()
        val items = try { JSONArray(crudo) } catch (e: Exception) {
            RegistroFallos.anotar("MatchVault.leerBuzon: parseo", e)
            return emptyList<Entry>() to emptyList()
        }
        if (items.length() == 0) return emptyList<Entry>() to emptyList()
        val dek = WalletManager.claveDeBoveda() ?: return emptyList<Entry>() to (0 until items.length()).map { items.getString(it) }
        val privSell = prefs.getString(BUZON_PRIV, null); val privIv = prefs.getString(BUZON_PRIV_IV, null)
        val pubB64 = prefs.getString(BUZON_PUB, null)
        val priv = if (privSell != null && privIv != null) SeedEnvelope.abrir(dek, b64d(privSell), b64d(privIv)) else null
        if (priv == null || pubB64 == null) return emptyList<Entry>() to (0 until items.length()).map { items.getString(it) }
        val pub = b64d(pubB64)
        val ok = ArrayList<Entry>(); val mal = ArrayList<String>()
        for (i in 0 until items.length()) {
            val p = items.getString(i)
            val dec = try { BuzonCifrado.descifrar(priv, b64d(p), pub) } catch (e: Exception) {
                RegistroFallos.anotar("MatchVault.leerBuzon: descifrar item", e); null
            }
            val lote = dec?.let { try { fromJson(JSONArray(String(it, Charsets.UTF_8))) } catch (e: Exception) {
                RegistroFallos.anotar("MatchVault.leerBuzon: parseo item", e); null
            } }
            if (lote != null) ok.addAll(lote) else mal.add(p)
        }
        java.util.Arrays.fill(priv, 0)
        return ok to mal
    }

    /** Escribe en el buzón (cifrado para la pública, sin necesitar el código). Lanza si aún no hay buzón. */
    private fun aBuzon(ctx: Context, entries: List<Entry>): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Sin buzón (instalación anterior a este sobre y sin desbloquear aún) no hay dónde dejarlo con seguridad:
        // se lanza, y el motor lo conserva en memoria y lo reintenta (ver recoger).
        val pub = prefs.getString(BUZON_PUB, null) ?: throw IllegalStateException("vault locked")
        val paquete = BuzonCifrado.cifrar(b64d(pub), toJson(entries).toString().toByteArray(Charsets.UTF_8))
        val arr = try { JSONArray(prefs.getString(BUZON_ITEMS, "[]")) } catch (e: Exception) {
            RegistroFallos.anotar("MatchVault.aBuzon: parseo (se pierden los pendientes)", e); JSONArray()
        }
        if (arr.length() >= BUZON_MAX) throw IllegalStateException("vault mailbox full")
        arr.put(b64e(paquete))
        if (!prefs.edit().putString(BUZON_ITEMS, arr.toString()).commit()) throw IllegalStateException("vault locked")
        return entries.size
    }

    /**
     * La llama WalletManager al abrirse la sesión (código correcto o código nuevo): crea el buzón si falta, pasa
     * el baúl al sobre si hacía falta y recoge lo que llegó con el código cerrado.
     */
    fun alDesbloquear(ctx: Context) {
        val recogidas: List<Entry>
        synchronized(this) {
            if (!WalletManager.hasPin(ctx) || WalletManager.claveDeBoveda() == null) return
            asegurarBuzon(ctx)
            val a = abrir(ctx) ?: return
            recogidas = integrar(ctx, a).second
        }
        // Lo recogido también entra en la cartera si el ajuste lo pide (necesitaba la sesión, que ahora hay).
        for (e in recogidas) alGuardar(ctx, e)
    }

    /** ¿Hay una copia del baúl que no se pudo descifrar y quedó apartada? */
    fun hayCopiaIlegible(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.keys.any { it.startsWith(PREF_ENC_PERDIDO) }

    /**
     * @param seguro escribe a disco antes de volver (commit) en vez de en
     *   segundo plano (apply). Para un hallazgo recién encontrado: si el
     *   proceso muere justo después, con apply() se habría perdido.
     */
    private fun write(ctx: Context, entries: List<Entry>, seguro: Boolean = true, conservarAntigua: Boolean = false) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Un baúl vacío quita sólo su contenido: borrar todo se llevaría también las copias apartadas y el buzón.
        if (entries.isEmpty()) { prefs.edit().remove(PREF_ENC).remove(PREF_IV).remove(PREF_ENC_P).remove(PREF_IV_P).commit(); return }
        val conPin = WalletManager.hasPin(ctx)
        val dek = if (conPin) (WalletManager.claveDeBoveda() ?: throw IllegalStateException("vault locked")) else null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val enc = cipher.doFinal(toJson(entries).toString().toByteArray(Charsets.UTF_8))
        val ed = prefs.edit()
        if (dek != null) {
            val (sellado, iv) = SeedEnvelope.sellar(dek, SeedEnvelope.empaquetar(cipher.iv, enc))
            ed.putString(PREF_ENC_P, b64e(sellado)).putString(PREF_IV_P, b64e(iv))
            if (!conservarAntigua) ed.remove(PREF_ENC).remove(PREF_IV)
        } else {
            ed.putString(PREF_ENC, b64e(enc)).putString(PREF_IV, b64e(cipher.iv))
        }
        if (seguro || dek != null) ed.commit() else ed.apply()
    }

    /**
     * Añade evitando duplicados por dirección; los más recientes primero.
     *
     * @throws IllegalStateException si el baúl tiene contenido y no se puede
     *   abrir: escribir encima lo borraría.
     */
    @Synchronized
    fun add(ctx: Context, entries: List<Entry>, seguro: Boolean = true): Int {
        if (entries.isEmpty()) return 0
        // Con código y la sesión cerrada (pantalla apagada, app en segundo plano) el baúl no se puede abrir: el
        // hallazgo va al buzón, cifrado para una clave pública, y entra en el baúl al desbloquear.
        if (bloqueado(ctx)) return aBuzon(ctx, entries)
        val current = leer(ctx) ?: throw IllegalStateException("vault locked")
        val known = current.mapTo(HashSet()) { it.addr }
        val clavesConocidas = current.mapNotNullTo(HashSet()) { it.privHex.ifEmpty { null } }
        // Un hallazgo sin dirección (no se pudo derivar) SE GUARDA si trae clave:
        // antes se tiraba, y era una clave privada encontrada. Se identifica por
        // la clave en vez de por la dirección.
        val nuevos = entries.filter { e ->
            if (e.addr.isNotEmpty()) e.addr !in known
            else e.privHex.isNotEmpty() && e.privHex !in clavesConocidas
        }
        if (nuevos.isEmpty()) return 0
        write(ctx, recortar(nuevos + current), seguro)
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
    fun completarClaves(ctx: Context): Int { synchronized(this) {
        // Dentro del cerrojo (como add y eliminar): si no, un hallazgo que
        // entrara mientras se derivan las claves se perdía al escribir la lista
        // vieja. Y con leer(): si el baúl no se puede abrir, no se escribe nada.
        val todas = leer(ctx) ?: return 0
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
    } }

    /**
     * Borra un hallazgo del baúl. Identifica por clave privada (única e
     * irrepetible) y, si no la hay, por dirección + momento. Devuelve true si
     * se quitó algo.
     */
    @Synchronized
    fun eliminar(ctx: Context, e: Entry): Boolean {
        val actual = leer(ctx) ?: return false
        val quedan = actual.filterNot {
            if (e.privHex.isNotEmpty()) it.privHex == e.privHex
            else it.addr == e.addr && it.ts == e.ts
        }
        if (quedan.size == actual.size) return false
        write(ctx, quedan, seguro = true)
        return true
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
    fun resolvePendingBalances(ctx: Context, max: Int = 25): Int =
        actualizarSaldos(ctx, soloPendientes = true, max = max).second

    /**
     * Consulta en la cadena el saldo de los hallazgos y lo guarda.
     *
     * @param soloPendientes true: sólo los que nunca se han consultado. false:
     *   todos, empezando por los consultados hace más tiempo (los pendientes
     *   van primero, porque su fecha de consulta es cero).
     *
     *   El botón "Refresh balances" del baúl llamaba a la versión de sólo
     *   pendientes. Con todo ya consultado no preguntaba nada a nadie, volvía
     *   con cero respuestas y la pantalla decía "No source answered" teniendo
     *   conexión.
     * @return (cuántas se consultaron, cuántas respondieron). Hacen falta las
     *   dos para distinguir "no había nada que consultar" de "nadie respondió".
     */
    fun actualizarSaldos(ctx: Context, soloPendientes: Boolean, max: Int = 25): Pair<Int, Int> {
        val candidatas = list(ctx).filter { it.addr.isNotEmpty() && (!soloPendientes || it.checkedTs == 0L) }
            .sortedBy { it.checkedTs }.take(max)
        if (candidatas.isEmpty()) return 0 to 0

        val saldos = HashMap<String, Long>()
        var consultadas = 0
        // Sin esto, una consulta sin cobertura encadena 25 esperas completas
        // —cada una probando dos APIs web y diez servidores Electrum— y el hilo
        // se queda minutos dando vueltas para acabar sin nada.
        var fallosSeguidos = 0
        for (e in candidatas) {
            consultadas++
            val r = BalanceLookup.query(e.addr)
            if (r == null) {
                // Tres seguidas es que no hay ruta a la cadena, no que esas tres
                // direcciones tengan mala suerte.
                if (++fallosSeguidos >= 3) break
                continue
            }
            fallosSeguidos = 0
            saldos[e.addr] = r.sat
        }
        if (saldos.isEmpty()) return consultadas to 0

        val now = System.currentTimeMillis()
        synchronized(this) {
            // Se relee dentro del cerrojo: mientras se consultaba la red pudo
            // entrar un hallazgo nuevo, y escribir la lista vieja lo perdería.
            // leer() y no list(): si el Keystore falla aquí, list() da vacío y se
            // escribía un baúl vacío encima de todos los hallazgos.
            val actuales = leer(ctx) ?: return consultadas to 0
            write(ctx, actuales.map { e ->
                val sat = saldos[e.addr]
                if (sat == null) e else e.copy(btc = sat / 1e8, checkedTs = now)
            })
        }
        return consultadas to saldos.size
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
        // Con dirección, o al menos con una clave: las de Kangaroo antiguas sólo
        // tenían la privada, y filtrarlas aquí hacía que la siguiente escritura las
        // borrara para siempre (completarClaves ni llegaba a verlas).
        }.filter { it.addr.isNotEmpty() || it.privHex.isNotEmpty() || it.wif.isNotEmpty() || it.extra.contains("SEED:") }

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
        val priv = field("PRIV").ifEmpty { field("RAW") }.ifEmpty { field("HEX") }
        // Sin dirección pero CON clave se conserva (add() ya admite entradas así): descartarla perdía una
        // clave privada hallada, y recoger() ya la había sacado de la cola del motor.
        if (addr.isEmpty() && priv.length != 64) return null
        return Entry(
            ts      = ts,
            source  = when {
                line.contains("SEED:")    -> "scanner"       // BIP39
                line.contains("RAW:")     -> "scanner"       // raw keys
                line.contains("TRAUDIT")  -> "taproot-audit"
                else                      -> "puzzle"
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
            val cerrado = bloqueado(ctx)
            add(ctx, listOf(e), seguro = true)
            if (cerrado) return true   // quedó en el buzón cifrado; entra al baúl al desbloquear
            val ok = leer(ctx)?.any { it.addr == e.addr } == true
            if (ok) alGuardar(ctx, e)
            ok
        } catch (t: Throwable) {
            android.util.Log.e("MatchVault", "could not store a find: ${t.javaClass.simpleName}")
            false
        }
    }

    /**
     * Guarda una clave encontrada por Kangaroo o por la auditoría de claves débiles: deriva su
     * dirección y su WIF y la escribe en el baúl a disco antes de volver.
     *
     * @return true SÓLO si la clave queda realmente dentro del baúl (también si ya estaba).
     *   Quien llama no debe parar el motor ni dar el hallazgo por guardado si devuelve false:
     *   la clave sigue en el motor y se reintenta.
     */
    fun guardarClave(ctx: Context, claveHex: String, source: String, extra: String): Boolean {
        var addr = ""; var wif = ""
        try {
            val d = HunterEngine.datosDeClave(claveHex)
            if (d.contains("|")) { wif = d.substringBefore("|"); addr = d.substringAfter("|") }
        } catch (t: Throwable) {
            // Si la derivación falla se guarda igual con la clave: perderla sería peor.
            android.util.Log.w("MatchVault", "datosDeClave: ${t.message}")
        }
        return try {
            val cerrado = bloqueado(ctx)
            add(ctx, listOf(Entry(ts = System.currentTimeMillis(), source = source, addr = addr, wif = wif,
                privHex = claveHex, btc = 0.0, extra = extra, checkedTs = 0L)), seguro = true)
            if (cerrado) return true
            val dentro = leer(ctx)?.any {
                it.privHex.equals(claveHex, ignoreCase = true) || (addr.isNotEmpty() && it.addr == addr)
            } == true
            if (dentro) alGuardar(ctx, Entry(System.currentTimeMillis(), source, addr, wif, claveHex, 0.0, extra, 0L))
            dentro
        } catch (t: Throwable) {
            android.util.Log.e("MatchVault", "could not store a key: ${t.javaClass.simpleName}")
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
        if (e.wif.isNotEmpty()) {
            if (e.addr.isNotEmpty()) return e.wif to e.addr
            // Una WIF sin dirección (derivación fallida al guardar): se saca de la propia WIF, para no
            // dejar en la cartera una clave con la dirección en blanco.
            val a = try { HunterEngine.wifToAddr(e.wif) } catch (t: Throwable) { "" }
            return e.wif to (if (a.isEmpty() || a.startsWith("ERROR")) "" else a)
        }
        if (e.privHex.length != 64) return null
        val d = try { HunterEngine.datosDeClave(e.privHex) } catch (t: Throwable) { "" }
        if (!d.contains("|")) return null
        return d.substringBefore("|") to e.addr.ifEmpty { d.substringAfter("|") }
    }

    /** La seed de una entrada, si la trae (BIP39 del escáner, Recovery, carteras). */
    fun seedDe(e: Entry): String =
        Regex("""SEED:(.+?)\s+PATH:""").find(e.extra)?.groupValues?.get(1)?.trim() ?: ""

    /** El nombre con que se guardó una cartera propia. */
    fun nombreDe(e: Entry): String =
        Regex("""NAME:(.+)$""").find(e.extra)?.groupValues?.get(1)?.trim() ?: ""

    /**
     * ¿Ya está en la cartera? Por su WIF si lo trae, y si no por su seed.
     */
    fun enCartera(e: Entry, wifsCartera: Set<String>, seedsCartera: Set<String>): Boolean {
        if (e.wif.isNotEmpty()) return e.wif in wifsCartera
        val seed = seedDe(e)
        return seed.isNotEmpty() && seed in seedsCartera
    }

    /**
     * Añade el hallazgo a la cartera, con un nombre que dice de dónde sale y
     * su origen apuntado. Si trae WIF va como clave WIF; si sólo trae seed
     * (Recovery, una cartera propia), como seed. Si ya estaba no la duplica.
     *
     * @return true si queda en la cartera.
     */
    fun aCartera(ctx: Context, e: Entry): Boolean {
        val origen = when (e.source) {
            "puzzle"   -> "Puzzle"
            "scanner"  -> "Scanner"
            "kangaroo" -> "Kangaroo"
            "weak-key" -> "Weak-key audit"
            "tool"     -> "Key tools"
            "recovery" -> "Recovered"
            else       -> "Find"
        }
        val par = wifDe(e)
        if (par != null) {
            val (wif, addr) = par
            return WalletManager.saveWif(ctx, wif, addr,
                nombreDe(e).ifEmpty { "$origen find ${addr.take(8)}" }, e.source)
        }
        val seed = seedDe(e)
        if (seed.isEmpty()) return false
        // La semilla de una cartera sólo se escribe con el PIN de la sesión. Sin
        // ella no se guarda y se avisa (false), en vez de lanzar una excepción.
        if (!WalletManager.claveSemillaLista()) return false
        WalletManager.agregarSeed(ctx, seed,
            nombreDe(e).ifEmpty { "$origen ${e.addr.take(8)}" }, e.source)
        return true
    }

    /**
     * La primera dirección de recepción de una seed y su ruta: la nativa
     * SegWit (bc1q…, m/84') si sale, y si no la heredada (1…, m/44').
     * Sirve para identificar la entrada en la lista: el baúl lista por
     * dirección.
     */
    fun primeraDireccion(mn: String): Pair<String, String>? = try {
        val o = org.json.JSONObject(HunterEngine.deriveWallet(mn, false))
        val bech = o.optString("p2wpkh_0", "")
        val leg  = o.optString("p2pkh_0", "")
        when {
            bech.isNotEmpty() -> bech to "m/84'/0'/0'/0/0"
            leg.isNotEmpty()  -> leg to "m/44'/0'/0'/0/0"
            else -> o.keys().asSequence().firstOrNull()?.let { k -> o.optString(k) to "?" }
        }
    } catch (t: Throwable) { null }

    /**
     * Guarda en el baúl la seed que ha encontrado Recovery.
     *
     * Antes sólo se enseñaba en pantalla con un botón "Save wallet": si se
     * salía de la app sin pulsarlo, la frase se perdía y había que repetir
     * una búsqueda que puede llevar horas.
     *
     * @return true si queda guardada.
     */
    fun guardarRecuperada(ctx: Context, mn: String): Boolean {
        val (addr, ruta) = primeraDireccion(mn) ?: return false
        val e = Entry(ts = System.currentTimeMillis(), source = "recovery", addr = addr,
                      wif = "", privHex = "", btc = 0.0, extra = "SEED:$mn PATH:$ruta")
        return try {
            val cerrado = bloqueado(ctx)
            add(ctx, listOf(e), seguro = true)
            if (cerrado) return true
            val ok = leer(ctx)?.any { it.addr == addr } == true
            if (ok) alGuardar(ctx, e)
            ok
        } catch (t: Throwable) { false }
    }

    /**
     * Guarda en el baúl una copia de una cartera del usuario —creada aquí o
     * añadida por él—, con su seed o su WIF.
     *
     * @param origen "created" o "imported", o null si no se sabe.
     * @return 1 si se ha guardado, 0 si ya estaba, -1 si no se ha podido.
     */
    fun guardarCartera(ctx: Context, nombre: String, origen: String?,
                       seed: String, wif: String, wifAddr: String): Int {
        val nom = WalletManager.limpiarNombre(nombre)
        val e = if (seed.isNotEmpty()) {
            val (addr, ruta) = primeraDireccion(seed) ?: return -1
            Entry(ts = System.currentTimeMillis(), source = origen ?: "imported", addr = addr,
                  wif = "", privHex = "", btc = 0.0, extra = "SEED:$seed PATH:$ruta NAME:$nom")
        } else if (wif.isNotEmpty()) {
            val addr = wifAddr.ifEmpty { try { HunterEngine.wifToAddr(wif) } catch (t: Throwable) { "" } }
            if (addr.isEmpty()) return -1
            Entry(ts = System.currentTimeMillis(), source = origen ?: "imported", addr = addr,
                  wif = wif, privHex = "", btc = 0.0, extra = "NAME:$nom")
        } else return -1
        return try { if (add(ctx, listOf(e), seguro = true) > 0) 1 else 0 } catch (t: Throwable) { -1 }
    }

    /** Añade a la cartera todos los hallazgos que tengan clave. @return cuántos. */
    fun todosACartera(ctx: Context): Int = todosACarteraResumen(ctx).first

    /** (los que quedan en la cartera, los que no se pudieron añadir). */
    fun todosACarteraResumen(ctx: Context): Pair<Int, Int> {
        var ok = 0; var mal = 0
        for (e in list(ctx)) {
            if (e.wif.isEmpty() && e.privHex.length != 64 && seedDe(e).isEmpty()) continue   // sin clave: nada que añadir
            if (try { aCartera(ctx, e) } catch (t: Throwable) { false }) ok++ else mal++
        }
        return ok to mal
    }

    /** ¿Añadir esta entrada exige la sesión del PIN (porque es una seed) y no está abierta? */
    fun faltaSesion(e: Entry): Boolean =
        e.wif.isEmpty() && seedDe(e).isNotEmpty() && !WalletManager.claveSemillaLista()

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
            if (!f.exists() || bloqueado(ctx)) continue
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
