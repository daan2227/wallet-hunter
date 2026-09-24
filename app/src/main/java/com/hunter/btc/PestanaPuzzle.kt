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
 * La pestana Puzzle: seleccion de puzzle, fuerza bruta por rango y Kangaroo.
 *
 * Es una funcion de extension de MainActivity: estaba dentro de ese fichero,
 * que pasaba de 6.000 lineas. El cuerpo no cambia.
 */

// ── BUILD PUZZLE TAB ──────────────────────────────────────────────────────
internal fun MainActivity.buildPuzzleTab(): ScrollView {
    val ACCENT  = AppTheme.ACCENT
    val ACCENT2 = AppTheme.BLUE

    val scroll = ScrollView(this).apply {
        setBackgroundColor(AppTheme.BG_DEEP)
        visibility = android.view.View.GONE
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(AppTheme.BG_DEEP)
        // El margen lateral lo ponen ahora las tarjetas, no la página: así
        // una tarjeta mide lo mismo aquí que en la pestaña de escaneo.
        setPadding(0, 0, 0, dp(80))
    }

    // ── HELPERS ───────────────────────────────────────────────────────
    fun pCard(marginTop: Int = AppTheme.GAP): LinearLayout = Ui.card(this, marginTop)

    fun sectionLabel(text: String) = Ui.sectionLabel(this, text)

    fun styledInput(hint: String, color: Int = AppTheme.TXT_PRI): EditText =
        Ui.input(this, hint, mono = true).apply {
            setTextColor(color)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

    fun collapsibleSection(icon: Int, title: String, build: LinearLayout.() -> Unit) =
        Ui.section(this, icon, title, build)

    // ── CABECERA ──────────────────────────────────────────────────────
    page.addView(Ui.pageTitle(this, "Puzzle", lados = true))
    // "Selecciona el puzzle objetivo" explicaba a quien ya está mirando la
    // lista de puzzles lo que hace la lista de puzzles.

    // ── PUZZLE CHIP SELECTOR ──────────────────────────────────────────
    val hiddenPuzzles = getSharedPreferences("hidden_puzzles", android.content.Context.MODE_PRIVATE)
    // Se descartan las entradas cuya dirección NO es una dirección de
    // Bitcoin. Buscar una clave cuyo hash160 dé una dirección con el
    // checksum roto es buscar algo que no puede existir: el contador
    // subiría igual, para siempre, sin ninguna posibilidad.
    val invalidas = puzzles.filter {
        BtcAddress.validate(it.addr, false) !is BtcAddress.Result.Valid
    }
    if (invalidas.isNotEmpty())
        android.util.Log.e("MainActivity",
            "Puzzle table: ${invalidas.size} invalid addresses: " +
            invalidas.joinToString { "#${it.num}" })
    // Los que valen como objetivo, sin mirar todavia si tienen fondos.
    val todosPuzzles = puzzles.filter {
        BtcAddress.validate(it.addr, false) is BtcAddress.Result.Valid
    }
    // "Sin fondos" = alguien ya lo resolvio y se llevo el premio. La marca la
    // pone la consulta de saldo, aqui solo se lee.
    // Un puzzle del que se conoce la clave esta resuelto por definicion: no
    // hace falta preguntarle el saldo a nadie para saber que no paga.
    val resueltos = todosPuzzles.filter { it.clave.isNotEmpty() }.map { it.num }.toSet()
    fun sinFondos(n: Int) =
        n in resueltos || hiddenPuzzles.getBoolean("hidden_$n", false)

    /* MODO PRUEBA: enseñar tambien los ya resueltos.
     *
     * Un puzzle resuelto no da dinero, pero da algo que ninguna prueba del
     * banco puede dar: un objetivo REAL, con clave conocida y rango pequeño,
     * que este movil puede llegar a encontrar de verdad. Es la unica forma
     * de comprobar de punta a punta —motor, tabla, cluster y aviso— en el
     * aparato de uno y no en un ordenador de escritorio.
     *
     * Viene apagado: lo normal es querer los que pagan. */
    fun listaVisible(): List<PuzzleInfo> {
        if (prefs.getBoolean("mostrar_sin_fondos", false)) return todosPuzzles
        val conFondos = todosPuzzles.filter { !sinFondos(it.num) }
        // Si se hubieran ocultado todos, mas vale enseñarlos que dejar la
        // pestaña vacia y sin forma de salir de ahi.
        return if (conFondos.isEmpty()) todosPuzzles else conFondos
    }
    var visiblePuzzles = listaVisible().toMutableList()

    // Track selected puzzle
    var selectedPuzzleIdx = 0

    // Container for chip rows
    val chipSection = pCard(0)
    chipSection.addView(sectionLabel("Select puzzle"))

    // Interruptor de "enseñar tambien los ya resueltos". Se crea aqui para
    // que quede en su sitio en la pantalla; lo que hace se le cuelga mas
    // abajo, cuando ya existen las funciones que repintan los chips.
    val btnSinFondos = TextView(this).apply {
        textSize = AppTheme.SP_CAPTION
        gravity = Gravity.CENTER
        typeface = AppTheme.medium(context)
        isClickable = true; isFocusable = true
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }
    fun pintarBotonSinFondos() {
        val ver = prefs.getBoolean("mostrar_sin_fondos", false)
        btnSinFondos.text = if (ver) "Hide the ones with no funds left"
                            else     "Also show the already solved ones (test)"
        btnSinFondos.background = Ui.cardBg(AppTheme.R_CHIP,
            if (ver) AppTheme.BG_ELEV else AppTheme.BG_CARD, this@buildPuzzleTab)
        btnSinFondos.setTextColor(if (ver) AppTheme.WARN else AppTheme.TXT_SEC)
    }
    pintarBotonSinFondos()
    chipSection.addView(btnSinFondos)

    // Horizontal scroll for group chips
    val groupScroll = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }
    val groupRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    groupScroll.addView(groupRow)
    chipSection.addView(groupScroll)

    // Individual chips container (shown below group)
    val indivScroll = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    val indivRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    indivScroll.addView(indivRow)
    chipSection.addView(indivScroll)
    page.addView(chipSection)

    // ── STATUS ────────────────────────────────────────────────────────
    // Era texto verde a 11sp en monoespaciada dentro de un recuadro
    // translúcido con filo verde. El acento estaba diciendo "hay un puzzle
    // seleccionado", que no es ni una acción, ni un saldo, ni que algo esté
    // corriendo: es sólo información.
    tvPuzzleStatus = TextView(this).apply {
        text = "Select a puzzle"
        textSize = AppTheme.SP_BODY
        typeface = AppTheme.medium(context)
        setTextColor(AppTheme.TXT_SEC)
        background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(AppTheme.PAD_SIDE), dp(AppTheme.GAP), dp(AppTheme.PAD_SIDE), 0)
        }
    }
    page.addView(tvPuzzleStatus)

    // ── PROGRESO VISUAL ───────────────────────────────────────────────
    val progressCard = pCard()
    val progressHeader = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }
    val tvProgressPct = TextView(this).apply {
        text = "0,00 %"
        textSize = AppTheme.SP_BODY
        setTextColor(AppTheme.TXT_PRI)
        typeface = AppTheme.bold(context)
    }
    progressHeader.addView(TextView(this).apply {
        text = "Range coverage"
        textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    })
    progressHeader.addView(tvProgressPct)
    progressCard.addView(progressHeader)

    val progressTrack = android.widget.FrameLayout(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(AppTheme.BG_ELEV); cornerRadius = dp(4).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
        ).apply { bottomMargin = dp(8) }
    }
    val progressBarPuzzle = android.widget.ProgressBar(
        this, null, android.R.attr.progressBarStyleHorizontal
    ).apply {
        max = 10000; progress = 0
        // Se asignaba un GradientDrawable pelado como progressDrawable, y
        // ProgressBar lo pintaba entero sin recortarlo: la barra se veía
        // siempre llena independientemente del valor. El drawable de
        // progreso tiene que ir envuelto en ClipDrawable dentro de un
        // LayerDrawable con los ids que ProgressBar espera.
        val fill = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(ACCENT2, ACCENT)
        ).apply { cornerRadius = dp(4).toFloat() }
        val track = android.graphics.drawable.GradientDrawable().apply {
            setColor(AppTheme.BG_ELEV); cornerRadius = dp(4).toFloat()
        }
        progressDrawable = android.graphics.drawable.LayerDrawable(
            arrayOf(
                track,
                android.graphics.drawable.ClipDrawable(
                    fill, Gravity.START,
                    android.graphics.drawable.ClipDrawable.HORIZONTAL)
            )
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
    progressTrack.addView(progressBarPuzzle)
    progressCard.addView(progressTrack)

    val tvProgressDetail = TextView(this).apply {
        text = "Blocks: —"; textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        setPadding(0, dp(10), 0, 0)
    }
    progressCard.addView(tvProgressDetail)

    // Salto aleatorio dentro del rango del puzzle.
    tvRandomJump = TextView(this).apply {
        text = "Jump to a random point in the range"
        textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI)
        typeface = AppTheme.medium(context)
        background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(14), dp(14), dp(14))
        minHeight = dp(48)
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
        setOnClickListener { pickRandomJump() }
    }
    progressCard.addView(tvRandomJump)

    // En qué bloque se está (o se va a empezar) y dónde cae en el rango.
    // Esto estaba en tvPuzzleStatus, pero updateUI() reescribe esa línea con
    // el ETA cada 800 ms, así que la posición se borraba antes de poder
    // leerla. Aquí no la pisa nadie.
    tvCurrentBlock = TextView(this).apply {
        text = "Current block: —"
        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = Typeface.MONOSPACE   // lleva el índice en hex
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }
    progressCard.addView(tvCurrentBlock)

    progressCard.addView(TextView(this).apply {
        // 9sp en gris #555 sobre fondo casi negro es ilegible y demasiado
        // pequeño para acertar con el dedo, siendo además destructivo.
        text = "Reset progress"; textSize = AppTheme.SP_BODY
        setTextColor(AppTheme.RED)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        typeface = AppTheme.medium(context)
        minHeight = dp(48)
        gravity = Gravity.CENTER; isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        setOnClickListener {
            // Con el scan en marcha esto era null y el botón salía por el
            // return sin decir nada: pulsar "Reset progress" no hacía
            // absolutamente nada mientras estabas buscando.
            val puzzleNum = currentPuzzleNum()
            if (puzzleNum == 0) return@setOnClickListener
            AlertDialog.Builder(this@buildPuzzleTab)
                .setTitle("Reset progress")
                .setMessage("Delete the progress of puzzle #$puzzleNum?")
                .setPositiveButton("Reset") { _, _ ->
                    getBlockPrefs().edit().remove("scanned_$puzzleNum").apply()
                    progressBarPuzzle.progress = 0
                    tvProgressPct.text = "0.00%"
                    tvProgressDetail.text = "Blocks: 0 / —"
                    android.widget.Toast.makeText(this@buildPuzzleTab, "Progress reset", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null).show()
        }
    })
    page.addView(progressCard)
    cardCobertura = progressCard

    /* Estaba definida aquí dentro y no se llamaba desde ningún sitio, así
       que la barra y el detalle se quedaban en sus valores iniciales
       ("0.00%" y "Blocks: —"). Se expone como campo para poder
       dispararla al seleccionar puzzle y desde updateUI. */
    puzzleProgressUpdater = { puzzleNum: Int, rangeStart: String, rangeEnd: String ->
        Thread {
            try {
                // Mismo truncado que en getBlockProgressText: con BigInteger
                // el total es correcto para cualquier puzzle.
                val total = totalBlocksOf(rangeStart, rangeEnd)
                val scanned = getBlockPrefs().getStringSet("scanned_$puzzleNum", emptySet())?.size ?: 0
                val pctD = blockPercent(java.math.BigInteger.valueOf(scanned.toLong()), total)
                val pct = (pctD * 100.0).toInt().coerceIn(0, 10000)
                val pctStr = "%.4f%%".format(pctD)
                runOnUiThread {
                    progressBarPuzzle.progress = pct
                    tvProgressPct.text = pctStr
                    tvProgressDetail.text = "Blocks: $scanned / $total"
                }
            } catch (e: Exception) {}
        }.start()
        Unit
    }

    // El botón "Ver QR de dirección" mostraba un QR de la dirección del
    // puzzle. Nada lo escanea: la dirección es pública y conocida, y no hay
    // ningún flujo que la reciba por cámara. Retirado.

    // Balance indicator - debajo del puzzle seleccionado
    val tvBalResult = TextView(this).apply {
        text = "Checking balance…"
        textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(AppTheme.PAD_SIDE), dp(8), dp(AppTheme.PAD_SIDE), 0)
        }
    }
    page.addView(tvBalResult)

    // ── ¿TIENE ATAJO ESTE PUZZLE? ─────────────────────────────────────
    //
    // Es la información que decide si merece la pena dejar el móvil
    // corriendo. Una dirección que nunca ha gastado no ha revelado su clave
    // pública, y sin clave pública lo único que queda es probar claves de
    // una en una: para el rango del #70 son 2^69, millones de años. Si la
    // clave está publicada, sirve Pollard's Kangaroo, que es O(raiz(n)):
    // el mismo rango baja a unas 2^35 operaciones.
    //
    // Kangaroo no está implementado todavía. Esto dice si sería posible,
    // que es lo que hay que saber ANTES de dedicarle el móvil a algo.
    tvPuzzleAtajo = TextView(this).apply {
        text = "Checking whether the public key is published…"
        textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        setLineSpacing(0f, 1.4f)
        background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(AppTheme.PAD_SIDE), dp(8), dp(AppTheme.PAD_SIDE), 0)
        }
    }
    page.addView(tvPuzzleAtajo)

    btnKangaroo = Button(this).apply {
        text = "Search with Kangaroo"
        textSize = AppTheme.SP_BODY
        setTextColor(AppTheme.ON_ACCENT)
        typeface = AppTheme.bold(context)
        isAllCaps = false
        stateListAnimator = null
        background = Ui.cardBg(AppTheme.R_INNER, AppTheme.ACCENT, this@buildPuzzleTab)
        visibility = android.view.View.GONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
        ).apply {
            setMargins(dp(AppTheme.PAD_SIDE), dp(8), dp(AppTheme.PAD_SIDE), 0)
        }
        setOnClickListener { alternarKangaroo() }
    }
    page.addView(btnKangaroo)

    // ── RANGE CONFIG ──────────────────────────────────────────────────
    page.addView(collapsibleSection(R.drawable.ic_target, "Hex range") {
        val rangeRow = LinearLayout(this@buildPuzzleTab).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        val colStart = LinearLayout(this@buildPuzzleTab).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        }
        colStart.addView(TextView(this@buildPuzzleTab).apply { text = "From"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context); setPadding(0,0,0,dp(6)) })
        etRangeStart = styledInput("0x...")
        colStart.addView(etRangeStart)
        val colEnd = LinearLayout(this@buildPuzzleTab).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        colEnd.addView(TextView(this@buildPuzzleTab).apply { text = "To"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context); setPadding(0,0,0,dp(6)) })
        etRangeEnd = styledInput("0x...")
        colEnd.addView(etRangeEnd)
        rangeRow.addView(colStart); rangeRow.addView(colEnd)
        addView(rangeRow)
        addView(TextView(this@buildPuzzleTab).apply { text = "Target address"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context); setPadding(0,0,0,dp(6)) })
        etTarget = styledInput("1A2B3C…")
        addView(etTarget)
    })

    // ── CHECKPOINT ────────────────────────────────────────────────────
    tvCheckpointLive = TextView(this).apply {
        text = ""
        textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        // Sin checkpoint el texto queda vacío, pero el fondo, el borde y el
        // padding seguían dibujándose: un rectángulo hueco de ~40dp bajo
        // "Rango Hex". Se oculta mientras no tenga contenido.
        visibility = android.view.View.GONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(AppTheme.PAD_SIDE), dp(AppTheme.GAP), dp(AppTheme.PAD_SIDE), 0)
        }
    }
    page.addView(tvCheckpointLive)

    // ── STATS ─────────────────────────────────────────────────────────
    val statsCard = pCard()
    statsCard.addView(sectionLabel("Performance"))
    val speedRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
    }
    // Misma corrección que en el escáner: la unidad iba apilada en dos
    // líneas ("Keys" sobre "por seg") a un lado de la cifra, y el pico
    // flotaba abajo a la derecha a 9sp. Ahora la unidad va junto a la
    // cifra y el pico baja a su propia línea.
    val tvWpsP = TextView(this).apply {
        text = "0"; textSize = AppTheme.SP_DISPLAY
        setTextColor(AppTheme.TXT_PRI)
        typeface = AppTheme.display(context)
        letterSpacing = -0.04f
    }
    tvWpsPuzzle = tvWpsP
    speedRow.addView(tvWpsP)
    // getWps() devuelve claves/s directas; la etiqueta decía "kKeys", lo que
    // multiplicaba por mil la lectura. La unidad la fija el escalado.
    tvSpeedUnitPuzzle = TextView(this).apply {
        text = "Keys/s"; textSize = AppTheme.SP_TITLE
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) }
    }
    speedRow.addView(tvSpeedUnitPuzzle)
    statsCard.addView(speedRow)

    tvPeakWpsPuzzle = TextView(this).apply {
        text = ""; textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }
    statsCard.addView(tvPeakWpsPuzzle)

    // Gráfica de velocidad: una muestra cada 10 s, la última hora.
    //
    // SpeedChartView existía desde hacía tiempo y `chartView` NUNCA se
    // asignaba: era siempre null, así que addPoint() no hacía nada y la
    // gráfica no aparecía en ninguna pantalla.
    //
    // Cada diez segundos, y no cada segundo, porque lo que interesa de una
    // búsqueda que dura días es si el móvil mantiene el ritmo o se está
    // frenando por calor o por batería, y eso no se ve en un segundo. 360
    // puntos son una hora de historia.
    tvMediaPuzzle = TextView(this).apply {
        text = "Average speed · one sample every 10 s, last hour"
        textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
    }
    statsCard.addView(tvMediaPuzzle)
    chartView = SpeedChartView(this).apply {
        // 110 y no 90: ahora hay una fila de etiquetas arriba (máx/mín) y
        // otra abajo (tiempo), y con 90 la línea se quedaba sin sitio.
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(110)
        ).apply { topMargin = dp(8) }
    }
    statsCard.addView(chartView)

    fun miniStat(label: String, tv: TextView, guardarEtiqueta: ((TextView)->Unit)? = null):
            LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat()
        }
        setPadding(dp(14), dp(14), dp(14), dp(14))
        addView(TextView(this@buildPuzzleTab).apply {
            text = label; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            guardarEtiqueta?.invoke(this)
        })
        addView(tv)
    }

    // Eran 16sp blanco, 16sp blanco, 13sp azul y 13sp blanco: cuatro datos
    // del mismo rango con tres tratamientos. Uno solo.
    fun miniValue(initial: String) = TextView(this).apply {
        text = initial; textSize = AppTheme.SP_FIGURE
        setTextColor(AppTheme.TXT_PRI)
        typeface = AppTheme.title(context)
        letterSpacing = -0.02f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }
    val tvCntP = miniValue("0")
    val tvTmP  = miniValue("00:00:00")
    tvCountPuzzle = tvCntP; tvTimePuzzle = tvTmP
    tvPctPuzzle = miniValue("—")
    tvBlockProgress = miniValue("—")

    val miniRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
    val miniRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } }
    miniRow1.addView(miniStat("Scanned", tvCntP) { lblEscaneadas = it }
        .also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
    miniRow1.addView(miniStat("Time", tvTmP))
    val pctLocal = tvPctPuzzle!!
    val blkLocal = tvBlockProgress!!
    miniRow2.addView(miniStat("Progress", pctLocal).also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
    miniRow2.addView(miniStat("Blocks left", blkLocal) { lblRestantes = it })
    statsCard.addView(miniRow1); statsCard.addView(miniRow2)
    page.addView(statsCard)

    // ── POTENCIA: LOW / MEDIUM / HIGH ─────────────────────────────────
    val powerCard = pCard()
    powerCard.addView(sectionLabel("Power"))

    val powerRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    // Hidden sliders (kept for logic compatibility)
    tvThreadsPuzzle = TextView(this).apply { visibility = android.view.View.GONE }
    tvCpuPuzzle     = TextView(this).apply { visibility = android.view.View.GONE }
    sbThreadsPuzzle = SeekBar(this).apply {
        visibility = android.view.View.GONE; max = 7
        progress = prefs.getInt("puzzle_threads", 3)
        setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() })
    }
    sbCpuPuzzle = SeekBar(this).apply {
        visibility = android.view.View.GONE; max = 90
        progress = prefs.getInt("puzzle_cpu", 70)
        setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() })
    }
    powerCard.addView(tvThreadsPuzzle); powerCard.addView(sbThreadsPuzzle)
    powerCard.addView(tvCpuPuzzle); powerCard.addView(sbCpuPuzzle)

    data class PowerLevel(val label: String, val threads: Int, val cpu: Int)
    // "High" eran 7 hilos fijos. En un móvil de 8 núcleos eso deja uno sin
    // usar, y si el móvil tiene menos de 8 pide más hilos que núcleos.
    // Ahora sale del hardware: Alta = todos, Media = la mitad, Baja = 1.
    //
    // No se reserva núcleo para la interfaz porque el freno de CPU ya deja
    // aire: al 90 % los hilos duermen un 11 % del tiempo.
    val nuc = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    val levels = listOf(
        PowerLevel("Low",  1, 30),
        PowerLevel("Medium", (nuc / 2).coerceAtLeast(1), 60),
        PowerLevel("High",  nuc, 90)
    )

    // Eran tres TextViews con su borde, repintados a mano en un
    // forEachIndexed: el mismo patrón que el selector de modo del escáner y
    // el de aleatorio/secuencial de aquí abajo, escrito tres veces.
    // Cual de los tres esta guardado. Iba fijo a 1 (Media), asi que al
    // reabrir la app el selector mentia: marcaba Media aunque hubieras
    // elegido Alta.
    val nivelGuardado = prefs.getInt("puzzle_threads", 3).let { g ->
        levels.indexOfFirst { it.threads - 1 == g }.let { if (it < 0) 1 else it }
    }
    powerRow.addView(Ui.segmented(
        this, levels.map { it.label to null }, initial = nivelGuardado
    ) { idx ->
        val level = levels[idx]
        sbThreadsPuzzle?.progress = level.threads - 1
        sbCpuPuzzle?.progress = level.cpu - 10
        // Si Kangaroo está corriendo, el freno cambia al momento. El número
        // de hilos no: eso sí obligaría a reiniciar la búsqueda.
        Termico.pedir(level.cpu)
        prefs.edit()
            .putInt("puzzle_threads", level.threads - 1)
            .putInt("puzzle_cpu", level.cpu - 10)
            .apply()
        updatePuzzleLabels()
    })
    // ESTO TIRABA LA PREFERENCIA. Los dos deslizadores se cargan de prefs
    // unas lineas mas arriba y aqui se pisaban con 3 y 50 fijos, pasara lo
    // que pasara. Efecto: elegias "High" (7 hilos, 90 % de CPU), cerrabas
    // la app, y al volver buscaba con 4 hilos al 60 % sin avisar de nada.
    // En una busqueda que dura dias eso es casi la mitad del trabajo tirado.
    sbThreadsPuzzle?.progress = prefs.getInt("puzzle_threads", 3)
    sbCpuPuzzle?.progress    = prefs.getInt("puzzle_cpu", 50)
    powerCard.addView(powerRow)

    // Bajo la potencia: que se aplica al momento y que no.
    //
    // El % de CPU si cambia en caliente, pero el numero de hilos no: eso
    // obliga a reiniciar la busqueda. Sin decirlo, mover "Power" con
    // Kangaroo en marcha parece que hace mas de lo que hace.
    powerCard.addView(TextView(this).apply {
        text = "The CPU % applies right away. Threads, when the search restarts."
        textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    })

    // ── GPU ───────────────────────────────────────────────────────────
    // La gráfica como un trabajador más de Kangaroo, sobre la misma tabla
    // que los hilos de CPU (gpu/gpu_kangaroo.h). Qué gráfica es se pregunta
    // a Vulkan en segundo plano; si no hay Vulkan, el interruptor se apaga.
    //
    // No en todos los móviles compensa: en el Galaxy A34 (Mali-G68) rinde lo
    // que un núcleo; en el A56 (Xclipse 540), del orden de toda la CPU. Por
    // eso es una opción y no va siempre.
    val filaGpu = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
    }
    val colGpu = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    colGpu.addView(TextView(this).apply {
        text = "Use the graphics card (GPU)"
        textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI)
        typeface = AppTheme.medium(context)
    })
    val tvGpu = TextView(this).apply {
        text = "Checking the GPU…"
        textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        setPadding(0, dp(2), dp(8), 0)
    }
    colGpu.addView(tvGpu)
    filaGpu.addView(colGpu)
    val swGpu = android.widget.Switch(this).apply {
        isChecked = prefs.getBoolean("usar_gpu", false)
        isEnabled = false
    }
    filaGpu.addView(swGpu)
    powerCard.addView(filaGpu)
    HunterEngine.setUsarGpu(swGpu.isChecked)
    swGpu.setOnCheckedChangeListener { _, on ->
        prefs.edit().putBoolean("usar_gpu", on).apply()
        HunterEngine.setUsarGpu(on)
        android.widget.Toast.makeText(this,
            if (on) "GPU on: applies when Kangaroo (re)starts" else "GPU off: applies when Kangaroo (re)starts",
            android.widget.Toast.LENGTH_SHORT).show()
    }
    Thread {
        val info = try { HunterEngine.gpuInfo() } catch (e: Throwable) { "" }
        runOnUiThread {
            val p = info.split("|")
            if (p.size >= 5 && p[0].isNotBlank()) {
                tvGpu.text = "${p[0]} · ${p[1]} · Vulkan ${p[2]} · ${p[4]} MB. " +
                             "Kangaroo only; runs next to the CPU threads."
                swGpu.isEnabled = true
            } else {
                tvGpu.text = "No Vulkan GPU on this phone."
                swGpu.isChecked = false; swGpu.isEnabled = false
                HunterEngine.setUsarGpu(false)
            }
        }
    }.start()


    // ── AJUSTE FINO ───────────────────────────────────────────────────
    // Cómo se recorre el rango y el tamaño de lote se tocan una vez y ya:
    // uno sólo afecta a la fuerza bruta y el otro tiene un óptimo medido
    // que ya viene puesto. Ocupaban media pantalla en medio de la tarjeta
    // de Potencia, entre el selector que sí se usa a diario y el botón de
    // arrancar. Van a una sección plegada, con el mismo mecanismo que ya
    // usan "Hex range" y "Tools" en esta misma pestaña.
    //
    // Potencia NO se pliega: es lo que se mira cuando el móvil se calienta
    // o la velocidad baja, y esconderlo detrás de un toque sería cambiar
    // una pantalla cargada por una pantalla incómoda.
    // MATCH_PARENT explícito: el cuerpo de una sección plegable no lo
    // impone, y sin esto el contenedor mide a lo que ocupe su hijo más
    // ancho — los textos de ayuda se partirían a media frase.
    val tuningCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    // ── RECORRIDO DEL RANGO ───────────────────────────────────────────
    tuningCard.addView(Ui.sectionLabel(this, "How the range is walked", topGap = 0))
    tuningCard.addView(Ui.segmented(
        this, listOf("Random" to null, "Sequential" to null), initial = 0
    ) { idx ->
        HunterEngine.setSequential(idx == 1)
        if (HunterEngine.kangarooRunning())
            android.widget.Toast.makeText(this,
                "This only affects brute force: in Kangaroo the path " +
                "is decided by the jump function.",
                android.widget.Toast.LENGTH_LONG).show()
    })
    // El aviso de arriba sólo salía SI tocabas el control Y Kangaroo estaba
    // en marcha. Si lo dejabas puesto y arrancabas, nada te decía que no
    // hace nada. Va fijo en la pantalla.
    tuningCard.addView(TextView(this).apply {
        text = "Brute force only. Kangaroo does not walk the range: " +
               "it hops along the curve using the jump function."
        textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    })

    // ── BATCH SIZE SLIDER ─────────────────────────────────────────────
    //
    // OJO CON EL COMENTARIO QUE HABÍA AQUÍ. Decía que "lotes mayores
    // amortizan mejor la única inversión modular por lote", y por eso se
    // subió el tope del deslizador a 16000. Medido en el banco, en Kangaroo
    // eso es FALSO por encima de ~2048: el conjunto de trabajo se sale de
    // la caché y va peor.
    //
    //     1024  4,21 M saltos/s      8192  4,08 M
    //     2048  4,33 M  <- óptimo   16000  3,69 M   (16 % peor)
    //     4096  4,28 M              32000  3,45 M
    //
    // Kangaroo acota a 256..4096 por eso. El deslizador sigue llegando a
    // 16000 porque el motor de fuerza bruta es otro bucle y ahí no se ha
    // medido lo mismo.
    val batchLabels = listOf(64, 128, 256, 512, 1024, 2048, 4096, 8192, 16000)

    val batchHeaderRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) }
    }
    batchHeaderRow.addView(TextView(this).apply {
        text = "Batch size"
        textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    })
    // El valor iba en azul: un número de ajuste no es información de otro
    // tipo que el resto, sólo es el valor de la fila.
    val tvBatchVal = TextView(this).apply {
        text = "256 claves"
        textSize = AppTheme.SP_BODY
        setTextColor(AppTheme.TXT_PRI)
        typeface = AppTheme.bold(context)
    }
    batchHeaderRow.addView(tvBatchVal)
    tuningCard.addView(batchHeaderRow)

    val sbBatch = SeekBar(this).apply {
        max = batchLabels.size - 1
        progress = 2 // default 1000
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                val size = batchLabels[p]
                tvBatchVal.text = "$size keys"
                HunterEngine.setBatchSize(size)
                prefs.edit().putInt("puzzle_batch", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
    // Restaurar valor guardado
    sbBatch.progress = prefs.getInt("puzzle_batch", 2)
    HunterEngine.setBatchSize(batchLabels[sbBatch.progress])
    tuningCard.addView(sbBatch)

    // Labels del slider
    val batchLabelRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) }
    }
    listOf("64", "", "256", "", "1K", "", "4K", "", "16K").forEach { lbl ->
        batchLabelRow.addView(TextView(this).apply {
            // 8sp: por debajo del mínimo legible de Android, que son 12.
            text = lbl; textSize = AppTheme.SP_MICRO
            setTextColor(AppTheme.TXT_MUTED)
            typeface = AppTheme.body(context)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    }
    tuningCard.addView(batchLabelRow)
    // Medido: en Kangaroo por encima de ~2048 va peor, no mejor. Y el valor
    // se lee al arrancar, asi que moverlo con la busqueda en marcha no hace
    // nada hasta reiniciarla. Las dos cosas son invisibles sin decirlas.
    tuningCard.addView(TextView(this).apply {
        text = "Kangaroo caps it at 4096 and the measured optimum is 2048: " +
               "bigger is worse. Applies when the search restarts."
        textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    })

    page.addView(powerCard)
    page.addView(collapsibleSection(R.drawable.ic_dice, "Fine tuning") {
        addView(tuningCard)
    })
    updatePuzzleLabels()

    // ── HERRAMIENTAS ──────────────────────────────────────────────────
    page.addView(collapsibleSection(R.drawable.ic_gear, "Tools") {
        listOf(
            Triple(R.drawable.ic_search, "Look for public keys",      { auditarClavesPublicas() }),
            Triple(R.drawable.ic_gear,   "Set up for this hardware", { showHardwareInfo() }),
            Triple(R.drawable.ic_export, "Export progress",            { exportPuzzleProgress() }),
            Triple(R.drawable.ic_import, "Import progress",            { importPuzzleProgress() })
        ).forEach { (icon, label, action) ->
            val row = LinearLayout(this@buildPuzzleTab).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(14)); isClickable = true; isFocusable = true
                setOnClickListener { action() }
            }
            row.addView(Ui.icon(this@buildPuzzleTab, icon).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
            })
            row.addView(TextView(this@buildPuzzleTab).apply {
                text = label
                textSize = AppTheme.SP_BODY
                setTextColor(AppTheme.TXT_PRI)
                typeface = AppTheme.body(context)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Ui.icon(this@buildPuzzleTab, R.drawable.ic_chevron, 16, AppTheme.TXT_MUTED))
            addView(row)
        }
    })

    // ── THERMAL & BALANCE ─────────────────────────────────────────────
    val tvThermalPuzzle = TextView(this).apply {
        text = ""; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        visibility = android.view.View.GONE
    }
    tvThermal = tvThermalPuzzle
    page.addView(tvThermalPuzzle)

    // ── START BUTTON ──────────────────────────────────────────────────
    val startBg = android.graphics.drawable.GradientDrawable().apply {
        setColor(AppTheme.ACCENT); cornerRadius = dp(AppTheme.R_KEY).toFloat()
    }
    val stopRed = android.graphics.drawable.GradientDrawable().apply {
        setColor(AppTheme.RED); cornerRadius = dp(AppTheme.R_KEY).toFloat()
    }
    btnPuzzleToggle = Button(this).apply {
        text = "Start puzzle"
        textSize = AppTheme.SP_TITLE
        setTextColor(AppTheme.ON_ACCENT)
        typeface = AppTheme.bold(context)
        isAllCaps = false
        stateListAnimator = null
        background = startBg
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
        ).apply {
            setMargins(dp(AppTheme.PAD_SIDE), dp(20), dp(AppTheme.PAD_SIDE), dp(8))
        }
        setOnClickListener {
            try {
                puzzleMode = true
                HunterEngine.setMode(1)
                doToggle(btnPuzzleToggle)
            } catch (e: Exception) {
                val msg = "${e.javaClass.simpleName}: ${e.message}"
                android.widget.Toast.makeText(this@buildPuzzleTab, msg, android.widget.Toast.LENGTH_LONG).show()
                java.io.File(filesDir, "crash_log.txt").appendText("\nPUZZLE_BTN: $msg\n${e.stackTraceToString()}\n")
            }
        }
    }
    btnPuzzleToggle?.tag = arrayOf(startBg, stopRed)
    page.addView(btnPuzzleToggle)

    // ── BUILD CHIP GROUPS ─────────────────────────────────────────────
    // Group puzzles by ranges of 10
    val groupSize = 10
    var groups = visiblePuzzles.chunked(groupSize)
    var activeGroupIdx = 0
    val groupChips = mutableListOf<TextView>()

    fun applyPuzzleAndCheckBalance(p: PuzzleInfo, chipView: TextView? = null) {
        applyPuzzle(p)
        val puzzlePrefs2 = getSharedPreferences("puzzle_checkpoint", android.content.Context.MODE_PRIVATE)
        val savedKey = puzzlePrefs2.getString("last_key_${p.num}", null)
        val savedTime = puzzlePrefs2.getLong("last_time_${p.num}", 0)
        if (savedKey != null && savedTime > 0) {
            val ts = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(savedTime))
            tvCheckpointLive?.text = "Checkpoint #${p.num} · $ts · ${savedKey.take(12)}…${savedKey.takeLast(6)}"
            tvCheckpointLive?.visibility = android.view.View.VISIBLE
        } else {
            tvCheckpointLive?.text = ""
            tvCheckpointLive?.visibility = android.view.View.GONE
        }
        puzzleSeleccionado = p.num
        tvBalResult.text = "Checking the balance of #${p.num}…"
        comprobarAtajo(p)
        checkPuzzleBalance(p.addr) { bal ->
            runOnUiThread {
                when {
                    bal > 0L -> {
                        if (puzzleSeleccionado != p.num) return@runOnUiThread
                        tvBalResult.text = "${bal / 100_000_000.0} BTC available"
                        tvBalResult.setTextColor(AppTheme.ACCENT)
                    }
                    bal == 0L -> {
                        // La marca se pone siempre: es un hecho sobre el
                        // puzzle, no una preferencia de quien mira.
                        hiddenPuzzles.edit().putBoolean("hidden_${p.num}", true).apply()
                        // En modo prueba se ha elegido a proposito uno ya
                        // resuelto. Esconderlo y saltar a otro seria pelearse
                        // con lo que acaba de pedir el usuario.
                        if (prefs.getBoolean("mostrar_sin_fondos", false)) {
                            tvBalResult.text =
                                "No funds — #${p.num} already solved. Good as a test: " +
                                "the key exists and can be found."
                            tvBalResult.setTextColor(AppTheme.WARN)
                            return@runOnUiThread
                        }
                        tvBalResult.text = "No funds — #${p.num} hidden"
                        tvBalResult.setTextColor(AppTheme.WARN)
                        // Ocultar chip visualmente
                        chipView?.visibility = android.view.View.GONE
                        // Seleccionar el siguiente chip visible
                        var nextSelected = false
                        for (k in 0 until indivRow.childCount) {
                            val c = indivRow.getChildAt(k) as? TextView ?: continue
                            if (c.visibility == android.view.View.VISIBLE && c != chipView) {
                                c.performClick()
                                nextSelected = true
                                break
                            }
                        }
                        if (!nextSelected) {
                            tvPuzzleStatus?.text = "No puzzles available in this group"
                        }
                    }
                    else -> {
                        tvBalResult.text = "No connection — try again"
                        tvBalResult.setTextColor(AppTheme.TXT_SEC)
                    }
                }
            }
        }
    }

    fun buildIndivChips(groupIdx: Int) {
        indivRow.removeAllViews()
        val group = groups.getOrNull(groupIdx) ?: return
        group.forEachIndexed { i, p ->
            val chip = TextView(this).apply {
                text = "#${p.num}"
                textSize = AppTheme.SP_BODY; gravity = Gravity.CENTER
                typeface = if (i == 0) AppTheme.bold(context) else AppTheme.medium(context)
                background = Ui.cardBg(AppTheme.R_CHIP,
                    if (i == 0) AppTheme.TXT_PRI else AppTheme.BG_CARD, context)
                // El numero del puzzle viaja en el tag para que al repintar
                // se pueda saber cual de ellos esta vacio. Sin esto, el
                // primer clic borraba la marca de todos los demas.
                tag = p.num
                setTextColor(when {
                    i == 0            -> AppTheme.BG_DEEP
                    sinFondos(p.num)  -> AppTheme.WARN
                    else              -> AppTheme.TXT_SEC
                })
                layoutParams = LinearLayout.LayoutParams(dp(68), dp(44)).apply { marginEnd = dp(8) }
                isClickable = true; isFocusable = true
                setOnClickListener {
                    for (j in 0 until indivRow.childCount) {
                        val c = indivRow.getChildAt(j) as? TextView ?: continue
                        c.background = Ui.cardBg(AppTheme.R_CHIP, AppTheme.BG_CARD, context)
                        val n = c.tag as? Int
                        c.setTextColor(
                            if (n != null && sinFondos(n)) AppTheme.WARN
                            else AppTheme.TXT_SEC)
                        c.typeface = AppTheme.medium(context)
                    }
                    background = Ui.cardBg(AppTheme.R_CHIP, AppTheme.TXT_PRI, context)
                    setTextColor(AppTheme.BG_DEEP)
                    typeface = AppTheme.bold(context)
                    applyPuzzleAndCheckBalance(p, this)
                }
            }
            indivRow.addView(chip)
        }
        // Auto-select first
        groups.getOrNull(groupIdx)?.firstOrNull()?.let { applyPuzzleAndCheckBalance(it) }
    }

    fun buildGroupChips() {
        groupRow.removeAllViews()
        groupChips.clear()
        groups.forEachIndexed { idx, group ->
            val first = group.first().num
            val last  = group.last().num
            val chip = TextView(this).apply {
                text = "$first–$last"
                textSize = AppTheme.SP_CAPTION; gravity = Gravity.CENTER
                typeface = if (idx == 0) AppTheme.bold(context) else AppTheme.medium(context)
                background = Ui.cardBg(AppTheme.R_CHIP,
                    if (idx == 0) AppTheme.BG_ELEV else AppTheme.BG_CARD, context)
                setTextColor(if (idx == 0) AppTheme.TXT_PRI else AppTheme.TXT_SEC)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
                ).apply { marginEnd = dp(8) }
                setPadding(dp(14), 0, dp(14), 0)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    activeGroupIdx = idx
                    groupChips.forEachIndexed { i, c ->
                        val active = i == idx
                        c.background = Ui.cardBg(AppTheme.R_CHIP,
                            if (active) AppTheme.BG_ELEV else AppTheme.BG_CARD, context)
                        c.setTextColor(if (active) AppTheme.TXT_PRI else AppTheme.TXT_SEC)
                        c.typeface = if (active) AppTheme.bold(context) else AppTheme.medium(context)
                    }
                    buildIndivChips(idx)
                }
            }
            groupChips.add(chip)
            groupRow.addView(chip)
        }
    }

    buildGroupChips()
    buildIndivChips(0)

    btnSinFondos.setOnClickListener {
        prefs.edit().putBoolean("mostrar_sin_fondos",
            !prefs.getBoolean("mostrar_sin_fondos", false)).apply()
        visiblePuzzles = listaVisible().toMutableList()
        groups = visiblePuzzles.chunked(groupSize)
        activeGroupIdx = 0
        pintarBotonSinFondos()
        buildGroupChips()
        buildIndivChips(0)
    }

    // Default puzzle setup
    val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
    val defaultIdx = dayOfYear % visiblePuzzles.size
    applyPuzzle(visiblePuzzles.getOrElse(defaultIdx) { visiblePuzzles.first() })

    // Load checkpoint for default
    val defaultPuzzle = visiblePuzzles.getOrElse(defaultIdx) { visiblePuzzles.first() }
    val puzzlePrefsInit = getSharedPreferences("puzzle_checkpoint", android.content.Context.MODE_PRIVATE)
    val savedKeyInit = puzzlePrefsInit.getString("last_key_${defaultPuzzle.num}", null)
    val savedTimeInit = puzzlePrefsInit.getLong("last_time_${defaultPuzzle.num}", 0)
    if (savedKeyInit != null && savedTimeInit > 0) {
        val ts = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(savedTimeInit))
        tvCheckpointLive?.text = "Checkpoint #${defaultPuzzle.num} · $ts · ${savedKeyInit.take(12)}…${savedKeyInit.takeLast(6)}"
        tvCheckpointLive?.visibility = android.view.View.VISIBLE
    }

    Thread {
        checkPuzzleBalance(defaultPuzzle.addr) { bal ->
            runOnUiThread {
                // Consultar el saldo tarda, y en ese rato el usuario ya suele
                // haber tocado otro chip. Escribir aquí sin comprobarlo pisaba
                // la etiqueta del puzzle que sí había elegido.
                // Dos consultas escriben este mismo TextView: la del chip que
                // pulsas y ésta, la del puzzle por defecto. Sin una marca
                // de a quién pertenece la respuesta, gana la que termine
                // última y acabas viendo el saldo de OTRO puzzle bajo el
                // que tienes seleccionado. Comparar el rango no basta: no
                // cambia hasta que applyPuzzle() lo escribe.
                if (puzzleSeleccionado != defaultPuzzle.num) return@runOnUiThread
                if (puzzleFullStart != defaultPuzzle.start) return@runOnUiThread
                if (bal > 0) {
                    tvBalResult.text = "${bal / 100_000_000.0} BTC available"
                    tvBalResult.setTextColor(ACCENT)
                } else {
                    // Antes ponía "Buscando puzzle con fondos..." y llamaba a
                    // autoSelectPuzzle(), que no busca nada: la autoselección
                    // está desactivada y lo único que hacía era dejar el
                    // estado en "Select a puzzle" con uno ya elegido.
                    tvBalResult.text = "No confirmed funds in #${defaultPuzzle.num}"
                    tvBalResult.setTextColor(AppTheme.WARN)
                }
            }
        }
    }.start()

    scroll.addView(page)
    return scroll
}
