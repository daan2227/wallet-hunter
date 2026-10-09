package com.hunter.btc

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

/**
 * Herramientas de clave pública, cada una una "función" de More:
 *   0 · Addresses   — todas las direcciones de una clave (pública o privada).
 *   1 · Brainwallet — prueba frases (SHA256→privada) contra una dirección.
 *   2 · Nonce audit — busca nonce (k) reutilizado en las firmas de una dirección.
 *
 * El extra "tool" elige cuál se muestra. Todas comparten el marco (título,
 * ScrollView, nota honesta) y viven aquí para no multiplicar actividades.
 */
class PubkeyToolsActivity : Activity() {

    companion object { const val EXTRA_TOOL = "tool" }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var root: LinearLayout

    override fun onCreate(s: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(s)
        AppTheme.init(this)
        AppLock.init(this)

        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        when (intent.getIntExtra(EXTRA_TOOL, 0)) {
            1 -> construirBrainwallet()
            2 -> construirNonce()
            else -> construirDirecciones()
        }
    }

    // ── Marco común ───────────────────────────────────────────────────────
    private fun titulo(t: String, sub: String) {
        root.addView(TextView(this).apply {
            text = t; textSize = 24f; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
        })
        root.addView(TextView(this).apply {
            text = sub; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context); setPadding(0, dp(4), 0, dp(16))
            setLineSpacing(0f, 1.3f)
        })
    }

    private fun rotulo(t: String) = TextView(this).apply {
        text = t; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context); setPadding(0, dp(6), 0, dp(6))
    }

    private fun entrada(hint: String, varias: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setHorizontallyScrolling(!varias)
        if (varias) { maxLines = 8; minLines = 4; gravity = Gravity.TOP or Gravity.START }
        else maxLines = 2
        textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
        typeface = AppTheme.mono(context)
        background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun boton(t: String, click: () -> Unit) = Button(this).apply {
        text = t; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.ON_ACCENT)
        typeface = AppTheme.bold(context); isAllCaps = false; stateListAnimator = null
        background = Ui.botonAccento(this@PubkeyToolsActivity, AppTheme.R_INNER, AppTheme.ACCENT)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
            .apply { topMargin = dp(12) }
        setOnClickListener { Ui.pulso(this); click() }
    }

    private fun nota(color: Int, t: String) {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.cardBg(AppTheme.R_CARD, AppTheme.BG_CARD, context)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        fila.addView(android.view.View(this).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { marginEnd = dp(14) }
        })
        fila.addView(TextView(this).apply {
            text = t; textSize = AppTheme.SP_CAPTION + 1f; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.4f)
            gravity = Gravity.CENTER_VERTICAL
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

    /** Una fila de resultado "etiqueta / valor" que copia el valor al tocarla. */
    private fun filaResultado(etiqueta: String, valor: String, destacado: Boolean = false): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
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
            text = valor; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.mono(context)
            setPadding(0, dp(3), 0, 0)
        })
        return col
    }

    // ── 0 · Addresses ───────────────────────────────────────────────────────
    private fun construirDirecciones() {
        titulo("Addresses from a key",
            "Every address a key maps to — compressed and uncompressed. Paste a public " +
            "key (02/03/04) or a private key (64 hex).")
        root.addView(rotulo("Public or private key (hex)"))
        val et = entrada("02… / 03… / 04… or 64-hex private key", varias = true)
        root.addView(et)
        val salida = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        root.addView(boton("Derive addresses") {
            salida.removeAllViews()
            val txt = try { HunterEngine.direccionesDe(et.text.toString().trim()) }
                      catch (e: Throwable) { "" }
            if (txt.isBlank()) {
                Toast.makeText(this, "Not a valid public or private key", Toast.LENGTH_SHORT).show()
            } else {
                txt.trim().split('\n').forEach { linea ->
                    val i = linea.indexOf('=')
                    if (i > 0) salida.addView(filaResultado(linea.substring(0, i), linea.substring(i + 1)))
                }
                salida.addView(TextView(this).apply {
                    text = "Tap any address to copy it."
                    textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED)
                    typeface = AppTheme.body(context); setPadding(dp(2), dp(10), 0, 0)
                })
            }
        })
        root.addView(salida)
        nota(AppTheme.BLUE,
            "A key is not one address: compressed and uncompressed public keys hash to " +
            "different ones, and each has P2PKH, P2SH-P2WPKH, bech32 and taproot forms. " +
            "Funds could sit on any of them.")
    }

    // ── 1 · Brainwallet ───────────────────────────────────────────────────
    @Volatile private var bwCorriendo = false

    private fun sha256Hex(s: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(64); for (b in d) sb.append("%02x".format(b)); return sb.toString()
    }

    /** Las direcciones de una clave (priv o pub) como conjunto, para cruzar. */
    private fun dirsDe(entrada: String): Set<String> {
        val t = try { HunterEngine.direccionesDe(entrada) } catch (e: Throwable) { "" }
        if (t.isBlank()) return emptySet()
        return t.trim().split('\n').mapNotNull { l ->
            val i = l.indexOf('='); if (i > 0) l.substring(i + 1).trim() else null
        }.toSet()
    }

    private fun construirBrainwallet() {
        titulo("Brainwallet check",
            "Many funds are lost to keys made from a passphrase (private key = " +
            "SHA-256 of the phrase). This tests phrases against a target and tells you " +
            "if that address came from a weak one. Use it on your own addresses.")
        root.addView(rotulo("Target address or public key"))
        val etTarget = entrada("1…/3…/bc1… or 02…/03…/04…")
        root.addView(etTarget)
        root.addView(rotulo("Phrases to try (one per line)"))
        val etFrases = entrada("correct horse battery staple\npassword\n…", varias = true)
        root.addView(etFrases)

        val tvEstado = TextView(this).apply {
            text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context); setPadding(dp(2), dp(12), 0, 0)
        }
        val salida = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        lateinit var btn: Button
        btn = boton("Check") {
            if (bwCorriendo) { bwCorriendo = false; return@boton }
            val objetivo = dirsDe(etTarget.text.toString().trim())
            if (objetivo.isEmpty()) {
                Toast.makeText(this, "Enter a valid target address or public key", Toast.LENGTH_SHORT).show()
                return@boton
            }
            val frases = etFrases.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (frases.isEmpty()) {
                Toast.makeText(this, "Paste some phrases to try", Toast.LENGTH_SHORT).show()
                return@boton
            }
            salida.removeAllViews()
            bwCorriendo = true; btn.text = "Stop"
            Thread {
                var n = 0; var hits = 0
                for (frase in frases) {
                    if (!bwCorriendo) break
                    n++
                    val priv = sha256Hex(frase)
                    val dirs = dirsDe(priv)
                    if (dirs.any { it in objetivo }) {
                        hits++
                        val wif = try { HunterEngine.datosDeClave(priv).substringBefore("|") } catch (e: Throwable) { "" }
                        runOnUiThread {
                            salida.addView(filaResultado("MATCH · \"$frase\"",
                                "priv $priv" + (if (wif.isNotEmpty()) "\nWIF  $wif" else ""), destacado = true))
                        }
                    }
                    if (n % 25 == 0 || n == frases.size) {
                        val hechas = n; val encontr = hits
                        runOnUiThread { tvEstado.text = "Checked $hechas / ${frases.size} · $encontr found" }
                    }
                }
                runOnUiThread {
                    bwCorriendo = false; btn.text = "Check"
                    if (hits == 0) salida.addView(TextView(this).apply {
                        text = "No match. The address did not come from any of these phrases."
                        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED)
                        typeface = AppTheme.body(context); setPadding(dp(2), dp(8), 0, 0)
                    })
                }
            }.apply { isDaemon = true; start() }
        }
        root.addView(btn)
        root.addView(tvEstado)
        root.addView(salida)
        nota(AppTheme.WARN,
            "Only tests the exact phrases you paste (SHA-256 brainwallets). It won't " +
            "find a key made some other way. Meant to audit whether your own address " +
            "used a guessable phrase.")
    }

    // ── 2 · Nonce-reuse audit ─────────────────────────────────────────────
    @Volatile private var nonceCorriendo = false

    private fun hexABytes(h: String): ByteArray? {
        if (h.length % 2 != 0) return null
        val o = ByteArray(h.length / 2)
        for (i in o.indices) {
            val v = h.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            o[i] = v.toByte()
        }
        return o
    }

    /** La r de una firma DER (sin el byte de sighash final), en hex sin ceros. */
    private fun derR(sigHex: String): String? {
        val b = hexABytes(sigHex) ?: return null
        if (b.size < 8 || (b[0].toInt() and 0xFF) != 0x30 || (b[2].toInt() and 0xFF) != 0x02) return null
        val rlen = b[3].toInt() and 0xFF
        if (4 + rlen > b.size) return null
        var start = 4; var len = rlen
        while (len > 1 && b[start].toInt() == 0) { start++; len-- }   // quitar ceros de relleno
        val sb = StringBuilder()
        for (i in start until start + len) sb.append("%02x".format(b[i].toInt() and 0xFF))
        return sb.toString()
    }

    private fun esPub(t: String) =
        (t.length == 66 && (t.startsWith("02") || t.startsWith("03"))) ||
        (t.length == 130 && t.startsWith("04"))

    /** (pubkey, r) de un input, de su witness o de su scriptsig_asm. */
    private fun firmaDe(vin: org.json.JSONObject): Pair<String, String>? {
        val w = vin.optJSONArray("witness")
        if (w != null && w.length() >= 2) {
            val sig = w.optString(0, ""); val pub = w.optString(w.length() - 1, "")
            val r = derR(sig)
            if (r != null && esPub(pub.lowercase())) return pub.lowercase() to r
        }
        val asm = vin.optString("scriptsig_asm", "")
        if (asm.isNotEmpty()) {
            val toks = asm.split(' ').filter { !it.startsWith("OP_") && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
            val pub = toks.firstOrNull { esPub(it.lowercase()) }?.lowercase()
            val sig = toks.firstOrNull { it.startsWith("30") && it.length > 16 }
            if (pub != null && sig != null) { val r = derR(sig); if (r != null) return pub to r }
        }
        return null
    }

    private fun construirNonce() {
        titulo("Nonce-reuse audit",
            "The deadliest ECDSA flaw: if an address signs two inputs with the same " +
            "random k (nonce), its private key can be recovered from those signatures. " +
            "This scans a spent address's transactions and flags a reused nonce.")
        root.addView(rotulo("Spent address"))
        val etAddr = entrada("1…/3…/bc1… (must have spent at least once)")
        root.addView(etAddr)

        val tvEstado = TextView(this).apply {
            text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context); setPadding(dp(2), dp(12), 0, 0)
        }
        val salida = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        lateinit var btn: Button
        btn = boton("Scan signatures") {
            if (nonceCorriendo) { nonceCorriendo = false; return@boton }
            val addr = etAddr.text.toString().trim()
            if (addr.isEmpty()) { Toast.makeText(this, "Enter an address", Toast.LENGTH_SHORT).show(); return@boton }
            salida.removeAllViews()
            nonceCorriendo = true; btn.text = "Stop"
            tvEstado.text = "Fetching transactions…"
            Thread {
                // pub -> (r -> txid de la primera vez que se vio)
                val visto = HashMap<String, HashMap<String, String>>()
                val reusos = ArrayList<Triple<String, String, Pair<String, String>>>() // pub, r, (txid1,txid2)
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
                        val txid = tx.optString("txid", "")
                        ultimo = txid
                        val vins = tx.optJSONArray("vin") ?: continue
                        for (vi in 0 until vins.length()) {
                            val par = firmaDe(vins.optJSONObject(vi) ?: continue) ?: continue
                            firmas++
                            val (pub, r) = par
                            val m = visto.getOrPut(pub) { HashMap() }
                            val previo = m[r]
                            if (previo != null && previo != txid + "#" + vi) {
                                reusos.add(Triple(pub, r, previo.substringBefore("#") to txid))
                            } else if (previo == null) m[r] = txid + "#" + vi
                        }
                        txs++
                    }
                    val hechas = txs
                    runOnUiThread { tvEstado.text = "Scanned $hechas tx · $firmas signatures" }
                    if (arr.length() < 25) break@bucle   // última página
                }
                runOnUiThread {
                    nonceCorriendo = false; btn.text = "Scan signatures"
                    when {
                        !red -> tvEstado.text = "No network, or address not found / never spent."
                        reusos.isNotEmpty() -> {
                            tvEstado.text = "⚠ Reused nonce found · $txs tx · $firmas signatures"
                            reusos.distinctBy { it.first + it.second }.forEach { (pub, r, txs2) ->
                                salida.addView(filaResultado("VULNERABLE · reused r",
                                    "pubkey ${pub.take(16)}…\nr ${r.take(20)}…\ntx1 ${txs2.first.take(16)}…\ntx2 ${txs2.second.take(16)}…",
                                    destacado = true))
                            }
                            salida.addView(TextView(this).apply {
                                text = "The private key is recoverable from these two signatures " +
                                       "(same r ⇒ same k). This address is compromised — move any funds."
                                textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.WARN)
                                typeface = AppTheme.body(context); setLineSpacing(0f, 1.35f)
                                setPadding(dp(2), dp(10), 0, 0)
                            })
                        }
                        else -> tvEstado.text = "Clean · $txs tx · $firmas signatures · no nonce reuse"
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        root.addView(btn)
        root.addView(tvEstado)
        root.addView(salida)
        nota(AppTheme.BLUE,
            "Reads the address's transactions from a public explorer and compares the r " +
            "value of every signature. Two equal r for the same public key means the " +
            "nonce repeated. Scans the most recent transactions (several pages).")
    }

    override fun onDestroy() { bwCorriendo = false; nonceCorriendo = false; super.onDestroy() }
}
