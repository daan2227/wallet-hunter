package com.hunter.btc

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

/**
 * Herramientas: una sola pantalla dividida en secciones plegables, cada una
 * una utilidad pequeña de clave pública/privada. Antes eran varias entradas en
 * More abriendo pantallas distintas; ahora viven juntas aquí.
 *
 *  · Addresses       — todas las direcciones de una clave.
 *  · Hex ↔ WIF        — convierte una privada entre hex y WIF.
 *  · Inspector       — dice qué es lo que pegas (clave/dirección/tipo/red).
 *  · Balance & UTXOs — saldo y salidas de una dirección (watch-only).
 *  · Vanity          — genera una dirección con el prefijo que elijas.
 *  · Key split (XOR) — parte una privada en dos trozos, o los junta.
 *  · Brainwallet     — prueba frases contra una dirección.
 *  · Nonce audit     — nonce reutilizado → recupera la privada.
 */
class PubkeyToolsActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var root: LinearLayout   // contenedor actual (cuerpo de la sección en construcción)

    override fun onCreate(s: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(s)
        AppTheme.init(this)
        AppLock.init(this)

        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(0, dp(24), 0, dp(32))
        }
        scroll.addView(page)
        setContentView(scroll)
        root = page

        page.addView(TextView(this).apply {
            text = "Tools"; textSize = 24f; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
            setPadding(dp(20), 0, dp(20), dp(8))
        })

        seccion(page, R.drawable.ic_search, "Addresses from a key") { construirDirecciones() }
        seccion(page, R.drawable.ic_copy,   "Hex ↔ WIF")            { construirWifHex() }
        seccion(page, R.drawable.ic_eye,    "Key / address inspector") { construirInspector() }
        seccion(page, R.drawable.ic_wallet, "Balance & UTXOs (watch-only)") { construirBalance() }
        seccion(page, R.drawable.ic_dice,   "Vanity address")       { construirVanity() }
        seccion(page, R.drawable.ic_lock,   "Key split (XOR)")      { construirSplit() }
        seccion(page, R.drawable.ic_edit,   "Brainwallet check")    { construirBrainwallet() }
        seccion(page, R.drawable.ic_warning,"Nonce-reuse audit")    { construirNonce() }
    }

    /** Añade una sección plegable y construye su cuerpo apuntando [root] a él. */
    private fun seccion(page: LinearLayout, icon: Int, title: String, build: () -> Unit) {
        page.addView(Ui.section(this, icon, title) {
            val prev = root; root = this; build(); root = prev
        })
    }

    // ── Piezas comunes (operan sobre [root]) ──────────────────────────────
    private fun desc(t: String) {
        root.addView(TextView(this).apply {
            text = t; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.3f)
            setPadding(0, 0, 0, dp(12))
        })
    }

    private fun rotulo(t: String) = TextView(this).apply {
        text = t; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context); setPadding(0, dp(6), 0, dp(6))
    }

    private fun entrada(hint: String, varias: Boolean = false): EditText {
        val e = EditText(this).apply {
            this.hint = hint
            setHorizontallyScrolling(!varias)
            if (varias) { maxLines = 8; minLines = 3; gravity = Gravity.TOP or Gravity.START } else maxLines = 2
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
            typeface = AppTheme.mono(context)
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(e); return e
    }

    private fun boton(t: String, click: () -> Unit): Button {
        val b = Button(this).apply {
            text = t; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.ON_ACCENT)
            typeface = AppTheme.bold(context); isAllCaps = false; stateListAnimator = null
            background = Ui.botonAccento(this@PubkeyToolsActivity, AppTheme.R_INNER, AppTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
                .apply { topMargin = dp(12) }
            setOnClickListener { Ui.pulso(this); click() }
        }
        root.addView(b); return b
    }

    private fun salidaBox(): LinearLayout {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        root.addView(l); return l
    }

    private fun nota(color: Int, t: String) {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.cardBg(AppTheme.R_CARD, AppTheme.BG_ELEV, context)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        }
        fila.addView(android.view.View(this).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { marginEnd = dp(12) }
        })
        fila.addView(TextView(this).apply {
            text = t; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.35f); gravity = Gravity.CENTER_VERTICAL
        })
        root.addView(fila)
    }

    private fun copiar(texto: String) {
        try {
            val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cb.setPrimaryClip(android.content.ClipData.newPlainText("btc", texto))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {}
    }

    /** Fila "etiqueta / valor" que copia el valor al tocarla. */
    private fun filaResultado(etiqueta: String, valor: String, destacado: Boolean = false): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            isClickable = true; isFocusable = true; foreground = Ui.toque()
            setOnClickListener { copiar(valor) }
        }
        col.addView(TextView(this@PubkeyToolsActivity).apply {
            text = etiqueta; textSize = AppTheme.SP_CAPTION
            setTextColor(if (destacado) AppTheme.ACCENT else AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
        })
        col.addView(TextView(this@PubkeyToolsActivity).apply {
            text = valor; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.mono(context); setPadding(0, dp(3), 0, 0)
        })
        return col
    }

    /** Las direcciones de una clave (priv o pub) como conjunto. */
    private fun dirsDe(entrada: String): Set<String> {
        val t = try { HunterEngine.direccionesDe(entrada) } catch (e: Throwable) { "" }
        if (t.isBlank()) return emptySet()
        return t.trim().split('\n').mapNotNull { l ->
            val i = l.indexOf('='); if (i > 0) l.substring(i + 1).trim() else null
        }.toSet()
    }

    // ── Addresses ─────────────────────────────────────────────────────────
    private fun construirDirecciones() {
        desc("Every address a key maps to — compressed and uncompressed. Paste a public " +
             "key (02/03/04) or a 64-hex private key.")
        val et = entrada("02…/03…/04… or 64-hex private key", varias = true)
        val salida = salidaBox()
        boton("Derive addresses") {
            salida.removeAllViews()
            val txt = try { HunterEngine.direccionesDe(et.text.toString().trim()) } catch (e: Throwable) { "" }
            if (txt.isBlank()) Toast.makeText(this, "Not a valid public or private key", Toast.LENGTH_SHORT).show()
            else txt.trim().split('\n').forEach { l ->
                val i = l.indexOf('='); if (i > 0) salida.addView(filaResultado(l.substring(0, i), l.substring(i + 1)))
            }
        }
        nota(AppTheme.BLUE, "Compressed and uncompressed keys hash to different addresses, " +
             "each with P2PKH, P2SH-P2WPKH, bech32 and taproot forms. Tap one to copy.")
    }

    // ── Hex ↔ WIF ─────────────────────────────────────────────────────────
    private fun construirWifHex() {
        desc("Convert a private key between raw 64-hex and WIF. Paste either.")
        val et = entrada("64-hex private key, or 5…/K…/L… WIF", varias = true)
        val salida = salidaBox()
        boton("Convert") {
            salida.removeAllViews()
            val txt = et.text.toString().trim().removePrefix("0x")
            val esHex = txt.length == 64 && txt.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            if (esHex) {
                val w = try { HunterEngine.wifDeHex(txt) } catch (e: Throwable) { "" }
                if (w.isBlank()) Toast.makeText(this, "Not a valid private key", Toast.LENGTH_SHORT).show()
                else { val p = w.split('|'); salida.addView(filaResultado("WIF (compressed)", p.getOrElse(0){""}, true))
                    if (p.size > 1) salida.addView(filaResultado("WIF (uncompressed)", p[1])) }
            } else {
                val h = try { HunterEngine.hexDeWif(txt) } catch (e: Throwable) { "" }
                if (h.isBlank()) Toast.makeText(this, "Not a valid WIF or 64-hex key", Toast.LENGTH_SHORT).show()
                else salida.addView(filaResultado("Private key (hex)", h, true))
            }
        }
    }

    // ── Inspector ─────────────────────────────────────────────────────────
    private fun construirInspector() {
        desc("Paste anything — key or address — and it tells you what it is.")
        val et = entrada("key or address", varias = true)
        val salida = salidaBox()
        boton("Inspect") {
            salida.removeAllViews()
            val t = et.text.toString().trim()
            val low = t.removePrefix("0x").lowercase()
            val esHex = low.all { it.isDigit() || it in 'a'..'f' }
            when {
                esHex && low.length == 64 ->
                    salida.addView(filaResultado("Private key · 64-hex", t, true))
                esHex && low.length == 66 && (low.startsWith("02") || low.startsWith("03")) ->
                    salida.addView(filaResultado("Public key · compressed", t, true))
                esHex && low.length == 130 && low.startsWith("04") ->
                    salida.addView(filaResultado("Public key · uncompressed", t, true))
                HunterEngine.hexDeWif(t).isNotBlank() ->
                    salida.addView(filaResultado("WIF private key", "hex ${HunterEngine.hexDeWif(t)}", true))
                else -> {
                    val r = BtcAddress.validate(t, false)
                    if (r is BtcAddress.Result.Valid) {
                        salida.addView(filaResultado("Address · ${r.info.type}" +
                            (if (r.info.testnet) " · testnet" else " · mainnet"), t, true))
                    } else {
                        val motivo = (r as? BtcAddress.Result.Invalid)?.reason ?: "unrecognized"
                        salida.addView(filaResultado("Not valid", motivo))
                    }
                }
            }
        }
    }

    // ── Balance & UTXOs (watch-only) ───────────────────────────────────────
    @Volatile private var balCorriendo = false
    private fun satsABtc(s: Long) = "%.8f".format(s / 1e8).trimEnd('0').trimEnd('.') + " BTC"
    private fun construirBalance() {
        desc("Balance, transaction count and unspent outputs of any address — read-only, " +
             "from a public explorer. No key needed.")
        val et = entrada("1…/3…/bc1… address")
        val salida = salidaBox()
        boton("Check") {
            if (balCorriendo) return@boton
            val addr = et.text.toString().trim()
            if (addr.isEmpty()) { Toast.makeText(this, "Enter an address", Toast.LENGTH_SHORT).show(); return@boton }
            salida.removeAllViews(); balCorriendo = true
            salida.addView(TextView(this).apply { text = "Checking…"; textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context) })
            Thread {
                val info = ChainApi.get("/address/$addr")
                val utxo = ChainApi.get("/address/$addr/utxo")
                runOnUiThread {
                    balCorriendo = false; salida.removeAllViews()
                    if (info == null) { salida.addView(TextView(this).apply {
                        text = "No network, or address not found."; textSize = AppTheme.SP_CAPTION
                        setTextColor(AppTheme.TXT_MUTED); typeface = AppTheme.body(context) }); return@runOnUiThread }
                    try {
                        val o = org.json.JSONObject(info)
                        val cs = o.getJSONObject("chain_stats")
                        val saldo = cs.getLong("funded_txo_sum") - cs.getLong("spent_txo_sum")
                        val txn = cs.getInt("tx_count")
                        salida.addView(filaResultado("Balance", satsABtc(saldo), true))
                        salida.addView(filaResultado("Transactions", "$txn"))
                        if (utxo != null) {
                            val a = org.json.JSONArray(utxo); var suma = 0L
                            for (i in 0 until a.length()) suma += a.getJSONObject(i).optLong("value", 0)
                            salida.addView(filaResultado("Unspent outputs", "${a.length()} · ${satsABtc(suma)}"))
                        }
                    } catch (e: Throwable) {
                        salida.addView(TextView(this).apply { text = "Could not read the response."
                            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED); typeface = AppTheme.body(context) })
                    }
                }
            }.apply { isDaemon = true; start() }
        }
    }

    // ── Vanity address ─────────────────────────────────────────────────────
    @Volatile private var vanCorriendo = false
    private fun construirVanity() {
        desc("Grind a P2PKH address (starts with 1) that contains your text right after " +
             "the 1. Longer prefixes take exponentially longer. Base58 has no 0, O, I or l.")
        val et = entrada("desired prefix, e.g. Love")
        val tv = TextView(this).apply { text = ""; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context); setPadding(dp(2), dp(12), 0, 0) }
        val salida = salidaBox()
        lateinit var btn: Button
        btn = boton("Generate") {
            if (vanCorriendo) { vanCorriendo = false; return@boton }
            val pref = et.text.toString().trim()
            if (pref.isEmpty()) { Toast.makeText(this, "Enter a prefix", Toast.LENGTH_SHORT).show(); return@boton }
            if (pref.any { it in "0OIl" }) { Toast.makeText(this, "Base58 excludes 0 O I l", Toast.LENGTH_LONG).show(); return@boton }
            salida.removeAllViews(); vanCorriendo = true; btn.text = "Stop"
            Thread {
                val rnd = java.security.SecureRandom()
                val buscado = "1$pref"
                var intentos = 0L
                while (vanCorriendo) {
                    val b = ByteArray(32); rnd.nextBytes(b)
                    val priv = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                    intentos++
                    val dirs = try { HunterEngine.direccionesDe(priv) } catch (e: Throwable) { "" }
                    val p2pkh = dirs.trim().split('\n').firstOrNull { it.startsWith("P2PKH (compressed)=") }
                        ?.substringAfter('=')
                    if (p2pkh != null && p2pkh.startsWith(buscado)) {
                        val wif = try { HunterEngine.wifDeHex(priv).substringBefore("|") } catch (e: Throwable) { "" }
                        runOnUiThread {
                            vanCorriendo = false; btn.text = "Generate"
                            salida.addView(filaResultado("Address", p2pkh, true))
                            salida.addView(filaResultado("Private key", priv))
                            if (wif.isNotEmpty()) salida.addView(filaResultado("WIF", wif))
                            tv.text = "Found after $intentos tries."
                        }
                        break
                    }
                    if (intentos % 500 == 0L) { val n = intentos; runOnUiThread { tv.text = "Tried $n…" } }
                }
                if (!vanCorriendo) runOnUiThread { btn.text = "Generate" }
            }.apply { isDaemon = true; start() }
        }
        root.addView(tv)
    }

    // ── Key split (XOR) ────────────────────────────────────────────────────
    private fun construirSplit() {
        desc("Split a private key into two random halves (A XOR B = key) for backup, or " +
             "paste both halves to recombine. Keep each half apart — one alone reveals nothing.")
        val et = entrada("one 64-hex key to split, or two hex halves (one per line)", varias = true)
        val salida = salidaBox()
        boton("Split / combine") {
            salida.removeAllViews()
            val lineas = et.text.toString().split('\n').map { it.trim().removePrefix("0x") }.filter { it.isNotEmpty() }
            fun hx(s: String) = if (s.length == 64 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) s.lowercase() else null
            fun bytes(h: String) = ByteArray(32) { h.substring(it*2, it*2+2).toInt(16).toByte() }
            fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            when {
                lineas.size == 1 && hx(lineas[0]) != null -> {
                    val key = bytes(hx(lineas[0])!!)
                    val a = ByteArray(32); java.security.SecureRandom().nextBytes(a)
                    val b = ByteArray(32) { (key[it].toInt() xor a[it].toInt()).toByte() }
                    salida.addView(filaResultado("Share A (random)", hex(a), true))
                    salida.addView(filaResultado("Share B (key ⊕ A)", hex(b), true))
                }
                lineas.size == 2 && hx(lineas[0]) != null && hx(lineas[1]) != null -> {
                    val a = bytes(hx(lineas[0])!!); val b = bytes(hx(lineas[1])!!)
                    val key = ByteArray(32) { (a[it].toInt() xor b[it].toInt()).toByte() }
                    salida.addView(filaResultado("Recombined private key", hex(key), true))
                }
                else -> Toast.makeText(this, "Give one 64-hex key, or two hex halves", Toast.LENGTH_SHORT).show()
            }
        }
        nota(AppTheme.WARN, "A 2-of-2 XOR split: you need BOTH halves to restore the key. " +
             "It is not Shamir — there is no 2-of-3. Store the halves in different places.")
    }

    // ── Brainwallet ─────────────────────────────────────────────────────────
    @Volatile private var bwCorriendo = false
    private fun sha256Hex(s: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(64); for (b in d) sb.append("%02x".format(b.toInt() and 0xFF)); return sb.toString()
    }
    private fun construirBrainwallet() {
        desc("Tests phrases (private key = SHA-256 of the phrase) against a target address " +
             "or public key. Audits whether an address came from a guessable phrase.")
        val etTarget = entrada("target 1…/3…/bc1… or 02…/03…/04…")
        root.addView(rotulo("Phrases to try (one per line)"))
        val etFrases = entrada("correct horse battery staple\npassword\n…", varias = true)
        val tvEstado = TextView(this).apply { text = ""; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context); setPadding(dp(2), dp(12), 0, 0) }
        val salida = salidaBox()
        lateinit var btn: Button
        btn = boton("Check") {
            if (bwCorriendo) { bwCorriendo = false; return@boton }
            val objetivo = dirsDe(etTarget.text.toString().trim())
            if (objetivo.isEmpty()) { Toast.makeText(this, "Enter a valid target", Toast.LENGTH_SHORT).show(); return@boton }
            val frases = etFrases.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (frases.isEmpty()) { Toast.makeText(this, "Paste some phrases", Toast.LENGTH_SHORT).show(); return@boton }
            salida.removeAllViews(); bwCorriendo = true; btn.text = "Stop"
            Thread {
                var n = 0; var hits = 0
                for (frase in frases) {
                    if (!bwCorriendo) break
                    n++
                    val priv = sha256Hex(frase)
                    if (dirsDe(priv).any { it in objetivo }) {
                        hits++
                        val wif = try { HunterEngine.datosDeClave(priv).substringBefore("|") } catch (e: Throwable) { "" }
                        runOnUiThread { salida.addView(filaResultado("MATCH · \"$frase\"",
                            "priv $priv" + (if (wif.isNotEmpty()) "\nWIF  $wif" else ""), true)) }
                    }
                    if (n % 25 == 0 || n == frases.size) { val h = n; val f = hits
                        runOnUiThread { tvEstado.text = "Checked $h / ${frases.size} · $f found" } }
                }
                runOnUiThread { bwCorriendo = false; btn.text = "Check"
                    if (hits == 0) salida.addView(TextView(this).apply {
                        text = "No match for those phrases."; textSize = AppTheme.SP_CAPTION
                        setTextColor(AppTheme.TXT_MUTED); typeface = AppTheme.body(context); setPadding(dp(2), dp(8), 0, 0) }) }
            }.apply { isDaemon = true; start() }
        }
        root.addView(tvEstado)
    }

    // ── Nonce-reuse audit ─────────────────────────────────────────────────
    @Volatile private var nonceCorriendo = false
    private class FirmaRS(val pub: String, val r: String, val s: String, val htype: Int)
    private class RegFirma(val tx: org.json.JSONObject, val vin: Int, val s: String, val htype: Int)

    private fun hexABytes(h: String): ByteArray? {
        if (h.length % 2 != 0) return null
        val o = ByteArray(h.length / 2)
        for (i in o.indices) { val v = h.substring(i*2, i*2+2).toIntOrNull(16) ?: return null; o[i] = v.toByte() }
        return o
    }
    private fun parseSig(sigHex: String): Triple<String, String, Int>? {
        val b = hexABytes(sigHex) ?: return null
        if (b.size < 9 || (b[0].toInt() and 0xFF) != 0x30 || (b[2].toInt() and 0xFF) != 0x02) return null
        val htype = b[b.size - 1].toInt() and 0xFF
        val rlen = b[3].toInt() and 0xFF
        val sPos = 4 + rlen
        if (sPos + 2 > b.size || (b[sPos].toInt() and 0xFF) != 0x02) return null
        val slen = b[sPos + 1].toInt() and 0xFF
        if (sPos + 2 + slen > b.size) return null
        var rs = 4; var rl = rlen; while (rl > 1 && b[rs].toInt() == 0) { rs++; rl-- }
        var ss = sPos + 2; var sl = slen; while (sl > 1 && b[ss].toInt() == 0) { ss++; sl-- }
        fun hx(a: Int, n: Int): String { val sb = StringBuilder(); for (i in a until a + n) sb.append("%02x".format(b[i].toInt() and 0xFF)); return sb.toString() }
        return Triple(hx(rs, rl), hx(ss, sl), htype)
    }
    private fun esPub(t: String) =
        (t.length == 66 && (t.startsWith("02") || t.startsWith("03"))) || (t.length == 130 && t.startsWith("04"))
    private fun firmaDe(vin: org.json.JSONObject): FirmaRS? {
        val w = vin.optJSONArray("witness")
        if (w != null && w.length() >= 2) {
            val sig = w.optString(0, ""); val pub = w.optString(w.length() - 1, "").lowercase()
            val p = parseSig(sig); if (p != null && esPub(pub)) return FirmaRS(pub, p.first, p.second, p.third)
        }
        val asm = vin.optString("scriptsig_asm", "")
        if (asm.isNotEmpty()) {
            val toks = asm.split(' ').filter { !it.startsWith("OP_") && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
            val pub = toks.firstOrNull { esPub(it.lowercase()) }?.lowercase()
            val sig = toks.firstOrNull { it.startsWith("30") && it.length > 16 }
            if (pub != null && sig != null) { val p = parseSig(sig); if (p != null) return FirmaRS(pub, p.first, p.second, p.third) }
        }
        return null
    }
    private fun recuperar(pub: String, r: String, a: RegFirma, bTx: org.json.JSONObject, bVin: Int, bS: String, bHtype: Int): String? {
        val z1 = FirmaTx.z(a.tx, a.vin, a.htype) ?: return null
        val z2 = FirmaTx.z(bTx, bVin, bHtype) ?: return null
        val d = try { HunterEngine.recuperarNonce(r, a.s, z1, bS, z2) } catch (e: Throwable) { "" }
        if (d.isBlank()) return null
        val dirPub = dirsDe(pub); val dirD = dirsDe(d)
        return if (dirPub.isNotEmpty() && dirD.any { it in dirPub }) d else null
    }
    private fun construirNonce() {
        desc("If an address signs with the same random k (nonce) twice, its private key " +
             "falls out of the two signatures. Scans a spent address and recovers the key " +
             "(P2PKH / P2WPKH).")
        val etAddr = entrada("spent 1…/3…/bc1… address")
        val tvEstado = TextView(this).apply { text = ""; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context); setPadding(dp(2), dp(12), 0, 0) }
        val salida = salidaBox()
        lateinit var btn: Button
        btn = boton("Scan signatures") {
            if (nonceCorriendo) { nonceCorriendo = false; return@boton }
            val addr = etAddr.text.toString().trim()
            if (addr.isEmpty()) { Toast.makeText(this, "Enter an address", Toast.LENGTH_SHORT).show(); return@boton }
            salida.removeAllViews(); nonceCorriendo = true; btn.text = "Stop"; tvEstado.text = "Fetching transactions…"
            Thread {
                val visto = HashMap<String, HashMap<String, RegFirma>>()
                val reusos = ArrayList<Array<String?>>()
                val yaVisto = HashSet<String>()
                var firmas = 0; var txs = 0; var ultimo = ""; var paginas = 0; var red = true
                bucle@ while (nonceCorriendo && paginas < 6) {
                    val ruta = "/address/$addr/txs" + (if (ultimo.isEmpty()) "" else "/chain/$ultimo")
                    val cuerpo = ChainApi.get(ruta) ?: run { if (txs == 0) red = false; null } ?: break@bucle
                    paginas++
                    val arr = try { org.json.JSONArray(cuerpo) } catch (e: Throwable) { break@bucle }
                    if (arr.length() == 0) break@bucle
                    for (ti in 0 until arr.length()) {
                        if (!nonceCorriendo) break@bucle
                        val tx = arr.optJSONObject(ti) ?: continue
                        val txid = tx.optString("txid", ""); ultimo = txid
                        val vins = tx.optJSONArray("vin") ?: continue
                        for (vi in 0 until vins.length()) {
                            val f = firmaDe(vins.optJSONObject(vi) ?: continue) ?: continue
                            firmas++
                            val m = visto.getOrPut(f.pub) { HashMap() }
                            val previo = m[f.r]
                            if (previo == null) m[f.r] = RegFirma(tx, vi, f.s, f.htype)
                            else if (previo.tx.optString("txid") + "#" + previo.vin != txid + "#" + vi
                                     && yaVisto.add(f.pub + ":" + f.r)) {
                                val priv = recuperar(f.pub, f.r, previo, tx, vi, f.s, f.htype)
                                reusos.add(arrayOf(f.pub, f.r, previo.tx.optString("txid"), txid, priv))
                            }
                        }
                        txs++
                    }
                    val ht = txs; val hf = firmas
                    runOnUiThread { tvEstado.text = "Scanned $ht tx · $hf signatures" }
                    if (arr.length() < 25) break@bucle
                }
                val fin = txs; val sigs = firmas
                runOnUiThread {
                    nonceCorriendo = false; btn.text = "Scan signatures"
                    when {
                        !red -> tvEstado.text = "No network, or address not found / never spent."
                        reusos.isNotEmpty() -> {
                            val rec = reusos.count { it[4] != null }
                            tvEstado.text = "⚠ Reused nonce · $fin tx · $sigs sig · $rec key(s) recovered"
                            for (x in reusos) {
                                val pub = x[0] ?: ""; val r = x[1] ?: ""; val t1 = x[2] ?: ""; val t2 = x[3] ?: ""; val priv = x[4]
                                if (priv != null) {
                                    val wif = try { HunterEngine.wifDeHex(priv).substringBefore("|") } catch (e: Throwable) { "" }
                                    salida.addView(filaResultado("RECOVERED PRIVATE KEY",
                                        "priv $priv" + (if (wif.isNotEmpty()) "\nWIF  $wif" else "") + "\npubkey ${pub.take(16)}…", true))
                                } else salida.addView(filaResultado("VULNERABLE · reused r (recovery n/a)",
                                        "pubkey ${pub.take(16)}…\nr ${r.take(20)}…\ntx1 ${t1.take(16)}…\ntx2 ${t2.take(16)}…", true))
                            }
                            salida.addView(TextView(this).apply {
                                text = "Same r ⇒ same nonce ⇒ key recoverable. This address is compromised — move any funds."
                                textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.WARN)
                                typeface = AppTheme.body(context); setLineSpacing(0f, 1.35f); setPadding(dp(2), dp(10), 0, 0) })
                        }
                        else -> tvEstado.text = "Clean · $fin tx · $sigs signatures · no nonce reuse"
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        root.addView(tvEstado)
    }

    override fun onDestroy() {
        bwCorriendo = false; nonceCorriendo = false; vanCorriendo = false; balCorriendo = false
        super.onDestroy()
    }
}
