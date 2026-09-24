package com.hunter.btc

import android.app.*
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import java.io.File
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import java.io.*
import com.hunter.btc.recovery.RecoveryEngine
import com.hunter.btc.recovery.RecoveryParser
import com.hunter.btc.recovery.ParseResult
import com.hunter.btc.MainActivity.PuzzleInfo

/*
 * La pestana Recovery: recuperar una seed a la que le faltan palabras.
 *
 * Es una funcion de extension de MainActivity: estaba dentro de ese fichero,
 * que pasaba de 6.000 lineas. El cuerpo no cambia.
 */

// ── BUILD RECOVERY TAB ────────────────────────────────────────────────────
internal fun MainActivity.buildRecoveryTab(): ScrollView {
    val ACCENT  = AppTheme.ACCENT
    val ACCENT2 = AppTheme.BLUE

    val recoveryScroll = ScrollView(this).apply {
        setBackgroundColor(AppTheme.BG_DEEP)
        visibility = android.view.View.GONE
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
    val recoveryPage = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(AppTheme.BG_DEEP)
        setPadding(dp(AppTheme.PAD_SIDE), 0, dp(AppTheme.PAD_SIDE), dp(80))
    }

    // ── HELPER ────────────────────────────────────────────────────────
    fun rCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(AppTheme.BG_CARD)
            cornerRadius = dp(AppTheme.R_CARD).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(AppTheme.GAP) }
        setPadding(dp(AppTheme.PAD_CARD), dp(AppTheme.PAD_CARD),
                   dp(AppTheme.PAD_CARD), dp(AppTheme.PAD_CARD))
    }

    fun fieldLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }

    // ── CABECERA ──────────────────────────────────────────────────────
    recoveryPage.addView(Ui.pageTitle(this, "Recover seed", lados = false))
    // El subtítulo repetía el título en otras palabras. En su lugar, lo que
    // de verdad hay que saber para usar la pantalla.
    recoveryPage.addView(TextView(this).apply {
        text = "Type the words you remember and mark the gaps with ?"
        textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        setLineSpacing(dp(4).toFloat(), 1f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(20) }
    })

    // ── LAS PALABRAS ──────────────────────────────────────────────────
    //
    // Era un cuadro de texto donde escribías la frase entera separada por
    // espacios y ponías "???" en los huecos. Eso obliga a llevar la cuenta
    // mental de en qué posición vas, a no equivocarte con los espacios, y a
    // saberse una convención que no está escrita en ninguna parte salvo en
    // la línea de ayuda de encima.
    //
    // Son doce (o veinticuatro) casillas numeradas. Tocas una y escribes la
    // palabra, con las sugerencias del diccionario BIP39 debajo; o la
    // marcas como hueco. El estado se ve sin leer nada: los huecos van en
    // el acento y con su borde.
    val palabras = MutableList(12) { "" }

    /** Lo que espera el motor: las palabras separadas por espacios, "???" en los huecos. */
    fun seedText() = palabras.joinToString(" ") { it.ifEmpty { "???" } }

    val wordGrid = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(20) }
    }

    val tvRecoveryInfo = TextView(this).apply {
        text = "Tap a box to type it"
        textSize = AppTheme.SP_BODY
        setTextColor(AppTheme.TXT_PRI)
        typeface = AppTheme.medium(context)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val tvRecoveryEta = TextView(this).apply {
        text = ""
        textSize = AppTheme.SP_TITLE
        setTextColor(AppTheme.ACCENT)
        typeface = AppTheme.title(context)
    }
    val tvRecoveryCombos = TextView(this).apply {
        text = "Some words are still missing"
        textSize = AppTheme.SP_MICRO
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        setPadding(0, dp(6), 0, 0)
    }

    lateinit var pintarPalabras: () -> Unit

    /** Recalcula cuánto va a costar el intento, antes de empezarlo. */
    fun refrescarCoste() {
        val huecos = palabras.count { it.isEmpty() }
        val puestas = palabras.size - huecos
        when {
            huecos == 0 && puestas == palabras.size -> {
                tvRecoveryInfo.text = "None missing"
                tvRecoveryEta.text = ""
                tvRecoveryCombos.text = "With no gaps there is nothing to try: " +
                                        "mark the ones you do not remember."
            }
            huecos == palabras.size -> {
                tvRecoveryInfo.text = "Tap a box to type it"
                tvRecoveryEta.text = ""
                tvRecoveryCombos.text = "Type at least the ones you remember."
            }
            else -> {
                // 2048^huecos. Por encima de 4 huecos se sale de Long, así
                // que la cuenta va en Double y se dice en texto.
                val combos = Math.pow(2048.0, huecos.toDouble())
                val porSeg = 6_300_000.0   // orden de magnitud de un móvil
                val secs = combos / porSeg
                tvRecoveryInfo.text =
                    if (huecos == 1) "1 word to guess"
                    else "$huecos words to guess"
                tvRecoveryEta.text = when {
                    secs < 60        -> "~ ${secs.toInt()} s"
                    secs < 3600      -> "~ ${(secs / 60).toInt()} min"
                    secs < 86_400    -> "~ ${(secs / 3600).toInt()} h"
                    secs < 31_536_000-> "~ ${(secs / 86_400).toInt()} days"
                    else             -> "over a year"
                }
                tvRecoveryEta.setTextColor(
                    if (secs > 86_400) AppTheme.WARN else AppTheme.ACCENT)
                val combosTxt = when {
                    combos >= 1e12 -> "%.1f trillion".format(combos / 1e12)
                    combos >= 1e9  -> "%.1f billion".format(combos / 1e9)
                    combos >= 1e6  -> "%.1f million".format(combos / 1e6)
                    else           -> numberFmt.format(combos.toLong())
                }
                tvRecoveryCombos.text = "$combosTxt combinations"
            }
        }
    }

    /** Pide la palabra de una casilla, con el diccionario delante. */
    fun pedirPalabra(idx: Int) {
        val cont = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(8))
        }
        val campo = android.widget.AutoCompleteTextView(this).apply {
            setText(palabras[idx])
            hint = "word ${idx + 1}"
            setTextColor(AppTheme.TXT_PRI); setHintTextColor(AppTheme.TXT_MUTED)
            textSize = AppTheme.SP_BODY
            typeface = AppTheme.body(context)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // Las 2048 del diccionario: teclear una que no esté hace que la
            // búsqueda no pueda encontrar nada, y antes no avisaba nadie.
            setAdapter(android.widget.ArrayAdapter(
                this@buildRecoveryTab, android.R.layout.simple_list_item_1,
                Bip39Words.WORDS))
            threshold = 1
            setSelection(text.length)
        }
        cont.addView(campo)
        AlertDialog.Builder(this)
            .setTitle("Word ${idx + 1}")
            .setView(cont)
            .setPositiveButton("Save") { _, _ ->
                val w = campo.text.toString().trim().lowercase()
                palabras[idx] = if (w.isNotEmpty() && Bip39Words.WORDS.contains(w)) w else ""
                if (w.isNotEmpty() && !Bip39Words.WORDS.contains(w))
                    Toast.makeText(this, "\"$w\" is not in the BIP39 word list",
                        Toast.LENGTH_LONG).show()
                pintarPalabras(); refrescarCoste()
            }
            .setNeutralButton("I do not remember it") { _, _ ->
                palabras[idx] = ""
                pintarPalabras(); refrescarCoste()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    pintarPalabras = {
        wordGrid.removeAllViews()
        var fila: LinearLayout? = null
        palabras.forEachIndexed { i, w ->
            if (i % 3 == 0) {
                fila = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { if (i > 0) topMargin = dp(8) }
                }
                wordGrid.addView(fila)
            }
            val hueco = w.isEmpty()
            val chip = TextView(this).apply {
                val sp = android.text.SpannableStringBuilder("${i + 1}  ")
                sp.setSpan(android.text.style.ForegroundColorSpan(AppTheme.TXT_MUTED),
                    0, sp.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(android.text.style.AbsoluteSizeSpan(
                    (11 * resources.displayMetrics.scaledDensity).toInt()),
                    0, sp.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.append(if (hueco) "falta" else w)
                text = sp
                textSize = AppTheme.SP_BODY
                gravity = Gravity.CENTER
                setTextColor(if (hueco) AppTheme.ACCENT else AppTheme.TXT_PRI)
                typeface = if (hueco) AppTheme.bold(context) else AppTheme.body(context)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (hueco) AppTheme.BG_ELEV else AppTheme.BG_KEY)
                    cornerRadius = dp(AppTheme.R_INNER).toFloat()
                    if (hueco) setStroke(dp(2), AppTheme.ACCENT)
                }
                setPadding(dp(6), dp(14), dp(6), dp(14))
                isClickable = true; isFocusable = true
                setOnClickListener { pedirPalabra(i) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i % 3 < 2) marginEnd = dp(8) }
            }
            fila?.addView(chip)
        }
    }

    // Selector de 12 o 24 palabras. Antes se deducía de cuántas escribías,
    // así que una frase de 24 a medio poner se trataba como de 12.
    val largoRow = Ui.segmented(this,
        listOf("12 palabras" to null, "24 palabras" to null), initial = 0) { idx ->
        val nuevo = if (idx == 0) 12 else 24
        while (palabras.size < nuevo) palabras.add("")
        while (palabras.size > nuevo) palabras.removeAt(palabras.size - 1)
        pintarPalabras(); refrescarCoste()
    }
    recoveryPage.addView(largoRow.apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(18) }
    })
    pintarPalabras()
    recoveryPage.addView(wordGrid)

    // ── LO QUE CUESTA EL INTENTO ──────────────────────────────────────
    val costeCard = rCard()
    val costeHead = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = true
    }
    costeHead.addView(tvRecoveryInfo); costeHead.addView(tvRecoveryEta)
    costeCard.addView(costeHead)
    costeCard.addView(tvRecoveryCombos)
    refrescarCoste()

    recoveryPage.addView(costeCard)

    // ── TARGET ADDRESS CARD ───────────────────────────────────────────
    val targetCard = rCard()
    targetCard.addView(fieldLabel("Known address · optional"))
    val etTarget = android.widget.EditText(this).apply {
        hint = "1A2B3C... o bc1q..."
        setHintTextColor(AppTheme.TXT_MUTED); setTextColor(AppTheme.TXT_PRI)
        textSize = AppTheme.SP_BODY; typeface = Typeface.MONOSPACE
        background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
        minHeight = dp(48)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        isSingleLine = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    targetCard.addView(etTarget)
    recoveryPage.addView(targetCard)

    // ── PROGRESS CARD ─────────────────────────────────────────────────
    // El coste del intento está arriba, en costeCard; aquí sólo va la barra
    // y lo que está probando ahora mismo.
    val progressCard = rCard()
    val pbRecovery = android.widget.ProgressBar(
        this, null, android.R.attr.progressBarStyleHorizontal
    ).apply {
        max = 1000; progress = 0
        // Mismo fallo que tenía la barra del puzzle: un GradientDrawable
        // pelado como progressDrawable se pinta entero, sin recortarse,
        // así que la barra aparecía llena desde el primer instante.
        progressDrawable = android.graphics.drawable.LayerDrawable(
            arrayOf(
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(AppTheme.BG_ELEV); cornerRadius = dp(4).toFloat()
                },
                android.graphics.drawable.ClipDrawable(
                    android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(ACCENT2, ACCENT)
                    ).apply { cornerRadius = dp(4).toFloat() },
                    Gravity.START,
                    android.graphics.drawable.ClipDrawable.HORIZONTAL)
            )
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
        ).apply { bottomMargin = dp(8) }
        visibility = android.view.View.GONE
    }
    val tvRecoveryStatus = TextView(this).apply {
        text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        visibility = android.view.View.GONE
    }
    progressCard.addView(pbRecovery)
    progressCard.addView(tvRecoveryStatus)

    val tvRecoveryResult = TextView(this).apply {
        // Un resultado encontrado SÍ merece el acento: es el hallazgo.
        text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.ACCENT)
        typeface = Typeface.MONOSPACE   // lleva la seed entera
        background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setLineSpacing(0f, 1.35f)
        visibility = android.view.View.GONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }
    progressCard.addView(tvRecoveryResult)
    recoveryPage.addView(progressCard)

    // ── BUTTONS ───────────────────────────────────────────────────────
    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }
    val btnStartRecovery = Button(this).apply {
        text = "Start recovery"; textSize = AppTheme.SP_TITLE
        setTextColor(AppTheme.ON_ACCENT)
        background = Ui.cardBg(AppTheme.R_KEY, AppTheme.ACCENT, context)
        typeface = AppTheme.bold(context)
        isAllCaps = false
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(8) }
    }
    val btnCancelRecovery = Button(this).apply {
        text = "Cancel"; textSize = AppTheme.SP_BODY
        setTextColor(AppTheme.RED)
        background = Ui.cardBg(AppTheme.R_KEY, AppTheme.BG_ELEV, context)
        typeface = AppTheme.medium(context)
        isAllCaps = false
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
        visibility = android.view.View.GONE
    }
    btnRow.addView(btnStartRecovery); btnRow.addView(btnCancelRecovery)
    recoveryPage.addView(btnRow)

    val btnSaveWallet = Button(this).apply {
        text = "Add to your wallets"; textSize = AppTheme.SP_TITLE
        setTextColor(AppTheme.ON_ACCENT)
        background = Ui.cardBg(AppTheme.R_KEY, AppTheme.ACCENT, context)
        typeface = AppTheme.bold(context)
        isAllCaps = false
        stateListAnimator = null
        visibility = android.view.View.GONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        )
    }
    recoveryPage.addView(btnSaveWallet)

    // ── LISTENERS ─────────────────────────────────────────────────────

    btnSaveWallet.setOnClickListener {
        val foundMnemonic = it.tag as? String ?: return@setOnClickListener
        // Con agregarSeed y no saveSeed: saveSeed escribe la seed PRINCIPAL,
        // así que con una cartera ya guardada la recuperada la SUSTITUÍA y
        // la anterior se perdía. Ahora va al lado si ya hay una.
        fun guardar() {
            val g = WalletManager.agregarSeed(this, foundMnemonic, "Recovered", "recovery")
            btnSaveWallet.visibility = android.view.View.GONE
            tvRecoveryStatus.text = if (g.yaEstaba) "That seed was already in your wallets (${g.nombre})"
                                    else "Seed saved to your wallets as \"${g.nombre}\""
            tvRecoveryStatus.visibility = android.view.View.VISIBLE
        }
        val d = AlertDialog.Builder(this)
            .setTitle("Save to your wallets")
            .setMessage("It is already kept in the finds vault. Also add it to " +
                        "your wallets, to see its balance and use it?")
            .setPositiveButton("Add") { _, _ ->
                if (PinAuthHelper.isSessionValid()) guardar()
                else PinAuthHelper.show(this) { ok -> if (ok) guardar() }
            }
            .setNegativeButton("Cancel", null)
            .create()
        d.show()
    }

    recoveryEngine = RecoveryEngine(this)
    val wordlistLoaded = recoveryEngine?.loadWordlist() ?: false

    recoveryEngine?.listener = object : com.hunter.btc.recovery.RecoveryEngine.ProgressListener {
        override fun onProgress(attempts: Long, total: Long, currentWord: String) {
            runOnUiThread {
                // attempts.toFloat() pierde precisión por encima de ~16.7M,
                // y con 3-4 palabras faltantes el total llega a 1e13.
                val pct = if (total > 0)
                    ((attempts.toDouble() / total) * 1000).toInt().coerceIn(0, 1000)
                else 0
                pbRecovery.progress = pct
                tvRecoveryStatus.text = "Trying: $currentWord  ($attempts / $total)"
            }
        }
        override fun onFoundWithAddress(mnemonic: String, address: String) {
            runOnUiThread {
                pbRecovery.visibility = android.view.View.GONE
                tvRecoveryStatus.visibility = android.view.View.GONE
                btnCancelRecovery.visibility = android.view.View.GONE
                btnStartRecovery.visibility = android.view.View.VISIBLE
                tvRecoveryResult.text = "DERIVED ADDRESS:\n$address\n\nPHRASE:\n$mnemonic"
                tvRecoveryResult.visibility = android.view.View.VISIBLE
            }
        }
        override fun onFound(mnemonic: String) {
            runOnUiThread {
                pbRecovery.visibility = android.view.View.GONE
                tvRecoveryStatus.visibility = android.view.View.GONE
                btnCancelRecovery.visibility = android.view.View.GONE
                btnStartRecovery.visibility = android.view.View.VISIBLE
                tvRecoveryResult.text = "✓ FOUND\n\n$mnemonic"
                tvRecoveryResult.visibility = android.view.View.VISIBLE
                btnSaveWallet.tag = mnemonic
                btnSaveWallet.visibility = android.view.View.VISIBLE
                // La seed NO se escribe en disco. Antes se volcaba en claro a
                // getExternalFilesDir()/recovery_<ts>.txt, legible por cualquier
                // app con MANAGE_EXTERNAL_STORAGE y visible por USB, lo que
                // anulaba el cifrado del resto de la app. Para conservarla, el
                // usuario pulsa "Guardar wallet", que la cifra con el Keystore.
                // Borramos también los ficheros que dejaron versiones anteriores.
                purgeLegacyRecoveryFiles()
                // Al baúl en el momento: antes, salir de la app sin pulsar
                // "Save wallet" perdía la frase, y había que repetir una
                // búsqueda de horas.
                Thread {
                    val ok = try { MatchVault.guardarRecuperada(this@buildRecoveryTab, mnemonic) }
                             catch (t: Throwable) { false }
                    runOnUiThread {
                        tvRecoveryStatus.text = if (ok) "Kept in the finds vault, encrypted."
                            else "Could not keep it in the vault: save it to your wallets now."
                        tvRecoveryStatus.visibility = android.view.View.VISIBLE
                    }
                }.start()
                sendMatchNotification("Seed recovered", "It is kept in the finds vault.")
            }
        }
        override fun onNotFound() {
            runOnUiThread {
                pbRecovery.visibility = android.view.View.GONE
                tvRecoveryStatus.text = "Not found. Check the words you entered."
                btnCancelRecovery.visibility = android.view.View.GONE
                btnStartRecovery.visibility = android.view.View.VISIBLE
            }
        }
        override fun onCancelled() {
            runOnUiThread {
                pbRecovery.visibility = android.view.View.GONE
                tvRecoveryStatus.text = "Cancelled."
                btnCancelRecovery.visibility = android.view.View.GONE
                btnStartRecovery.visibility = android.view.View.VISIBLE
            }
        }
    }

    btnStartRecovery.setOnClickListener {
        val input = seedText()
        if (palabras.all { it.isEmpty() }) {
            tvRecoveryStatus.text = "Type at least the words you remember."
            tvRecoveryStatus.visibility = android.view.View.VISIBLE
            return@setOnClickListener
        }
        if (palabras.none { it.isEmpty() }) {
            tvRecoveryStatus.text = "There is no gap to try: mark the ones you do not remember."
            tvRecoveryStatus.visibility = android.view.View.VISIBLE
            return@setOnClickListener
        }
        if (!wordlistLoaded) {
            tvRecoveryStatus.text = "Could not load the BIP39 word list."
            tvRecoveryStatus.visibility = android.view.View.VISIBLE
            return@setOnClickListener
        }
        val wl = recoveryEngine?.getWordlistSet() ?: emptySet()
        val parseResult = com.hunter.btc.recovery.RecoveryParser.parse(input, wl)
        when (parseResult) {
            is com.hunter.btc.recovery.ParseResult.Error -> {
                tvRecoveryStatus.text = parseResult.message
                tvRecoveryStatus.visibility = android.view.View.VISIBLE
            }
            is com.hunter.btc.recovery.ParseResult.Success -> {
                tvRecoveryResult.visibility = android.view.View.GONE
                pbRecovery.progress = 0
                pbRecovery.visibility = android.view.View.VISIBLE
                tvRecoveryStatus.visibility = android.view.View.VISIBLE
                tvRecoveryStatus.text = "Starting..."
                btnStartRecovery.visibility = android.view.View.GONE
                btnCancelRecovery.visibility = android.view.View.VISIBLE
                recoveryEngine?.startRecovery(parseResult.parsed, etTarget?.text.toString().trim() ?: "")
            }
        }
    }

    btnCancelRecovery.setOnClickListener { recoveryEngine?.cancel() }

    recoveryScroll.addView(recoveryPage)
    return recoveryScroll
}
