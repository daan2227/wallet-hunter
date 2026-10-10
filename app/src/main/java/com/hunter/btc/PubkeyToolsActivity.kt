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

        cabecera(page, "Keys & addresses")
        seccion(page, R.drawable.ic_search, "Addresses from a key") { construirDirecciones() }
        seccion(page, R.drawable.ic_copy,   "Hex ↔ WIF")            { construirWifHex() }
        seccion(page, R.drawable.ic_eye,    "Key / address inspector") { construirInspector() }
        seccion(page, R.drawable.ic_import, "Mnemonic tools (BIP39)") { construirMnemonic() }
        seccion(page, R.drawable.ic_recovery, "Custom derivation path") { construirDerivacion() }
        seccion(page, R.drawable.ic_recovery, "xpub → addresses")     { construirXpub() }

        cabecera(page, "Sign, encrypt & backup")
        seccion(page, R.drawable.ic_finger, "Sign & verify message") { construirFirma() }
        seccion(page, R.drawable.ic_lock,   "BIP38 (encrypt key)")  { construirBip38() }
        seccion(page, R.drawable.ic_lock,   "Shamir split (k-of-n)") { construirShamir() }
        seccion(page, R.drawable.ic_lock,   "Key split (XOR)")      { construirSplit() }

        cabecera(page, "Transactions")
        seccion(page, R.drawable.ic_wallet, "Balance & UTXOs (watch-only)") { construirBalance() }
        seccion(page, R.drawable.ic_clock,  "Fee estimator")        { construirFee() }
        seccion(page, R.drawable.ic_gear,   "Tx size & fee calc")   { construirFeeCalc() }
        seccion(page, R.drawable.ic_send,   "Sweep a key")          { construirSweep() }
        seccion(page, R.drawable.ic_play,   "Broadcast raw tx")     { construirBroadcast() }
        seccion(page, R.drawable.ic_export, "Transaction decoder")  { construirDecoder() }
        seccion(page, R.drawable.ic_import, "PSBT (decode / sign)") { construirPsbt() }
        seccion(page, R.drawable.ic_search, "Script decoder")       { construirScript() }

        cabecera(page, "Monitor & generate")
        seccion(page, R.drawable.ic_notif,  "Watch addresses")      { construirWatch() }
        seccion(page, R.drawable.ic_dice,   "Vanity address")       { construirVanity() }
        seccion(page, R.drawable.ic_dice,   "Dice / coin → key")    { construirDados() }
        seccion(page, R.drawable.ic_receive,"QR code")              { construirQr() }

        cabecera(page, "Audit & analysis")
        seccion(page, R.drawable.ic_warning,"Nonce-reuse audit")    { construirNonce() }
        seccion(page, R.drawable.ic_edit,   "Brainwallet check")    { construirBrainwallet() }
        seccion(page, R.drawable.ic_eye,    "Entropy checker")      { construirEntropia() }
        seccion(page, R.drawable.ic_clock,  "Difficulty / time")    { construirDificultad() }

        cabecera(page, "Utility")
        seccion(page, R.drawable.ic_copy,   "BTC ↔ sat")            { construirUnidades() }
    }

    /** Cabecera de categoría entre grupos de herramientas. */
    private fun cabecera(page: LinearLayout, t: String) {
        page.addView(TextView(this).apply {
            text = t.uppercase(); textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.bold(context); letterSpacing = 0.08f
            setPadding(dp(20), dp(22), dp(20), dp(6))
        })
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

    // ── Sign & verify message ─────────────────────────────────────────────
    private fun hexClave(k: String): String {
        val t = k.trim().removePrefix("0x")
        if (t.length == 64 && t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return t.lowercase()
        return try { HunterEngine.hexDeWif(t) } catch (e: Throwable) { "" }
    }
    private fun construirFirma() {
        desc("Prove you control an address by signing a message with its key — and verify " +
             "anyone else's signed message. Bitcoin's standard signed-message format.")
        root.addView(rotulo("Sign — private key (hex or WIF)"))
        val etKey = entrada("64-hex or WIF")
        root.addView(rotulo("Message"))
        val etMsg = entrada("your message", varias = true)
        val salFirma = salidaBox()
        boton("Sign") {
            salFirma.removeAllViews()
            val hx = hexClave(etKey.text.toString())
            if (hx.isBlank()) { Toast.makeText(this, "Invalid private key", Toast.LENGTH_SHORT).show(); return@boton }
            val sigHex = try { HunterEngine.firmarMensaje(hx, etMsg.text.toString()) } catch (e: Throwable) { "" }
            if (sigHex.isBlank()) { Toast.makeText(this, "Could not sign", Toast.LENGTH_SHORT).show(); return@boton }
            val b64 = try {
                val raw = ByteArray(65) { sigHex.substring(it*2, it*2+2).toInt(16).toByte() }
                android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
            } catch (e: Throwable) { "" }
            val quien = try { HunterEngine.verificarMensaje(etMsg.text.toString(), sigHex) } catch (e: Throwable) { "" }
            salFirma.addView(filaResultado("Signature (base64)", b64, true))
            if (quien.isNotEmpty()) salFirma.addView(filaResultado("Signed by address", quien))
        }

        root.addView(android.view.View(this).apply {
            setBackgroundColor(AppTheme.BORDER_C)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                .apply { topMargin = dp(18); bottomMargin = dp(6) }
        })
        root.addView(rotulo("Verify — address"))
        val etA = entrada("1… address that claims to have signed")
        root.addView(rotulo("Message"))
        val etM2 = entrada("the message", varias = true)
        root.addView(rotulo("Signature (base64)"))
        val etS = entrada("H…/I… base64 signature", varias = true)
        val salVer = salidaBox()
        boton("Verify") {
            salVer.removeAllViews()
            val sigHex = try {
                val raw = android.util.Base64.decode(etS.text.toString().trim(), android.util.Base64.DEFAULT)
                if (raw.size != 65) "" else raw.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            } catch (e: Throwable) { "" }
            if (sigHex.isBlank()) { Toast.makeText(this, "Signature must be 65 bytes base64", Toast.LENGTH_SHORT).show(); return@boton }
            val quien = try { HunterEngine.verificarMensaje(etM2.text.toString(), sigHex) } catch (e: Throwable) { "" }
            val addr = etA.text.toString().trim()
            when {
                quien.isBlank() -> salVer.addView(filaResultado("Invalid signature", "could not recover a key"))
                quien == addr -> salVer.addView(filaResultado("✓ VALID", "signed by $quien", true))
                else -> salVer.addView(filaResultado("✗ Does NOT match", "recovered $quien"))
            }
        }
        nota(AppTheme.BLUE, "Signing never touches funds — it just proves control of the key. " +
             "Verify recovers the signer's address from the signature and compares it.")
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

    // ── xpub → addresses ───────────────────────────────────────────────────
    private fun construirXpub() {
        desc("First receiving addresses (m/0/i) of an extended public key. The address " +
             "type follows the key: xpub→P2PKH, ypub→P2SH-segwit, zpub→bech32.")
        val et = entrada("xpub…/ypub…/zpub…", varias = true)
        root.addView(rotulo("How many"))
        val etN = EditText(this).apply {
            setText("10"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.mono(context)
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(etN)
        val salida = salidaBox()
        boton("Derive") {
            salida.removeAllViews()
            val n = etN.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 10
            val txt = try { HunterEngine.xpubDirecciones(et.text.toString().trim(), n) } catch (e: Throwable) { "" }
            if (txt.isBlank()) Toast.makeText(this, "Not a valid xpub/ypub/zpub", Toast.LENGTH_SHORT).show()
            else txt.trim().split('\n').forEach { l ->
                val i = l.indexOf('='); if (i > 0) salida.addView(filaResultado("m/0/${l.substring(0, i)}", l.substring(i + 1)))
            }
        }
        nota(AppTheme.BLUE, "Watch-only: an xpub derives addresses but never the private keys. " +
             "Handy to audit or monitor a hardware/watch-only wallet.")
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

    // ── Sweep a key ────────────────────────────────────────────────────────
    @Volatile private var sweepCorriendo = false
    private fun construirSweep() {
        desc("Move ALL funds from a private key you control to an address. For compressed " +
             "P2PKH keys (hex or K/L WIF) — e.g. a find from the vault. Shows a confirmation " +
             "before broadcasting.")
        root.addView(rotulo("Private key (hex or WIF)"))
        val etKey = entrada("64-hex or K/L WIF")
        root.addView(rotulo("Destination address"))
        val etTo = entrada("where to send everything")
        root.addView(rotulo("Fee (sats)"))
        val etFee = EditText(this).apply {
            setText("500"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.mono(context)
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(etFee)
        val tvEstado = TextView(this).apply { text = ""; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context); setPadding(dp(2), dp(12), 0, 0) }
        lateinit var btn: Button
        btn = boton("Prepare sweep") {
            if (sweepCorriendo) return@boton
            val hx = hexClave(etKey.text.toString())
            val to = etTo.text.toString().trim()
            val fee = etFee.text.toString().toLongOrNull() ?: 500L
            if (hx.isBlank()) { Toast.makeText(this, "Invalid private key", Toast.LENGTH_SHORT).show(); return@boton }
            if (BtcAddress.validate(to, false) !is BtcAddress.Result.Valid) { Toast.makeText(this, "Invalid destination", Toast.LENGTH_SHORT).show(); return@boton }
            sweepCorriendo = true; btn.text = "…"; tvEstado.text = "Reading UTXOs…"
            Thread {
                val wif = try { HunterEngine.wifDeHex(hx).substringBefore("|") } catch (e: Throwable) { "" }
                val dirTxt = try { HunterEngine.direccionesDe(hx) } catch (e: Throwable) { "" }
                val addr = dirTxt.split('\n').firstOrNull { it.startsWith("P2PKH (compressed)=") }?.substringAfter('=') ?: ""
                if (wif.isBlank() || !(wif[0] == 'K' || wif[0] == 'L') || addr.isBlank()) {
                    runOnUiThread { sweepCorriendo = false; btn.text = "Prepare sweep"; tvEstado.text = "Only compressed P2PKH keys are supported here." }; return@Thread
                }
                val utxoStr = ChainApi.get("/address/$addr/utxo")
                val tip = ChainApi.get("/blocks/tip/height")?.trim()?.toIntOrNull() ?: 0
                if (utxoStr == null) { runOnUiThread { sweepCorriendo = false; btn.text = "Prepare sweep"; tvEstado.text = "No network." }; return@Thread }
                try {
                    val a = org.json.JSONArray(utxoStr); var total = 0L
                    val arr = org.json.JSONArray()
                    for (i in 0 until a.length()) {
                        val u = a.getJSONObject(i); val v = u.optLong("value", 0); total += v
                        arr.put(org.json.JSONObject().put("txid", u.getString("txid"))
                            .put("vout", u.getInt("vout")).put("amount", v))
                    }
                    val envio = total - fee
                    if (a.length() == 0 || envio <= 0) {
                        runOnUiThread { sweepCorriendo = false; btn.text = "Prepare sweep"
                            tvEstado.text = if (a.length() == 0) "No funds on ${addr.take(12)}…" else "Fee is larger than the balance." }; return@Thread
                    }
                    val req = org.json.JSONObject().put("wif", wif).put("utxos", arr)
                        .put("to", to).put("amount", envio).put("fee", fee).put("locktime", tip)
                    val raw = try { HunterEngine.buildAndSignTx(req.toString()) } catch (e: Throwable) { "ERROR:build" }
                    runOnUiThread {
                        sweepCorriendo = false; btn.text = "Prepare sweep"
                        if (raw.startsWith("ERROR") || raw.length < 20) { tvEstado.text = "Could not build: $raw"; return@runOnUiThread }
                        tvEstado.text = "Ready: ${satsABtc(envio)} to ${to.take(14)}…"
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Broadcast sweep?")
                            .setMessage("Send ${satsABtc(envio)} (fee ${fee} sats) to\n$to\n\nThis is irreversible.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Broadcast") { _, _ ->
                                tvEstado.text = "Broadcasting…"
                                Thread {
                                    val r = ChainApi.broadcast(raw)
                                    runOnUiThread {
                                        tvEstado.text = when (r) {
                                            is ChainApi.Envio.Ok -> "✓ Sent · txid ${r.txid.take(20)}…"
                                            is ChainApi.Envio.Rechazada -> "Rejected: ${r.motivo}"
                                            else -> "No response — try again."
                                        }
                                    }
                                }.apply { isDaemon = true; start() }
                            }.show()
                    }
                } catch (e: Throwable) {
                    runOnUiThread { sweepCorriendo = false; btn.text = "Prepare sweep"; tvEstado.text = "Could not read UTXOs." }
                }
            }.apply { isDaemon = true; start() }
        }
        root.addView(tvEstado)
        nota(AppTheme.WARN, "Sweeping spends real funds and cannot be undone. Double-check the " +
             "destination. Only use keys you control.")
    }

    // ── Watch addresses ────────────────────────────────────────────────────
    @Volatile private var watchCorriendo = false
    private fun construirWatch() {
        desc("Keep an eye on addresses: it stores their balance and flags any change since " +
             "the last check. Checks when you open this screen and when you tap Check.")
        val wp = getSharedPreferences("watchlist", MODE_PRIVATE)
        val et = entrada("addresses to watch, one per line", varias = true)
        et.setText(wp.getString("addrs", "") ?: "")
        val salida = salidaBox()
        fun chequear() {
            if (watchCorriendo) return
            val addrs = et.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            wp.edit().putString("addrs", addrs.joinToString("\n")).apply()
            if (addrs.isEmpty()) { salida.removeAllViews(); return }
            watchCorriendo = true; salida.removeAllViews()
            salida.addView(TextView(this).apply { text = "Checking…"; textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context) })
            Thread {
                val filas = ArrayList<Triple<String, Long, Long>>()
                var cambios = 0
                for (a in addrs) {
                    val info = ChainApi.get("/address/$a") ?: continue
                    val saldo = try { val o = org.json.JSONObject(info); val cs = o.getJSONObject("chain_stats")
                        cs.getLong("funded_txo_sum") - cs.getLong("spent_txo_sum") } catch (e: Throwable) { continue }
                    val prev = wp.getLong("bal_$a", Long.MIN_VALUE)
                    if (prev != Long.MIN_VALUE && prev != saldo) cambios++
                    wp.edit().putLong("bal_$a", saldo).apply()
                    filas.add(Triple(a, saldo, prev))
                }
                runOnUiThread {
                    watchCorriendo = false; salida.removeAllViews()
                    for ((a, saldo, prev) in filas) {
                        val marca = when { prev == Long.MIN_VALUE -> "" ; saldo > prev -> " ▲" ; saldo < prev -> " ▼" ; else -> "" }
                        salida.addView(filaResultado(a.take(22) + (if (a.length > 22) "…" else "") + marca, satsABtc(saldo)))
                    }
                    if (filas.isEmpty()) salida.addView(TextView(this).apply { text = "No network, or no addresses resolved."
                        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED); typeface = AppTheme.body(context) })
                    if (cambios > 0 && Avisos.notifOn(this))
                        Avisos.hallazgo(this, "Balance changed", "$cambios watched address(es) changed since last check")
                }
            }.apply { isDaemon = true; start() }
        }
        boton("Save & check") { chequear() }
        if ((wp.getString("addrs", "") ?: "").isNotBlank()) chequear()   // chequeo automático al abrir
        nota(AppTheme.BLUE, "Checks happen when this screen is open or you tap Check — there's " +
             "no always-on background polling. A change fires a notification if alerts are on.")
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

    // ── QR code ─────────────────────────────────────────────────────────────
    private fun qrBitmap(content: String, size: Int): android.graphics.Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter()
            .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val w = m.width; val h = m.height; val px = IntArray(w * h)
        for (y in 0 until h) { val row = y * w; for (x in 0 until w)
            px[row + x] = if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.RGB_565)
    }
    private fun construirQr() {
        desc("A QR for any address, key or bitcoin: URI — to scan or print on paper.")
        val et = entrada("address, key, or bitcoin:… URI", varias = true)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE); cornerRadius = dp(16).toFloat() }
            setPadding(dp(16), dp(16), dp(16), dp(16)); visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL }
        }
        val iv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220))
        }
        card.addView(iv)
        boton("Make QR") {
            val txt = et.text.toString().trim()
            if (txt.isEmpty()) { Toast.makeText(this, "Enter something", Toast.LENGTH_SHORT).show(); return@boton }
            try { iv.setImageBitmap(qrBitmap(txt, 512)); card.visibility = android.view.View.VISIBLE }
            catch (e: Throwable) { Toast.makeText(this, "Too long for a QR", Toast.LENGTH_SHORT).show() }
        }
        root.addView(card)
    }

    // ── Mnemonic tools (BIP39) ─────────────────────────────────────────────
    private fun construirMnemonic() {
        desc("Validate a BIP39 seed phrase, generate a fresh one, and see its first " +
             "addresses. The phrase never leaves the device.")
        val et = entrada("12 or 24 words — or tap Generate", varias = true)
        val salida = salidaBox()
        boton("Generate new (12 words)") { et.setText(Bip39.generate(12)); salida.removeAllViews() }
        boton("Validate & derive") {
            salida.removeAllViews()
            val m = et.text.toString().trim().lowercase().replace(Regex("\\s+"), " ")
            when (val r = Bip39.validate(m)) {
                is Bip39.Result.Invalid -> salida.addView(filaResultado("Invalid phrase", r.reason))
                else -> {
                    salida.addView(filaResultado("Valid BIP39 phrase", "checksum OK", true))
                    val json = try { HunterEngine.deriveWallet(m, false) } catch (e: Throwable) { "" }
                    try {
                        val o = org.json.JSONObject(json)
                        listOf("p2pkh_0" to "P2PKH (BIP44)", "p2sh_0" to "P2SH-P2WPKH (BIP49)",
                               "p2wpkh_0" to "P2WPKH (BIP84)", "p2tr_0" to "P2TR (BIP86)").forEach { (k, lbl) ->
                            if (o.has(k)) salida.addView(filaResultado(lbl, o.getString(k)))
                        }
                    } catch (e: Throwable) {}
                }
            }
        }
        nota(AppTheme.WARN, "A generated phrase controls real funds if you send to it. " +
             "Write it down offline; anyone with the phrase has the money.")
    }

    // ── Custom derivation path ─────────────────────────────────────────────
    private fun construirDerivacion() {
        desc("Derive the key at an exact BIP32 path from a seed phrase — e.g. the 6th " +
             "receiving address of your native-segwit account.")
        root.addView(rotulo("Seed phrase"))
        val etM = entrada("12 or 24 words", varias = true)
        root.addView(rotulo("Path"))
        val etP = entrada("m/84'/0'/0'/0/0")
        val salida = salidaBox()
        boton("Derive") {
            salida.removeAllViews()
            val m = etM.text.toString().trim().lowercase().replace(Regex("\\s+"), " ")
            val p = etP.text.toString().trim()
            if (m.isEmpty() || p.isEmpty()) { Toast.makeText(this, "Enter a phrase and a path", Toast.LENGTH_SHORT).show(); return@boton }
            val priv = try { HunterEngine.deriveRuta(m, p) } catch (e: Throwable) { "" }
            if (priv.isBlank()) { Toast.makeText(this, "That path gives no valid key", Toast.LENGTH_SHORT).show(); return@boton }
            val wif = try { HunterEngine.wifDeHex(priv).substringBefore("|") } catch (e: Throwable) { "" }
            salida.addView(filaResultado("Private key", priv, true))
            if (wif.isNotEmpty()) salida.addView(filaResultado("WIF", wif))
            val txt = try { HunterEngine.direccionesDe(priv) } catch (e: Throwable) { "" }
            // La dirección que corresponde a la ruta: por el propósito del path.
            val lbl = when {
                p.contains("/86'") -> "P2TR (taproot)"
                p.contains("/84'") -> "P2WPKH (bech32)"
                p.contains("/49'") -> "P2SH-P2WPKH"
                else -> "P2PKH (compressed)"
            }
            val addr = txt.split('\n').firstOrNull { it.startsWith("$lbl=") }?.substringAfter('=')
            if (addr != null) salida.addView(filaResultado("Address · ${lbl.substringBefore(' ')}", addr))
        }
        nota(AppTheme.BLUE, "Hardened levels use ' (or h). Common accounts: BIP44 m/44'/0'/0'/0/i, " +
             "BIP49 m/49'/0'/0'/0/i, BIP84 m/84'/0'/0'/0/i, BIP86 m/86'/0'/0'/0/i.")
    }

    // ── Fee estimator ──────────────────────────────────────────────────────
    private fun construirFee() {
        desc("Current recommended fee rates (sat/vB) from a public explorer.")
        val salida = salidaBox()
        boton("Fetch fees") {
            salida.removeAllViews()
            salida.addView(TextView(this).apply { text = "Fetching…"; textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context) })
            Thread {
                val s = ChainApi.get("/fee-estimates")
                runOnUiThread {
                    salida.removeAllViews()
                    if (s == null) { salida.addView(TextView(this).apply { text = "No network."
                        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED); typeface = AppTheme.body(context) }); return@runOnUiThread }
                    try {
                        val o = org.json.JSONObject(s)
                        fun tier(block: String, label: String) { if (o.has(block))
                            salida.addView(filaResultado(label, "%.1f sat/vB".format(o.getDouble(block)))) }
                        tier("1", "Next block (~10 min)"); tier("3", "~30 min"); tier("6", "~1 hour"); tier("144", "~1 day")
                    } catch (e: Throwable) { salida.addView(TextView(this).apply { text = "Could not read fees."
                        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED); typeface = AppTheme.body(context) }) }
                }
            }.apply { isDaemon = true; start() }
        }
    }

    // ── Broadcast raw tx ───────────────────────────────────────────────────
    @Volatile private var bcCorriendo = false
    private fun construirBroadcast() {
        desc("Paste a signed raw transaction (hex) and push it to the network. For a tx " +
             "signed elsewhere.")
        val et = entrada("signed raw tx hex", varias = true)
        val tv = TextView(this).apply { text = ""; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context); setPadding(dp(2), dp(12), 0, 0) }
        boton("Broadcast") {
            if (bcCorriendo) return@boton
            val raw = et.text.toString().trim().removePrefix("0x")
            if (raw.length < 20 || raw.any { it.lowercaseChar() !in "0123456789abcdef" }) {
                Toast.makeText(this, "Enter a raw tx in hex", Toast.LENGTH_SHORT).show(); return@boton }
            bcCorriendo = true; tv.text = "Broadcasting…"
            Thread {
                val r = ChainApi.broadcast(raw)
                runOnUiThread { bcCorriendo = false
                    tv.text = when (r) {
                        is ChainApi.Envio.Ok -> "✓ Sent · txid ${r.txid}"
                        is ChainApi.Envio.Rechazada -> "Rejected: ${r.motivo}"
                        else -> "No response — try again."
                    } }
            }.apply { isDaemon = true; start() }
        }
        root.addView(tv)
    }

    // ── Transaction decoder ────────────────────────────────────────────────
    private fun construirDecoder() {
        desc("Paste a raw transaction (hex) to read it: version, inputs, outputs with " +
             "amount and type, locktime and size. Offline — no signatures checked.")
        val et = entrada("raw tx hex", varias = true)
        val salida = salidaBox()
        boton("Decode") {
            salida.removeAllViews()
            val filas = TxDecoder.decode(et.text.toString())
            if (filas == null) Toast.makeText(this, "Not a valid raw transaction", Toast.LENGTH_SHORT).show()
            else filas.forEach { (l, v) -> salida.addView(filaResultado(l, v)) }
        }
    }

    private fun campoNum(ini: String, ancho: Int = 90): EditText {
        val e = EditText(this).apply {
            setText(ini); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.mono(context)
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(ancho), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(e); return e
    }

    // ── Tx size & fee calculator ───────────────────────────────────────────
    private fun construirFeeCalc() {
        desc("Estimate a transaction's vsize and fee from its shape. Pick the input type, " +
             "the counts and a fee rate.")
        var tipo = 1   // 0 P2PKH, 1 P2WPKH, 2 P2SH-P2WPKH, 3 P2TR
        root.addView(Ui.segmented(this, listOf("P2PKH" to null, "P2WPKH" to null, "P2SH" to null, "P2TR" to null), 1) { tipo = it })
        root.addView(rotulo("Inputs"));  val etIn = campoNum("1")
        root.addView(rotulo("Outputs")); val etOut = campoNum("2")
        root.addView(rotulo("Fee rate (sat/vB)")); val etR = campoNum("10", 110)
        val salida = salidaBox()
        boton("Calculate") {
            salida.removeAllViews()
            val nin = etIn.text.toString().toIntOrNull()?.coerceIn(1, 5000) ?: 1
            val nout = etOut.text.toString().toIntOrNull()?.coerceIn(1, 5000) ?: 1
            val rate = etR.text.toString().toDoubleOrNull() ?: 1.0
            val vin = when (tipo) { 0 -> 148.0; 2 -> 91.0; 3 -> 57.5; else -> 68.0 }
            val vout = 31.0   // salida bech32 típica
            val vsize = (10.5 + nin * vin + nout * vout)
            val fee = Math.ceil(vsize * rate).toLong()
            salida.addView(filaResultado("Estimated vsize", "${Math.ceil(vsize).toInt()} vB", true))
            salida.addView(filaResultado("Fee", "$fee sats (${"%.8f".format(fee / 1e8).trimEnd('0').trimEnd('.')} BTC)"))
        }
        nota(AppTheme.BLUE, "Rough estimate: ~148 vB per P2PKH input, ~68 P2WPKH, ~91 P2SH-P2WPKH, " +
             "~58 P2TR, ~31 per output, +10.5 overhead.")
    }

    // ── PSBT (decode / sign) ───────────────────────────────────────────────
    private fun construirPsbt() {
        desc("Read and sign a PSBT (BIP-174). Paste base64 or hex. Decode shows the " +
             "transaction; Sign adds your partial signatures for the inputs you control.")
        val et = entrada("cHNidP8… (base64) or hex", varias = true)
        val salida = salidaBox()
        fun leer(): Psbt.Doc? {
            val t = et.text.toString().trim()
            val data = try {
                if (t.isNotEmpty() && t.length % 2 == 0 && t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) hexABytes(t)
                else android.util.Base64.decode(t, android.util.Base64.DEFAULT)
            } catch (e: Throwable) { null } ?: return null
            return Psbt.parse(data)
        }
        boton("Decode") {
            salida.removeAllViews()
            val d = leer()
            if (d == null) { Toast.makeText(this, "Not a valid PSBT", Toast.LENGTH_SHORT).show(); return@boton }
            Psbt.resumen(d).forEach { (l, v) -> salida.addView(filaResultado(l, v)) }
        }
        root.addView(rotulo("Sign — private key (hex or WIF)"))
        val etKey = entrada("64-hex or WIF")
        boton("Sign") {
            salida.removeAllViews()
            val d = leer()
            if (d == null) { Toast.makeText(this, "Not a valid PSBT", Toast.LENGTH_SHORT).show(); return@boton }
            val hx = hexClave(etKey.text.toString())
            if (hx.isBlank()) { Toast.makeText(this, "Invalid key", Toast.LENGTH_SHORT).show(); return@boton }
            val n = Psbt.sign(d, hx)
            if (n == 0) salida.addView(filaResultado("Nothing signed", "no inputs match this key, or unsupported type"))
            else {
                val out = android.util.Base64.encodeToString(Psbt.serialize(d), android.util.Base64.NO_WRAP)
                salida.addView(filaResultado("Signed $n input(s) · updated PSBT", out, true))
            }
        }
        nota(AppTheme.BLUE, "Signs P2WPKH and P2PKH inputs whose key you hold (from witness_utxo " +
             "or non_witness_utxo). Adds partial signatures — it does not finalize or broadcast.")
    }

    // ── Script decoder ─────────────────────────────────────────────────────
    private val opNames = mapOf(0x00 to "OP_0", 0x4c to "OP_PUSHDATA1", 0x4d to "OP_PUSHDATA2", 0x4e to "OP_PUSHDATA4",
        0x4f to "OP_1NEGATE", 0x61 to "OP_NOP", 0x63 to "OP_IF", 0x64 to "OP_NOTIF", 0x67 to "OP_ELSE", 0x68 to "OP_ENDIF",
        0x69 to "OP_VERIFY", 0x6a to "OP_RETURN", 0x6f to "OP_3DUP", 0x76 to "OP_DUP", 0x78 to "OP_SWAP",
        0x87 to "OP_EQUAL", 0x88 to "OP_EQUALVERIFY", 0x8b to "OP_1ADD", 0xa6 to "OP_RIPEMD160", 0xa7 to "OP_SHA1",
        0xa8 to "OP_SHA256", 0xa9 to "OP_HASH160", 0xaa to "OP_HASH256", 0xac to "OP_CHECKSIG", 0xad to "OP_CHECKSIGVERIFY",
        0xae to "OP_CHECKMULTISIG", 0xaf to "OP_CHECKMULTISIGVERIFY", 0xb1 to "OP_CLTV", 0xb2 to "OP_CSV")
    private fun construirScript() {
        desc("Disassemble a Bitcoin script (hex) — scriptPubKey, scriptSig or redeem — into " +
             "its opcodes and pushes.")
        val et = entrada("script hex", varias = true)
        val salida = salidaBox()
        boton("Decode") {
            salida.removeAllViews()
            val b = hexABytes(et.text.toString().trim().removePrefix("0x"))
            if (b == null) { Toast.makeText(this, "Not valid hex", Toast.LENGTH_SHORT).show(); return@boton }
            val out = StringBuilder(); var p = 0
            try {
                while (p < b.size) {
                    val op = b[p].toInt() and 0xFF; p++
                    when {
                        op in 0x01..0x4b -> { val n = op; val d = b.copyOfRange(p, minOf(b.size, p+n)); p += n
                            out.append("PUSH($n) ").append(d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }).append('\n') }
                        op == 0x4c -> { val n = b[p].toInt() and 0xFF; p++; val d = b.copyOfRange(p, minOf(b.size, p+n)); p += n
                            out.append("OP_PUSHDATA1 ").append(d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }).append('\n') }
                        op in 0x51..0x60 -> out.append("OP_").append(op - 0x50).append('\n')
                        else -> out.append(opNames[op] ?: "OP_%02x".format(op)).append('\n')
                    }
                }
                salida.addView(filaResultado("Disassembly", out.toString().trim()))
            } catch (e: Throwable) { Toast.makeText(this, "Could not parse", Toast.LENGTH_SHORT).show() }
        }
    }

    // ── Dice / coin → key ──────────────────────────────────────────────────
    private fun construirDados() {
        desc("Build a private key from PHYSICAL entropy — dice (1-6) or coin flips (0/1) you " +
             "roll yourself. Trustless: no RNG involved. The key is SHA-256 of your rolls.")
        val et = entrada("e.g. 4 2 6 1 5 3 …  or  0 1 1 0 1 …", varias = true)
        val tv = TextView(this).apply { text = ""; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context); setPadding(dp(2), dp(8), 0, 0) }
        val salida = salidaBox()
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                var bits = 0.0
                for (c in (s?.toString() ?: "")) bits += when { c in '1'..'6' -> 2.585; c == '0' || c == '1' -> 1.0; else -> 0.0 }
                tv.text = "≈ ${bits.toInt()} bits of entropy" + (if (bits < 128) " — roll more (aim for 128+)" else " ✓")
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        root.addView(tv)
        boton("Make key") {
            salida.removeAllViews()
            val seq = et.text.toString().filter { it in '0'..'9' }
            if (seq.length < 20) { Toast.makeText(this, "Give more rolls", Toast.LENGTH_SHORT).show(); return@boton }
            val priv = sha256Hex(seq)
            val wif = try { HunterEngine.wifDeHex(priv).substringBefore("|") } catch (e: Throwable) { "" }
            salida.addView(filaResultado("Private key", priv, true))
            if (wif.isNotEmpty()) salida.addView(filaResultado("WIF", wif))
            val txt = try { HunterEngine.direccionesDe(priv) } catch (e: Throwable) { "" }
            txt.split('\n').firstOrNull { it.startsWith("P2WPKH") }?.let { salida.addView(filaResultado("Address (bech32)", it.substringAfter('='))) }
        }
        nota(AppTheme.WARN, "Only trustless if YOU roll real dice/coins and type them. Don't reuse " +
             "a sequence, and keep it secret — it IS the key.")
    }

    // ── Shamir split (k-of-n) ──────────────────────────────────────────────
    private val gfExp by lazy { val e = IntArray(512); var a = 1; for (i in 0 until 255) { e[i] = a; var b = a shl 1; if (a and 0x80 != 0) b = b xor 0x11b; a = (b xor a) and 0xff }; for (i in 255 until 512) e[i] = e[i-255]; e }
    private val gfLog by lazy { val l = IntArray(256); for (i in 0 until 255) l[gfExp[i]] = i; l }
    private fun gmul(x: Int, y: Int) = if (x == 0 || y == 0) 0 else gfExp[gfLog[x] + gfLog[y]]
    private fun construirShamir() {
        desc("Real secret sharing: split a key into n shares so any k of them restore it " +
             "(k-of-n over GF(256)). Fewer than k reveal nothing. Paste k shares to combine.")
        root.addView(rotulo("Secret (64-hex) to split"))
        val etK = entrada("64-hex private key")
        root.addView(rotulo("Threshold k"));  val etT = campoNum("2")
        root.addView(rotulo("Shares n"));      val etN = campoNum("3")
        val salS = salidaBox()
        boton("Split") {
            salS.removeAllViews()
            val h = etK.text.toString().trim().removePrefix("0x")
            val sec = hexABytes(h)
            val k = etT.text.toString().toIntOrNull() ?: 2; val n = etN.text.toString().toIntOrNull() ?: 3
            if (sec == null || sec.size != 32) { Toast.makeText(this, "Secret must be 64 hex", Toast.LENGTH_SHORT).show(); return@boton }
            if (k < 2 || n < k || n > 255) { Toast.makeText(this, "Need 2 ≤ k ≤ n ≤ 255", Toast.LENGTH_SHORT).show(); return@boton }
            val rnd = java.security.SecureRandom()
            for (x in 1..n) {
                val y = ByteArray(32)
                for (bi in 0 until 32) {
                    var acc = sec[bi].toInt() and 0xFF           // c0 = byte del secreto
                    var xp = 1
                    for (c in 1 until k) { xp = gmul(xp, x); val coef = rnd.nextInt(256); acc = acc xor gmul(coef, xp) }
                    y[bi] = acc.toByte()
                }
                val hex = "%02x".format(x) + y.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                salS.addView(filaResultado("Share $x of $n", hex, true))
            }
        }
        root.addView(rotulo("Combine — paste k shares (one per line)"))
        val etC = entrada("share hex per line", varias = true)
        val salC = salidaBox()
        boton("Combine") {
            salC.removeAllViews()
            val sh = etC.text.toString().split('\n').map { it.trim().removePrefix("0x") }.filter { it.length == 66 }
            if (sh.size < 2) { Toast.makeText(this, "Paste at least 2 valid shares", Toast.LENGTH_SHORT).show(); return@boton }
            val xs = IntArray(sh.size); val ys = Array(sh.size) { ByteArray(32) }
            var okp = true
            for (i in sh.indices) { val bb = hexABytes(sh[i]) ?: run { okp = false; null } ?: break
                xs[i] = bb[0].toInt() and 0xFF; for (j in 0 until 32) ys[i][j] = bb[j+1] }
            if (!okp) { Toast.makeText(this, "A share is not valid hex", Toast.LENGTH_SHORT).show(); return@boton }
            val out = ByteArray(32)
            for (bi in 0 until 32) {
                var s = 0
                for (i in sh.indices) {
                    var num = 1; var den = 1
                    for (j in sh.indices) if (j != i) { num = gmul(num, xs[j]); den = gmul(den, xs[j] xor xs[i]) }
                    val lag = gmul(num, if (den == 0) 0 else gfExp[255 - gfLog[den]])   // num/den
                    s = s xor gmul(ys[i][bi].toInt() and 0xFF, lag)
                }
                out[bi] = s.toByte()
            }
            salC.addView(filaResultado("Recombined key", out.joinToString("") { "%02x".format(it.toInt() and 0xFF) }, true))
        }
    }

    // ── BIP38 (encrypt / decrypt a key) ────────────────────────────────────
    @Volatile private var bip38Corriendo = false
    private fun construirBip38() {
        desc("Password-protect a private key (BIP38). Encrypt turns a key into a 6P… string; " +
             "Decrypt needs the same password. Standard scrypt, so it's compatible with other " +
             "wallets. Slow on purpose (a second or two).")
        root.addView(rotulo("Encrypt — private key (hex or WIF)"))
        val etKey = entrada("64-hex or WIF")
        root.addView(rotulo("Password"))
        val etP1 = entrada("passphrase")
        var comp = true
        root.addView(Ui.segmented(this, listOf("Compressed" to null, "Uncompressed" to null), 0) { comp = it == 0 })
        val salE = salidaBox()
        boton("Encrypt") {
            if (bip38Corriendo) return@boton
            salE.removeAllViews()
            val hx = hexClave(etKey.text.toString())
            val pw = etP1.text.toString()
            if (hx.isBlank()) { Toast.makeText(this, "Invalid private key", Toast.LENGTH_SHORT).show(); return@boton }
            if (pw.isEmpty()) { Toast.makeText(this, "Enter a password", Toast.LENGTH_SHORT).show(); return@boton }
            bip38Corriendo = true
            salE.addView(TextView(this).apply { text = "Encrypting…"; textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context) })
            Thread {
                val enc = try { HunterEngine.bip38Cifrar(hx, pw, comp) } catch (e: Throwable) { "" }
                runOnUiThread { bip38Corriendo = false; salE.removeAllViews()
                    if (enc.isBlank()) Toast.makeText(this, "Could not encrypt", Toast.LENGTH_SHORT).show()
                    else salE.addView(filaResultado("Encrypted key (BIP38)", enc, true)) }
            }.apply { isDaemon = true; start() }
        }
        root.addView(android.view.View(this).apply {
            setBackgroundColor(AppTheme.BORDER_C)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                .apply { topMargin = dp(18); bottomMargin = dp(6) } })
        root.addView(rotulo("Decrypt — 6P… key"))
        val etEnc = entrada("6P…", varias = true)
        root.addView(rotulo("Password"))
        val etP2 = entrada("passphrase")
        val salD = salidaBox()
        boton("Decrypt") {
            if (bip38Corriendo) return@boton
            salD.removeAllViews()
            val key = etEnc.text.toString().trim(); val pw = etP2.text.toString()
            if (!key.startsWith("6P")) { Toast.makeText(this, "A BIP38 key starts with 6P", Toast.LENGTH_SHORT).show(); return@boton }
            bip38Corriendo = true
            salD.addView(TextView(this).apply { text = "Decrypting…"; textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context) })
            Thread {
                val r = try { HunterEngine.bip38Descifrar(key, pw) } catch (e: Throwable) { "" }
                runOnUiThread { bip38Corriendo = false; salD.removeAllViews()
                    if (r.isBlank()) salD.addView(filaResultado("Wrong password", "or not a valid BIP38 key"))
                    else {
                        val priv = r.substringBefore("|")
                        val wif = try { HunterEngine.wifDeHex(priv).let { if (r.endsWith("|1")) it.substringBefore("|") else it.substringAfter("|") } } catch (e: Throwable) { "" }
                        salD.addView(filaResultado("Private key", priv, true))
                        if (wif.isNotEmpty()) salD.addView(filaResultado("WIF", wif))
                    } }
            }.apply { isDaemon = true; start() }
        }

        // ── EC-multiplied (código intermedio + generación por un tercero) ──
        root.addView(android.view.View(this).apply {
            setBackgroundColor(AppTheme.BORDER_C)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                .apply { topMargin = dp(18); bottomMargin = dp(6) } })
        root.addView(TextView(this).apply {
            text = "EC-multiplied"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.ACCENT)
            typeface = AppTheme.bold(context); setPadding(0, dp(4), 0, dp(4)) })
        root.addView(rotulo("Owner — password → intermediate code"))
        val etIcP = entrada("passphrase")
        val salIc = salidaBox()
        boton("Make intermediate code") {
            if (bip38Corriendo) return@boton
            val pw = etIcP.text.toString()
            if (pw.isEmpty()) { Toast.makeText(this, "Enter a password", Toast.LENGTH_SHORT).show(); return@boton }
            salIc.removeAllViews(); bip38Corriendo = true
            salIc.addView(TextView(this).apply { text = "Working…"; textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context) })
            Thread {
                val ic = try { HunterEngine.bip38Intermediate(pw, -1, 0) } catch (e: Throwable) { "" }
                runOnUiThread { bip38Corriendo = false; salIc.removeAllViews()
                    if (ic.isBlank()) Toast.makeText(this, "Could not generate", Toast.LENGTH_SHORT).show()
                    else salIc.addView(filaResultado("Intermediate code", ic, true)) }
            }.apply { isDaemon = true; start() }
        }
        root.addView(rotulo("Generator — intermediate code → new encrypted key"))
        val etIc2 = entrada("passphrase… (intermediate code)", varias = true)
        var compEc = true
        root.addView(Ui.segmented(this, listOf("Compressed" to null, "Uncompressed" to null), 0) { compEc = it == 0 })
        val salGen = salidaBox()
        boton("Generate encrypted key") {
            if (bip38Corriendo) return@boton
            val ic = etIc2.text.toString().trim()
            if (!ic.startsWith("passphrase")) { Toast.makeText(this, "Paste an intermediate code (starts with 'passphrase')", Toast.LENGTH_SHORT).show(); return@boton }
            salGen.removeAllViews(); bip38Corriendo = true
            salGen.addView(TextView(this).apply { text = "Working…"; textSize = AppTheme.SP_CAPTION
                setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context) })
            Thread {
                val r = try { HunterEngine.bip38GenerarCifrada(ic, compEc) } catch (e: Throwable) { "" }
                runOnUiThread { bip38Corriendo = false; salGen.removeAllViews()
                    if (r.isBlank()) Toast.makeText(this, "Could not generate", Toast.LENGTH_SHORT).show()
                    else { salGen.addView(filaResultado("Encrypted key (6P…)", r.substringBefore("|"), true))
                           salGen.addView(filaResultado("Address", r.substringAfter("|"))) } }
            }.apply { isDaemon = true; start() }
        }
        nota(AppTheme.BLUE, "EC-multiplied lets a third party mint an encrypted key + address from " +
             "your intermediate code without knowing your password. Decrypt it above with the password.")
    }

    // ── BTC ↔ sat ──────────────────────────────────────────────────────────
    private fun construirUnidades() {
        desc("Convert between BTC and satoshis. Type a value with a dot for BTC (0.0005) " +
             "or a whole number for sats (50000).")
        val et = entrada("0.0005  or  50000")
        val salida = salidaBox()
        boton("Convert") {
            salida.removeAllViews()
            val t = et.text.toString().trim().replace(",", "")
            if (t.contains('.')) {
                val btc = t.toDoubleOrNull() ?: return@boton
                salida.addView(filaResultado("Satoshis", "%,d".format(Math.round(btc * 1e8))))
            } else {
                val sat = t.toLongOrNull() ?: return@boton
                salida.addView(filaResultado("BTC", "%.8f".format(sat / 1e8).trimEnd('0').trimEnd('.')))
            }
        }
    }

    // ── Difficulty / time ──────────────────────────────────────────────────
    private fun construirDificultad() {
        desc("How long to brute-force a key whose private value lives in a range of N bits, " +
             "at a given speed. Shows why a full 256-bit key is impossible.")
        root.addView(rotulo("Range (bits)")); val etB = campoNum("64")
        root.addView(rotulo("Speed (keys/s)")); val etS = campoNum("10000000", 150)
        val salida = salidaBox()
        boton("Estimate") {
            salida.removeAllViews()
            val bits = etB.text.toString().toIntOrNull()?.coerceIn(1, 256) ?: 64
            val rate = etS.text.toString().toDoubleOrNull()?.coerceAtLeast(1.0) ?: 1e7
            val media = Math.pow(2.0, bits.toDouble()) / 2.0   // esperado: medio espacio
            val seg = media / rate
            salida.addView(filaResultado("Keys to try (avg)", "2^${bits - 1}"))
            salida.addView(filaResultado("Time (average)", tiempoHumano(seg), true))
        }
        nota(AppTheme.BLUE, "The age of the universe is ~4×10^17 s. Anything far beyond that is, " +
             "in practice, impossible — which is the whole point of 256-bit keys.")
    }
    private fun tiempoHumano(seg: Double): String {
        if (seg < 1) return "< 1 second"
        var v = seg
        if (v < 60) return "${v.toLong()} s"
        v /= 60; if (v < 60) return "${v.toLong()} min"
        v /= 60; if (v < 24) return "${v.toLong()} h"
        v /= 24; if (v < 365) return "${v.toLong()} days"
        val years = v / 365
        return when {
            years < 1e3 -> "${years.toLong()} years"
            years < 1e9 -> "%.1f thousand years".format(years / 1e3)
            years < 1e15 -> "%.1f billion years".format(years / 1e9)
            else -> "%.1e years (longer than the universe)".format(years)
        }
    }

    // ── Entropy checker ────────────────────────────────────────────────────
    private fun construirEntropia() {
        desc("Paste a private key (64 hex) and it flags obvious weaknesses — the kind that " +
             "make a key findable. A good key triggers none of these.")
        val et = entrada("64-hex private key", varias = true)
        val salida = salidaBox()
        boton("Check") {
            salida.removeAllViews()
            val h = et.text.toString().trim().removePrefix("0x").lowercase()
            if (h.length != 64 || h.any { it !in "0123456789abcdef" }) { Toast.makeText(this, "Need 64 hex", Toast.LENGTH_SHORT).show(); return@boton }
            val b = hexABytes(h)!!
            val avisos = ArrayList<String>()
            if (b.all { it == b[0] }) avisos.add("All bytes identical")
            val distinct = b.map { it }.toSet().size
            if (distinct <= 4) avisos.add("Only $distinct distinct byte values")
            if (b.take(28).all { it.toInt() == 0 }) avisos.add("Tiny value (fits in 32 bits) — trivially findable")
            var asc = true; for (i in 1 until 32) if ((b[i].toInt() and 0xFF) != ((b[i-1].toInt() and 0xFF) + 1) and 0xFF) { asc = false; break }
            if (asc) avisos.add("Sequential bytes")
            if (h.startsWith("0000000000000000")) avisos.add("Leading zero run")
            if (avisos.isEmpty()) salida.addView(filaResultado("No obvious weakness", "looks random · $distinct distinct bytes", true))
            else avisos.forEach { salida.addView(filaResultado("⚠ Weak", it)) }
        }
        nota(AppTheme.BLUE, "This only catches gross patterns. Passing it does NOT prove a key is " +
             "strong — it just means it isn't obviously broken.")
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
             "(P2PKH / P2WPKH / P2SH-P2WPKH).")
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
        bwCorriendo = false; nonceCorriendo = false; vanCorriendo = false
        balCorriendo = false; sweepCorriendo = false; watchCorriendo = false; bcCorriendo = false; bip38Corriendo = false
        super.onDestroy()
    }
}
