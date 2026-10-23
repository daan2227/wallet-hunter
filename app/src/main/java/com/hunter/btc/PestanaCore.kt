package com.hunter.btc

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hunter.btc.InstantaneaUtxo.Col
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

/*
 * La pestaña Core: un Bitcoin Core (bitcoind) funcionando en el propio teléfono, podado, con la opción de
 * arrancarlo desde un snapshot de UTXO (assumeutxo) en vez de bajar y comprobar toda la historia primero.
 *
 * Todo lo que habla con el nodo va en hilos aparte; la pantalla sólo pinta. La lógica sin Android está en
 * CoreConfig (con pruebas en la JVM); el proceso, en CoreNodo y CoreService.
 */
internal fun MainActivity.buildCoreTab(): ScrollView {
    val act = this
    val h = Handler(Looper.getMainLooper())
    val scroll = ScrollView(this).apply {
        background = AppTheme.fondoApp()
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }
    val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(32)) }
    scroll.addView(page)
    page.addView(Ui.pageTitle(this, "Core", lados = true))

    fun texto(padre: LinearLayout, s: String, color: Int = AppTheme.TXT_SEC, size: Float = AppTheme.SP_BODY,
              mono: Boolean = false) = TextView(act).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = if (mono) AppTheme.mono(act) else AppTheme.body(act)
        setLineSpacing(0f, 1.25f)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        padre.addView(this)
    }
    /**
     * Una fila con un título y, si hay [ayuda], un icono «i» (en cursiva, dentro de un círculo) que despliega o
     * recoge el texto largo debajo. Así las explicaciones no llenan la pantalla: se leen cuando se piden.
     */
    fun filaConInfo(padre: LinearLayout, titulo: String, ayuda: String?, grande: Boolean,
                    colorAyuda: Int = AppTheme.TXT_SEC): TextView {
        val fila = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (!grande) topMargin = dp(6) }
        }
        val tv = TextView(act).apply {
            text = titulo
            if (grande) { textSize = AppTheme.SP_TITLE; typeface = AppTheme.bold(act) }
            else { textSize = AppTheme.SP_BODY; typeface = AppTheme.body(act) }
            setTextColor(AppTheme.TXT_PRI)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fila.addView(tv)
        padre.addView(fila)
        if (ayuda != null) {
            val cuerpo = TextView(act).apply {
                text = ayuda; textSize = AppTheme.SP_CAPTION; setTextColor(colorAyuda)
                typeface = AppTheme.body(act); setLineSpacing(0f, 1.25f); visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            fun circulo(relleno: Int, borde: Int) = android.graphics.drawable.InsetDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(relleno); setStroke(dp(2), borde)
                }, dp(5))
            val icono = TextView(act).apply {
                text = "i"; gravity = android.view.Gravity.CENTER; textSize = 14f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD_ITALIC)
                setTextColor(AppTheme.TXT_SEC); background = circulo(android.graphics.Color.TRANSPARENT, AppTheme.TXT_SEC)
                contentDescription = "More information"
                isClickable = true; isFocusable = true; foreground = Ui.toque()
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            }
            icono.setOnClickListener {
                val ver = cuerpo.visibility != View.VISIBLE
                cuerpo.visibility = if (ver) View.VISIBLE else View.GONE
                if (ver) { cuerpo.alpha = 0f; cuerpo.animate().alpha(1f).setDuration(160).start() }
                icono.setTextColor(if (ver) AppTheme.ACCENT else AppTheme.TXT_SEC)
                icono.background = circulo(if (ver) (AppTheme.ACCENT and 0x00FFFFFF) or (0x33 shl 24) else android.graphics.Color.TRANSPARENT,
                    if (ver) AppTheme.ACCENT else AppTheme.TXT_SEC)
            }
            fila.addView(icono)
            padre.addView(cuerpo)
        }
        return tv
    }
    fun etiqueta(padre: LinearLayout, s: String, ayuda: String? = null) = filaConInfo(padre, s, ayuda, true)
    /** Un rótulo de ajuste con su explicación plegada detrás del icono «i». */
    fun rotulo(padre: LinearLayout, s: String, ayuda: String?, colorAyuda: Int = AppTheme.TXT_SEC) =
        filaConInfo(padre, s, ayuda, false, colorAyuda)
    fun barra(padre: LinearLayout) = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progress = 0; visibility = View.GONE
        progressTintList = ColorStateList.valueOf(AppTheme.ACCENT)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(10) }
        padre.addView(this)
    }
    fun boton(padre: LinearLayout, s: String, alto: Int = 46) = Ui.ghost(act, s, height = alto).apply {
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10); padre.addView(this)
    }

    // ── Estado ────────────────────────────────────────────────────────────
    val cEstado = Ui.card(this)
    page.addView(cEstado)
    etiqueta(cEstado, "Bitcoin Core on this phone", "The node checks the Bitcoin chain itself, without asking anyone. The wallet can use it to " +
        "send, to estimate fees and to check balances (see “Wallet” below); the rest still comes from the usual " +
        "servers. It downloads a lot of data: Wi-Fi and a charger are recommended.")
    // La luz de estado (parpadea según lo que hace el nodo) y el texto del estado, en una fila.
    val filaEstado = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
    }
    cEstado.addView(filaEstado)
    val luz = View(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(AppTheme.TXT_MUTED) }
        layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) }
    }
    filaEstado.addView(luz)
    val tvEstado = texto(filaEstado, "…", AppTheme.ACCENT, AppTheme.SP_TITLE).apply {
        (layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; topMargin = 0 }
    }
    val barraSync = barra(cEstado)
    val vAltura  = Ui.row(this, cEstado, "Blocks / headers", "–")
    val vPares   = Ui.row(this, cEstado, "Peers", "–")
    val vDisco   = Ui.row(this, cEstado, "Chain on disk", "–")
    val vPoda    = Ui.row(this, cEstado, "Pruned up to", "–")
    val vLibre   = Ui.row(this, cEstado, "Free space", "–")
    val tvAtras  = texto(cEstado, "", AppTheme.WARN, AppTheme.SP_CAPTION)
    val btnNodo  = Ui.primary(this, "Start node", height = 52).apply {
        (layoutParams as LinearLayout.LayoutParams).apply { leftMargin = 0; rightMargin = 0 }
    }
    cEstado.addView(btnNodo)
    btnNodo.foreground = Ui.toque()

    // El aspecto del botón y de la luz según lo que hace el nodo:
    //   0 parado (verde, «Start node»)        1 arrancando (ámbar, pulsa)       2 trabajando: poniéndose al día o
    //   cargando (rojo «Stop node», luz ámbar rápida)   3 al día (rojo «Stop node», luz verde lenta)
    //   4 parando (gris, pulsa)                5 este APK no lleva Core (atenuado)
    var modoNodo = -1
    var animLuz: android.animation.ObjectAnimator? = null
    var animBoton: android.animation.ObjectAnimator? = null
    fun aplicarModo(m: Int) {
        if (m == modoNodo) return
        modoNodo = m
        animLuz?.cancel(); animBoton?.cancel(); btnNodo.alpha = 1f; luz.alpha = 1f
        val blanco = android.graphics.Color.WHITE
        val a = when (m) {
            1 -> AspectoNodo("Starting…", AppTheme.WARN, AppTheme.ON_ACCENT, AppTheme.WARN, 500L, true)
            2 -> AspectoNodo("Stop node", AppTheme.RED, blanco, AppTheme.WARN, 700L, false)
            3 -> AspectoNodo("Stop node", AppTheme.RED, blanco, AppTheme.ACCENT, 1600L, false)
            4 -> AspectoNodo("Stopping…", AppTheme.BG_KEY, AppTheme.TXT_SEC, AppTheme.TXT_MUTED, 500L, true)
            else -> AspectoNodo("Start node", AppTheme.ACCENT, AppTheme.ON_ACCENT, AppTheme.TXT_MUTED, 0L, false)
        }
        btnNodo.text = a.rotulo; btnNodo.setTextColor(a.tinta)
        btnNodo.background = Ui.cardBg(AppTheme.R_KEY, a.fondo, act)
        (luz.background as android.graphics.drawable.GradientDrawable).setColor(a.luz)
        if (a.periodoLuz > 0) animLuz = android.animation.ObjectAnimator.ofFloat(luz, View.ALPHA, 1f, .2f).apply {
            duration = a.periodoLuz; repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE; start() }
        if (a.pulsaBoton) animBoton = android.animation.ObjectAnimator.ofFloat(btnNodo, View.ALPHA, 1f, .55f).apply {
            duration = 600; repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE; start() }
        else if (m == 5) btnNodo.alpha = .4f
        // Un pequeño «pop» al cambiar de estado.
        btnNodo.scaleX = .96f; btnNodo.scaleY = .96f
        btnNodo.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
    }
    // Las animaciones infinitas no siguen cuando la pantalla se desmonta; al volver se vuelve a pintar el estado.
    btnNodo.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { modoNodo = -1 }
        override fun onViewDetachedFromWindow(v: View) { animLuz?.cancel(); animBoton?.cancel() }
    })
    aplicarModo(0)

    // ── Red ───────────────────────────────────────────────────────────────
    val cRed = Ui.card(this)
    page.addView(cRed)
    etiqueta(cRed, "Bitcoin network")
    val rBloque = Ui.row(this, cRed, "Latest block", "–")
    val rUltimo = Ui.row(this, cRed, "Its transactions", "–")
    val rDif    = Ui.row(this, cRed, "Difficulty", "–")
    val rHash   = Ui.row(this, cRed, "Hashrate", "–")
    val rHalv   = Ui.row(this, cRed, "Next halving", "–")
    val rMem    = Ui.row(this, cRed, "Mempool", "–")
    val tvUtxo  = texto(cRed, "", AppTheme.TXT_PRI)
    val btnUtxo = boton(cRed, "Count all the coins (takes a few minutes)")
    texto(cRed, "Read from your own node. The mempool is empty in “Blocks only” mode.", AppTheme.TXT_MUTED, AppTheme.SP_CAPTION)

    // ── Herramientas ──────────────────────────────────────────────────────
    val cHer = Ui.card(this)
    page.addView(cHer)
    etiqueta(cHer, "Node tools", "Check an address, turn a descriptor into addresses, verify a signed message, and read, " +
        "combine or finalize PSBTs. Nothing here needs a server or a private key.")
    boton(cHer, "Open the tools").setOnClickListener { HerramientasNodo.mostrar(act) }

    // ── Crear un snapshot (.dat) ──────────────────────────────────────────
    val cDat = Ui.card(this)
    page.addView(cDat)
    etiqueta(cDat, "Create a snapshot file (.dat)", "Makes the same kind of file that is shared on the internet (for example mainnet-965000-utxos.dat): " +
        "the whole set of unspent coins of your node, with the block it is based on, the number of coins and the " +
        "hash of the set. You can keep it, share it, or load it in another Bitcoin Core with loadtxoutset (Core only " +
        "accepts it if its block is in Core's built-in list).")
    rotulo(cDat, "Which state", "“Roll back” rebuilds the set at an older block, which needs the blocks since then. A pruned node " +
        "keeps only the latest ones, so it will usually refuse: Core says why. The file is about 10 GB and takes " +
        "several minutes; it cannot be cancelled once started (stopping the node aborts it).")
    var modoDat = 0
    cDat.addView(Ui.segmented(this, listOf("Now (latest)" to null, "Roll back to a height" to null), initial = 0) { modoDat = it })
    val campoAlturaDat = Ui.input(this, "Height, only for “Roll back” (e.g. 965000)", mono = true).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
    }
    cDat.addView(campoAlturaDat)
    val barraDat = barra(cDat)
    val tvDat = texto(cDat, "", AppTheme.TXT_PRI)
    val btnDat = boton(cDat, "Create the snapshot file")

    // ── Instantánea de UTXO en CSV ────────────────────────────────────────
    val cCsv = Ui.card(this)
    page.addView(cCsv)
    etiqueta(cCsv, "UTXO snapshot (CSV)", "Writes the unspent coins of the whole chain to a CSV file, with the columns you choose: the address, " +
        "txid and output number, funds, wallet type, public key, key hash, locking script, block height, date, " +
        "coinbase flag and confirmations. The public key is only known where the chain shows it before the coin is " +
        "spent (P2PK and Taproot). The date is worked out from the block height, so it can be off by a few hours. " +
        "It reads the snapshot file you loaded, or asks the node for a fresh copy.")
    rotulo(cCsv, "Minimum amount per coin (BTC)", "There are about 190 million coins. With “All” and all columns the file is around 25 GB and takes hours; " +
        "a higher minimum, fewer columns or a filter make it far smaller. It is written as " +
        "it goes and needs the free space.")
    var pintarFiltro: () -> Unit = {}     // se asigna más abajo, cuando existe el filtro
    val minimosCsv = listOf(0L, 10_000L, 100_000L, 1_000_000L, 10_000_000L, 100_000_000L)
    var minimoCsv = prefsMinimoCsv()
    cCsv.addView(Ui.segmented(this, listOf("All" to null, "0.0001" to null, "0.001" to null, "0.01" to null, "0.1" to null, "1" to null),
        initial = minimoCsv) { minimoCsv = it
            getSharedPreferences("core", android.content.Context.MODE_PRIVATE).edit().putInt("csv_minimo", it).apply()
            pintarFiltro() })
    texto(cCsv, "What goes in the file", AppTheme.TXT_PRI)
    val prefsCore = act.getSharedPreferences("core", android.content.Context.MODE_PRIVATE)
    var columnasCsv: Set<InstantaneaUtxo.Col> = InstantaneaUtxo.columnasDeTexto(prefsCore.getString("csv_cols", null))
    var filtroCsv = FiltroUtxo.deJson(prefsCore.getString("csv_filtro", null))
    val tvCols = texto(cCsv, "", AppTheme.TXT_SEC, AppTheme.SP_CAPTION, mono = true)
    val btnCols = boton(cCsv, "Choose the columns…")
    val tvFiltro = texto(cCsv, "", AppTheme.TXT_SEC, AppTheme.SP_CAPTION)
    val btnFiltro = boton(cCsv, "Filters…")
    // El importe mínimo del selector de arriba se suma a este filtro.
    fun pintarCols() {
        tvCols.text = InstantaneaUtxo.cabecera(columnasCsv)
        prefsCore.edit().putString("csv_cols", InstantaneaUtxo.columnasATexto(columnasCsv))
            .putString("csv_filtro", filtroCsv.aJson()).apply()
        pintarFiltro()
    }
    btnFiltro.setOnClickListener {
        FiltroUtxoDialogo.mostrar(act, filtroCsv) { filtroCsv = it; pintarCols() }
    }
    val formatosClave = ExportadorUtxo.FormatoClave.values()
    var formatoClaveCsv = formatosClave.getOrElse(prefsCore.getInt("csv_formato_clave", 0)) { formatosClave[0] }
    rotulo(cCsv, "Public key format", "Taproot keys are stored as 32 bytes (x-only, 64 hex characters, no prefix). " +
        "By BIP340 their Y is even, so they equal 02 + x. “Compressed” gives 02/03 + x (66 characters), “Uncompressed” " +
        "gives 04 + x + y (130 characters) and “x-only” keeps just x. Converting also checks that the key is a point of " +
        "the curve: an invalid one comes out empty, so with the filter “with a public key” only valid keys remain. " +
        "It adds some calculation time.")
    cCsv.addView(Ui.segmented(this, listOf("As stored" to null, "Compressed" to null, "Uncompressed" to null, "x-only" to null),
        initial = formatoClaveCsv.ordinal) {
        formatoClaveCsv = formatosClave[it]; prefsCore.edit().putInt("csv_formato_clave", it).apply() })
    pintarFiltro = { tvFiltro.text = "Filters: " + filtroCsv.copy(minSat = minimosCsv[minimoCsv]).resumen() }
    pintarCols()
    btnCols.setOnClickListener {
        android.app.AlertDialog.Builder(act).setTitle("What goes in the CSV")
            .setItems(arrayOf("The six usual columns", "Wallet and UTXO only",
                              "Public keys only (skips coins without one)", "Choose the columns one by one…")) { _, i ->
                when (i) {
                    0 -> { columnasCsv = InstantaneaUtxo.PREDETERMINADAS; filtroCsv = filtroCsv.copy(clave = 0); pintarCols() }
                    1 -> { columnasCsv = linkedSetOf(Col.WALLET, Col.UTXO); filtroCsv = filtroCsv.copy(clave = 0); pintarCols() }
                    2 -> { columnasCsv = linkedSetOf(Col.CLAVE); filtroCsv = filtroCsv.copy(clave = 1); pintarCols() }
                    else -> {
                        val todas = Col.values()
                        val marcadas = BooleanArray(todas.size) { todas[it] in columnasCsv }
                        android.app.AlertDialog.Builder(act).setTitle("Columns")
                            .setMultiChoiceItems(todas.map { it.texto }.toTypedArray(), marcadas) { _, k, on -> marcadas[k] = on }
                            .setPositiveButton("OK") { _, _ ->
                                val elegidas = todas.filterIndexed { k, _ -> marcadas[k] }
                                if (elegidas.isEmpty()) Toast.makeText(act, "Choose at least one column", Toast.LENGTH_SHORT).show()
                                else { columnasCsv = elegidas.toCollection(LinkedHashSet()); pintarCols() }
                            }.setNegativeButton("Cancel", null).show()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }
    val barraCsv = barra(cCsv)
    val tvCsv = texto(cCsv, "", AppTheme.TXT_PRI)
    val btnCsvSnap = boton(cCsv, "Build it from the snapshot file")
    val btnCsvDump = boton(cCsv, "Ask the node for a fresh copy and build it")
    val btnCsvCancel = boton(cCsv, "Cancel", 42).apply { visibility = View.GONE }

    // ── Dataset del Scanner desde el nodo ─────────────────────────────────
    val cDs = Ui.card(this)
    page.addView(cDs)
    etiqueta(cDs, "Scanner dataset from your node", "Turns the set of unspent coins into the address index the Scanner uses (a .bin of about 1.5 GB), " +
        "always up to date and built on this phone: nothing is downloaded from anyone. It reads the snapshot file " +
        "you loaded, or asks the node for a fresh copy. It takes a while and needs about 4 GB free for the work " +
        "(14 GB if the node makes a fresh copy).")
    val barraDs = barra(cDs)
    val tvDs = texto(cDs, "", AppTheme.TXT_PRI)
    val btnDsSnap = boton(cDs, "Build it from the snapshot file")
    val btnDsDump = boton(cDs, "Ask the node for a fresh copy and build it")
    val btnDsCancel = boton(cDs, "Cancel", 42).apply { visibility = View.GONE }

    // ── Ajustes y poda ────────────────────────────────────────────────────
    val cAj = Ui.card(this)
    page.addView(cAj)
    etiqueta(cAj, "Pruning", "A pruned node keeps only the latest blocks (the chain state is always complete). " +
        "Smaller means less storage and less history to serve; it cannot be turned back into a full node " +
        "without downloading everything again. Changes apply the next time the node starts.")
    var aj = CoreNodo.ajustes(this)
    val nota = texto(cAj, "", AppTheme.TXT_MUTED, AppTheme.SP_CAPTION)
    fun guardar(n: CoreConfig.Ajustes) {
        aj = n.normalizado(); CoreNodo.guardarAjustes(act, aj)
        nota.text = if (CoreNodo.procesoVivo() || CoreService.activo) "Saved. Restart the node to apply." else "Saved."
    }
    texto(cAj, "Space for recent blocks", AppTheme.TXT_PRI)
    cAj.addView(Ui.segmented(this, listOf("0.5 GB" to null, "2 GB" to null, "5 GB" to null, "10 GB" to null),
        initial = CoreConfig.PRUNE_OPCIONES.indexOf(aj.pruneMiB).coerceAtLeast(0)) { guardar(aj.copy(pruneMiB = CoreConfig.PRUNE_OPCIONES[it])) })
    texto(cAj, "Memory for the database", AppTheme.TXT_PRI)
    cAj.addView(Ui.segmented(this, listOf("100 MB" to null, "450 MB" to null, "1 GB" to null),
        initial = CoreConfig.DBCACHE_OPCIONES.indexOf(aj.dbcacheMiB).coerceAtLeast(0)) { guardar(aj.copy(dbcacheMiB = CoreConfig.DBCACHE_OPCIONES[it])) })
    texto(cAj, "Network", AppTheme.TXT_PRI)
    cAj.addView(Ui.segmented(this, listOf("Wi-Fi only" to null, "Any network" to null),
        initial = if (aj.soloWifi) 0 else 1) { guardar(aj.copy(soloWifi = it == 0)) })
    texto(cAj, "Traffic", AppTheme.TXT_PRI)
    cAj.addView(Ui.segmented(this, listOf("Blocks only" to null, "Blocks + mempool" to null),
        initial = if (aj.soloBloques) 0 else 1) { guardar(aj.copy(soloBloques = it == 0)) })
    rotulo(cAj, "Connections", "“Through Tor” sends every connection of the node through a Tor app on this phone (Orbot: " +
        "127.0.0.1:9050), which hides your IP from the Bitcoin network. “Tor only” also talks only to .onion " +
        "nodes: more private, fewer peers, slower. Start the Tor app first, or the node will find no peers.")
    cAj.addView(Ui.segmented(this, listOf("Direct" to null, "Through Tor" to null, "Tor only" to null),
        initial = aj.red) { guardar(aj.copy(red = it)) })
    val campoProxy = Ui.input(this, "Tor proxy host:port", mono = true).apply {
        setText(aj.proxy)
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                val t = e?.toString()?.trim() ?: ""
                if (CoreConfig.proxyValido(t) && t != aj.proxy) guardar(aj.copy(proxy = t))
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }
    cAj.addView(campoProxy)
    rotulo(cAj, "Private broadcast (experimental)", "Needs Tor. Your own transactions leave through short-lived Tor connections instead of your node's " +
        "usual peers. It is marked experimental by Bitcoin Core: if Tor is not running the transaction may never " +
        "leave the phone, so after sending, check that it shows up in an explorer.", AppTheme.WARN)
    cAj.addView(Ui.segmented(this, listOf("Off" to null, "On" to null),
        initial = if (aj.difusionPrivada) 1 else 0) { guardar(aj.copy(difusionPrivada = it == 1)) })
    rotulo(cAj, "Filters for this app's light client", "Builds a block-filter index (about 10 GB more) and serves it only to 127.0.0.1, so “Verify with " +
        "the network” in the wallet can use your own node instead of public ones. The index is built as the node " +
        "validates, so it can take as long as the history check.")
    cAj.addView(Ui.segmented(this, listOf("Off" to null, "Serve on this phone" to null),
        initial = if (aj.servirFiltros) 1 else 0) { guardar(aj.copy(servirFiltros = it == 1)) })
    rotulo(cAj, "Wallet", "With “Use my node to send”, the wallet broadcasts transactions and reads the block height from " +
        "this node when it is running and synced, with the usual servers as a fallback. Fee estimates also come " +
        "from the node, but only if the traffic setting above is “Blocks + mempool”. The node can scan your " +
        "balance from the wallet menu (Advanced options → Verify with my node).")
    cAj.addView(Ui.segmented(this, listOf("Servers only" to null, "Use my node to send" to null),
        initial = if (CoreBackend.activado(this)) 1 else 0) { CoreBackend.activar(act, it == 1) })
    val btnPodar = boton(cAj, "Prune now (keep the latest ${CoreConfig.BLOQUES_QUE_QUEDAN} blocks)")

    // ── Snapshot ──────────────────────────────────────────────────────────
    val cSn = Ui.card(this)
    page.addView(cSn)
    etiqueta(cSn, "UTXO snapshot", "With a snapshot the node starts from a recent set of coins and checks the old history in the " +
        "background, so it is usable in hours instead of days. Core only accepts snapshots whose hash is built " +
        "into its code (blocks 840,000, 880,000, 910,000, 935,000 and 965,000 of mainnet), so it does not matter where the " +
        "file comes from. A snapshot taken at any other block is rejected. " +
        "Create one with `dumptxoutset` on a node you trust, or download one. The node must be running and " +
        "must already have the headers up to the snapshot's block. Loading cannot be resumed: if the node " +
        "stops halfway it starts again from zero.")
    val tvSnap = texto(cSn, "", AppTheme.TXT_PRI)
    val barraSnap = barra(cSn)
    val tvProg = texto(cSn, "", AppTheme.TXT_MUTED, AppTheme.SP_CAPTION)
    val btnElegir = boton(cSn, "Pick a snapshot file…")
    val btnUrl = boton(cSn, "Download from a link…")
    val btnCargar = boton(cSn, "Load snapshot into the node")
    val btnBorrar = boton(cSn, "Delete the snapshot file", 42)
    val btnCancelar = boton(cSn, "Cancel", 42).apply { visibility = View.GONE }

    // ── Registro ──────────────────────────────────────────────────────────
    val cLog = Ui.card(this)
    page.addView(cLog)
    etiqueta(cLog, "Node log")
    val tvLog = texto(cLog, "", AppTheme.TXT_SEC, AppTheme.SP_MICRO, mono = true)

    // ── Estado interno ────────────────────────────────────────────────────
    var tarea: CoreSnapshot.Cancelar? = null
    var ocupado = false          // copia, descarga o carga en curso
    var ultimo: CoreConfig.Estado? = null
    var deteniendo = false          // el usuario pidió parar y el nodo aún cierra (puede tardar hasta un minuto)
    var arranquePedido = 0L         // cuándo se pulsó Start: evita que el botón parpadee a «Start» antes de que el servicio arranque
    val refrescando = AtomicBoolean(false)

    fun enMarcha() = CoreService.activo || CoreNodo.procesoVivo()

    fun fijarOcupado(si: Boolean, texto: String = "") {
        ocupado = si
        runOnUiThread {
            btnElegir.isEnabled = !si; btnUrl.isEnabled = !si; btnCargar.isEnabled = !si; btnBorrar.isEnabled = !si
            btnElegir.alpha = if (si) .4f else 1f; btnUrl.alpha = btnElegir.alpha
            btnCargar.alpha = btnElegir.alpha; btnBorrar.alpha = btnElegir.alpha
            barraSnap.visibility = if (si) View.VISIBLE else View.GONE
            btnCancelar.visibility = if (si && tarea != null) View.VISIBLE else View.GONE
            tvProg.text = texto
        }
    }

    fun progreso(hecho: Long, total: Long) {
        runOnUiThread {
            barraSnap.isIndeterminate = total <= 0
            if (total > 0) barraSnap.progress = (hecho * 1000 / total).toInt().coerceIn(0, 1000)
            tvProg.text = if (total > 0) "${CoreConfig.bytes(hecho)} of ${CoreConfig.bytes(total)}" else CoreConfig.bytes(hecho)
        }
    }

    /** La carga del snapshot en curso, según el registro: barra, porcentaje, monedas y tiempo. */
    fun pintarCarga(carga: CoreConfig.CargaSnapshot?) {
        val cargando = carga != null && carga.enCurso
        if (cargando) {
            barraSnap.visibility = View.VISIBLE; barraSnap.isIndeterminate = false
            barraSnap.progress = (carga!!.porcentaje * 10).toInt().coerceIn(0, 1000)
            val eta = carga.etaS?.let { " · about ${CoreConfig.duracion(it)} left" } ?: " · estimating…"
            tvProg.text = "%.1f %% · %,d of %,d coins%s\nRunning for %s".format(carga.porcentaje, carga.cargados, carga.total,
                eta, if (carga.transcurridoS >= 0) CoreConfig.duracion(carga.transcurridoS) else "?")
            btnCargar.isEnabled = false; btnCargar.alpha = .4f
            if (ultimo == null) {
                tvEstado.setTextColor(AppTheme.TXT_SEC)
                tvEstado.text = "Loading the snapshot… ${"%.1f".format(carga.porcentaje)} %"
            }
        } else if (ocupado) {
            // La llamada de carga sigue esperando pero el registro ya no se mueve: no dejar la barra congelada
            // con el último valor como si fuera el actual.
            barraSnap.visibility = View.VISIBLE; barraSnap.isIndeterminate = true
            tvProg.text = "Waiting for the node… (the log shows no progress right now)"
        } else {
            btnCargar.isEnabled = true; btnCargar.alpha = 1f
            barraSnap.visibility = View.GONE
        }
    }

    /** Sólo lee el registro (no hace RPC, que se queda sin respuesta durante la carga): se puede repetir a menudo. */
    fun refrescarCarga() {
        Thread {
            val c = CoreNodo.cargaSnapshot(act)
            runOnUiThread { pintarCarga(c) }
        }.start()
    }

    fun pintar(est: CoreConfig.Estado?, bin: Boolean, arrancando: Boolean, carga: CoreConfig.CargaSnapshot?) {
        val corre = enMarcha()
        val libre = CoreNodo.libre(act)
        vLibre.text = CoreConfig.bytes(libre)
        if (!bin) {
            tvEstado.text = "Not included in this build"
            tvEstado.setTextColor(AppTheme.WARN)
            aplicarModo(5); btnNodo.alpha = .4f
            tvAtras.text = "This APK was built before the Core binary was published. Build the " +
                "\"Compilar Bitcoin Core\" workflow once and then build the app again."
            return
        }
        aplicarModo(when {
            deteniendo -> 4
            est != null -> if (est.descargaInicial || est.bloques < est.cabeceras || est.validandoAtras) 2 else 3
            carga != null && carga.enCurso -> 2
            arrancando || corre || System.currentTimeMillis() - arranquePedido < 20_000L -> 1
            else -> 0
        })
        tvAtras.text = ""
        if (est != null) {
            ultimo = est
            val atras = est.descargaInicial || est.bloques < est.cabeceras
            tvEstado.text = when {
                est.validandoAtras -> "Running from the snapshot"
                atras -> "Syncing… ${"%.1f".format(est.progreso * 100)} %"
                else -> "Synced"
            }
            tvEstado.setTextColor(if (atras || est.validandoAtras) AppTheme.WARN else AppTheme.ACCENT)
            barraSync.visibility = if (atras) View.VISIBLE else View.GONE
            barraSync.progress = (est.progreso * 1000).toInt()
            vAltura.text = "%,d / %,d".format(est.bloques, est.cabeceras)
            vPares.text = "${est.pares}"
            vDisco.text = CoreConfig.bytes(est.bytesEnDisco)
            vPoda.text = if (est.podado) "block %,d".format(est.alturaPoda) else "not pruned"
            if (est.validandoAtras)
                tvAtras.text = "Checking the history in the background: ${"%.1f".format(est.progresoAtras * 100)} %. " +
                    "The wallet data is usable already; this only re-verifies the past."
        } else {
            barraSync.visibility = View.GONE
            vAltura.text = "–"; vPares.text = "–"
            tvEstado.setTextColor(AppTheme.TXT_SEC)
            tvEstado.text = when {
                carga != null && carga.enCurso -> "Loading the snapshot… ${"%.1f".format(carga.porcentaje)} %"
                arrancando -> "Starting… (loading the block index)"
                corre -> "Starting…"
                else -> {
                    val p = act.getSharedPreferences("core", android.content.Context.MODE_PRIVATE)
                    val code = p.getInt("ultima_salida", 0)
                    if (code != 0 && p.getLong("ultima_salida_ts", 0L) > 0L) "Stopped (last exit code $code)" else "Stopped"
                }
            }
        }
        val f = CoreNodo.snapshot(act)
        pintarCarga(carga)
        tvSnap.text = if (f.isFile) {
            val c = CoreSnapshot.cabecera(f)
            val h = c?.let { CoreConfig.alturaAceptada(it.base) }
            "File ready: ${CoreConfig.bytes(f.length())}" +
                (if (c != null) "\nBase block ${c.base.take(16)}…\n${"%,d".format(c.monedas)} coins" else "") +
                (if (c == null) ""
                 else if (h != null) "\nAccepted by this Core (block %,d).".format(h)
                 else "\nNOT accepted by this Core: it only loads snapshots at blocks " +
                      CoreConfig.SNAPSHOTS_ACEPTADOS.values.joinToString(", ") { "%,d".format(it) } + ".")
        } else if (snapshotParcial(act)) "A partial download is waiting: press “Download from a link” to continue."
        else "No snapshot file yet."
        val aceptado = f.isFile && CoreSnapshot.cabecera(f)?.let { CoreConfig.alturaAceptada(it.base) } != null
        btnCargar.visibility = if (aceptado) View.VISIBLE else View.GONE
        btnBorrar.visibility = if (f.isFile || snapshotParcial(act)) View.VISIBLE else View.GONE
        tvLog.text = CoreNodo.ultimasLineas(act, 10).ifEmpty { "(empty)" }
    }

    fun refrescar() {
        if (!refrescando.compareAndSet(false, true)) return
        Thread {
            try {
                val bin = CoreNodo.binario(act) != null
                var est: CoreConfig.Estado? = null; var arrancando = false
                val carga = if (bin) CoreNodo.cargaSnapshot(act) else null
                if (bin) try {
                    val r = CoreNodo.rpc(act)
                    val ch = r.objeto("getblockchaininfo")
                    val red = try { r.objeto("getnetworkinfo") } catch (e: Exception) { null }
                    val cs = try { r.objeto("getchainstates") } catch (e: Exception) { null }
                    est = CoreConfig.leerEstado(ch, red, cs)
                } catch (e: CoreRpcError) { arrancando = e.arrancando }
                  catch (e: java.io.IOException) { /* parado */ }
                runOnUiThread { pintar(est, bin, arrancando, carga) }
            } finally { refrescando.set(false) }
        }.start()
    }

    var redMs = 0L
    /** El panel de la red: unas pocas consultas RPC, como mucho cada 30 s. */
    fun refrescarRed() {
        val ahora = System.currentTimeMillis()
        if (ultimo == null || ahora - redMs < 30_000) return
        redMs = ahora
        Thread {
            try {
                val r = CoreNodo.rpc(act)
                val ch = r.objeto("getblockchaininfo", lecturaMs = 8_000)
                val alt = ch.optLong("blocks", 0L)
                val hps = (r.llamar("getnetworkhashps", JSONArray(), 8_000) as? Number)?.toDouble() ?: 0.0
                val mem = try { CoreConfig.leerMempool(r.objeto("getmempoolinfo", lecturaMs = 8_000)) } catch (e: Exception) { null }
                val bs = try { r.objeto("getblockstats", JSONArray().put(alt).put(JSONArray().put("txs").put("avgfeerate")), 8_000) }
                         catch (e: Exception) { null }
                val hv = CoreConfig.proximoHalving(alt)
                val edad = System.currentTimeMillis() / 1000 - ch.optLong("time", 0L)
                runOnUiThread {
                    rBloque.text = "%,d · %s".format(alt, CoreConfig.hace(edad))
                    rUltimo.text = if (bs != null) "%,d · avg fee %d sat/vB".format(bs.optLong("txs"), bs.optLong("avgfeerate")) else "–"
                    rDif.text = CoreConfig.dificultad(ch.optDouble("difficulty", 0.0))
                    rHash.text = CoreConfig.hashrate(hps)
                    rHalv.text = "block %,d · in %,d blocks (~%,d days)".format(hv.altura, hv.faltan, hv.dias)
                    rMem.text = if (mem != null) "%,d tx · %s · min %.1f sat/vB".format(mem.tx, CoreConfig.bytes(mem.bytes), mem.minimaSatVb) else "–"
                }
            } catch (e: Exception) { redMs = 0L }
        }.start()
    }

    btnUtxo.setOnClickListener {
        if (ultimo == null) { Toast.makeText(act, "Start the node and let it sync first.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
        btnUtxo.isEnabled = false; btnUtxo.alpha = .4f; tvUtxo.text = "Counting the whole set of coins…"
        Thread {
            val txt = try {
                val o = CoreNodo.rpc(act).llamar("gettxoutsetinfo", JSONArray().put("none"), 30 * 60_000) as? org.json.JSONObject
                val u = o?.let { CoreConfig.leerUtxoStats(it) }
                if (u != null) "%,d coins · %,.2f BTC in existence\nAt block %,d".format(u.monedas, u.btc, u.altura) else "No answer."
            } catch (e: CoreRpcError) { "Core: ${e.message}" } catch (e: Exception) { "Failed: ${e.javaClass.simpleName}" }
            runOnUiThread { tvUtxo.text = txt; btnUtxo.isEnabled = true; btnUtxo.alpha = 1f }
        }.start()
    }

    fun aviso(s: String) = runOnUiThread { Toast.makeText(act, s, Toast.LENGTH_LONG).show() }
    /** Un mensaje largo (el de Core) en un diálogo, completo y seleccionable, no cortado en un aviso. */
    fun detalle(titulo: String, s: String) = runOnUiThread {
        android.app.AlertDialog.Builder(act).setTitle(titulo).setMessage(s)
            .setPositiveButton("OK", null).show()
            .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    // ── Acciones ──────────────────────────────────────────────────────────
    // ── Dataset del Scanner ───────────────────────────────────────────────
    var cancelDs: ConversorUtxo.Cancelar? = null
    var utxoOcupado = false     // una conversión del conjunto de UTXO (dataset o CSV) en marcha: no se lanza otra
    fun ocupadoDs(si: Boolean, txt: String = "") = runOnUiThread {
        utxoOcupado = si
        btnDsSnap.isEnabled = !si; btnDsDump.isEnabled = !si
        btnDsSnap.alpha = if (si) .4f else 1f; btnDsDump.alpha = btnDsSnap.alpha
        barraDs.visibility = if (si) View.VISIBLE else View.GONE
        btnDsCancel.visibility = if (si) View.VISIBLE else View.GONE
        tvDs.text = txt
    }
    fun progresoDs(fase: Int, parte: Long, total: Long) = runOnUiThread {
        barraDs.isIndeterminate = total <= 0
        if (total > 0) barraDs.progress = (parte * 1000 / total).toInt().coerceIn(0, 1000)
        tvDs.text = if (fase == 1) "Reading the coins… %,d of %,d".format(parte, total) else "Sorting… ${parte * 100 / total.coerceAtLeast(1)} %"
    }
    fun usarEnScanner(f: java.io.File) = runOnUiThread {
        android.app.AlertDialog.Builder(act).setTitle("Dataset ready")
            .setMessage("${f.name} · ${CoreConfig.bytes(f.length())}\nUse it in the Scanner now? It replaces the current dataset.")
            .setPositiveButton("Use it") { _, _ ->
                if (HunterEngine.isRunning() || HunterEngine.isLoading()) {
                    Toast.makeText(act, "Stop the scan before loading another dataset", Toast.LENGTH_LONG).show()
                } else {
                    csvPath = f.absolutePath
                    prefs.edit().putString("csvPath", csvPath).apply()
                    HunterEngine.loadCsv(csvPath)
                    Toast.makeText(act, "Loading ${f.name} in the Scanner", Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("Later", null).show()
    }
    fun convertirDs(origen: java.io.File, borrar: Boolean) {
        if (utxoOcupado) { aviso("Another UTXO job is running."); return }
        val cab = CoreSnapshot.cabecera(origen)
        if (cab == null) { aviso("That is not a UTXO snapshot."); return }
        val dir = act.getExternalFilesDir(null) ?: act.filesDir
        if (dir.usableSpace < 4L shl 30) { aviso("Free at least 4 GB first (free: ${CoreConfig.bytes(dir.usableSpace)})."); return }
        val nombre = "utxo-" + (CoreConfig.alturaAceptada(cab.base)?.toString()
            ?: java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())) + ".bin"
        val c = ConversorUtxo.Cancelar(); cancelDs = c
        ocupadoDs(true, "Starting…")
        Thread {
            var fin = ""
            var listo: java.io.File? = null
            try {
                val destino = java.io.File(dir, nombre)
                val r = ConversorUtxo.convertir(origen, destino, java.io.File(CoreNodo.datadir(act), "utxo_tmp"), c) { f, p, t -> progresoDs(f, p, t) }
                if (r == null) fin = "Cancelled."
                else {
                    listo = destino
                    fin = "%,d addresses from %,d coins\n1…: %,d · bc1q: %,d · 3…: %,d · bc1p/wsh: %,d".format(r.entradas, r.monedas,
                        r.porTipo[0], r.porTipo[1], r.porTipo[2], r.porTipo[3])
                    if (borrar) origen.delete()
                }
            } catch (e: ConversorUtxo.FormatoInvalido) { fin = "Could not read the file: ${e.message}" }
              catch (e: java.io.IOException) { fin = "Disk error: ${e.message}" }
              catch (e: Exception) { fin = "Failed: ${e.javaClass.simpleName}" }
            cancelDs = null
            ocupadoDs(false, fin)
            listo?.let { usarEnScanner(it) }
            runOnUiThread { refrescar() }
        }.start()
    }
    btnDsCancel.setOnClickListener { cancelDs?.cancelado = true }
    btnDsSnap.setOnClickListener {
        val f = CoreNodo.snapshot(act)
        if (!f.isFile) { aviso("There is no snapshot file. Pick one in the snapshot card, or ask the node for a fresh copy."); return@setOnClickListener }
        convertirDs(f, false)
    }
    btnDsDump.setOnClickListener {
        if (utxoOcupado) { aviso("Another UTXO job is running."); return@setOnClickListener }
        if (ultimo == null) { aviso("Start the node and let it answer first."); return@setOnClickListener }
        val dir = CoreNodo.datadir(act)
        if (dir.usableSpace < 14L shl 30) { aviso("A fresh copy needs about 14 GB free (free: ${CoreConfig.bytes(dir.usableSpace)})."); return@setOnClickListener }
        ocupadoDs(true, "The node is writing a copy of its coins… this takes several minutes")
        barraDs.isIndeterminate = true
        Thread {
            val ruta = java.io.File(dir, "utxo_dump.dat")
            val err = try {
                ruta.delete()
                CoreNodo.rpc(act).llamar("dumptxoutset", JSONArray().put("utxo_dump.dat").put("latest"), 120 * 60_000)
                null
            } catch (e: CoreRpcError) { "Core: ${e.message}" } catch (e: Exception) { "Failed: ${e.javaClass.simpleName}" }
            if (err != null || !ruta.isFile) { ocupadoDs(false, err ?: "The node did not write the file."); return@Thread }
            ocupadoDs(false)
            runOnUiThread { convertirDs(ruta, true) }
        }.start()
    }

    // ── Crear un snapshot (.dat) ──────────────────────────────────────────
    fun ocupadoDat(si: Boolean, txt: String = "") = runOnUiThread {
        utxoOcupado = si
        btnDat.isEnabled = !si; btnDat.alpha = if (si) .4f else 1f
        barraDat.visibility = if (si) View.VISIBLE else View.GONE
        tvDat.text = txt
    }
    fun terminadoDat(f: java.io.File, ficha: String) = runOnUiThread {
        if (act.isFinishing) return@runOnUiThread
        android.app.AlertDialog.Builder(act).setTitle("Snapshot ready")
            .setMessage("${f.name} · ${CoreConfig.bytes(f.length())}\n\n$ficha\nIt is in the app's private storage.")
            .setPositiveButton("Share") { _, _ ->
                val uri = androidx.core.content.FileProvider.getUriForFile(act, "${act.packageName}.provider", f)
                val envio = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"; putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, f.name); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                act.startActivity(Intent.createChooser(envio, "Share the snapshot"))
            }
            .setNeutralButton("Delete it") { _, _ ->
                f.delete(); java.io.File(f.path + ".txt").delete(); Toast.makeText(act, "Deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null).show()
    }
    btnDat.setOnClickListener {
        if (utxoOcupado) { aviso("Another UTXO job is running."); return@setOnClickListener }
        if (ultimo == null) { aviso("Start the node and let it answer first."); return@setOnClickListener }
        val altura = campoAlturaDat.text.toString().trim().toLongOrNull()
        if (modoDat == 1 && altura == null) { aviso("Type the height to roll back to."); return@setOnClickListener }
        val dir = BackupStore.compartidos(act)
        val previsto = CoreNodo.snapshot(act).takeIf { it.isFile }?.length() ?: (11L shl 30)
        if (dir.usableSpace < previsto + (1L shl 30)) {
            aviso("The file needs about ${CoreConfig.bytes(previsto + (1L shl 30))} free (free: ${CoreConfig.bytes(dir.usableSpace)}).")
            return@setOnClickListener
        }
        val temporal = java.io.File(dir, "utxo_nuevo.dat")
        val enCurso = java.io.File(temporal.path + ".incomplete")
        ocupadoDat(true, "The node is writing the file…")
        barraDat.isIndeterminate = true
        val t0 = System.currentTimeMillis()
        val vivo = java.util.concurrent.atomic.AtomicBoolean(true)
        // Core no da avance: se sigue el tamaño del fichero temporal que va escribiendo.
        Thread {
            while (vivo.get()) {
                try { Thread.sleep(2_000) } catch (e: InterruptedException) { break }
                val escrito = if (enCurso.isFile) enCurso.length() else 0L
                val corrido = (System.currentTimeMillis() - t0) / 1000
                runOnUiThread {
                    if (!vivo.get()) return@runOnUiThread
                    if (escrito > 0) {
                        barraDat.isIndeterminate = false
                        barraDat.progress = (escrito * 1000 / previsto.coerceAtLeast(1)).toInt().coerceIn(0, 995)
                        val eta = if (escrito < previsto && corrido > 5) " · about ${CoreConfig.duracion(corrido * (previsto - escrito) / escrito)} left" else ""
                        tvDat.text = "Writing… ${CoreConfig.bytes(escrito)} of about ${CoreConfig.bytes(previsto)}$eta\nRunning for ${CoreConfig.duracion(corrido)}"
                    } else tvDat.text = "The node is preparing the set… ${CoreConfig.duracion(corrido)}"
                }
            }
        }.also { it.isDaemon = true }.start()
        Thread {
            var fin = ""; var listo: java.io.File? = null; var ficha = ""
            try {
                temporal.delete(); enCurso.delete()
                val params = JSONArray().put(temporal.absolutePath)
                if (modoDat == 1) params.put("rollback").put(org.json.JSONObject().put("rollback", altura)) else params.put("latest")
                val r = CoreNodo.rpc(act).llamar("dumptxoutset", params, 4 * 3600_000) as? org.json.JSONObject
                val v = r?.let { CoreConfig.leerVolcado(it) }
                if (v == null) fin = "The node did not describe the file."
                else {
                    val destino = java.io.File(dir, CoreConfig.nombreSnapshot(v.baseAltura))
                    destino.delete()
                    if (!temporal.renameTo(destino)) fin = "Could not rename the file."
                    else {
                        val version = try { CoreNodo.rpc(act).objeto("getnetworkinfo").optString("subversion", "") } catch (e: Exception) { "" }
                        ficha = CoreConfig.fichaSnapshot(v, version, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", java.util.Locale.US)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
                        java.io.File(destino.path + ".txt").writeText(ficha)
                        listo = destino
                        fin = "%s · %,d coins at block %,d".format(destino.name, v.monedas, v.baseAltura)
                    }
                }
            } catch (e: CoreRpcError) { fin = "Core: ${e.message}" }
              catch (e: Exception) { fin = "Failed: ${e.javaClass.simpleName}" }
            vivo.set(false)
            if (listo == null) { temporal.delete(); enCurso.delete() }
            ocupadoDat(false, fin)
            listo?.let { terminadoDat(it, ficha) }
            if (listo == null) runOnUiThread { android.app.AlertDialog.Builder(act).setTitle("Snapshot not created").setMessage(fin).setPositiveButton("OK", null).show() }
        }.start()
    }

    // ── Instantánea de UTXO en CSV ────────────────────────────────────────
    var cancelCsv: ConversorUtxo.Cancelar? = null
    fun ocupadoCsv(si: Boolean, txt: String = "") = runOnUiThread {
        utxoOcupado = si
        btnCsvSnap.isEnabled = !si; btnCsvDump.isEnabled = !si
        btnCsvSnap.alpha = if (si) .4f else 1f; btnCsvDump.alpha = btnCsvSnap.alpha
        btnDsSnap.isEnabled = !si; btnDsDump.isEnabled = !si
        barraCsv.visibility = if (si) View.VISIBLE else View.GONE
        btnCsvCancel.visibility = if (si) View.VISIBLE else View.GONE
        tvCsv.text = txt
    }
    fun progresoCsv(fase: Int, hechas: Long, total: Long, filas: Long, t0: Long) = runOnUiThread {
        barraCsv.visibility = View.VISIBLE
        barraCsv.isIndeterminate = total <= 0
        if (total > 0) barraCsv.progress = (hechas * 1000 / total).toInt().coerceIn(0, 1000)
        tvCsv.text = if (fase == 1) "Reading the block dates… %,d of %,d".format(hechas, total) else {
            val corrido = (System.currentTimeMillis() - t0) / 1000
            val eta = if (hechas > 0) CoreConfig.duracion(corrido * (total - hechas) / hechas) + " left" else "estimating…"
            "Writing the CSV… %.1f %% · %,d rows · about %s\nRunning for %s".format(
                hechas * 100.0 / total.coerceAtLeast(1), filas, eta, CoreConfig.duracion(corrido))
        }
    }
    fun terminadoCsv(f: java.io.File, resumen: String) = runOnUiThread {
        if (act.isFinishing) return@runOnUiThread
        android.app.AlertDialog.Builder(act).setTitle("CSV ready")
            .setMessage("$resumen\n\nIt is saved in the app's private storage. Share it to put it where you want, " +
                "and delete it afterwards: it can be very large.")
            .setPositiveButton("Share") { _, _ ->
                val uri = androidx.core.content.FileProvider.getUriForFile(act, "${act.packageName}.provider", f)
                val envio = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "UTXO snapshot"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                act.startActivity(Intent.createChooser(envio, "Share the UTXO snapshot"))
            }
            .setNeutralButton("Delete it") { _, _ -> f.delete(); Toast.makeText(act, "Deleted", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("Close", null).show()
    }
    fun convertirCsv(origen: java.io.File, borrar: Boolean) {
        if (utxoOcupado) { aviso("Another UTXO job is running."); return }
        val cab = CoreSnapshot.cabecera(origen)
        if (cab == null) { aviso("That is not a UTXO snapshot."); return }
        if (ultimo == null) { aviso("Start the node first: it provides the dates of the blocks."); return }
        val dir = BackupStore.compartidos(act)
        if (dir.usableSpace < 3L shl 30) { aviso("Free at least 3 GB first (free: ${CoreConfig.bytes(dir.usableSpace)})."); return }
        val minimo = minimosCsv[minimoCsv]
        val filtro = filtroCsv.copy(minSat = minimo)
        val c = ConversorUtxo.Cancelar(); cancelCsv = c
        ocupadoCsv(true, "Reading the block dates…")
        Thread {
            var fin = ""; var listo: java.io.File? = null
            try {
                val r = CoreNodo.rpc(act)
                val altura = (r.llamar("getblockheader", JSONArray().put(cab.base), 20_000) as org.json.JSONObject).getLong("height")
                val t = ExportadorUtxo.anclas(r, altura, c) { a, b -> progresoCsv(1, a, b, 0, 0) }
                if (t == null) fin = "Cancelled."
                else {
                    dir.listFiles()?.filter { it.name.startsWith("utxo_chain_") }?.forEach { it.delete() }
                    val destino = java.io.File(dir, "utxo_chain_${altura}_min${minimo}sat.csv")
                    val t0 = System.currentTimeMillis()
                    val res = ExportadorUtxo.exportar(origen, destino, filtro, t, c, columnasCsv, formatoClaveCsv) { a, b, f -> progresoCsv(2, a, b, f, t0) }
                    if (res == null) fin = "Cancelled."
                    else {
                        listo = destino
                        fin = "%,d rows from %,d coins · %s".format(res.filas, res.monedas, CoreConfig.bytes(res.bytes))
                        if (borrar) origen.delete()
                    }
                }
            } catch (e: CoreRpcError) { fin = "Core: ${e.message}" }
              catch (e: ConversorUtxo.FormatoInvalido) { fin = "Could not read the file: ${e.message}" }
              catch (e: java.io.IOException) { fin = "Disk error: ${e.message}" }
              catch (e: Exception) { fin = "Failed: ${e.javaClass.simpleName}" }
            cancelCsv = null
            ocupadoCsv(false, fin)
            listo?.let { terminadoCsv(it, fin) }
        }.start()
    }
    btnCsvCancel.setOnClickListener { cancelCsv?.cancelado = true }
    btnCsvSnap.setOnClickListener {
        val f = CoreNodo.snapshot(act)
        if (!f.isFile) { aviso("There is no snapshot file. Pick one in the snapshot card, or ask the node for a fresh copy."); return@setOnClickListener }
        convertirCsv(f, false)
    }
    btnCsvDump.setOnClickListener {
        if (utxoOcupado) { aviso("Another UTXO job is running."); return@setOnClickListener }
        if (ultimo == null) { aviso("Start the node and let it answer first."); return@setOnClickListener }
        val dir = CoreNodo.datadir(act)
        if (dir.usableSpace < 14L shl 30) { aviso("A fresh copy needs about 14 GB free (free: ${CoreConfig.bytes(dir.usableSpace)})."); return@setOnClickListener }
        ocupadoCsv(true, "The node is writing a copy of its coins… this takes several minutes")
        barraCsv.isIndeterminate = true
        Thread {
            val ruta = java.io.File(dir, "utxo_dump.dat")
            val err = try {
                ruta.delete()
                CoreNodo.rpc(act).llamar("dumptxoutset", JSONArray().put("utxo_dump.dat").put("latest"), 120 * 60_000)
                null
            } catch (e: CoreRpcError) { "Core: ${e.message}" } catch (e: Exception) { "Failed: ${e.javaClass.simpleName}" }
            if (err != null || !ruta.isFile) { ocupadoCsv(false, err ?: "The node did not write the file."); return@Thread }
            ocupadoCsv(false)
            runOnUiThread { convertirCsv(ruta, true) }
        }.start()
    }

    btnNodo.setOnClickListener {
        if (CoreNodo.binario(act) == null) { aviso("This build does not include Bitcoin Core."); return@setOnClickListener }
        if (enMarcha() || ultimo != null) {
            deteniendo = true; aplicarModo(4); tvEstado.text = "Stopping…"; tvEstado.setTextColor(AppTheme.TXT_SEC)
            Thread {
                CoreNodo.parar(act); CoreService.parar(act)
                deteniendo = false; ultimo = null
                runOnUiThread { refrescar() }
            }.start()
        } else {
            arranquePedido = System.currentTimeMillis()
            aplicarModo(1); tvEstado.text = "Starting…"; tvEstado.setTextColor(AppTheme.WARN)
            CoreService.arrancar(act)
            h.postDelayed({ refrescar() }, 1500)
        }
    }

    btnPodar.setOnClickListener {
        val est = ultimo
        val altura = if (est == null) -1 else CoreConfig.alturaPoda(est.bloques.toInt())
        if (altura < 0) { aviso("Start the node and let it sync first."); return@setOnClickListener }
        android.app.AlertDialog.Builder(act).setTitle("Prune now?")
            .setMessage(("Deletes block files below height %,d to free space. The chain state is untouched. " +
                "The deleted blocks can only come back by downloading them again.").format(altura))
            .setPositiveButton("Prune") { _, _ ->
                Thread {
                    try {
                        val r = CoreNodo.rpc(act).llamar("pruneblockchain", JSONArray().put(altura), 120_000)
                        aviso("Pruned up to block $r")
                    } catch (e: CoreRpcError) { aviso("Core: ${e.message}") }
                      catch (e: Exception) { aviso("Could not prune: ${e.javaClass.simpleName}") }
                    runOnUiThread { refrescar() }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    fun correr(texto: String, trabajo: (CoreSnapshot.Cancelar) -> String?) {
        if (ocupado) return
        val c = CoreSnapshot.Cancelar(); tarea = c
        barraSnap.progress = 0; fijarOcupado(true, texto)
        Thread {
            val err = try { trabajo(c) } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message}" }
            tarea = null; fijarOcupado(false)
            aviso(err ?: "Snapshot file ready.")
            runOnUiThread { refrescar() }
        }.start()
    }

    alElegirSnapshot = { uri ->
        correr("Copying…") { c -> CoreSnapshot.copiar(act, uri, CoreNodo.snapshot(act), c) { a, b -> progreso(a, b) } }
    }
    btnElegir.setOnClickListener {
        if (ocupado) return@setOnClickListener
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        try { startActivityForResult(i, REQ_CORE_SNAPSHOT) } catch (e: Exception) { aviso("No file picker available.") }
    }

    btnUrl.setOnClickListener {
        if (ocupado) return@setOnClickListener
        val campo = Ui.input(act, "https://…/utxo-880000.dat", mono = true)
        android.app.AlertDialog.Builder(act).setTitle("Download a snapshot")
            .setMessage("Only https links. Core checks the snapshot's hash against its own, so a wrong or " +
                "tampered file is rejected. It is a large file (several GB).")
            .setView(campo)
            .setPositiveButton("Download") { _, _ ->
                val u = campo.text.toString().trim()
                if (!CoreConfig.urlValida(u)) { aviso("That is not a valid https link."); return@setPositiveButton }
                correr("Downloading…") { c -> CoreSnapshot.descargar(u, CoreNodo.snapshot(act), c) { a, b -> progreso(a, b) } }
            }.setNegativeButton("Cancel", null).show()
    }

    btnCancelar.setOnClickListener { tarea?.cancelado = true }

    btnBorrar.setOnClickListener {
        if (ocupado) return@setOnClickListener
        CoreNodo.snapshot(act).delete(); java.io.File(CoreNodo.snapshot(act).path + ".part").delete()
        refrescar()
    }

    btnCargar.setOnClickListener {
        if (ocupado) return@setOnClickListener
        if (!enMarcha() || ultimo == null) { aviso("Start the node first and let it download the headers."); return@setOnClickListener }
        val f = CoreNodo.snapshot(act)
        android.app.AlertDialog.Builder(act).setTitle("Load the snapshot?")
            .setMessage("This can take several minutes and cannot be undone except by deleting the node's data. " +
                "If the snapshot's block is not in the node's headers yet, Core will refuse; wait and try again.")
            .setPositiveButton("Load") { _, _ ->
                if (ocupado) return@setPositiveButton
                tarea = null; barraSnap.isIndeterminate = true; fijarOcupado(true, "Loading into Core… this takes a while")
                Thread {
                    val msg = try {
                        val r = CoreNodo.rpc(act).llamar("loadtxoutset", JSONArray().put(f.absolutePath), 3 * 3600_000)
                        val o = r as? org.json.JSONObject
                        "Loaded ${o?.optLong("coins_loaded", 0L) ?: 0} coins at block ${o?.optLong("base_height", 0L) ?: 0}"
                    } catch (e: CoreRpcError) { "Core refused: ${e.message}" }
                      catch (e: Exception) { "Failed: ${e.javaClass.simpleName}" }
                    fijarOcupado(false)
                    if (msg.startsWith("Loaded")) aviso(msg) else detalle("Snapshot not loaded", msg)
                    runOnUiThread { refrescar() }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    // Sondeo mientras la pestaña está a la vista y la pantalla montada.
    val sondeo = object : Runnable {
        override fun run() {
            if (!scroll.isAttachedToWindow) return
            if (scroll.visibility == View.VISIBLE) { refrescar(); refrescarCarga(); refrescarRed() }
            h.postDelayed(this, 3_000)
        }
    }
    scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { h.removeCallbacks(sondeo); h.post(sondeo) }
        override fun onViewDetachedFromWindow(v: View) { h.removeCallbacks(sondeo) }
    })
    return scroll
}

private class AspectoNodo(val rotulo: String, val fondo: Int, val tinta: Int, val luz: Int,
                          val periodoLuz: Long, val pulsaBoton: Boolean)

private fun MainActivity.prefsMinimoCsv(): Int =
    getSharedPreferences("core", android.content.Context.MODE_PRIVATE).getInt("csv_minimo", 2).coerceIn(0, 5)

private fun snapshotParcial(ctx: android.content.Context): Boolean =
    java.io.File(CoreNodo.snapshot(ctx).path + ".part").isFile
