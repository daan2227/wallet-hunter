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
 * La pestana Scanner: busqueda por fuerza bruta contra el dataset.
 *
 * Es una funcion de extension de MainActivity: estaba dentro de ese fichero,
 * que pasaba de 6.000 lineas. El cuerpo no cambia.
 */

// ── BUILD SCAN TAB ────────────────────────────────────────────────────────
internal fun MainActivity.buildScanTab(): ScrollView {
    val ACCENT  = AppTheme.ACCENT
    val ACCENT2 = AppTheme.BLUE

    val scroll = ScrollView(this).apply {
        background = AppTheme.fondoApp()
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(80))
    }

    fun side(v: android.view.View, top: Int = 0, bottom: Int = 0) = v.apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(AppTheme.PAD_SIDE), dp(top), dp(AppTheme.PAD_SIDE), dp(bottom))
        }
    }

    // ── CIFRA PRINCIPAL SOBRE EL ROSETÓN ("Security print") ──────────
    //
    // El título y las pestañas los pone la cabecera de Hunt. Aquí va lo que se mira desde lejos:
    // la velocidad, en oro, centrada sobre el rosetón de guilloche, que gira mientras el motor
    // corre y se para con él; debajo, la media y el estado.
    val wpsFigure = TextView(this).apply {
        text = "0,0"
        textSize = 76f
        setTextColor(AppTheme.ACCENT)
        typeface = AppTheme.display(context)
        letterSpacing = -0.04f
        includeFontPadding = false
    }
    tvWps = wpsFigure
    // La unidad era el literal fijo "K KEYS / SEG" sobre una cifra que
    // getWps() da en claves por segundo sin escalar: 1 843 200 se leía como
    // 1.8 G/s, mil veces la velocidad real. La escala scaleSpeed().
    val wpsUnit = TextView(this).apply {
        text = "K/s"
        textSize = AppTheme.SP_FIGURE
        setTextColor(AppTheme.TXT_PRI)
        typeface = AppTheme.title(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) }
    }
    tvSpeedUnitScan = wpsUnit
    val speedRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = true
        gravity = Gravity.CENTER_HORIZONTAL
        addView(wpsFigure); addView(wpsUnit)
    }
    // Pico y media: una sola línea, bajo la cifra.
    val peakLine = TextView(this).apply {
        text = "keys per second"
        textSize = AppTheme.SP_BODY
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, 0)
    }
    tvPeakWps = peakLine
    val avgHidden = TextView(this).apply { visibility = android.view.View.GONE }
    // Estado: punto y frase con el tiempo que lleva ("Searching · 12 h 04 m").
    val (statusDot, stateLabel) = Ui.puntoEstado(this)
    stateLabel.text = "Idle"
    stateLabel.setTextColor(AppTheme.TXT_SEC)
    tvScanState = stateLabel
    scanStateDot = statusDot
    val statusRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(14), 0, 0)
        addView(statusDot); addView(stateLabel)
    }
    val heroCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(speedRow); addView(peakLine); addView(statusRow)
    }
    val roseton = Roseton(this) { try { HunterEngine.isRunning() && !puzzleMode } catch (e: Throwable) { false } }
        // Más deprisa cuanto más rápido va el motor (escala logarítmica: 1 k/s ≈ 0,75; 1 M/s ≈ 1,5).
        .apply { ritmo = { (Math.log10(HunterEngine.getWps().coerceAtLeast(1.0)) / 4.0).toFloat() } }
    page.addView(Ui.heroe(this, roseton, heroCol, alto = 250))
    page.addView(avgHidden)

    // ── MICROIMPRESIÓN Y FILAS ────────────────────────────────────────
    //
    // Eran dos tarjetas ("Keys checked", "List loaded") y una tercera con el ritmo. Ahora son filas
    // bajo una franja de microimpresión con los parámetros del motor: se leen como una ficha, no
    // como tres cajas sueltas.
    val csvFile = if (csvPath.isNotEmpty()) java.io.File(csvPath) else null
    page.addView(side(Ui.microimpresion(this, listOfNotNull(
        csvFile?.takeIf { it.exists() }?.name,
        if (selectedScanMode == 2) "direct key" else "bip39",
        "secp256k1 arm64 asm", "neon hash160x4"
    ).joinToString("  ·  "))))

    val datos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val (filaKeys, vKeys) = Ui.filaDato(this, "Keys checked")
    val (filaList, vList) = Ui.filaDato(this, "Addresses loaded")
    val (filaRate, tvRate) = Ui.filaDato(this, "At this rate", ultima = true)
    vKeys.text = "0"
    tvCount = vKeys
    tvDatasetStat = vList
    tvBinInfoRef = tvRate
    vList.text = run {
        val f = csvFile
        if (f != null && f.exists()) {
            // Lo que el motor tiene cargado; si aún no, una estimación por el
            // tamaño: 9 bytes por dirección en el índice nuevo, 20 en el viejo.
            val cargadas = try { HunterEngine.getCsvCount() } catch (e: Throwable) { 0L }
            val h = if (cargadas > 0) cargadas
                    else if (f.name.endsWith(".bin") && f.length() % 9 == 0L) f.length() / 9
                    else f.length() / 20
            if (h >= 1_000_000) "%.1f M".format(h / 1e6) else "${h / 1000} K"
        } else "—"
    }
    datos.addView(filaKeys); datos.addView(filaList); datos.addView(filaRate)
    page.addView(side(datos, top = 6, bottom = 14))
    // Aquí va el botón de arrancar/parar (se crea abajo): en la primera pantalla, bajo las filas,
    // y no al final de la página tras los ajustes.
    val sitioBoton = page.childCount

    // Estado vacío: sin lista cargada el motor no puede encontrar nada, así que
    // en vez de dejar la cifra en 0 muda, se dice qué falta y se ofrece cargar.
    val vacio = Ui.estadoVacio(this, R.drawable.ic_scan,
        "No address list loaded",
        "Load a list of addresses to scan against — without one the engine has " +
        "nothing to compare and can't find anything.\n\nTap to load a file.")
    vacio.setOnClickListener { Ui.pulso(it); pickCsv() }
    vacio.visibility = if (HunterEngine.isCsvLoaded()) android.view.View.GONE
                       else android.view.View.VISIBLE
    vacioScan = vacio
    page.addView(side(vacio, bottom = 22))

    // Vistas que el motor sigue actualizando pero que ya no se enseñan:
    // el tiempo está en la línea de estado y el resto nunca se leía.
    tvTime      = TextView(this).apply { visibility = android.view.View.GONE }
    tvKps       = tvWps
    tvRam       = TextView(this).apply { visibility = android.view.View.GONE }
    tvBattery   = TextView(this).apply { visibility = android.view.View.GONE }
    tvTemp      = TextView(this).apply { visibility = android.view.View.GONE }
    tvMatches   = TextView(this).apply { visibility = android.view.View.GONE }
    tvFooter    = TextView(this).apply { visibility = android.view.View.GONE }
    tvStatus    = TextView(this).apply { visibility = android.view.View.GONE }
    listOf(tvTime, tvRam, tvBattery, tvFooter, tvStatus).forEach {
        it?.let { v -> page.addView(v) }
    }

    // ── AJUSTES: dos filas en UNA tarjeta ─────────────────────────────
    //
    // Eran dos tarjetas plegables sueltas, cada una ocupando su franja
    // entera. En el diseño son dos filas dentro de una tarjeta, con su
    // valor actual a la derecha: se ve de un vistazo cómo está configurado
    // el motor sin tener que abrir nada.
    val settingsCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // Sin caja (Security print): un filete arriba y otro abajo.
        background = Ui.lineas(Ui.filete(oro = false), arriba = true, abajo = true)
    }
    fun settingRow(icon: Int, title: String, primero: Boolean,
                   build: LinearLayout.() -> Unit): TextView {
        if (!primero) settingsCard.addView(android.view.View(this).apply {
            setBackgroundColor(AppTheme.BORDER_C)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { marginStart = 0; marginEnd = 0 }
        })
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(15), 0, dp(15))
            isClickable = true; isFocusable = true
        }
        head.addView(Ui.icon(this, icon, 19).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
        })
        head.addView(TextView(this).apply {
            text = title
            textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.body(context)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val value = TextView(this).apply {
            text = ""
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
        }
        head.addView(value)
        val chevron = Ui.icon(this, R.drawable.ic_chevron, 16, AppTheme.TXT_MUTED).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
        }
        head.addView(chevron)
        settingsCard.addView(head)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(0, 0, 0, dp(18))
        }
        body.build()
        settingsCard.addView(body)
        head.setOnClickListener {
            val abriendo = body.visibility == android.view.View.GONE
            val raiz = (settingsCard.parent as? android.view.ViewGroup) ?: settingsCard
            android.transition.TransitionManager.beginDelayedTransition(raiz,
                android.transition.AutoTransition().apply {
                    duration = 180
                    interpolator = android.view.animation.DecelerateInterpolator()
                })
            body.visibility = if (abriendo) android.view.View.VISIBLE else android.view.View.GONE
            chevron.animate().rotation(if (abriendo) 90f else 0f).setDuration(180).start()
        }
        return value
    }

    // ── SECTION: Config Hardware ──────────────────────────────────────
    tvEngineSummary = settingRow(R.drawable.ic_gear, "Engine", primero = true) {
        addView(TextView(this@buildScanTab).apply {
            text = "Dataset"; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, dp(4), 0, dp(10))
        })
        val dataRow = LinearLayout(this@buildScanTab).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        btnCsv = Button(this@buildScanTab).apply {
            text = "Load"
            textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.medium(context)
            isAllCaps = false
            stateListAnimator = null
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
            setPadding(dp(18), 0, dp(18), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
            setOnClickListener { pickCsv() }
        }
        val tvCsvLocal = TextView(this@buildScanTab).apply {
            text = if (csvPath.isNotEmpty() && java.io.File(csvPath).exists())
                java.io.File(csvPath).name else "No file"
            setTextColor(AppTheme.TXT_SEC)
            textSize = AppTheme.SP_CAPTION
            typeface = AppTheme.mono(context)   // es un nombre de fichero
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvCsvName = tvCsvLocal
        val btnClear = Button(this@buildScanTab).apply {
            text = "Clear"; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context)
            isAllCaps = false; stateListAnimator = null
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(8) }
            setOnClickListener { clearCsv() }
        }
        dataRow.addView(btnCsv); dataRow.addView(tvCsvLocal); dataRow.addView(btnClear)
        addView(dataRow)

        // Aquí había una segunda caja con "📦 utxos.bin · N hashes · N MB",
        // repitiendo lo que ya dicen el nombre de fichero de arriba y la
        // tarjeta DATASET. Peor: hacía "tvDatasetStat = tvBinInfo", pisando
        // la referencia a la tarjeta, así que updateUI() escribía el recuento
        // en esta caja y la tarjeta se quedaba con su texto inicial —de ahí
        // el "—" que se veía arriba con el dataset cargado—.

        addView(TextView(this@buildScanTab).apply {
            text = "Threads"; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, dp(18), 0, dp(4))
        })
        tvThreads = TextView(this@buildScanTab).apply {
            setTextColor(AppTheme.TXT_PRI); textSize = AppTheme.SP_BODY
            typeface = AppTheme.medium(context)
        }
        addView(tvThreads)
        sbThreads = SeekBar(this@buildScanTab).apply {
            max = 7; progress = prefs.getInt("threads", 3)
            setOnSeekBarChangeListener(mkSbl { updateLabels() })
        }
        addView(sbThreads)

        addView(TextView(this@buildScanTab).apply {
            text = "CPU limit"; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, dp(18), 0, dp(4))
        })
        tvCpu = TextView(this@buildScanTab).apply {
            setTextColor(AppTheme.TXT_PRI); textSize = AppTheme.SP_BODY
            typeface = AppTheme.medium(context)
        }
        addView(tvCpu)
        sbCpu = SeekBar(this@buildScanTab).apply {
            max = 90; progress = prefs.getInt("cpu", 70)
            setOnSeekBarChangeListener(mkSbl {
                updateLabels()
                // Por Termico y no directo al motor: el gobernador tiene que
                // saber lo que ha pedido el usuario para poder devolverselo
                // cuando el movil se enfrie. Sin el guardia de isRunning
                // porque setCpuLimit con el motor parado solo guarda un
                // atomico, y asi el ajuste vale tambien para el siguiente
                // arranque.
                Termico.pedir((sbCpu?.progress ?: 70) + 10)
            })
        }
        addView(sbCpu)

        // Selector de rutas de derivación. Derivar ambas duplica las
        // derivaciones y los hash160 por candidato; PBKDF2 domina, así que
        // el ahorro es del 2-5%, pero si el dataset sólo tiene un tipo de
        // dirección la mitad del trabajo no sirve para nada.
        // Las rutas solo existen en el modo BIP39: en clave directa no hay
        // frase de la que derivar, y verlas ahí hacía pensar que se usaban.
        val rutasBox = LinearLayout(this@buildScanTab).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (selectedScanMode == 2) android.view.View.GONE else android.view.View.VISIBLE
        }
        rutasScanBox = rutasBox
        rutasBox.addView(TextView(this@buildScanTab).apply {
            text = "Derivation paths"; textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, dp(20), 0, dp(4))
        })
        val pathRow = LinearLayout(this@buildScanTab).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var pathMask = prefs.getInt("bip39_paths", 3)
        // Cada ruta: su bit en el motor, el texto y el tipo de dirección que
        // busca. BIP49 (3…) y BIP86 (bc1p…) solo sirven si la lista los trae:
        // el índice de 9 bytes sí (P2SH y P2TR).
        val rutas = listOf(
            Triple(1, "BIP44 (1…)", "44"), Triple(2, "BIP84 (bc1q…)", "84"),
            Triple(4, "BIP49 (3…)", "49"), Triple(8, "BIP86 (bc1p…)", "86"))
        val casillas = rutas.map { (bit, texto, _) ->
            android.widget.CheckBox(this@buildScanTab).apply {
                text = texto; textSize = AppTheme.SP_BODY
                setTextColor(AppTheme.TXT_PRI)
                typeface = AppTheme.body(context)
                isChecked = (pathMask and bit) != 0
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        fun applyPaths(from: android.widget.CheckBox) {
            var m = 0
            casillas.forEachIndexed { i, cb -> if (cb.isChecked) m = m or rutas[i].first }
            if (m == 0) {           // no dejar desmarcar todas
                from.isChecked = true
                m = rutas[casillas.indexOf(from)].first
            }
            pathMask = m
            HunterEngine.setBip39Paths(m)
            prefs.edit().putInt("bip39_paths", m).apply()
        }
        casillas.forEach { cb -> cb.setOnCheckedChangeListener { _, _ -> applyPaths(cb) } }
        pathRow.addView(casillas[0]); pathRow.addView(casillas[1])
        rutasBox.addView(pathRow)
        rutasBox.addView(LinearLayout(this@buildScanTab).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(casillas[2]); addView(casillas[3])
        })
        addView(rutasBox)
        try { HunterEngine.setBip39Paths(pathMask) } catch (e: Throwable) {}

        // Actualizar visibilidad del fastRow cuando cambia el modo


        listOf(
            // El icono va a un TextView de 26dp: el texto entero se recortaba
            // a "🐕/Wat". El estado va en la etiqueta, que se reescribe al
            // pulsar en vez de esperar a que se reconstruya la pestaña.
            Triple(R.drawable.ic_clock, watchdogLabel(), { lbl: TextView ->
                watchdogEnabled = !watchdogEnabled
                prefs.edit().putBoolean("watchdog", watchdogEnabled).apply()
                lbl.text = watchdogLabel()
                android.widget.Toast.makeText(this@buildScanTab,
                    if (watchdogEnabled) "Watchdog on" else "Watchdog off",
                    android.widget.Toast.LENGTH_SHORT).show()
            }),
            Triple(R.drawable.ic_gear, "Set up for this hardware", { _: TextView -> showHardwareInfo() })
        ).forEach { (ic, lbl, action) ->
            val tvLabel = TextView(this@buildScanTab).apply {
                text = lbl
                textSize = AppTheme.SP_BODY
                setTextColor(AppTheme.TXT_PRI)
                typeface = AppTheme.body(context)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val row = LinearLayout(this@buildScanTab).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(14)); isClickable = true; isFocusable = true
                setOnClickListener { action(tvLabel) }
            }
            row.addView(Ui.icon(this@buildScanTab, ic).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
            })
            row.addView(tvLabel)
            row.addView(Ui.icon(this@buildScanTab, R.drawable.ic_chevron, 16, AppTheme.TXT_MUTED))
            addView(row)
        }
    }

    // La tarjeta de ajustes va aquí, entre el ritmo y el modo.
    page.addView(side(settingsCard, bottom = 22))

    // ── MODO DE ESCANEO ───────────────────────────────────────────────
    //
    // Eran dos tarjetas con borde y tres colores de acento repartidos entre
    // ellas. Y tenía los listeners DUPLICADOS: se asignaban en el bucle que
    // construye las pastillas y otra vez en un segundo bucle justo después,
    // que pisaba al primero, así que el código de arriba no se ejecutaba.
    page.addView(side(Ui.sectionLabel(this, "Mode"), bottom = 0))

    val scanModeValues = listOf(0, 2)
    val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val modeCards = mutableListOf<LinearLayout>()

    fun paintModes(sel: Int) {
        modeCards.forEachIndexed { i, c ->
            val on = i == sel
            // Sin recuadros (Security print): el elegido, subrayado en oro como una pestaña; el otro,
            // con el filete fino.
            c.background = if (on) Ui.lineas(AppTheme.ACCENT, arriba = false, abajo = true, grosor = dp(2))
                           else Ui.lineas(Ui.filete(oro = false), arriba = false, abajo = true)
            c.isSelected = on
            (c.getChildAt(0) as TextView).apply {
                setTextColor(if (on) AppTheme.ACCENT else AppTheme.TXT_SEC)
                typeface = if (on) AppTheme.bold(context) else AppTheme.medium(context)
            }
            (c.getChildAt(1) as TextView).setTextColor(
                if (on) AppTheme.ACCENT else AppTheme.TXT_SEC)
        }
    }
    // El subtítulo de "Direct key" decía "10× más rápido". Medido en el
    // banco de pruebas (tools/ec-harness), por candidato:
    //
    //   BIP39         2.074 µs   (PBKDF2 2048 + derivación BIP32 + 5 hash160)
    //   Clave directa     1,45 µs
    //   proporción       1.431×
    //
    // O sea que la etiqueta se quedaba corta 143 veces. No es un detalle de
    // presentación: con "10×" alguien puede pensar que BIP39 sale a cuenta,
    // y en realidad cada frase semilla cuesta lo que mil cuatrocientas
    // claves. Casi todo se va en el PBKDF2 de 2048 vueltas, que es
    // deliberadamente lento por diseño del propio BIP39.
    listOf("BIP39" to "Seed phrases", "Direct key" to "~1,400× faster")
        .forEachIndexed { i, (name, sub2) ->
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i == 0) marginEnd = dp(10) }
            }
            c.addView(TextView(this).apply { text = name; textSize = AppTheme.SP_BODY })
            c.addView(TextView(this).apply {
                text = sub2
                textSize = AppTheme.SP_MICRO
                typeface = AppTheme.body(context)
                setPadding(0, dp(3), 0, 0)
            })
            c.setOnClickListener {
                selectedScanMode = scanModeValues[i]
                paintModes(i)
                // El escaneo rápido sólo aplica a BIP39: en clave directa no
                // hay derivación que saltarse.
                try {
                    val vis = if (selectedScanMode == 2) android.view.View.GONE
                              else android.view.View.VISIBLE
                    rutasScanBox?.visibility = vis
                } catch (e: Exception) {}
            }
            modeCards.add(c); modeRow.addView(c)
        }
    paintModes(scanModeValues.indexOf(selectedScanMode).coerceAtLeast(0))
    page.addView(side(modeRow, top = 10, bottom = 18))

    // ── INICIAR / DETENER ─────────────────────────────────────────────
    //
    // Era un rectángulo de #1A1A1A con un filo blanco: exactamente el mismo
    // peso visual que las tarjetas que tiene encima. El único botón que
    // pone la app en marcha tiene que ser lo más sólido de la pantalla.
    val startBg = Ui.botonAccento(this, AppTheme.R_CARD)
    // Parar no es la acción principal, es la destructiva: fondo tenue y
    // texto rojo. Antes era un rectángulo rojo entero, que pide que lo
    // pulses.
    val stopRed = GradientDrawable().apply {
        setColor(AppTheme.BG_STOP)
        cornerRadius = dp(AppTheme.R_CARD).toFloat()
    }

    btnToggle = Button(this).apply {
        text = s.start
        textSize = AppTheme.SP_BODY + 1f
        setTextColor(AppTheme.ON_ACCENT)
        typeface = AppTheme.bold(context)
        isAllCaps = false
        stateListAnimator = null   // sin la sombra de Material sobre el plano
        background = startBg
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
        ).apply {
            setMargins(dp(AppTheme.PAD_SIDE), dp(4), dp(AppTheme.PAD_SIDE), dp(22))
        }
        setOnClickListener {
            Ui.pulso(this)
            puzzleMode = false
            HunterEngine.setMode(selectedScanMode)
            doToggle(btnToggle)
        }
    }
    btnToggle?.tag = arrayOf(startBg, stopRed)
    page.addView(btnToggle, sitioBoton)

    // Mientras escanea, estos parámetros quedan bloqueados (hasta pulsar Stop).
    scannerConfigViews = listOf(settingsCard, modeRow)
    refrescarBloqueos()

    scroll.addView(page)
    return scroll
}
