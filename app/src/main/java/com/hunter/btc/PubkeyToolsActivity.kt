package com.hunter.btc

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    // ── Índice de herramientas ────────────────────────────────────────────
    //
    // Era una lista de 45 secciones plegables bajo siete cabeceras: todas con el mismo peso, y para saber
    // qué hacía cada una había que desplegarla. Ahora es una cuadrícula de tarjetas (icono, nombre y para qué
    // sirve) con buscador, chips de categoría y las recientes arriba; cada herramienta se abre a pantalla
    // completa. Se construye la primera vez que se abre y se conserva: un barrido en marcha sigue ahí al volver.
    private class Herr(val cat: String, val icono: Int, val titulo: String, val sub: String,
                       val claves: String, val build: () -> Unit) {
        var vista: LinearLayout? = null
    }
    private val herrs = ArrayList<Herr>()
    private fun reg(cat: String, icono: Int, titulo: String, sub: String, claves: String = "", build: () -> Unit) {
        herrs.add(Herr(cat, icono, titulo, sub, claves, build))
    }
    private val CATS = listOf("Keys", "Sign & backup", "Transactions", "Explorer", "Monitor", "Audit", "Utility")

    private lateinit var inicio: ScrollView
    private lateinit var rejilla: LinearLayout
    private lateinit var detalle: ScrollView
    private var abierta: Herr? = null
    private var catSel: String? = null          // null = todas
    private var busqueda = ""
    private val chips = ArrayList<Pair<String?, TextView>>()

    override fun onCreate(s: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(s)
        PantallaSegura.aplicar(this)
        // Esta pantalla enseña claves privadas, WIF y partes de Shamir: sin capturas ni vista previa en
        // recientes, tenga o no activado el ajuste general.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        Fiat.cargar(this) { }   // el precio, para enseñar el valor junto a los importes
        AppTheme.init(this)
        AppLock.init(this)

        val marco = android.widget.FrameLayout(this).apply { setBackgroundColor(AppTheme.BG_DEEP) }
        inicio = ScrollView(this).apply { isFillViewport = true }
        detalle = ScrollView(this).apply { isFillViewport = true; visibility = android.view.View.GONE }
        marco.addView(inicio); marco.addView(detalle)
        setContentView(marco)
        registrar()
        construirInicio()
    }

    override fun onBackPressed() {
        if (abierta != null) cerrar() else super.onBackPressed()
    }

    private fun registrar() {
        val K = "Keys"; val F = "Sign & backup"; val T = "Transactions"; val E = "Explorer"; val M = "Monitor"; val A = "Audit"; val U = "Utility"
        reg(K, R.drawable.ic_search, "Addresses from a key", "Every address type of a private or public key", "wif hex p2pkh p2wpkh p2tr") { construirDirecciones() }
        reg(K, R.drawable.ic_copy, "Hex ↔ WIF", "Convert a private key between formats") { construirWifHex() }
        reg(K, R.drawable.ic_eye, "Key inspector", "Paste a key or an address: what it is", "address inspector") { construirInspector() }
        reg(K, R.drawable.ic_import, "Seed phrase (BIP39)", "Check a phrase, make one, see its addresses", "mnemonic bip39 validate generate") { construirMnemonic() }
        reg(K, R.drawable.ic_recovery, "Child seeds (BIP85)", "Independent seeds, keys and passwords from one", "bip85") { construirBip85() }
        reg(K, R.drawable.ic_recovery, "Derivation path", "The key at an exact BIP32 path", "custom derivation bip32") { construirDerivacion() }
        reg(K, R.drawable.ic_recovery, "xpub → addresses", "Addresses of an extended public key", "xpub zpub ypub") { construirXpub() }

        reg(F, R.drawable.ic_finger, "Sign message", "Sign and verify with 1… keys (classic)", "sign verify message legacy") { construirFirma() }
        reg(F, R.drawable.ic_finger, "Sign message (BIP322)", "Sign and verify with bc1q, bc1p and 3…", "bip322 sign verify") { construirBip322() }
        reg(F, R.drawable.ic_clock, "Timestamp a file", "Prove a file existed by a date", "opentimestamps ots") { construirOts() }
        reg(F, R.drawable.ic_wallet, "Proof of reserves", "Prove what you hold without moving it", "bip322 proof of funds reserves") { construirReservas() }
        reg(F, R.drawable.ic_lock, "BIP38", "Encrypt a private key with a password", "bip38 encrypt") { construirBip38() }
        reg(F, R.drawable.ic_lock, "Shamir split", "Split a secret into k-of-n shares", "shamir sss") { construirShamir() }
        reg(F, R.drawable.ic_lock, "XOR split", "Split a key into parts that need each other", "key split xor") { construirSplit() }
        reg(F, R.drawable.ic_scan, "SeedQR", "Your phrase as a QR for SeedSigner", "seedqr seedsigner qr backup") { construirSeedQr() }
        reg(F, R.drawable.ic_lock, "Seed XOR", "Split a phrase into decoy phrases (Coldcard)", "seed xor coldcard split backup") { construirSeedXor() }
        reg(F, R.drawable.ic_lock, "codex32", "Seed with a strong checksum, in k-of-n shares", "codex32 bip93 shamir shares backup") { construirCodex32() }

        reg(T, R.drawable.ic_wallet, "Balance & UTXOs", "Any address, read-only", "watch-only utxo") { construirBalance() }
        reg(T, R.drawable.ic_clock, "Fee estimator", "Recommended fee rates now", "fees") { construirFee() }
        reg(T, R.drawable.ic_gear, "Size & fee calculator", "A transaction's size and cost", "tx size fee calc") { construirFeeCalc() }
        reg(T, R.drawable.ic_send, "Sweep a key", "Move all funds of a private key to you") { construirSweep() }
        reg(T, R.drawable.ic_play, "Broadcast", "Send a signed raw transaction", "raw tx") { construirBroadcast() }
        reg(T, R.drawable.ic_export, "Transaction decoder", "Read a raw transaction, offline") { construirDecoder() }
        reg(T, R.drawable.ic_import, "PSBT", "Decode and sign partially signed transactions", "psbt decode sign") { construirPsbt() }
        reg(T, R.drawable.ic_search, "Script decoder", "Read a Bitcoin script", "script asm") { construirScript() }

        reg(E, R.drawable.ic_clock, "Network now", "Mempool, next blocks, halving, difficulty", "mempool fees halving difficulty") { construirRed() }
        reg(E, R.drawable.ic_export, "Recent blocks", "The latest blocks mined") { construirBloques() }
        reg(E, R.drawable.ic_search, "Look up", "A transaction, block or address", "tx block address explorer search txid") { construirBuscar() }
        reg(E, R.drawable.ic_dice, "Block miner", "Mine a practice block, or try the solo lottery", "mining miner pow stratum solo ckpool hashrate") { construirMinero() }

        reg(M, R.drawable.ic_notif, "Watch addresses", "Get notified when they move") { construirWatch() }
        reg(M, R.drawable.ic_dice, "Vanity address", "An address that starts how you want") { construirVanity() }
        reg(M, R.drawable.ic_dice, "Dice → key", "A key from dice or coin flips", "dice coin entropy") { construirDados() }
        reg(M, R.drawable.ic_receive, "QR code", "Turn any text into a QR") { construirQr() }

        reg(A, R.drawable.ic_target, "Key in range (BSGS)", "Find a key in a small range", "bsgs solve") { construirBsgs() }
        reg(A, R.drawable.ic_warning, "Nonce reuse", "Keys exposed by repeated signatures", "nonce-reuse audit") { construirNonce() }
        reg(A, R.drawable.ic_warning, "Nonce leak (lattice)", "Recover a key from biased nonces", "lattice hnp") { construirLattice() }
        reg(A, R.drawable.ic_finger, "RFC6979 check", "Are the signatures deterministic?", "rfc6979 nonce") { construirRfc6979() }
        reg(A, R.drawable.ic_search, "Low-S / malleability", "Check a signature's form", "signature malleability low-s") { construirMalleability() }
        reg(A, R.drawable.ic_edit, "Brainwallet check", "Is a passphrase key already known?") { construirBrainwallet() }
        reg(A, R.drawable.ic_recovery, "Phrase strength", "Red flags of a hand-made phrase", "mnemonic strength") { construirMnemonicStrength() }
        reg(A, R.drawable.ic_warning, "Weak RNG (Milk Sad)", "Did your phrase come from a broken generator?", "milk sad bx") { construirMilkSad() }
        reg(A, R.drawable.ic_warning, "Legacy weak wallets", "Randstorm and the 2013 Android bug", "randstorm android") { construirLegacyRng() }
        reg(A, R.drawable.ic_eye, "Entropy checker", "How random is a value?", "entropy") { construirEntropia() }
        reg(A, R.drawable.ic_clock, "Brute-force time", "How long to search a range of bits", "difficulty time") { construirDificultad() }

        reg(U, R.drawable.ic_copy, "BTC ↔ sat", "Convert units") { construirUnidades() }
    }

    // ── Inicio: buscador, categorías, recientes y cuadrícula ──

    private fun construirInicio() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(24), 0, dp(32)) }
        page.addView(TextView(this).apply {
            text = "Tools"; textSize = 24f; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.title(context)
            setPadding(dp(AppTheme.PAD_SIDE), 0, dp(AppTheme.PAD_SIDE), 0)
        })
        page.addView(TextView(this).apply {
            text = "${herrs.size} tools · everything runs on this phone"; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context)
            setPadding(dp(AppTheme.PAD_SIDE), dp(2), dp(AppTheme.PAD_SIDE), dp(12))
        })
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                .apply { setMargins(dp(AppTheme.PAD_SIDE), 0, dp(AppTheme.PAD_SIDE), dp(12)) }
            addView(Ui.icon(this@PubkeyToolsActivity, R.drawable.ic_search, 18, AppTheme.TXT_MUTED).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10)
            })
            addView(EditText(this@PubkeyToolsActivity).apply {
                hint = "Search tools…"; isSingleLine = true; background = null; setPadding(0, 0, 0, 0)
                textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(e: android.text.Editable?) { busqueda = e?.toString() ?: ""; pintarRejilla() }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            })
        })
        // Chips de categoría, en una fila que se desliza.
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(AppTheme.PAD_SIDE), 0, dp(AppTheme.PAD_SIDE), 0) }
        chips.clear()
        for (c in listOf<String?>(null) + CATS) {
            val chip = TextView(this).apply {
                text = c ?: "All"; textSize = AppTheme.SP_CAPTION; gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(8) }
                isClickable = true; foreground = Ui.toque()
                setOnClickListener { catSel = c; marcarChips(); pintarRejilla() }
            }
            chips.add(c to chip); fila.addView(chip)
        }
        page.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(fila)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        })
        rejilla = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(AppTheme.PAD_SIDE - 5), 0, dp(AppTheme.PAD_SIDE - 5), 0) }
        page.addView(rejilla)
        root = rejilla
        inicio.removeAllViews(); inicio.addView(page)
        marcarChips(); pintarRejilla()
    }

    /** El chip elegido va relleno (sin el acento: está reservado a la acción principal). */
    private fun marcarChips() {
        for ((c, v) in chips) {
            val on = c == catSel
            v.background = Ui.cardBg(AppTheme.R_CHIP, if (on) AppTheme.BG_KEY else AppTheme.BG_CARD, this)
            v.setTextColor(if (on) AppTheme.TXT_PRI else AppTheme.TXT_SEC)
            v.typeface = if (on) AppTheme.bold(this) else AppTheme.medium(this)
        }
    }

    private fun prefs() = getSharedPreferences("herramientas", MODE_PRIVATE)
    private fun recientes(): List<String> = prefs().getString("recientes", "")!!.split('\n').filter { it.isNotEmpty() }
    private fun anotarReciente(t: String) =
        prefs().edit().putString("recientes", (listOf(t) + recientes().filter { it != t }).take(4).joinToString("\n")).apply()

    private fun pintarRejilla() {
        rejilla.removeAllViews()
        val q = busqueda.trim().lowercase()
        val visibles = herrs.filter { h ->
            (catSel == null || h.cat == catSel) && (q.isEmpty() || "${h.titulo} ${h.sub} ${h.claves} ${h.cat}".lowercase().contains(q))
        }
        if (visibles.isEmpty()) {
            rejilla.addView(TextView(this).apply {
                text = "No tool matches “$busqueda”."; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.body(context); setPadding(dp(5), dp(24), dp(5), 0)
            })
            return
        }
        if (catSel == null && q.isEmpty()) {
            val rec = recientes().mapNotNull { t -> herrs.firstOrNull { it.titulo == t } }
            if (rec.isNotEmpty()) { etiqueta("Recent"); cuadricula(rec) }
        }
        val grupos = if (catSel != null) listOf(catSel!! to visibles)
                     else CATS.map { c -> c to visibles.filter { it.cat == c } }.filter { it.second.isNotEmpty() }
        for ((c, hs) in grupos) { etiqueta(c); cuadricula(hs) }
    }

    private fun etiqueta(t: String) = rejilla.addView(TextView(this).apply {
        text = t.uppercase(); textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.bold(context); letterSpacing = 0.08f
        setPadding(dp(5), dp(18), dp(5), dp(8))
    })

    /** Las tarjetas de dos en dos; si sobra una, la otra mitad queda vacía. */
    private fun cuadricula(hs: List<Herr>) {
        for (par in hs.chunked(2)) {
            val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (h in par) fila.addView(tarjeta(h))
            if (par.size == 1) fila.addView(android.view.View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
            rejilla.addView(fila)
        }
    }

    private fun tarjeta(h: Herr) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        minimumHeight = dp(124)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
        isClickable = true; isFocusable = true; foreground = Ui.toque()
        contentDescription = h.titulo
        addView(android.widget.FrameLayout(context).apply {
            background = Ui.cardBg(AppTheme.R_CHIP, AppTheme.BG_ELEV, context)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            addView(Ui.icon(this@PubkeyToolsActivity, h.icono, 20, AppTheme.TXT_PRI).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            })
        })
        addView(TextView(context).apply {
            text = h.titulo; textSize = AppTheme.SP_BODY - 1f; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.bold(context)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(12), 0, dp(2))
        })
        addView(TextView(context).apply {
            text = h.sub; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f)
        })
        setOnClickListener { Ui.pulso(this); abrir(h) }
    }

    // ── Una herramienta a pantalla completa ──

    private fun abrir(h: Herr) {
        abierta = h; anotarReciente(h.titulo)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(32)) }
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(AppTheme.PAD_SIDE - 12), 0, dp(AppTheme.PAD_SIDE), dp(10))
            addView(android.widget.FrameLayout(this@PubkeyToolsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                isClickable = true; foreground = Ui.toque(); contentDescription = "Back"
                setOnClickListener { cerrar() }
                addView(Ui.icon(this@PubkeyToolsActivity, R.drawable.ic_back, 22, AppTheme.TXT_PRI).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                })
            })
            addView(LinearLayout(this@PubkeyToolsActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, 0, 0)
                addView(TextView(context).apply {
                    text = h.cat.uppercase(); textSize = AppTheme.SP_CAPTION - 1f; setTextColor(AppTheme.TXT_SEC)
                    typeface = AppTheme.bold(context); letterSpacing = 0.08f
                })
                addView(TextView(context).apply { text = h.titulo; textSize = 22f; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.title(context) })
            })
        })
        val cuerpo = h.vista ?: LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { b ->
            val prev = root; root = b
            try { h.build() } finally { root = prev }
            h.vista = b
        }
        (cuerpo.parent as? ViewGroup)?.removeView(cuerpo)
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(AppTheme.R_CARD, AppTheme.BG_CARD, context)
            setPadding(dp(AppTheme.PAD_CARD), dp(AppTheme.PAD_CARD), dp(AppTheme.PAD_CARD), dp(AppTheme.PAD_CARD))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(AppTheme.PAD_SIDE - 6), 0, dp(AppTheme.PAD_SIDE - 6), 0) }
            addView(cuerpo)
        })
        detalle.removeAllViews(); detalle.addView(page); detalle.scrollTo(0, 0)
        inicio.visibility = android.view.View.GONE; detalle.visibility = android.view.View.VISIBLE
    }

    private fun cerrar() {
        abierta = null
        detalle.visibility = android.view.View.GONE; inicio.visibility = android.view.View.VISIBLE
        // El cuerpo se conserva en su Herr; se suelta de la página para poder volver a engancharlo.
        detalle.removeAllViews()
        pintarRejilla()
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

    private var alLeerQr: ((String) -> Unit)? = null
    private val REQ_QR_PSBT = 71

    override fun onActivityResult(req: Int, res: Int, data: android.content.Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_QR_PSBT && res == RESULT_OK)
            data?.getStringExtra(QrScanActivity.EXTRA_TEXTO)?.let { alLeerQr?.invoke(it) }
        if (req == REQ_ELEGIR && res == RESULT_OK) data?.data?.let { alElegir?.invoke(it) }
        if (req == REQ_QR_SEMILLA && res == RESULT_OK)
            alLeerSemilla?.invoke(data?.getStringExtra(QrScanActivity.EXTRA_TEXTO) ?: "", data?.getByteArrayExtra(QrScanActivity.EXTRA_SEGMENTOS))
        if (req == REQ_GUARDAR) {
            val u = data?.data; val b = porGuardar
            val ok = res == RESULT_OK && u != null && b != null &&
                try { contentResolver.openOutputStream(u)?.use { it.write(b) } != null } catch (e: Exception) { RegistroFallos.anotar("Tools.guardar", e); false }
            porGuardar = null; alGuardar?.invoke(ok)
        }
    }

    private var alLeerSemilla: ((String, ByteArray?) -> Unit)? = null
    private val REQ_QR_SEMILLA = 74

    /** Elegir una cartera del teléfono y, con el código, su frase (las palabras, sin passphrase ni cuenta). */
    private fun elegirCartera(alElegir: (String) -> Unit) {
        val carteras = listOf("" to WalletManager.mainName(this)) + WalletManager.listWallets(this)
        android.app.AlertDialog.Builder(this).setTitle("Wallet")
            .setItems(carteras.map { it.second }.toTypedArray()) { _, i ->
                PinAuthHelper.show(this, autoBiometric = false) { ok ->
                    if (!ok) return@show
                    val id = carteras[i].first
                    val mn = if (id.isEmpty()) WalletManager.loadSeed(this) else WalletManager.loadWalletSeed(this, id)
                    if (mn == null) Toast.makeText(this, "That wallet has no seed phrase on this phone.", Toast.LENGTH_LONG).show()
                    else alElegir(Frase.palabras(mn))
                }
            }.show()
    }

    // ── Archivos (selector del sistema): el archivo no sale del teléfono ──
    private var alElegir: ((android.net.Uri) -> Unit)? = null
    private var porGuardar: ByteArray? = null
    private var alGuardar: ((Boolean) -> Unit)? = null
    private val REQ_ELEGIR = 72
    private val REQ_GUARDAR = 73

    private fun elegirArchivo(f: (android.net.Uri) -> Unit) {
        alElegir = f
        startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_ELEGIR)
    }
    private fun guardarArchivo(nombre: String, bytes: ByteArray, listo: (Boolean) -> Unit) {
        porGuardar = bytes; alGuardar = listo
        startActivityForResult(android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
            .putExtra(android.content.Intent.EXTRA_TITLE, nombre), REQ_GUARDAR)
    }
    private fun nombreDe(u: android.net.Uri): String = try {
        contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) { null } ?: "file"
    /** SHA-256 del archivo, leído por trozos (puede ser grande). */
    private fun sha256De(u: android.net.Uri): ByteArray? = try {
        val md = java.security.MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024)
        contentResolver.openInputStream(u)?.use { while (true) { val n = it.read(buf); if (n < 0) break; md.update(buf, 0, n) } } ?: throw java.io.IOException("no se abre")
        md.digest()
    } catch (e: Exception) { RegistroFallos.anotar("Tools.sha256De", e); null }
    /** Un archivo pequeño entero (un .ots), con tope. */
    private fun leerPequeno(u: android.net.Uri, max: Int = 1 shl 20): ByteArray? = try {
        contentResolver.openInputStream(u)?.use { ent ->
            val o = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (true) { val n = ent.read(buf); if (n < 0) break; if (o.size() + n > max) throw java.io.IOException("demasiado grande"); o.write(buf, 0, n) }
            o.toByteArray()
        }
    } catch (e: Exception) { RegistroFallos.anotar("Tools.leerPequeno", e); null }

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
            // Si lleva una clave privada (hex de 64, WIF, xprv) va como secreto:
            // marcado como sensible para los teclados, y borrado a los 60 s.
            if (Secretos.tachar(texto) != texto) {
                Secretos.copiarClave(this, "btc", texto)
                Toast.makeText(this, "Copied · cleared from the clipboard in 60 s", Toast.LENGTH_SHORT).show()
                return
            }
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
    /**
     * BIP38 exige la contraseña en forma NFC: "é" escrita como un carácter o como
     * "e" + acento son la misma para el usuario, pero bytes distintos, y una clave
     * cifrada con una no se abría con la otra (ni en otras carteras).
     */
    private fun nfc(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)

    /** ¿Es un WIF de mainnet SIN comprimir (5…)? Sus fondos van a otra dirección. */
    private fun esWifSinComprimir(k: String): Boolean {
        val t = k.trim()
        return t.length == 51 && t.startsWith("5") && hexClave(t).isNotEmpty()
    }

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
            val sigComp = try { HunterEngine.firmarMensaje(hx, etMsg.text.toString()) } catch (e: Throwable) { "" }
            if (sigComp.isBlank()) { Toast.makeText(this, "Could not sign", Toast.LENGTH_SHORT).show(); return@boton }
            // El motor firma con la cabecera de clave comprimida (31-34). Un WIF 5…
            // es de la clave SIN comprimir: su cabecera es 27-30. Si no, la firma
            // "verificaba" contra la dirección comprimida, que no es la del WIF.
            val sigHex = if (esWifSinComprimir(etKey.text.toString())) {
                val h = sigComp.substring(0, 2).toInt(16) - 4
                "%02x".format(h) + sigComp.substring(2)
            } else sigComp
            val b64 = try {
                val raw = ByteArray(65) { sigHex.substring(it*2, it*2+2).toInt(16).toByte() }
                android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
            } catch (e: Throwable) { "" }
            val quien = try { HunterEngine.verificarMensaje(etMsg.text.toString(), sigHex) } catch (e: Throwable) { "" }
            salFirma.addView(filaResultado("Signature (base64)", b64, true))
            if (quien.isNotEmpty()) salFirma.addView(filaResultado("Signed by address", quien.lines().joinToString("\n")))
        }

        root.addView(android.view.View(this).apply {
            setBackgroundColor(AppTheme.BORDER_C)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                .apply { topMargin = dp(18); bottomMargin = dp(6) }
        })
        root.addView(rotulo("Verify — address"))
        val etA = entrada("1…, 3… or bc1q… address that claims to have signed")
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
            // El motor devuelve la dirección de la clave recuperada y, si es
            // comprimida, también su 3… y su bc1q… (BIP137 y Electrum).
            val candidatas = quien.lines().map { it.trim() }.filter { it.isNotEmpty() }
            when {
                candidatas.isEmpty() -> salVer.addView(filaResultado("Invalid signature", "could not recover a key"))
                candidatas.any { it.equals(addr, ignoreCase = it.startsWith("bc1")) } ->
                    salVer.addView(filaResultado("✓ VALID", "signed by $addr", true))
                else -> salVer.addView(filaResultado("✗ Does NOT match", "recovered " + candidatas.joinToString(" / ")))
            }
        }
        nota(AppTheme.BLUE, "Signing never touches funds — it just proves control of the key. " +
             "Verify recovers the signer's address from the signature and compares it.")
    }

    // ── Sign & verify (BIP322) ─────────────────────────────────────────────
    /*
     * Firma y verificación de mensajes para direcciones modernas. La firma se hace con una cartera del teléfono
     * (se busca la dirección entre sus primeras de recepción y cambio; pide el código) o con una clave pegada.
     * Antes de enseñarla se verifica con el mismo verificador. Bip322 está probado con los vectores oficiales.
     */
    private fun construirBip322() {
        desc("Prove you control a modern address — bc1q, bc1p (Taproot), 3… or 1… — by signing a message " +
             "(BIP322), and check anyone's BIP322 signature. The classic \"Sign message\" tool only covers 1… keys. " +
             "Nothing is sent: the signature is a transaction that can never be broadcast.")
        root.addView(rotulo("Sign with"))
        val carteras = listOf("" to WalletManager.mainName(this)) + WalletManager.listWallets(this)
        var deTelefono = true
        var carteraSel: Pair<String, String>? = null
        var btnCartera: Button? = null
        var etClave: EditText? = null
        root.addView(Ui.segmented(this, listOf("A wallet on this phone" to null, "Private key" to null)) {
            deTelefono = it == 0
            btnCartera?.visibility = if (deTelefono) android.view.View.VISIBLE else android.view.View.GONE
            etClave?.visibility = if (deTelefono) android.view.View.GONE else android.view.View.VISIBLE
        })
        btnCartera = boton("Choose the wallet ▾") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Wallet")
                .setItems(carteras.map { it.second }.toTypedArray()) { _, i ->
                    carteraSel = carteras[i]; btnCartera?.text = "Wallet: ${carteras[i].second}"
                }.show()
        }
        etClave = entrada("Private key: 64-hex or WIF").apply { visibility = android.view.View.GONE }
        root.addView(rotulo("Address"))
        val etDir = entrada("bc1q…, bc1p…, 3… or 1… — one of yours")
        root.addView(rotulo("Message"))
        val etMsg = entrada("the exact text to sign", varias = true)
        lateinit var salFirma: LinearLayout
        boton("Sign (BIP322)") {
            salFirma.removeAllViews()
            val dir = etDir.text.toString().trim()
            val inf = FirmaBip322.info(dir)
            if (inf == null) { salFirma.addView(filaResultado("Invalid address", "Check the address.")); return@boton }
            if (inf.type == BtcAddress.Type.P2WSH) {
                salFirma.addView(filaResultado("Not supported here", "Script addresses (bc1q… of 62 characters, e.g. multisig) sign with their co-signers' tools."))
                return@boton
            }
            val msg = etMsg.text.toString().toByteArray(Charsets.UTF_8)
            val telefono = deTelefono
            fun mostrar(f: String?) = runOnUiThread {
                if (f == null) {
                    salFirma.addView(filaResultado("Not signed", if (telefono)
                        "That address is not among the first 200 receive or change addresses of this wallet (account 0)."
                        else "That key does not control this address."))
                } else {
                    salFirma.addView(filaResultado("Signature (BIP322)", f, true))
                    if (f.startsWith("smp")) salFirma.addView(filaResultado("Same, without the 'smp' prefix (older verifiers)", f.substring(3)))
                    val ok = Bip322.verificar(inf, msg, f, FirmaBip322.verificador) is Bip322.Veredicto.Valida
                    salFirma.addView(filaResultado("Self-check", if (ok) "✓ The signature verifies." else "✗ It does not verify — don't use it."))
                }
            }
            if (telefono) {
                val sel = carteraSel
                if (sel == null) { Toast.makeText(this, "Choose the wallet", Toast.LENGTH_SHORT).show(); return@boton }
                PinAuthHelper.show(this, autoBiometric = false) { ok ->
                    if (!ok) return@show
                    val mn = if (sel.first.isEmpty()) WalletManager.loadSeed(this) else WalletManager.loadWalletSeed(this, sel.first)
                    if (mn == null) { salFirma.addView(filaResultado("No seed", "That wallet has no seed phrase on this phone.")); return@show }
                    Thread {
                        val priv = try { FirmaBip322.privadaDe(mn, inf, dir) } catch (e: Throwable) { null }
                        mostrar(priv?.let { try { FirmaBip322.firmar(it, inf, msg) } catch (e: Throwable) { null } })
                    }.start()
                }
            } else {
                val hx = hexClave(etClave?.text.toString())
                if (hx.isBlank()) { Toast.makeText(this, "Invalid private key", Toast.LENGTH_SHORT).show(); return@boton }
                Thread { mostrar(try { FirmaBip322.firmar(hx, inf, msg) } catch (e: Throwable) { null }) }.start()
            }
        }
        salFirma = salidaBox()

        root.addView(android.view.View(this).apply {
            setBackgroundColor(AppTheme.BORDER_C)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                .apply { topMargin = dp(18); bottomMargin = dp(6) }
        })
        root.addView(rotulo("Verify — address"))
        val etA = entrada("bc1q…, bc1p…, 3… or 1…")
        root.addView(rotulo("Message"))
        val etM2 = entrada("the exact message", varias = true)
        root.addView(rotulo("Signature"))
        val etS = entrada("smp…, ful… or plain base64", varias = true)
        lateinit var salVer: LinearLayout
        boton("Verify (BIP322)") {
            salVer.removeAllViews()
            val addr = etA.text.toString().trim()
            val inf = FirmaBip322.info(addr)
            if (inf == null) { salVer.addView(filaResultado("Invalid address", "Check the address.")); return@boton }
            val v = try {
                Bip322.verificar(inf, etM2.text.toString().toByteArray(Charsets.UTF_8), etS.text.toString(), FirmaBip322.verificador)
            } catch (e: Throwable) { Bip322.Veredicto.Invalida("The signature could not be read.") }
            when (v) {
                is Bip322.Veredicto.Valida -> salVer.addView(filaResultado("✓ VALID", "Signed by $addr" +
                    (if (v.tiempo != 0L || v.edad != 0L) "\nWith a time lock: nLockTime ${v.tiempo}, nSequence ${v.edad}." else ""), true))
                is Bip322.Veredicto.Invalida -> salVer.addView(filaResultado("✗ NOT valid", v.motivo))
                is Bip322.Veredicto.Inconclusa -> salVer.addView(filaResultado("? Can't tell", v.motivo))
            }
        }
        salVer = salidaBox()
        nota(AppTheme.BLUE, "The message must match exactly, including spaces and line breaks. Signing never moves " +
             "funds. Multisig and time-locked scripts can't be checked here and show as \"can't tell\".")
    }

    // ── Timestamp a file (OpenTimestamps) ─────────────────────────────────
    /*
     * Sellar: SHA-256 del archivo (aquí), nonce, a los calendarios, y se guarda el .ots. Comprobar: abrir un
     * .ots, ver si es del archivo, completar lo pendiente y comprobar contra la cabecera del bloque (con su
     * prueba de trabajo). Ots está probado con los ejemplos oficiales; la red va en SelloTiempo.
     */
    private fun construirOts() {
        desc("Prove a file existed by a date — a contract, a photo, a document — anchored in Bitcoin, for free. " +
             "Only a fingerprint (hash) mixed with a random value leaves the phone, never the file. Public calendars " +
             "put it in a Bitcoin block within a few hours; then 'Upgrade' completes the proof. Compatible with opentimestamps.org.")
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        fun fecha(s: Long) = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(s * 1000))

        root.addView(rotulo("1 · Timestamp"))
        lateinit var salSello: LinearLayout
        fun sellar(digest: ByteArray?, nombre: String) {
            salSello.removeAllViews()
            if (digest == null) { salSello.addView(filaResultado("Could not read it", "Try again.")); return }
            salSello.addView(filaResultado("SHA-256 of $nombre", hex(digest)))
            salSello.addView(filaResultado("Sending to the calendars…", "Only this fingerprint, mixed with a random value."))
            Thread {
                val (arch, n) = try { SelloTiempo.sellar(digest) } catch (e: Throwable) { RegistroFallos.anotar("Tools.ots", e); null to 0 }
                runOnUiThread {
                    if (arch == null || n == 0) { salSello.addView(filaResultado("No calendar answered", "Check the connection and try again.")); return@runOnUiThread }
                    salSello.addView(filaResultado("Sent to $n calendar(s)", "Pending: it will be in a Bitcoin block within a few hours.", true))
                    guardarArchivo("$nombre.ots", Ots.escribir(arch)) { ok ->
                        salSello.addView(filaResultado(if (ok) "Proof saved" else "Proof NOT saved", if (ok)
                            "Keep the .ots next to the original. Come back in a few hours and use 'Upgrade' to complete it."
                            else "Without the .ots there is no proof. Timestamp it again."))
                    }
                }
            }.start()
        }
        boton("Choose a file to timestamp") { elegirArchivo { u -> sellar(sha256De(u), nombreDe(u)) } }
        val etTxt = entrada("…or a text to timestamp (keep it exactly as typed)", varias = true)
        boton("Timestamp this text") {
            val t = etTxt.text.toString()
            if (t.isEmpty()) { Toast.makeText(this, "Type a text", Toast.LENGTH_SHORT).show(); return@boton }
            sellar(java.security.MessageDigest.getInstance("SHA-256").digest(t.toByteArray(Charsets.UTF_8)), "text")
        }
        salSello = salidaBox()

        root.addView(android.view.View(this).apply {
            setBackgroundColor(AppTheme.BORDER_C)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                .apply { topMargin = dp(18); bottomMargin = dp(6) }
        })
        root.addView(rotulo("2 · Check or upgrade a proof"))
        var prueba: Ots.Archivo? = null
        var nombrePrueba = "proof.ots"
        lateinit var salVer: LinearLayout
        fun resumen(a: Ots.Archivo) {
            salVer.removeAllViews()
            val todas = a.sello.todas().map { it.second }
            val alturas = todas.filterIsInstance<Ots.Atestacion.Bitcoin>().map { it.altura }.sorted()
            val pend = todas.count { it is Ots.Atestacion.Pendiente }
            salVer.addView(filaResultado("File fingerprint in the proof", hex(a.digest)))
            salVer.addView(filaResultado("State", when {
                alturas.isNotEmpty() -> "Anchored in Bitcoin block ${alturas.first()}" + (if (pend > 0) " (and $pend calendar(s) still pending)" else "") + ". Verify it on the chain."
                pend > 0 -> "Pending in $pend calendar(s). Use 'Upgrade' a few hours after timestamping."
                else -> "No Bitcoin attestation in this proof."
            }, true))
        }
        boton("Open a .ots proof") {
            elegirArchivo { u ->
                val b = leerPequeno(u)
                val a = try { if (b == null) null else Ots.leer(b) } catch (e: IllegalArgumentException) { null }
                if (a == null) { salVer.removeAllViews(); salVer.addView(filaResultado("Not a valid .ots proof", "Choose the .ots file.")); return@elegirArchivo }
                prueba = a; nombrePrueba = nombreDe(u); resumen(a)
            }
        }
        boton("Does it match the original file?") {
            val a = prueba ?: run { Toast.makeText(this, "Open a .ots proof first", Toast.LENGTH_SHORT).show(); return@boton }
            elegirArchivo { u ->
                Thread {
                    val d = if (a.hashTag == 0x08) sha256De(u) else leerPequeno(u, 32 shl 20)?.let { Ots.hashArchivo(a.hashTag, it) }
                    runOnUiThread {
                        salVer.addView(if (d != null && d.contentEquals(a.digest))
                            filaResultado("✓ Same file", "${nombreDe(u)} is the file this proof is about.", true)
                            else filaResultado("✗ Different file", "${nombreDe(u)} is NOT the file of this proof (any change, even one byte, gives another fingerprint)."))
                    }
                }.start()
            }
        }
        boton("Upgrade (complete pending)") {
            val a = prueba ?: run { Toast.makeText(this, "Open a .ots proof first", Toast.LENGTH_SHORT).show(); return@boton }
            salVer.addView(filaResultado("Asking the calendars…", ""))
            Thread {
                val n = try { SelloTiempo.actualizar(a) } catch (e: Throwable) { 0 }
                runOnUiThread {
                    if (n == 0) { salVer.addView(filaResultado("Still pending", "Not in a block yet. Try again later (usually a few hours).")); return@runOnUiThread }
                    resumen(a)
                    guardarArchivo(nombrePrueba, Ots.escribir(a)) { ok ->
                        salVer.addView(filaResultado(if (ok) "Upgraded proof saved" else "Upgraded proof NOT saved",
                            if (ok) "Now it can be verified without any calendar." else "Try 'Upgrade' again to save it."))
                    }
                }
            }.start()
        }
        boton("Verify on the Bitcoin chain") {
            val a = prueba ?: run { Toast.makeText(this, "Open a .ots proof first", Toast.LENGTH_SHORT).show(); return@boton }
            salVer.addView(filaResultado("Checking block headers…", ""))
            Thread {
                val r = try { SelloTiempo.verificar(a) } catch (e: Throwable) { emptyList() }
                runOnUiThread {
                    if (r.isEmpty()) { salVer.addView(filaResultado("Nothing to verify yet", "The proof has no Bitcoin attestation. Use 'Upgrade' first.")); return@runOnUiThread }
                    val buenos = r.filterIsInstance<SelloTiempo.Resultado.Anclado>().sortedBy { it.altura }
                    buenos.firstOrNull()?.let { salVer.addView(filaResultado("✓ VERIFIED", "The file existed by ${fecha(it.hora)} — Bitcoin block ${it.altura}.\nIt also needs 'Does it match the original file?' to say it is the same file.", true)) }
                    for (f in r.filterIsInstance<SelloTiempo.Resultado.Falla>()) salVer.addView(filaResultado("✗ Block ${f.altura}", f.motivo))
                }
            }.start()
        }
        salVer = salidaBox()
        nota(AppTheme.BLUE, "A timestamp proves the file existed by that date — not who made it or that it is true. " +
             "Keep the original file unchanged together with its .ots.")
    }

    // ── Proof of reserves (BIP322 proof of funds) ─────────────────────────
    /*
     * Crear: con una cartera del teléfono (pide el código) se buscan sus monedas sin gastar y se firma una
     * prueba "pof" con todas, que se autoverifica. Comprobar: la firma (Bip322, probado con los vectores) y,
     * moneda a moneda en la cadena, que existe con ese importe y sigue sin gastar.
     */
    private fun construirReservas() {
        desc("Prove how much you hold without moving it: a signed proof (BIP322 \"proof of funds\") that you control " +
             "these coins. Useful for an inheritance, a loan or an audit. Whoever checks it sees each coin on the chain " +
             "and whether it is still unspent. Privacy: the proof reveals those coins and links them together.")
        fun btc(s: Long) = "%.8f BTC".format(s / 1e8)
        root.addView(rotulo("1 · Create a proof"))
        val carteras = listOf("" to WalletManager.mainName(this)) + WalletManager.listWallets(this)
        var carteraSel: Pair<String, String>? = null
        var btnCartera: Button? = null
        btnCartera = boton("Choose the wallet ▾") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Wallet")
                .setItems(carteras.map { it.second }.toTypedArray()) { _, i ->
                    carteraSel = carteras[i]; btnCartera?.text = "Wallet: ${carteras[i].second}"
                }.show()
        }
        val etMsg = entrada("Message, e.g. \"Reserves of Ana, 29 Oct 2026\"", varias = true)
        lateinit var salCrear: LinearLayout
        boton("Create proof") {
            salCrear.removeAllViews()
            val sel = carteraSel
            if (sel == null) { Toast.makeText(this, "Choose the wallet", Toast.LENGTH_SHORT).show(); return@boton }
            val msgTxt = etMsg.text.toString()
            if (msgTxt.isBlank()) { Toast.makeText(this, "Write a message (who, what for, date)", Toast.LENGTH_SHORT).show(); return@boton }
            PinAuthHelper.show(this, autoBiometric = false) { ok ->
                if (!ok) return@show
                val mn = if (sel.first.isEmpty()) WalletManager.loadSeed(this) else WalletManager.loadWalletSeed(this, sel.first)
                if (mn == null) { salCrear.addView(filaResultado("No seed", "That wallet has no seed phrase on this phone.")); return@show }
                salCrear.addView(filaResultado("Looking for your coins…", "This checks each address of the wallet; it can take a minute."))
                Thread {
                    val (ms, incompleto) = try { FirmaBip322.monedasDe(mn) } catch (e: Throwable) { RegistroFallos.anotar("Tools.reservas", e); emptyList<FirmaBip322.MonedaPropia>() to true }
                    val dir = ms.maxByOrNull { it.moneda.previa.valor }?.direccion
                    val prueba = if (dir == null) null else try { FirmaBip322.firmarFondos(mn, dir, msgTxt.toByteArray(Charsets.UTF_8), ms) } catch (e: Throwable) { null }
                    runOnUiThread {
                        salCrear.removeAllViews()
                        if (ms.isEmpty() || dir == null) {
                            salCrear.addView(filaResultado("No coins found", if (incompleto) "Some lookups failed; check the connection." else "This wallet has no unspent coins."))
                            return@runOnUiThread
                        }
                        if (prueba == null) { salCrear.addView(filaResultado("Could not sign the proof", "Try again.")); return@runOnUiThread }
                        salCrear.addView(filaResultado("Proves ${ms.size} coin(s)", btc(ms.sumOf { it.moneda.previa.valor }), true))
                        if (incompleto) salCrear.addView(filaResultado("Note", "Some lookups failed: the proof may not include every coin."))
                        salCrear.addView(filaResultado("Address", dir))
                        salCrear.addView(filaResultado("Message", msgTxt))
                        salCrear.addView(filaResultado("Proof (tap to copy)", if (prueba.length > 400) prueba.take(400) + "…" else prueba).apply {
                            setOnClickListener { copiar(prueba) }
                        })
                        salCrear.addView(filaResultado("Save as a file", "Address, message and proof in a text file.").apply {
                            setOnClickListener {
                                guardarArchivo("proof-of-reserves.txt", "address: $dir\nmessage: $msgTxt\nproof: $prueba\n".toByteArray()) { g ->
                                    Toast.makeText(this@PubkeyToolsActivity, if (g) "Saved" else "Not saved", Toast.LENGTH_SHORT).show()
                                }
                            }
                        })
                    }
                }.start()
            }
        }
        salCrear = salidaBox()

        root.addView(android.view.View(this).apply {
            setBackgroundColor(AppTheme.BORDER_C)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                .apply { topMargin = dp(18); bottomMargin = dp(6) }
        })
        root.addView(rotulo("2 · Check a proof"))
        val etA = entrada("Address")
        val etM = entrada("Message (exactly as given)", varias = true)
        val etP = entrada("Proof: pof…", varias = true)
        lateinit var salVer: LinearLayout
        boton("Check proof") {
            salVer.removeAllViews()
            val inf = FirmaBip322.info(etA.text.toString().trim())
            if (inf == null) { salVer.addView(filaResultado("Invalid address", "Check the address.")); return@boton }
            val msg = etM.text.toString().toByteArray(Charsets.UTF_8); val p = etP.text.toString()
            salVer.addView(filaResultado("Checking…", "Signatures, then each coin on the chain."))
            Thread {
                val f = try { Bip322.verificarFondos(inf, msg, p, FirmaBip322.verificador) } catch (e: Throwable) { Bip322.Fondos(Bip322.Veredicto.Invalida("The proof could not be read."), emptyList()) }
                val cadena = if (f.veredicto is Bip322.Veredicto.Valida) FirmaBip322.comprobarEnCadena(f.monedas) else emptyList()
                runOnUiThread {
                    salVer.removeAllViews()
                    when (val v = f.veredicto) {
                        is Bip322.Veredicto.Invalida -> { salVer.addView(filaResultado("✗ NOT valid", v.motivo)); return@runOnUiThread }
                        is Bip322.Veredicto.Inconclusa -> { salVer.addView(filaResultado("? Can't tell", v.motivo)); return@runOnUiThread }
                        is Bip322.Veredicto.Valida -> {}
                    }
                    val vivas = cadena.filter { it.existe && it.sinGastar == true }
                    salVer.addView(filaResultado("✓ Signatures valid", "Whoever made this controls the address and ${f.monedas.size} coin(s).", true))
                    salVer.addView(filaResultado("Unspent right now", "${vivas.size} of ${cadena.size} coin(s): ${btc(vivas.sumOf { it.moneda.previa.valor })}", true))
                    for (c in cadena.take(30)) salVer.addView(filaResultado(
                        "${if (c.existe && c.sinGastar == true) "✓" else "✗"} ${btc(c.moneda.previa.valor)}", "${c.moneda.txidTexto}:${c.moneda.vout}\n${c.nota}"))
                    if (cadena.size > 30) salVer.addView(filaResultado("…", "${cadena.size - 30} more coin(s)"))
                }
            }.start()
        }
        salVer = salidaBox()
        nota(AppTheme.BLUE, "A valid proof shows control of those coins when it was made; \"unspent right now\" is checked " +
             "against the chain when you press the button. It never proves there are no OTHER coins.")
    }

    // ── SeedQR, Seed XOR y codex32 ─────────────────────────────────────────

    private fun imagenQr(bmp: android.graphics.Bitmap, pie: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.WHITE); cornerRadius = dp(16).toFloat() }
        setPadding(dp(16), dp(16), dp(16), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        addView(ImageView(context).apply { setImageBitmap(bmp); adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)) })
        addView(TextView(context).apply { text = pie; textSize = AppTheme.SP_CAPTION; setTextColor(android.graphics.Color.DKGRAY); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
    }

    /** QR binario (modo byte) con [b] tal cual: sin ECI, como lo lee SeedSigner. */
    private fun qrBinario(b: ByteArray, lado: Int): android.graphics.Bitmap {
        val pistas = mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
                           com.google.zxing.EncodeHintType.MARGIN to 1)
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(String(b, Charsets.ISO_8859_1), com.google.zxing.BarcodeFormat.QR_CODE, lado, lado, pistas)
        val px = IntArray(m.width * m.height) { if (m.get(it % m.width, it / m.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        return android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.RGB_565)
    }

    private fun construirSeedQr() {
        desc("Your seed phrase as a QR, the SeedSigner standard: scan it into SeedSigner (and other devices that read " +
             "SeedQR) instead of typing the words. Standard is digits you could even copy by hand; Compact is smaller. " +
             "Anyone who sees this QR can take your money: show it only to your own device.")
        val et = entrada("12 or 24 words", varias = true)
        boton("Use a wallet on this phone") { elegirCartera { et.setText(it) } }
        lateinit var salida: LinearLayout
        boton("Make SeedQR") {
            salida.removeAllViews()
            val f = Bip39.normalize(et.text.toString()).joinToString(" ")
            val d = SeedQr.estandar(f); val c = SeedQr.compacto(f)
            if (d == null || c == null) { salida.addView(filaResultado("Not a valid phrase", "SeedQR is for valid 12- or 24-word phrases.")); return@boton }
            salida.addView(imagenQr(qrBitmap(d, 640), "Standard SeedQR"))
            salida.addView(imagenQr(qrBinario(c, 640), "Compact SeedQR"))
            salida.addView(filaResultado("Standard SeedQR digits (4 per word)", d.chunked(4).joinToString(" ")))
        }
        boton("Read a SeedQR") {
            alLeerSemilla = { texto, bytes ->
                salida.removeAllViews()
                val f = bytes?.let { SeedQr.deCompacto(it) } ?: SeedQr.deEstandar(texto.filter { it.isDigit() })
                if (f == null) salida.addView(filaResultado("Not a SeedQR", "It is neither a standard nor a compact SeedQR of a valid phrase."))
                else { et.setText(f); salida.addView(filaResultado("Seed phrase", f, true)) }
            }
            startActivityForResult(android.content.Intent(this, QrScanActivity::class.java), REQ_QR_SEMILLA)
        }
        salida = salidaBox()
        nota(AppTheme.WARN, "A passphrase (25th word), if you use one, is NOT in the QR: you still need it apart.")
    }

    private fun construirSeedXor() {
        desc("Split your phrase into 2–4 phrases (Coldcard's Seed XOR). Each part is a normal, valid wallet — a decoy — " +
             "and only ALL of them together give your real one. Keep them in different places. Compatible with Coldcard.")
        root.addView(rotulo("Split"))
        val et = entrada("Your 12 or 24 words", varias = true)
        boton("Use a wallet on this phone") { elegirCartera { et.setText(it) } }
        var n = 3
        root.addView(Ui.segmented(this, listOf("2 parts" to null, "3 parts" to null, "4 parts" to null), initial = 1) { n = it + 2 })
        lateinit var salPartir: LinearLayout
        boton("Split") {
            salPartir.removeAllViews()
            val partes = SeedXor.partir(Bip39.normalize(et.text.toString()).joinToString(" "), n)
            if (partes == null) { salPartir.addView(filaResultado("Not a valid phrase", "Use a valid 12- or 24-word phrase.")); return@boton }
            partes.forEachIndexed { i, p -> salPartir.addView(filaResultado("Part ${i + 1} of $n", p, true)) }
            salPartir.addView(filaResultado("Check", "Combining the $n parts gives back your phrase: " + if (SeedXor.juntar(partes) == Bip39.normalize(et.text.toString()).joinToString(" ")) "✓" else "✗"))
        }
        salPartir = salidaBox()
        root.addView(rotulo("Combine"))
        val etP = entrada("All the parts, one per line", varias = true)
        lateinit var salJuntar: LinearLayout
        boton("Combine") {
            salJuntar.removeAllViews()
            val partes = etP.text.toString().lines().map { Bip39.normalize(it).joinToString(" ") }.filter { it.isNotEmpty() }
            val r = SeedXor.juntar(partes)
            salJuntar.addView(if (r == null) filaResultado("Can't combine", "Each line must be a valid phrase, all of the same length (at least 2).")
                              else filaResultado("Your phrase", r, true))
        }
        salJuntar = salidaBox()
        nota(AppTheme.WARN, "Lose any one part and the real phrase is gone. If you use a passphrase, it is separate.")
    }

    private fun construirCodex32() {
        desc("codex32 (BIP93): a BIP32 master seed written with a strong checksum that catches up to 8 wrong characters, " +
             "optionally split into k-of-n shares you can even check by hand. Note: it holds the seed itself, NOT a " +
             "12/24-word phrase, so it is for wallets that accept codex32 or an xprv.")
        root.addView(rotulo("Check or recover"))
        val et = entrada("A codex32 secret, or k shares — one per line", varias = true)
        lateinit var sal: LinearLayout
        fun mostrarSecreto(s: Codex32.Cadena) {
            val semilla = Codex32.semilla(s)
            if (semilla == null) { sal.addView(filaResultado("Not a master seed", "Valid codex32, but its payload is not a 16–64 byte seed.")); return }
            sal.addView(filaResultado("✓ Valid secret (${semilla.size * 8}-bit seed)", s.toString(), true))
            sal.addView(filaResultado("Master seed (hex)", semilla.joinToString("") { "%02x".format(it) }))
            sal.addView(filaResultado("Master xprv", Codex32.xprv(semilla)))
        }
        boton("Check") {
            sal.removeAllViews()
            val lineas = et.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lineas.isEmpty()) return@boton
            val cad = lineas.map { Codex32.leer(it) }
            cad.forEachIndexed { i, c -> if (c == null) { sal.addView(filaResultado("✗ Line ${i + 1} is not valid codex32", "A typo, a missing character, or mixed upper and lower case.")); return@boton } }
            val ok = cad.filterNotNull()
            val secreto = ok.firstOrNull { it.esSecreto }
            when {
                secreto != null && ok.size == 1 -> mostrarSecreto(secreto)
                else -> {
                    Codex32.problema(ok)?.let { sal.addView(filaResultado("Can't recover yet", it)); return@boton }
                    mostrarSecreto(Codex32.recuperar(ok)!!)
                }
            }
        }
        sal = salidaBox()
        root.addView(rotulo("Split a seed into shares"))
        val etSemilla = entrada("Master seed in hex (16 or 32 bytes) — empty = a new random one")
        val etId = entrada("Identifier: 4 characters (e.g. cash)")
        var k = 2; var n = 3
        root.addView(Ui.segmented(this, listOf("2 of 3" to null, "3 of 5" to null, "4 of 7" to null)) { k = listOf(2, 3, 4)[it]; n = listOf(3, 5, 7)[it] })
        lateinit var salPartir: LinearLayout
        boton("Make shares") {
            salPartir.removeAllViews()
            val h = etSemilla.text.toString().trim().lowercase()
            val semilla = if (h.isEmpty()) ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                          else if (Regex("^([0-9a-f]{32}|[0-9a-f]{64})$").matches(h)) Dnssec.hexa(h) else null
            val id = etId.text.toString().trim().lowercase()
            if (semilla == null) { salPartir.addView(filaResultado("Invalid seed", "Use 32 or 64 hex characters, or leave it empty.")); return@boton }
            if (id.length != 4 || !id.all { it in Codex32.CS }) { salPartir.addView(filaResultado("Invalid identifier", "4 characters from: ${Codex32.CS}")); return@boton }
            val partes = Codex32.partir(semilla, k, n, id)
            partes.forEachIndexed { i, p -> salPartir.addView(filaResultado("Share ${i + 1} of $n (any $k recover it)", p.uppercase(), true)) }
            salPartir.addView(filaResultado("Master xprv of this seed", Codex32.xprv(semilla)))
        }
        salPartir = salidaBox()
    }

    // ── Explorer ───────────────────────────────────────────────────────────
    /*
     * Un explorador de bloques con la API Esplora que ya usa la app (mempool.space o blockstream.info, el que
     * conteste; por Tor si está activado). Las cuentas (bloques proyectados, halving, ajuste) son de Explorador.
     */
    private fun btcTxt(s: Long) = "%.8f BTC".format(s / 1e8)
    private fun haceTxt(t: Long): String {
        val s = System.currentTimeMillis() / 1000 - t
        return when { s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> "${s / 86400} days ago" }
    }
    private fun fechaTxt(t: Long) = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.US).format(java.util.Date(t * 1000))
    private fun cargando(salida: LinearLayout) { salida.removeAllViews(); salida.addView(filaResultado("Loading…", "")) }

    private fun construirRed() {
        desc("The network right now: height, mempool, what would fit in the next blocks, the next halving and " +
             "the next difficulty adjustment. From a public explorer (via Tor if it is on).")
        lateinit var salida: LinearLayout
        boton("Refresh") {
            cargando(salida)
            Thread {
                val punta = ChainApi.get("/blocks/tip/height")?.trim()?.toLongOrNull()
                val mem = ChainApi.get("/mempool")?.let { Explorador.leerMempool(it) }
                val tPunta = ChainApi.get("/blocks/tip/hash")?.trim()?.let { ChainApi.get("/block/$it") }
                    ?.let { try { org.json.JSONObject(it).optLong("timestamp") } catch (e: Exception) { null } }
                val tInicio = punta?.let { p -> ChainApi.get("/block-height/${Explorador.inicioPeriodo(p)}")?.trim()?.let { ChainApi.get("/block/$it") } }
                    ?.let { try { org.json.JSONObject(it).optLong("timestamp") } catch (e: Exception) { null } }
                runOnUiThread {
                    salida.removeAllViews()
                    if (punta == null) { salida.addView(filaResultado("No network", "No explorer answered.")); return@runOnUiThread }
                    salida.addView(filaResultado("Block height", "%,d".format(punta) + (tPunta?.let { " · last block ${haceTxt(it)}" } ?: ""), true))
                    if (mem != null) {
                        salida.addView(filaResultado("Mempool", "%,d transactions · %.1f MvB · fees %s".format(mem.txs, mem.vsize / 1e6, btcTxt(mem.comisionTotal))))
                        val bloques = Explorador.proyectar(mem.histograma, 6)
                        for ((i, b) in bloques.withIndex()) salida.addView(filaResultado(
                            if (i == 0) "Next block (~10 min)" else "Block +${i + 1} (~${(i + 1) * 10} min)",
                            "%.1f – %.1f sat/vB · median %.1f · %.2f MvB".format(b.minima, b.maxima, b.mediana, b.vsize / 1e6)))
                    }
                    val h = Explorador.halving(punta)
                    salida.addView(filaResultado("Next halving", "Block %,d · in %,d blocks (~%,d days)\nSubsidy %s → %s".format(
                        h.siguiente, h.faltan, h.faltan * 10 / 1440, btcTxt(h.subvencionAhora), btcTxt(h.subvencionDespues))))
                    val a = if (tInicio != null && tPunta != null) Explorador.ajuste(punta, tInicio, tPunta) else null
                    if (a != null) salida.addView(filaResultado("Next difficulty adjustment", "%+.1f %% estimated · in %,d blocks (%s)\nAverage block time this period: %.1f min".format(
                        a.cambio * 100, a.faltan, fechaTxt(System.currentTimeMillis() / 1000 + a.segundosHasta.toLong()), a.mediaSeg / 60)))
                }
            }.start()
        }
        salida = salidaBox()
        nota(AppTheme.TXT_MUTED, "Projected blocks assume miners take the highest fee rates first; real blocks can differ (CPFP, private deals).")
    }

    private fun construirBloques() {
        desc("The latest blocks: height, when, how many transactions and how big. Tap a row to copy its hash.")
        lateinit var salida: LinearLayout
        boton("Load") {
            cargando(salida)
            Thread {
                val bs = ChainApi.get("/blocks")?.let { Explorador.leerBloques(it) }.orEmpty()
                runOnUiThread {
                    salida.removeAllViews()
                    if (bs.isEmpty()) { salida.addView(filaResultado("No network", "No explorer answered.")); return@runOnUiThread }
                    for (b in bs) salida.addView(filaResultado("#%,d · %s".format(b.altura, haceTxt(b.hora)),
                        "%,d txs · %.2f MB · %.2f MWU\n%s".format(b.txs, b.tamano / 1e6, b.peso / 1e6, b.hash)).apply { setOnClickListener { copiar(b.hash) } })
                }
            }.start()
        }
        salida = salidaBox()
    }

    private fun construirBuscar() {
        desc("Paste a transaction id, a block height or hash, or an address. For an unconfirmed transaction it " +
             "estimates in which upcoming block it would fit at its fee rate.")
        val et = entrada("txid, block height/hash or address")
        lateinit var salida: LinearLayout
        boton("Look up") {
            val q = Explorador.que(et.text.toString())
            if (q == null) { Toast.makeText(this, "Not a txid, block or address", Toast.LENGTH_SHORT).show(); return@boton }
            cargando(salida)
            Thread {
                val filas = ArrayList<Triple<String, String, Boolean>>()
                fun fila(a: String, b: String, d: Boolean = false) = filas.add(Triple(a, b, d))
                when (q) {
                    is Explorador.Busqueda.Tx -> {
                        val punta = ChainApi.get("/blocks/tip/height")?.trim()?.toLongOrNull()
                        val t = ChainApi.get("/tx/${q.txid}")?.let { Explorador.leerTx(it, punta) }
                        if (t == null) fila("Not found", "No explorer knows this transaction.")
                        else {
                            if (t.altura != null) fila("✓ Confirmed", "%,d confirmation(s) · block %,d".format(t.confirmaciones, t.altura) + (t.hora?.let { " · ${fechaTxt(it)}" } ?: ""), true)
                            else {
                                val b = ChainApi.get("/mempool")?.let { Explorador.leerMempool(it) }?.let { Explorador.proyectar(it.histograma) }
                                val pos = b?.let { Explorador.posicion(t.tasa, it) }
                                fila("Unconfirmed", when {
                                    b == null -> "In the mempool."
                                    pos == null -> "Its fee rate is below the next ${b.size} projected blocks: it may wait a long time." +
                                        (if (t.rbf) " It is replaceable: you can raise its fee (RBF)." else " Speed it up with a child (CPFP).")
                                    else -> "Would fit in projected block #${pos + 1} (~${(pos + 1) * 10} min) at its fee rate."
                                }, true)
                            }
                            fila("Fee", "%,d sat · %.1f sat/vB · %,d vB".format(t.comision, t.tasa, t.vsize))
                            fila("Inputs / outputs", "${t.entradas} → ${t.salidas} · ${btcTxt(t.totalSalidas)} out")
                            fila("Replaceable (RBF)", if (t.rbf) "Yes" else "No")
                            fila("txid", t.txid)
                        }
                    }
                    is Explorador.Busqueda.BloqueAltura, is Explorador.Busqueda.BloqueHash -> {
                        val hash = if (q is Explorador.Busqueda.BloqueHash) q.hash else ChainApi.get("/block-height/${(q as Explorador.Busqueda.BloqueAltura).altura}")?.trim()
                        val b = hash?.let { ChainApi.get("/block/$it") }?.let { try { Explorador.leerBloque(org.json.JSONObject(it)) } catch (e: Exception) { null } }
                        if (b == null) fila("Not found", "No explorer knows this block.")
                        else {
                            fila("Block %,d".format(b.altura), "${fechaTxt(b.hora)} (${haceTxt(b.hora)})", true)
                            fila("Transactions", "%,d".format(b.txs)); fila("Size", "%.2f MB · %.2f MWU".format(b.tamano / 1e6, b.peso / 1e6))
                            fila("Halving era", "#${b.altura / 210_000 + 1} · subsidy ${btcTxt(Explorador.halving(b.altura).subvencionAhora)}")
                            fila("Hash", b.hash)
                        }
                    }
                    is Explorador.Busqueda.Direccion -> {
                        val s = ChainApi.get("/address/${q.d}")?.let { Explorador.leerDireccion(it) }
                        if (s == null) fila("Not found", "No explorer answered.")
                        else {
                            fila("Balance", btcTxt(s.confirmado), true)
                            if (s.sinConfirmar != 0L) fila("Unconfirmed", (if (s.sinConfirmar > 0) "+" else "") + btcTxt(s.sinConfirmar))
                            fila("Transactions", "%,d".format(s.txs) + if (s.txs > 1) " · reused address (less private)" else "")
                        }
                    }
                }
                runOnUiThread { salida.removeAllViews(); for ((a, b, d) in filas) salida.addView(filaResultado(a, b, d)) }
            }.start()
        }
        salida = salidaBox()
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

    /**
     * Una clave que estas herramientas han encontrado o generado se guarda en el baúl cifrado
     * en el momento: salir de la pantalla la perdía. Devuelve el texto para la fila de resultado.
     */
    private fun guardarEnBaul(priv: String, extra: String): String =
        if (priv.length == 64 && MatchVault.guardarClave(this, priv, "tool", extra)) "Saved in the finds vault (encrypted)."
        else "NOT saved to the vault. Copy the key now."

    // ── Balance & UTXOs (watch-only) ───────────────────────────────────────
    @Volatile private var balCorriendo = false
    private fun satsABtc(s: Long) = "%.8f".format(s / 1e8).trimEnd('0').trimEnd('.') + " BTC" + Fiat.entre(this, s)
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
            // Un 5… se convertía en su WIF comprimido y se barría la dirección
            // comprimida, que no es donde están sus fondos: decía "sin fondos".
            if (esWifSinComprimir(etKey.text.toString())) {
                tvEstado.text = "This is an uncompressed key (5…): its coins are on its uncompressed " +
                                "address, which this sweep can't spend. Use a wallet that supports " +
                                "uncompressed keys, such as Electrum or Sparrow."
                return@boton
            }
            if (BtcAddress.validate(to, false) !is BtcAddress.Result.Valid) { Toast.makeText(this, "Invalid destination", Toast.LENGTH_SHORT).show(); return@boton }
            sweepCorriendo = true; btn.text = "…"; tvEstado.text = "Reading UTXOs…"
            Thread {
                if (!PinAuthHelper.codigoParaFirmar(this@PubkeyToolsActivity)) {
                    runOnUiThread { sweepCorriendo = false; btn.text = "Prepare sweep"; tvEstado.text = "Cancelled: the code is needed to send." }
                    return@Thread
                }
                val wif = try { HunterEngine.wifDeHex(hx).substringBefore("|") } catch (e: Throwable) { "" }
                val dirTxt = try { HunterEngine.direccionesDe(hx) } catch (e: Throwable) { "" }
                val addr = dirTxt.split('\n').firstOrNull { it.startsWith("P2PKH (compressed)=") }?.substringAfter('=') ?: ""
                if (wif.isBlank() || !(wif[0] == 'K' || wif[0] == 'L') || addr.isBlank()) {
                    runOnUiThread { sweepCorriendo = false; btn.text = "Prepare sweep"; tvEstado.text = "Only compressed P2PKH keys are supported here." }; return@Thread
                }
                val utxoStr = ChainApi.get("/address/$addr/utxo")
                // Con la comprobación de altura creíble (ver ChainInfo.tipHeight): una
                // inflada dejaba la transacción sin validez durante meses.
                val tip = ChainInfo.tipHeight() ?: 0
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
                        sweepCorriendo = false
                        // Se tarda en leer la red: si se salió de la pantalla mientras, no se
                        // abre un diálogo sobre una Activity muerta (cerraba la app).
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        btn.text = "Prepare sweep"
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
                                        if (isFinishing || isDestroyed) return@runOnUiThread
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

    // ── BSGS: resolver clave en un rango pequeño ───────────────────────────
    @Volatile private var bsgsCorriendo = false
    private fun construirBsgs() {
        desc("Given a PUBLIC key that spent (02/03/04…) and a SMALL known range for " +
             "its private key, solve it deterministically (baby-step/giant-step). " +
             "Best for ranges up to ~2^40. For big ranges use the Weak-key / Puzzle " +
             "Kangaroo instead. Only works on keys you own.")
        val etPub = entrada("public key hex (02/03 + 64, or 04 + 128)")
        val etIni = entrada("range start (hex), e.g. 1")
        val etFin = entrada("range end (hex), e.g. fffff")
        val tv = TextView(this).apply { text = ""; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context); setPadding(dp(2), dp(12), 0, 0) }
        val salida = salidaBox()
        val poll = Handler(Looper.getMainLooper())
        val t0 = longArrayOf(0L)
        lateinit var btn: Button
        lateinit var tick: Runnable
        tick = Runnable {
            if (!bsgsCorriendo) return@Runnable
            val res = try { HunterEngine.bsgsResult() } catch (e: Throwable) { "ERROR" }
            if (res.isEmpty()) {
                val n = try { HunterEngine.bsgsCount() } catch (e: Throwable) { 0L }
                val secs = ((System.currentTimeMillis() - t0[0]) / 1000.0).coerceAtLeast(0.001)
                tv.text = "Searching… ${"%,d".format(n)} tried · ~${"%,d".format((n / secs).toLong())}/s"
                poll.postDelayed(tick, 300); return@Runnable
            }
            bsgsCorriendo = false; btn.text = "Solve"
            when (res) {
                "NOT_FOUND"     -> tv.text = "Not found: the key is not in that range."
                "RANGE_TOO_BIG" -> tv.text = "Range too big for BSGS. Use the Weak-key / Puzzle Kangaroo."
                "ERROR"         -> tv.text = "Invalid public key or range."
                else -> {
                    val wif = try { HunterEngine.wifDeHex(res).substringBefore("|") } catch (e: Throwable) { "" }
                    salida.addView(filaResultado("Private key", res, true))
                    if (wif.isNotEmpty()) salida.addView(filaResultado("WIF", wif))
                    salida.addView(filaResultado("Finds vault", guardarEnBaul(res, "BSGS solver")))
                    val n = try { HunterEngine.bsgsCount() } catch (e: Throwable) { 0L }
                    tv.text = "Solved after ${"%,d".format(n)} giant steps."
                }
            }
        }
        btn = boton("Solve") {
            if (bsgsCorriendo) { bsgsCorriendo = false; try { HunterEngine.bsgsStop() } catch (e: Throwable) {}; btn.text = "Solve"; return@boton }
            val pub = etPub.text.toString().trim().removePrefix("0x").lowercase()
            val ini = etIni.text.toString().trim().removePrefix("0x").ifEmpty { "1" }
            val fin = etFin.text.toString().trim().removePrefix("0x")
            if (pub.isEmpty() || fin.isEmpty()) { Toast.makeText(this, "Fill the key and the range", Toast.LENGTH_SHORT).show(); return@boton }
            salida.removeAllViews(); bsgsCorriendo = true; btn.text = "Stop"
            t0[0] = System.currentTimeMillis()
            try { HunterEngine.bsgsStart(pub, ini, fin, 21) } catch (e: Throwable) {
                bsgsCorriendo = false; btn.text = "Solve"; Toast.makeText(this, "Engine error", Toast.LENGTH_SHORT).show(); return@boton
            }
            poll.postDelayed(tick, 300)
        }
        root.addView(tv)
        nota(AppTheme.WARN, "Deterministic: if the key really is in the range, it WILL be found. " +
             "Needs the public key (a spent address), not just the address.")
    }

    // ── Nonce-leak recovery (lattice / HNP) ───────────────────────────────
    @Volatile private var latCorriendo = false
    private fun normalizaPubComprimida(pub: String): String? {
        val p = pub.trim().removePrefix("0x").lowercase()
        return when {
            p.length == 66 && (p.startsWith("02") || p.startsWith("03")) && p.drop(2).all { it in "0123456789abcdef" } -> p
            p.length == 130 && p.startsWith("04") -> {
                val x = p.substring(2, 66); val y = p.substring(66)
                val yOdd = java.math.BigInteger(y, 16).testBit(0)
                (if (yOdd) "03" else "02") + x
            }
            else -> null
        }
    }
    private fun construirLattice() {
        desc("Recovers a private key when several signatures from the SAME key used " +
             "SHORT or biased nonces (top bits zero — e.g. a 128-bit RNG on a 256-bit " +
             "curve). This is the 'Biased Nonce Sense' lattice attack (HNP + LLL). " +
             "Enter the public key and the signatures as 'r s z' per line (hex), where z " +
             "is the message hash each signature signed. Only works on your own keys.")
        val etPub = entrada("public key 02…/03…/04…")
        root.addView(rotulo("Signatures: r s z (hex), one per line"))
        val etSigs = entrada("r s z\nr s z\n…", varias = true)
        root.addView(rotulo("Nonce size (bits) — leave blank to auto-detect"))
        val etBits = entrada("blank = auto")
        val salida = salidaBox()
        val tv = TextView(this).apply { text = ""; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context); setPadding(dp(2), dp(12), 0, 0) }
        lateinit var btn: Button
        btn = boton("Recover") {
            // El mismo botón para: antes no había forma de parar el barrido
            // (hasta 40 firmas por 14 tamaños de nonce), ni al salir de la pantalla.
            if (latCorriendo) { latCorriendo = false; btn.text = "Stopping…"; return@boton }
            val target = normalizaPubComprimida(etPub.text.toString())
            if (target == null) { Toast.makeText(this, "Enter a valid public key", Toast.LENGTH_SHORT).show(); return@boton }
            val fixed = etBits.text.toString().trim().toIntOrNull()?.coerceIn(1, 255)   // null = auto-detect
            val sigs = ArrayList<Triple<java.math.BigInteger, java.math.BigInteger, java.math.BigInteger>>()
            for (ln in etSigs.text.toString().split('\n')) {
                val toks = ln.split(Regex("[^0-9a-fA-Fx]+")).map { it.removePrefix("0x").trim() }.filter { it.isNotEmpty() }
                if (toks.size < 3) continue
                try { sigs.add(Triple(java.math.BigInteger(toks[0], 16), java.math.BigInteger(toks[1], 16), java.math.BigInteger(toks[2], 16))) } catch (e: Throwable) {}
            }
            if (sigs.size < 2) { Toast.makeText(this, "Need at least 2 signatures (r s z)", Toast.LENGTH_LONG).show(); return@boton }
            if (sigs.size > 40) { Toast.makeText(this, "Too many signatures (max 40 here)", Toast.LENGTH_LONG).show(); return@boton }
            salida.removeAllViews(); latCorriendo = true; btn.text = "Stop"
            tv.text = if (fixed != null) "Reducing lattice (2^$fixed nonces)…" else "Auto-detecting nonce size…"
            Thread {
                val verify = { cand: java.math.BigInteger ->
                    val priv = cand.toString(16).padStart(64, '0')
                    (try { HunterEngine.pubDeHex(priv) } catch (e: Throwable) { "" }).equals(target, ignoreCase = true)
                }
                var d: java.math.BigInteger? = null
                try {
                    if (fixed != null) d = Lattice.recover(sigs, fixed, verify)
                    else Lattice.recoverAuto(sigs, { !latCorriendo }, verify)?.let { d = it.first }
                } catch (e: Throwable) {}
                val dd = d
                val parado = !latCorriendo
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    latCorriendo = false; btn.text = "Recover"
                    if (dd == null && parado) { tv.text = "Stopped."; return@runOnUiThread }
                    if (dd != null) {
                        val priv = dd.toString(16).padStart(64, '0')
                        val wif = try { HunterEngine.wifDeHex(priv).substringBefore("|") } catch (e: Throwable) { "" }
                        salida.addView(filaResultado("Private key", priv, true))
                        if (wif.isNotEmpty()) salida.addView(filaResultado("WIF", wif))
                        salida.addView(filaResultado("Finds vault", guardarEnBaul(priv, "Lattice nonce recovery")))
                        tv.text = "Recovered — the nonces were weak" + (if (fixed == null) " (auto-detected)." else ".")
                    } else {
                        tv.text = "Not recovered. The nonces may not be short/biased, or you need more signatures."
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        root.addView(tv)
        nota(AppTheme.WARN, "Self-verifying: it only returns a key whose d·G matches the public key. " +
             "Short-nonce case (128-bit) needs ~2–4 signatures; smaller bias needs more.")
    }

    // ── RFC6979 deterministic-nonce check ─────────────────────────────────
    private val SECP_N = java.math.BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private fun hmac256(key: ByteArray, msg: ByteArray): ByteArray {
        val m = javax.crypto.Mac.getInstance("HmacSHA256")
        m.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")); return m.doFinal(msg)
    }
    private fun i2o(x: java.math.BigInteger): ByteArray {
        val b = x.toByteArray(); val o = ByteArray(32)
        if (b.size >= 32) System.arraycopy(b, b.size - 32, o, 0, 32)
        else System.arraycopy(b, 0, o, 32 - b.size, b.size)
        return o
    }
    /** Nonce determinista RFC6979 (SHA-256) para secp256k1. */
    private fun rfc6979(priv: java.math.BigInteger, z: java.math.BigInteger): java.math.BigInteger {
        val x = i2o(priv.mod(SECP_N)); val h = i2o(z.mod(SECP_N))
        var v = ByteArray(32) { 1 }; var k = ByteArray(32) { 0 }
        k = hmac256(k, v + byteArrayOf(0) + x + h); v = hmac256(k, v)
        k = hmac256(k, v + byteArrayOf(1) + x + h); v = hmac256(k, v)
        while (true) {
            v = hmac256(k, v)
            val cand = java.math.BigInteger(1, v)
            if (cand.signum() > 0 && cand < SECP_N) return cand
            k = hmac256(k, v + byteArrayOf(0)); v = hmac256(k, v)
        }
    }
    private fun construirRfc6979() {
        desc("Checks whether a signature used the RFC6979 DETERMINISTIC nonce (the modern, " +
             "safe standard) or a random one (RNG-dependent — a bad RNG is how keys leak). " +
             "Give the private key and lines of 'r s z' (hex). With only 'z' it signs it " +
             "deterministically so you can see the expected r/s.")
        val etPriv = entrada("private key (64 hex)")
        root.addView(rotulo("r s z per line (hex) — or just z to sign"))
        val etSigs = entrada("r s z\n…", varias = true)
        val salida = salidaBox()
        boton("Check") {
            salida.removeAllViews()
            val pv = etPriv.text.toString().trim().removePrefix("0x").lowercase()
            if (pv.length != 64) { Toast.makeText(this, "Enter a 64-hex private key", Toast.LENGTH_SHORT).show(); return@boton }
            val d = try { java.math.BigInteger(pv, 16) } catch (e: Throwable) { null } ?: return@boton
            for (ln in etSigs.text.toString().split('\n')) {
                val t = ln.split(Regex("[^0-9a-fA-Fx]+")).map { it.removePrefix("0x").trim() }.filter { it.isNotEmpty() }
                if (t.isEmpty()) continue
                try {
                    if (t.size == 1) {   // sólo z → firmar
                        val z = java.math.BigInteger(t[0], 16)
                        val k = rfc6979(d, z)
                        val rx = HunterEngine.pubDeHex(k.toString(16).padStart(64, '0')).substring(2)
                        val r = java.math.BigInteger(rx, 16).mod(SECP_N)
                        val s0 = k.modInverse(SECP_N).multiply(z.add(r.multiply(d))).mod(SECP_N)
                        // s bajo (BIP62/BIP146): con s > n/2 la firma es maleable y la red
                        // no la retransmite.
                        val s = if (s0 > SECP_N.shiftRight(1)) SECP_N.subtract(s0) else s0
                        salida.addView(filaResultado("RFC6979 signature", "r ${r.toString(16)}\ns ${s.toString(16)}", true))
                    } else if (t.size >= 3) {
                        val r = java.math.BigInteger(t[0], 16); val z = java.math.BigInteger(t[2], 16)
                        val k = rfc6979(d, z)
                        val rx = HunterEngine.pubDeHex(k.toString(16).padStart(64, '0')).substring(2)
                        val rDet = java.math.BigInteger(rx, 16).mod(SECP_N)
                        val det = rDet == r
                        // Que no coincida NO prueba un nonce aleatorio: Bitcoin Core (y
                        // otras) repiten RFC6979 con datos extra hasta sacar una r corta,
                        // y así salen la mitad de sus firmas. Antes se marcaba como
                        // "Random nonce" sin más.
                        salida.addView(filaResultado(if (det) "✓ Deterministic (plain RFC6979)" else "• Not plain RFC6979",
                            if (det) "r ${r.toString(16).take(20)}…"
                            else "r ${r.toString(16).take(20)}…\nMay be RFC6979 with extra data (e.g. Bitcoin Core " +
                                 "grinding for a short r) or a random nonce. This check can't tell them apart."))
                    }
                } catch (e: Throwable) {}
            }
            if (salida.childCount == 0) salida.addView(filaResultado("—", "No valid r s z / z lines."))
        }
        nota(AppTheme.TXT_MUTED, "A deterministic nonce (RFC6979) is safe even with a weak RNG. A random one " +
             "is only as safe as the device's RNG — the source of most nonce-reuse leaks.")
    }

    // ── Signature low-S / malleability auditor ─────────────────────────────
    private fun construirMalleability() {
        desc("Audits ECDSA signatures for BIP62/BIP146 'low-S' and strict DER. A high-S " +
             "signature is third-party malleable (its txid can change before confirmation) " +
             "and non-standard on today's network. Paste DER signatures (hex) or 'r s' per line.")
        val et = entrada("DER sig hex, or 'r s' (hex), one per line", varias = true)
        val salida = salidaBox()
        val half = SECP_N.shiftRight(1)
        boton("Audit") {
            salida.removeAllViews()
            var n = 0; var high = 0
            for (ln in et.text.toString().split('\n')) {
                val raw = ln.trim().removePrefix("0x")
                if (raw.isEmpty()) continue
                var s: java.math.BigInteger? = null; var der = false
                val p = parseSig(raw.lowercase())
                if (p != null) { der = true; s = try { java.math.BigInteger(p.second, 16) } catch (e: Throwable) { null } }
                else {
                    val t = ln.split(Regex("[^0-9a-fA-Fx]+")).map { it.removePrefix("0x").trim() }.filter { it.isNotEmpty() }
                    if (t.size >= 2) s = try { java.math.BigInteger(t[1], 16) } catch (e: Throwable) { null }
                }
                if (s == null) { salida.addView(filaResultado("? unparseable", ln.take(24))); continue }
                n++
                val low = s <= half
                if (!low) high++
                salida.addView(filaResultado(
                    (if (low) "✓ low-S" else "⚠ HIGH-S (malleable)") + (if (der) " · DER ok" else " · raw"),
                    "s ${s.toString(16).take(24)}…"))
            }
            if (n > 0) salida.addView(filaResultado("Summary", "$n checked · $high high-S (malleable)"))
            else if (salida.childCount == 0) Toast.makeText(this, "Paste signatures", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Weak RNG check (Milk Sad, CVE-2023-39910) ───────────────────────────
    @Volatile private var msCorriendo = false
    private fun construirMilkSad() {
        desc("Checks whether YOUR seed phrase could have come from the broken \"bx seed\" generator " +
             "(Milk Sad, CVE-2023-39910): it seeded a random generator with only 32 bits, so the whole key " +
             "space is tiny and anyone can rebuild those keys. Paste your phrase; it stays on-device. " +
             "If it matches, move your coins to a new wallet made with a trusted generator.")
        val et = entrada("Your 12 / 24-word phrase", varias = true)
        // Modo: todo (2^32) o una ventana de fechas, para los generadores débiles que sembraban con la hora
        // en SEGUNDOS (muchos de la familia Milk Sad). Con bx (nanosegundos) hay que barrer todo.
        var modoFechas = false
        var etDesde: EditText? = null
        var etHasta: EditText? = null
        val seg = Ui.segmented(this, listOf("All 2^32 seeds" to null, "Date window (seconds-seeded)" to null)) {
            modoFechas = it == 1
            val vis = if (modoFechas) android.view.View.VISIBLE else android.view.View.GONE
            etDesde?.visibility = vis; etHasta?.visibility = vis
        }
        root.addView(seg)
        etDesde = entrada("From date (YYYY-MM-DD, e.g. 2015-01-01)").apply { visibility = android.view.View.GONE }
        etHasta = entrada("To date (YYYY-MM-DD, e.g. 2024-01-01)").apply { visibility = android.view.View.GONE }
        val salida = salidaBox()
        val tvProg = TextView(this).apply { textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); setPadding(0, dp(8), 0, 0) }
        fun diaASeg(t: String): Long? = try {
            java.text.SimpleDateFormat("yyyy-MM-dd").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(t.trim())?.time?.div(1000)
        } catch (e: Exception) { null }
        lateinit var btn: Button
        btn = boton("Check") {
            if (msCorriendo) { HunterEngine.milksadDetener(); return@boton }
            salida.removeAllViews()
            val frase = et.text.toString()
            val ent = Bip39.entropia(frase)
            if (ent == null) { salida.addView(filaResultado("Not a valid phrase", "Enter a complete, valid BIP39 phrase.")); return@boton }
            var ini = 0L; var fin = 0x100000000L
            if (modoFechas) {
                val d = diaASeg(etDesde!!.text.toString()); val h0 = diaASeg(etHasta!!.text.toString())
                if (d == null || h0 == null || h0 <= d) { salida.addView(filaResultado("Bad dates", "Use YYYY-MM-DD, and 'to' after 'from'.")); return@boton }
                ini = d; fin = (h0 + 86400).coerceAtMost(0x100000000L)
            }
            val hex = ent.joinToString("") { "%02x".format(it) }
            val err = HunterEngine.milksadBuscar(hex, ini, fin, maxOf(1, Runtime.getRuntime().availableProcessors() - 1))
            if (err.isNotEmpty()) { salida.addView(filaResultado("Could not start", err)); return@boton }
            msCorriendo = true; btn.text = "Stop"
            val n = ent.size
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            h.post(object : Runnable {
                override fun run() {
                    val corre = HunterEngine.milksadCorriendo()
                    val hechas = HunterEngine.milksadHechas(); val total = HunterEngine.milksadTotal().coerceAtLeast(1)
                    tvProg.text = "Scanned %,d of %,d seeds (%.2f %%)".format(hechas, total, hechas * 100.0 / total)
                    if (corre) { h.postDelayed(this, 500); return }
                    msCorriendo = false; btn.text = "Check"
                    val sem = HunterEngine.milksadResultado()
                    if (sem >= 0) {
                        salida.addView(filaResultado("⚠ VULNERABLE — Milk Sad", "Your phrase comes from the broken generator (32-bit seed $sem). Anyone can rebuild its keys.", true))
                        salida.addView(filaResultado("What to do", "Create a NEW wallet with a freshly generated phrase and move ALL your coins to it now."))
                    } else {
                        salida.addView(filaResultado("✓ Not found", if (modoFechas) "Not produced by a seconds-seeded generator in that date window." else "Your phrase was not produced by this broken generator across all 2^32 seeds.", true))
                    }
                }
            })
        }
        root.addView(tvProg)
        nota(AppTheme.TXT_MUTED, "This only rules out the 'bx seed' / Milk Sad generator. Scanning all seeds can take a while; " +
             "it runs on this phone and nothing is sent anywhere.")
    }

    // ── Legacy weak wallets: Randstorm & the 2013 Android SecureRandom bug ──
    /*
     * Honesto a propósito: NO finge escanear. Estos dos fallos no se pueden
     * reproducir como el de Milk Sad (barriendo semillas): el de Android 2013
     * sólo deja rastro EXPLOTABLE cuando repitió un nonce de firma —y eso ya lo
     * caza "Nonce-reuse audit"—; y Randstorm (BitcoinJS 2011–2015) no tiene una
     * reproducción pública determinista, así que el consejo responsable es mover
     * los fondos, no prometer un barrido que no existe.
     */
    private fun construirLegacyRng() {
        desc("Two famous weak random-number bugs hit old wallets. Neither can be scanned seed-by-seed like " +
             "Milk Sad, so this is guidance, not a brute-force — it never promises a sweep it can't do.")

        root.addView(filaResultado("Android 2013 SecureRandom bug",
            "Bitcoin apps built on Android before mid-2013 could get poor randomness. The part an attacker can " +
            "actually exploit is a REPEATED signature nonce — and the 'Nonce reuse' tool (Audit) already " +
            "detects exactly that on your own transactions. If that audit flags a key, move its coins now.", true))
        nota(AppTheme.TXT_SEC, "What to do: run 'Nonce reuse' (in Audit) on any wallet you created " +
            "in an Android app around 2013. A clean result there means this bug did not expose your keys.")

        root.addView(filaResultado("Randstorm (BitcoinJS, 2011–2015)",
            "Browser wallets made on sites that used an old BitcoinJS SecureRandom (e.g. some blockchain.info / " +
            "bitaddress-style pages) could have low-entropy keys. There is NO public, deterministic way to " +
            "reproduce those keys, so no honest scanner exists — anyone claiming to 'scan for Randstorm' is guessing.", true))
        nota(AppTheme.TXT_SEC, "What to do: if you ever generated a wallet in a web browser between 2011 and 2015, treat it " +
            "as at risk. Create a NEW wallet here with a freshly generated phrase and move ALL funds to it. Don't wait " +
            "for a scan that can't be made.")

        nota(AppTheme.TXT_MUTED, "Nothing here leaves the phone. For a full self-check of your seeds and wallets, use " +
            "'Security check' in More → it bundles these warnings with the on-chain nonce and address-reuse checks.")
    }

    // ── Mnemonic strength audit ────────────────────────────────────────────
    private fun construirMnemonicStrength() {
        desc("Audits a BIP39 phrase for low-entropy red flags: bad checksum, repeated words, " +
             "dictionary order, a tiny range of words, too few distinct words. A phrase that " +
             "trips these was likely hand-picked and is brute-forceable. The phrase stays on-device.")
        val et = entrada("12 / 15 / 18 / 21 / 24 words", varias = true)
        val salida = salidaBox()
        boton("Audit") {
            salida.removeAllViews()
            val words = Bip39.normalize(et.text.toString())
            if (words.size < 12) { salida.addView(filaResultado("Too short", "${words.size} words")); return@boton }
            val idx = words.map { Bip39Words.WORDS.indexOf(it) }
            if (idx.any { it < 0 }) { salida.addView(filaResultado("Not all BIP39 words", "some words are not in the list")); return@boton }
            val flags = ArrayList<String>()
            val cs = Bip39.validate(words.joinToString(" "))
            if (cs is Bip39.Result.Invalid) flags.add("checksum fails (${cs.reason})")
            val distinct = words.toSet().size
            if (distinct <= words.size / 2) flags.add("only $distinct distinct words of ${words.size}")
            if (words.toSet().size == 1) flags.add("all the same word")
            if (idx == idx.sorted()) flags.add("words in dictionary order")
            if (idx.zipWithNext().all { (a, b) -> b - a == 1 }) flags.add("consecutive dictionary indices")
            val span = (idx.maxOrNull()!! - idx.minOrNull()!!)
            if (span in 1..63) flags.add("all words within a tiny range ($span)")
            if (flags.isEmpty()) {
                salida.addView(filaResultado("✓ Looks strong", "checksum OK, no obvious low-entropy pattern", true))
            } else {
                salida.addView(filaResultado("⚠ Weak / guessable", flags.joinToString("\n• ", "• "), true))
            }
        }
        nota(AppTheme.TXT_MUTED, "This only catches obvious human patterns. Passing it does NOT prove the phrase " +
             "was generated with real entropy — only a trusted generator does.")
    }

    // ── Vanity address ─────────────────────────────────────────────────────
    // Corre en el motor nativo del escáner (curva por lotes + endomorfismo +
    // hash160 ×4), unas 100× más rápido que probar clave a clave por JNI.
    @Volatile private var vanCorriendo = false
    private var vanMode = 0   // 0=P2PKH(1), 1=P2SH(3), 2=bech32(bc1q)
    private fun construirVanity() {
        desc("Grind an address that contains your text right after the fixed start. " +
             "Longer prefixes take exponentially longer. Runs on the native engine.")
        // Selector de tipo de dirección.
        val tipos = arrayOf("P2PKH (1…)", "P2SH (3…)", "bech32 (bc1q…)")
        lateinit var btnTipo: Button
        val et = entrada("desired prefix, e.g. Love")
        fun hintDe() { et.hint = when (vanMode) {
            2 -> "prefix after bc1q (chars: qpzry9x8gf2tvdw0s3jn54khce6mua7l)"
            else -> "prefix after ${if (vanMode==1) "3" else "1"} (base58, no 0 O I l)" } }
        btnTipo = boton("Type: ${tipos[vanMode]}") {
            if (vanCorriendo) return@boton
            vanMode = (vanMode + 1) % 3
            btnTipo.text = "Type: ${tipos[vanMode]}"; hintDe()
        }
        hintDe()
        val tv = TextView(this).apply { text = ""; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context); setPadding(dp(2), dp(12), 0, 0) }
        val salida = salidaBox()
        lateinit var btn: Button
        val poll = Handler(Looper.getMainLooper())
        val t0 = longArrayOf(0L)
        lateinit var tick: Runnable
        tick = Runnable {
            if (!vanCorriendo) return@Runnable
            val res = try { HunterEngine.vanityResult() } catch (e: Throwable) { "" }
            if (res.contains("|")) {
                vanCorriendo = false; btn.text = "Generate"
                val priv = res.substringBefore("|"); val addr = res.substringAfter("|")
                val wif = try { HunterEngine.wifDeHex(priv).substringBefore("|") } catch (e: Throwable) { "" }
                salida.removeAllViews()
                salida.addView(filaResultado("Address", addr, true))
                salida.addView(filaResultado("Private key", priv))
                if (wif.isNotEmpty()) salida.addView(filaResultado("WIF", wif))
                salida.addView(filaResultado("Finds vault", guardarEnBaul(priv, "Vanity address")))
                val n = try { HunterEngine.vanityCount() } catch (e: Throwable) { 0L }
                tv.text = "Found after ${"%,d".format(n)} tries."
                return@Runnable
            }
            val n = try { HunterEngine.vanityCount() } catch (e: Throwable) { 0L }
            val secs = ((System.currentTimeMillis() - t0[0]) / 1000.0).coerceAtLeast(0.001)
            val rate = (n / secs).toLong()
            tv.text = "Tried ${"%,d".format(n)} · ~${"%,d".format(rate)}/s"
            poll.postDelayed(tick, 300)
        }
        btn = boton("Generate") {
            if (vanCorriendo) { vanCorriendo = false; try { HunterEngine.vanityStop() } catch (e: Throwable) {}; btn.text = "Generate"; return@boton }
            var pref = et.text.toString().trim()
            if (pref.isEmpty()) { Toast.makeText(this, "Enter a prefix", Toast.LENGTH_SHORT).show(); return@boton }
            if (vanMode == 2) {
                pref = pref.lowercase()
                if (pref.any { it !in "qpzry9x8gf2tvdw0s3jn54khce6mua7l" }) {
                    Toast.makeText(this, "bech32 chars: qpzry9x8gf2tvdw0s3jn54khce6mua7l", Toast.LENGTH_LONG).show(); return@boton }
            } else if (pref.any { it in "0OIl" }) {
                Toast.makeText(this, "Base58 excludes 0 O I l", Toast.LENGTH_LONG).show(); return@boton }
            salida.removeAllViews(); vanCorriendo = true; btn.text = "Stop"
            t0[0] = System.currentTimeMillis()
            val hilos = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            try { HunterEngine.vanityStart(pref, hilos, vanMode) } catch (e: Throwable) {
                vanCorriendo = false; btn.text = "Generate"
                Toast.makeText(this, "Engine error", Toast.LENGTH_SHORT).show(); return@boton
            }
            poll.postDelayed(tick, 300)
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

    // ── Child seeds (BIP85) ────────────────────────────────────────────────
    /*
     * De una semilla madre salen hijas independientes por su número. La madre puede ser una cartera de este
     * teléfono (pide el código; si tiene passphrase, cuenta, como en Coldcard) o una frase pegada. La clave
     * de la ruta la deriva el motor; Bip85 (probado con los vectores oficiales) hace el resto.
     */
    private fun construirBip85() {
        desc("Derive independent child seeds, keys or passwords from ONE parent seed, each by its number " +
             "(BIP85). Use them for a hot wallet, a gift or a test wallet without new backups: the parent plus " +
             "the number rebuilds any child. A leaked child never exposes the parent or its siblings — but " +
             "losing the parent loses every child. Same results as Coldcard, Passport, SeedSigner and others.")
        val carteras = listOf("" to WalletManager.mainName(this)) + WalletManager.listWallets(this)
        var deTelefono = true
        var carteraSel: Pair<String, String>? = null
        var btnCartera: Button? = null
        var etFrase: EditText? = null
        root.addView(Ui.segmented(this, listOf("A wallet on this phone" to null, "Paste a phrase" to null)) {
            deTelefono = it == 0
            btnCartera?.visibility = if (deTelefono) android.view.View.VISIBLE else android.view.View.GONE
            etFrase?.visibility = if (deTelefono) android.view.View.GONE else android.view.View.VISIBLE
        })
        btnCartera = boton("Choose the parent wallet ▾") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Parent wallet")
                .setItems(carteras.map { it.second }.toTypedArray()) { _, i ->
                    carteraSel = carteras[i]; btnCartera?.text = "Parent: ${carteras[i].second}"
                }.show()
        }
        etFrase = entrada("Parent phrase (12–24 words)", varias = true).apply { visibility = android.view.View.GONE }

        root.addView(rotulo("What to derive"))
        var tipo = 0; var palabras = 12
        var segPalabras: android.view.View? = null
        var etLargo: EditText? = null
        root.addView(Ui.segmented(this, listOf("Phrase" to null, "WIF key" to null, "Password" to null, "Hex" to null)) {
            tipo = it
            segPalabras?.visibility = if (tipo == 0) android.view.View.VISIBLE else android.view.View.GONE
            etLargo?.visibility = if (tipo >= 2) android.view.View.VISIBLE else android.view.View.GONE
        })
        segPalabras = Ui.segmented(this, listOf("12 words" to null, "18 words" to null, "24 words" to null)) {
            palabras = listOf(12, 18, 24)[it]
        }.also { root.addView(it) }
        etLargo = entrada("Length: password 20–86 characters, hex 16–64 bytes").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; visibility = android.view.View.GONE
        }
        root.addView(rotulo("Child number"))
        val etIdx = entrada("0, 1, 2…").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }

        lateinit var salida: LinearLayout
        boton("Derive child") {
            salida.removeAllViews()
            val indice = etIdx.text.toString().trim().ifEmpty { "0" }.toIntOrNull()
            if (indice == null || indice < 0) { salida.addView(filaResultado("Invalid number", "Use 0, 1, 2…")); return@boton }
            val largo = etLargo?.text.toString().trim().toIntOrNull() ?: if (tipo == 2) 21 else 32
            val t = tipo; val n = palabras
            val ruta = try {
                when (t) {
                    0 -> Bip85.rutaFrase(n, indice); 1 -> Bip85.rutaWif(indice)
                    2 -> Bip85.rutaClave(largo, indice); else -> Bip85.rutaHex(largo, indice)
                }
            } catch (e: IllegalArgumentException) {
                salida.addView(filaResultado("Invalid length", if (t == 2) "Passwords: 20–86 characters." else "Hex: 16–64 bytes.")); return@boton
            }
            fun derivar(madre: String) = Thread {
                val k = try { HunterEngine.deriveRuta(madre, ruta) } catch (e: Throwable) { "" }
                val res = if (k.length != 64) null else {
                    val ent = Bip85.entropia(Descriptor.bytes(k))
                    val v = when (t) {
                        0 -> "Child phrase #$indice ($n words)" to Bip85.frase(ent, n)
                        1 -> Bip85.wif(ent)?.let { "Child WIF #$indice (Bitcoin Core hdseed)" to it }
                        2 -> "Child password #$indice ($largo characters)" to Bip85.clave(ent, largo)
                        else -> "Child hex #$indice ($largo bytes)" to Bip85.hex(ent, largo)
                    }
                    java.util.Arrays.fill(ent, 0); v
                }
                runOnUiThread {
                    if (res == null) salida.addView(filaResultado("Could not derive", "This number gives no valid key; try the next one."))
                    else { salida.addView(filaResultado(res.first, res.second, true)); salida.addView(filaResultado("Path", ruta)) }
                }
            }.start()
            if (deTelefono) {
                val sel = carteraSel
                if (sel == null) { Toast.makeText(this, "Choose the parent wallet", Toast.LENGTH_SHORT).show(); return@boton }
                PinAuthHelper.show(this, autoBiometric = false) { ok ->
                    if (!ok) return@show
                    val mn = if (sel.first.isEmpty()) WalletManager.loadSeed(this) else WalletManager.loadWalletSeed(this, sel.first)
                    if (mn == null) salida.addView(filaResultado("No seed", "That wallet has no seed phrase on this phone."))
                    else derivar(mn)
                }
            } else {
                val m = etFrase?.text.toString().trim().lowercase().replace(Regex("\\s+"), " ")
                if (Bip39.validate(m) !is Bip39.Result.Valid) { salida.addView(filaResultado("Invalid phrase", "Enter a valid BIP39 parent phrase.")); return@boton }
                derivar(m)
            }
        }
        salida = salidaBox()
        nota(AppTheme.BLUE, "Write down which number you used for each child. With a phone wallet, its passphrase " +
             "(if any) is part of the parent, as in hardware wallets. Everything is computed on this phone.")
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
            salida.addView(filaResultado("Fee", "$fee sats (${"%.8f".format(fee / 1e8).trimEnd('0').trimEnd('.')} BTC)" + Fiat.entre(this@PubkeyToolsActivity, fee)))
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
        // Desde un aparato sin conexión (Keystone, SeedSigner, Coldcard Q…): QR animado UR o BBQr.
        boton("Scan animated QR") {
            alLeerQr = { txt -> et.setText(txt) }
            startActivityForResult(android.content.Intent(this, QrScanActivity::class.java)
                .putExtra(QrScanActivity.EXTRA_ANIMADO, true), REQ_QR_PSBT)
        }
        boton("Show as animated QR") {
            val d = leer()
            if (d == null) { Toast.makeText(this, "Not a valid PSBT", Toast.LENGTH_SHORT).show(); return@boton }
            QrAnimado.mostrar(this, Psbt.serialize(d), "PSBT")
        }
        root.addView(rotulo("Sign — private key (hex or WIF)"))
        val etKey = entrada("64-hex or WIF")
        boton("Sign") {
            salida.removeAllViews()
            val d = leer()
            if (d == null) { Toast.makeText(this, "Not a valid PSBT", Toast.LENGTH_SHORT).show(); return@boton }
            val hx = hexClave(etKey.text.toString())
            if (hx.isBlank()) { Toast.makeText(this, "Invalid key", Toast.LENGTH_SHORT).show(); return@boton }
            // Antes se firmaba sin enseñar nada. Ahora: revisión (entradas que no
            // cuadran = no se firma) y confirmación con destinos, importes y comisión.
            val rev = try { Psbt.revisar(d) } catch (e: Throwable) { null }
            if (rev == null) { salida.addView(filaResultado("Cannot sign", "The PSBT could not be read completely.")); return@boton }
            if (rev.problemas.isNotEmpty()) {
                salida.addView(filaResultado("Not signed — check this PSBT", rev.problemas.joinToString("\n")))
                return@boton
            }
            fun btcTxt(sat: Long) = "%.8f BTC".format(sat / 1e8) + Fiat.entre(this@PubkeyToolsActivity, sat)
            val texto = buildString {
                appendLine("It pays:")
                for ((dir, v) in rev.salidas) appendLine("• ${btcTxt(v)} → $dir")
                appendLine()
                if (rev.comision != null) appendLine("Fee: ${btcTxt(rev.comision)}" +
                    (if (rev.totalEntradas != null && rev.comision * 10 > rev.totalEntradas) "  ⚠ more than 10 % of the inputs" else ""))
                else appendLine("Fee: unknown (some input amounts are missing)")
                append("\nSign only if this is exactly what you expect.")
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Sign this PSBT?")
                .setMessage(texto)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign") { _, _ ->
                    val n = try { Psbt.sign(d, hx) } catch (e: Throwable) { -1 }
                    when {
                        n < 0 -> salida.addView(filaResultado("Cannot sign", "The PSBT is malformed."))
                        n == 0 -> salida.addView(filaResultado("Nothing signed", "no inputs match this key, or unsupported type"))
                        else -> {
                            val out = android.util.Base64.encodeToString(Psbt.serialize(d), android.util.Base64.NO_WRAP)
                            salida.addView(filaResultado("Signed $n input(s) · updated PSBT", out, true))
                        }
                    }
                }.show()
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
                val txt = s?.toString() ?: ""
                val bits = EntropiaFisica.bits(txt)
                tv.text = if (txt.any { it.isDigit() } && bits == 0.0)
                    "Use only dice (1-6) or only coins (0/1), not both"
                else "≈ ${bits.toInt()} bits of entropy" + (if (bits < 128) " — roll more (aim for 128+)" else " ✓")
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
            for ((x, y) in Shamir256.repartir(sec, k, n, java.security.SecureRandom())) {
                // Desde aquí cada parte lleva k delante (68 hex): así, al combinar,
                // se sabe cuántas hacen falta y se rechaza un juego incompleto.
                val hex = "%02x".format(k) + "%02x".format(x) + y.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                salS.addView(filaResultado("Share $x of $n", hex, true))
            }
        }
        root.addView(rotulo("Combine — paste k shares (one per line)"))
        val etC = entrada("share hex per line", varias = true)
        val salC = salidaBox()
        boton("Combine") {
            salC.removeAllViews()
            // 68 hex: partes nuevas, con k delante. 66: partes de antes, sin k.
            val leidas = etC.text.toString().split('\n').mapNotNull { Shamir256.leerParte(it) }
            if (leidas.size < 2) { Toast.makeText(this, "Paste at least 2 valid shares", Toast.LENGTH_SHORT).show(); return@boton }
            val ks = leidas.mapNotNull { it.k }.distinct()
            if (ks.size > 1) { Toast.makeText(this, "The shares come from different splits (different k)", Toast.LENGTH_LONG).show(); return@boton }
            val k = ks.firstOrNull()
            if (k != null && leidas.map { it.x }.toSet().size < k) {
                Toast.makeText(this, "Need at least $k different shares (k = $k)", Toast.LENGTH_LONG).show(); return@boton
            }
            val partes = leidas.map { it.x to it.y }
            // La misma parte pegada dos veces no cuenta doble; dos distintas con el mismo número sí es un error.
            val porX = partes.groupBy { it.first }
            val choque = porX.entries.firstOrNull { (_, v) -> v.map { it.second.toList() }.distinct().size > 1 }
            if (choque != null) {
                salC.addView(filaResultado("Cannot combine", "Two different shares both say they are share #${choque.key}."))
                return@boton
            }
            val unicas = porX.values.map { it.first() }
            // Con partes de sobra se comprueban entre sí: antes, una parte con un solo bit cambiado daba
            // una clave equivocada sin ningún aviso.
            val comprobacion = if (k != null) ShamirComprobacion.comprobar(unicas, k) else null
            if (comprobacion is ShamirComprobacion.R.Incoherente) {
                salC.addView(filaResultado("Cannot combine", "Some shares disagree with the others, and there aren't " +
                    "enough extra shares to tell which one is wrong. Check each share, or paste more of them."))
                return@boton
            }
            val clave: ByteArray? = when (comprobacion) {
                is ShamirComprobacion.R.Corregido -> comprobacion.secreto
                else -> when (val r = Shamir256.combinar(unicas)) {
                    is Shamir256.Resultado.Error -> { salC.addView(filaResultado("Cannot combine", r.motivo)); null }
                    is Shamir256.Resultado.Ok -> r.secreto
                }
            }
            if (clave != null) {
                salC.addView(filaResultado("Recombined key", clave.joinToString("") { "%02x".format(it.toInt() and 0xFF) }, true))
                salC.addView(filaResultado("Check", when (comprobacion) {
                    is ShamirComprobacion.R.Coherente -> "Verified: ${comprobacion.extra} extra share(s) agree with the rest."
                    is ShamirComprobacion.R.Corregido -> "Share(s) #${comprobacion.malas.joinToString(", #")} were wrong " +
                        "and were left out; the key was rebuilt from the others."
                    is ShamirComprobacion.R.DemasiadasPartes -> "Combined, but too many shares to cross-check them all."
                    ShamirComprobacion.R.SinRedundancia -> "Exactly $k shares (k = $k): correct only if none of them has " +
                        "a typo. Paste one more share to check them against each other."
                    else -> "Old shares (no k in them): only correct with at least k distinct shares, " +
                        "and fewer give a wrong key without any warning."
                }))
            }
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
                val enc = try { HunterEngine.bip38Cifrar(hx, nfc(pw), comp) } catch (e: Throwable) { "" }
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
                val r = try { HunterEngine.bip38Descifrar(key, nfc(pw)) } catch (e: Throwable) { "" }
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
                val ic = try { HunterEngine.bip38Intermediate(nfc(pw), -1, 0) } catch (e: Throwable) { "" }
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
    /** La calculadora de probabilidad aquí; el minero, en su pantalla (para pararlo al salir de ella). */
    private fun construirMinero() {
        desc("Bitcoin blocks are found by trial and error: whoever first finds a header whose hash is below the target " +
             "wins the block reward. Try it on this phone with a practice block, or join the real lottery through a solo pool.")
        boton("Open the miner") { startActivity(android.content.Intent(this, MineroActivity::class.java)) }
        desc("Odds calculator: your hash rate against today's network difficulty.")
        val et = entrada("Hash rate in MH/s (a phone: 1–10)")
        val salida = salidaBox()
        boton("Calculate") {
            val mh = et.text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: return@boton
            salida.removeAllViews()
            Thread {
                val dif = try { org.json.JSONObject(ChainApi.get("/v1/mining/hashrate/3d", false) ?: "").optDouble("currentDifficulty", 0.0) } catch (e: Exception) { 0.0 }
                runOnUiThread {
                    if (dif <= 0) { salida.addView(filaResultado("Network", "Could not get the difficulty")); return@runOnUiThread }
                    val hr = mh * 1e6
                    salida.addView(filaResultado("Network difficulty", "%.4g".format(java.util.Locale.US, dif)))
                    salida.addView(filaResultado("One block every", Minero.escala(Minero.segundosPorBloque(hr, dif))))
                    salida.addView(filaResultado("Chance in a day", "1 in %.2e".format(java.util.Locale.US, 1 / Minero.probabilidad(hr, dif, 86_400.0))))
                    salida.addView(filaResultado("Chance in a year", "1 in %.2e".format(java.util.Locale.US, 1 / Minero.probabilidad(hr, dif, 31_557_600.0))))
                }
            }.start()
        }
    }

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
    // Expansión de variaciones (estilo BTCRecover/brainflayer): a partir de una
    // frase base genera un conjunto ACOTADO de candidatos comunes.
    private fun variaciones(f: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(f); out.add(f.lowercase()); out.add(f.uppercase())
        val cap = f.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        out.add(cap)
        out.add(f.map { when (it.lowercaseChar()) { 'a'->'4';'e'->'3';'i'->'1';'o'->'0';'s'->'5';'t'->'7'; else->it } }.joinToString(""))
        val sufijos = listOf("0","1","2","3","4","5","6","7","8","9",
            "12","123","1234","2020","2021","2022","2023","2024","2025","69","666","777","111","!","!!","?",".","#","$")
        for (s in sufijos) { out.add(f + s); out.add(cap + s) }
        return out.toList()
    }
    @Volatile private var bwVar = false
    private fun construirBrainwallet() {
        desc("Tests phrases (private key = SHA-256 of the phrase) against target " +
             "address(es)/public key(s). Leave the targets empty to just LIST the address " +
             "each phrase maps to. Audits whether an address came from a guessable phrase.")
        root.addView(rotulo("Targets (one per line) — empty = list addresses"))
        val etTarget = entrada("1…/3…/bc1… or 02…/03…/04…, one per line", varias = true)
        root.addView(rotulo("Phrases to try (one per line)"))
        val etFrases = entrada("correct horse battery staple\npassword\n…", varias = true)
        lateinit var btnVar: Button
        btnVar = boton("Variations: off") {
            if (bwCorriendo) return@boton
            bwVar = !bwVar; btnVar.text = "Variations: ${if (bwVar) "on (~60×)" else "off"}"
        }
        val tvEstado = TextView(this).apply { text = ""; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context); setPadding(dp(2), dp(12), 0, 0) }
        val salida = salidaBox()
        lateinit var btn: Button
        btn = boton("Check") {
            if (bwCorriendo) { bwCorriendo = false; return@boton }
            val dianas = etTarget.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val listMode = dianas.isEmpty()
            // Conjunto de direcciones objetivo: lo derivado de cada diana + la propia línea.
            val objetivo = HashSet<String>()
            for (d in dianas) { objetivo.addAll(dirsDe(d)); objetivo.add(d) }
            val base = etFrases.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (base.isEmpty()) { Toast.makeText(this, "Paste some phrases", Toast.LENGTH_SHORT).show(); return@boton }
            val frases = if (bwVar) base.flatMap { variaciones(it) }.distinct() else base
            salida.removeAllViews(); bwCorriendo = true; btn.text = "Stop"
            Thread {
                var n = 0; var hits = 0; var mostrados = 0
                for (frase in frases) {
                    if (!bwCorriendo) break
                    n++
                    val priv = sha256Hex(frase)
                    if (listMode) {
                        if (mostrados < 300) {
                            val p2pkh = try { HunterEngine.direccionesDe(priv).split('\n')
                                .firstOrNull { it.startsWith("P2PKH (compressed)=") }?.substringAfter('=')?.trim() ?: "" } catch (e: Throwable) { "" }
                            mostrados++
                            runOnUiThread { salida.addView(filaResultado("\"$frase\"", p2pkh)) }
                        }
                    } else if (dirsDe(priv).any { it in objetivo }) {
                        hits++
                        // El WIF de la dirección que coincidió: los brainwallets antiguos
                        // solían ser SIN comprimir, y el WIF comprimido no controla esa
                        // dirección.
                        val linea = try { HunterEngine.direccionesDe(priv) } catch (e: Throwable) { "" }
                            .split('\n').firstOrNull { l -> l.substringAfter('=', "").trim() in objetivo } ?: ""
                        val wifs = try { HunterEngine.wifDeHex(priv).split('|') } catch (e: Throwable) { emptyList() }
                        val sinComp = linea.substringBefore('=').contains("uncompressed", ignoreCase = true)
                        val wif = (if (sinComp) wifs.getOrNull(1) else wifs.getOrNull(0)) ?: ""
                        runOnUiThread { salida.addView(filaResultado("MATCH · \"$frase\"",
                            "priv $priv" + (if (linea.isNotEmpty()) "\n${linea.replace("=", ": ")}" else "") +
                            (if (wif.isNotEmpty()) "\nWIF  $wif" else ""), true)) }
                    }
                    if (n % 25 == 0 || n == frases.size) { val h = n; val f = hits
                        runOnUiThread { tvEstado.text = if (listMode) "Listed $h / ${frases.size}" else "Checked $h / ${frases.size} · $f found" } }
                }
                runOnUiThread { bwCorriendo = false; btn.text = "Check"
                    if (!listMode && hits == 0) salida.addView(TextView(this).apply {
                        text = "No match for those phrases."; textSize = AppTheme.SP_CAPTION
                        setTextColor(AppTheme.TXT_MUTED); typeface = AppTheme.body(context); setPadding(dp(2), dp(8), 0, 0) })
                    if (listMode && frases.size > 300) salida.addView(TextView(this).apply {
                        text = "Showing first 300 of ${frases.size}."; textSize = AppTheme.SP_CAPTION
                        setTextColor(AppTheme.TXT_MUTED); typeface = AppTheme.body(context); setPadding(dp(2), dp(8), 0, 0) }) }
            }.apply { isDaemon = true; start() }
        }
        root.addView(tvEstado)
    }

    // ── Nonce-reuse audit ─────────────────────────────────────────────────
    @Volatile private var nonceCorriendo = false
    private class FirmaRS(val pub: String, val r: String, val s: String, val htype: Int)
    private class RegFirma(val tx: org.json.JSONObject, val vin: Int, val s: String, val htype: Int)
    private class SigReg(val r: String, val s: String, val tx: org.json.JSONObject, val vin: Int, val htype: Int)

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
        // Las firmas se normalizan a "s baja" (BIP62): en la mitad de los pares una de las dos s está
        // negada respecto a la otra y la cuenta sale con la clave equivocada. Se prueba tal cual y con
        // la segunda s cambiada por n - s, y vale la que dé la dirección de la clave pública.
        val n = java.math.BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
        val bSneg = try { n.subtract(java.math.BigInteger(bS, 16)).toString(16).padStart(64, '0') } catch (e: Exception) { null }
        val dirPub = dirsDe(pub)
        for (s2 in listOfNotNull(bS, bSneg)) {
            val d = try { HunterEngine.recuperarNonce(r, a.s, z1, s2, z2) } catch (e: Throwable) { "" }
            if (d.isBlank()) continue
            val dirD = dirsDe(d)
            if (dirPub.isNotEmpty() && dirD.any { it in dirPub }) return d
        }
        return null
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
                val porPub = HashMap<String, ArrayList<SigReg>>()   // para el ataque de retícula
                val reusos = ArrayList<Array<String?>>()
                val yaVisto = HashSet<String>()
                var firmas = 0; var txs = 0; var ultimo = ""; var paginas = 0; var red = true
                var hayMas = false; var latticeHecho = false
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
                            if (porPub.values.sumOf { it.size } < 60)
                                porPub.getOrPut(f.pub) { ArrayList() }.add(SigReg(f.r, f.s, tx, vi, f.htype))
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
                    hayMas = arr.length() >= 25
                    if (!hayMas) break@bucle
                }
                val fin = txs; val sigs = firmas
                val parado = !nonceCorriendo
                // Si no hubo clave recuperada por reutilización, probar el ataque de
                // retícula (nonces cortos/sesgados) sobre la pubkey con más firmas.
                var latPriv: String? = null; var latPub: String? = null
                val recuperadas = reusos.count { it[4] != null }
                if (red && recuperadas == 0 && nonceCorriendo) {
                    val best = porPub.maxByOrNull { it.value.size }
                    if (best != null && best.value.size >= 3) {
                        runOnUiThread { tvEstado.text = "No reuse — trying lattice on short nonces…" }
                        val target = normalizaPubComprimida(best.key)
                        val tri = ArrayList<Triple<java.math.BigInteger, java.math.BigInteger, java.math.BigInteger>>()
                        for (sr in best.value.take(10)) {   // pocas firmas bastan para nonces cortos; mantiene la retícula pequeña
                            if (!nonceCorriendo) break
                            val z = FirmaTx.z(sr.tx, sr.vin, sr.htype) ?: continue
                            try { tri.add(Triple(java.math.BigInteger(sr.r, 16), java.math.BigInteger(sr.s, 16), java.math.BigInteger(z, 16))) } catch (e: Throwable) {}
                        }
                        if (target != null && tri.size >= 3) {
                            latticeHecho = true
                            val pr = try { Lattice.recoverAuto(tri, { !nonceCorriendo }) { cand ->
                                val pv = cand.toString(16).padStart(64, '0')
                                (try { HunterEngine.pubDeHex(pv) } catch (e: Throwable) { "" }).equals(target, ignoreCase = true)
                            } } catch (e: Throwable) { null }
                            if (pr != null) { latPriv = pr.first.toString(16).padStart(64, '0'); latPub = best.key }
                        }
                    }
                }
                runOnUiThread {
                    nonceCorriendo = false; btn.text = "Scan signatures"
                    if (latPriv != null) {
                        val wif = try { HunterEngine.wifDeHex(latPriv!!).substringBefore("|") } catch (e: Throwable) { "" }
                        tvEstado.text = "⚠ Weak nonces (lattice) · $fin tx · $sigs sig · key recovered"
                        salida.addView(filaResultado("RECOVERED PRIVATE KEY (lattice)",
                            "priv $latPriv" + (if (wif.isNotEmpty()) "\nWIF  $wif" else "") + "\npubkey ${latPub?.take(16)}…", true))
                        salida.addView(filaResultado("Finds vault", guardarEnBaul(latPriv!!, "Lattice nonce recovery")))
                        salida.addView(TextView(this).apply {
                            text = "The signatures used short/biased nonces (HNP + LLL). This address is compromised — move any funds."
                            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.WARN)
                            typeface = AppTheme.body(context); setLineSpacing(0f, 1.35f); setPadding(dp(2), dp(10), 0, 0) })
                        return@runOnUiThread
                    }
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
                                    salida.addView(filaResultado("Finds vault", guardarEnBaul(priv, "Reused nonce recovery")))
                                } else salida.addView(filaResultado("VULNERABLE · reused r (recovery n/a)",
                                        "pubkey ${pub.take(16)}…\nr ${r.take(20)}…\ntx1 ${t1.take(16)}…\ntx2 ${t2.take(16)}…", true))
                            }
                            salida.addView(TextView(this).apply {
                                text = "Same r ⇒ same nonce ⇒ key recoverable. This address is compromised — move any funds."
                                textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.WARN)
                                typeface = AppTheme.body(context); setLineSpacing(0f, 1.35f); setPadding(dp(2), dp(10), 0, 0) })
                        }
                        // "Clean" sólo si de verdad se miró todo. Antes salía también al
                        // pulsar Stop, al llegar al tope de 150 tx o sin haber podido
                        // probar la retícula: un nonce reutilizado en la página 8 quedaba
                        // como "limpio".
                        parado -> tvEstado.text = "Incomplete (stopped) · $fin tx · $sigs signatures checked · no reuse in them"
                        hayMas -> tvEstado.text = "Incomplete: only the latest $fin tx were checked (limit 150) · no reuse in them"
                        !latticeHecho -> tvEstado.text = "No reuse · $fin tx · $sigs signatures · weak-nonce (lattice) check " +
                                                         "not run: it needs at least 3 signatures from one key"
                        else -> tvEstado.text = "Clean · $fin tx · $sigs signatures · no reuse, no weak nonces"
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        root.addView(tvEstado)
    }

    override fun onDestroy() {
        try { HunterEngine.vanityStop() } catch (e: Throwable) {}
        try { HunterEngine.bsgsStop() } catch (e: Throwable) {}
        bsgsCorriendo = false
        bwCorriendo = false; nonceCorriendo = false; vanCorriendo = false; latCorriendo = false
        balCorriendo = false; sweepCorriendo = false; watchCorriendo = false; bcCorriendo = false; bip38Corriendo = false
        super.onDestroy()
    }
}
