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
        setBackgroundColor(AppTheme.BG_DEEP)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(AppTheme.BG_DEEP)
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

    // ── ESTADO ────────────────────────────────────────────────────────
    //
    // El estado vivía en un punto gris de 8dp en la cabecera, sin texto:
    // había que saberse que el punto significaba algo. Aquí es una frase
    // con el tiempo que lleva corriendo, que es lo primero que quieres
    // saber al abrir la app.
    val statusRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val statusDot = android.view.View(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(AppTheme.TXT_MUTED)
        }
        layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(9) }
    }
    val stateLabel = TextView(this).apply {
        text = "Idle"
        textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context)
    }
    tvScanState = stateLabel
    scanStateDot = statusDot
    statusRow.addView(statusDot); statusRow.addView(stateLabel)
    // El título lo decía la cabecera, que ya no existe: ahora lo dice la
    // página, como todas.
    page.addView(Ui.pageTitle(this, "Scanner", lados = true))
    page.addView(side(statusRow, top = 0, bottom = 18))

    // ── CIFRA PRINCIPAL ───────────────────────────────────────────────
    val speedRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = true
    }
    val wpsFigure = TextView(this).apply {
        text = "0,0"
        textSize = AppTheme.SP_HERO
        setTextColor(AppTheme.TXT_PRI)
        typeface = AppTheme.display(context)
        letterSpacing = -0.045f
    }
    tvWps = wpsFigure
    // La unidad era el literal fijo "K KEYS / SEG" sobre una cifra que
    // getWps() da en claves por segundo sin escalar: 1 843 200 se leía como
    // 1.8 G/s, mil veces la velocidad real. La escala scaleSpeed().
    val wpsUnit = TextView(this).apply {
        text = "K/s"
        textSize = AppTheme.SP_FIGURE
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) }
    }
    tvSpeedUnitScan = wpsUnit
    speedRow.addView(wpsFigure); speedRow.addView(wpsUnit)
    page.addView(side(speedRow))

    // Pico y media: una sola línea. Antes el pico iba alineado a la derecha
    // y la media al centro, ambos a 9sp.
    val peakLine = TextView(this).apply {
        text = ""
        textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context)
    }
    tvPeakWps = peakLine
    val avgHidden = TextView(this).apply { visibility = android.view.View.GONE }
    page.addView(side(peakLine, top = 12, bottom = 24))
    page.addView(avgHidden)

    // ── DOS TARJETAS, NO CUATRO ───────────────────────────────────────
    //
    // Eran cuatro: TOTAL KEYS, RITMO, DATASET y TIEMPO, en una rejilla 2x2
    // con cuatro tratamientos de color distintos. De esas cuatro, el ritmo
    // se entiende mucho mejor dicho en una frase (abajo) y el tiempo ya
    // está en la línea de estado. Quedan las dos que son cifras de verdad.
    fun statCard(label: String, accent: Boolean, last: Boolean = false):
            Pair<LinearLayout, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(ctx = this@buildScanTab)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (!last) marginEnd = dp(AppTheme.GAP) }
        }
        card.addView(TextView(this).apply {
            text = label
            textSize = AppTheme.SP_MICRO
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
        })
        val value = TextView(this).apply {
            text = "0"
            textSize = 24f
            setTextColor(if (accent) AppTheme.ACCENT else AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
            letterSpacing = -0.02f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(7) }
        }
        card.addView(value)
        return card to value
    }

    val (cardKeys, vKeys) = statCard("Keys checked", accent = false)
    val (cardList, vList) = statCard("List loaded", accent = true, last = true)
    tvCount = vKeys
    tvDatasetStat = vList
    vList.text = run {
        val f = if (csvPath.isNotEmpty()) java.io.File(csvPath) else null
        if (f != null && f.exists()) {
            val h = f.length() / 20
            if (h >= 1_000_000) "%.1f M".format(h / 1e6) else "${h / 1000} K"
        } else "—"
    }
    val statRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(cardKeys); addView(cardList)
    }
    page.addView(side(statRow, bottom = 14))

    // ── RITMO, EN LENGUAJE LLANO ──────────────────────────────────────
    //
    // Decía "159B/día" bajo un rótulo que ponía "SESIÓN". Ni era de la
    // sesión ni hay forma de leer "159B" sin pararse a pensar.
    val rateCard = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.cardBg(ctx = this@buildScanTab)
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }
    rateCard.addView(Ui.icon(this, R.drawable.ic_play, 20).apply {
        (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12)
    })
    val rateCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    rateCol.addView(TextView(this).apply {
        text = "At this rate"
        textSize = AppTheme.SP_BODY
        setTextColor(AppTheme.TXT_PRI)
        typeface = AppTheme.body(context)
    })
    val tvRate = TextView(this).apply {
        text = "—"
        textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        setPadding(0, dp(2), 0, 0)
    }
    tvBinInfoRef = tvRate
    rateCol.addView(tvRate)
    rateCard.addView(rateCol)
    page.addView(side(rateCard, bottom = 22))

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
        background = Ui.cardBg(ctx = this@buildScanTab)
    }
    fun settingRow(icon: Int, title: String, primero: Boolean,
                   build: LinearLayout.() -> Unit): TextView {
        if (!primero) settingsCard.addView(android.view.View(this).apply {
            setBackgroundColor(AppTheme.BORDER_C)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { marginStart = dp(18); marginEnd = dp(18) }
        })
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(15))
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
            setPadding(dp(18), 0, dp(18), dp(18))
        }
        body.build()
        settingsCard.addView(body)
        head.setOnClickListener {
            val abriendo = body.visibility == android.view.View.GONE
            body.visibility = if (abriendo) android.view.View.VISIBLE else android.view.View.GONE
            chevron.animate().rotation(if (abriendo) 90f else 0f).setDuration(140).start()
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
            typeface = Typeface.MONOSPACE   // es un nombre de fichero
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvCsvName = tvCsvLocal
        dataRow.addView(btnCsv); dataRow.addView(tvCsvLocal)
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

        val fastRow = LinearLayout(this@buildScanTab).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
            visibility = if (selectedScanMode == 2) android.view.View.GONE else android.view.View.VISIBLE
        }
        fastScanRow = fastRow
        val fastLabels = LinearLayout(this@buildScanTab).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fastLabels.addView(TextView(this@buildScanTab).apply {
            text = "Fast scan"
            textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.body(context)
        })
        // Este modo baja PBKDF2 de 2048 iteraciones a 1. El contador sube
        // muchísimo, pero las seeds resultantes no son las de ningún
        // mnemónico BIP39: es velocidad sin ninguna posibilidad de acierto.
        val tvFastWarn = TextView(this@buildScanTab).apply {
            text = "Benchmark only: with one iteration the seeds are not BIP39, so it cannot find anything."
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.WARN)
            typeface = AppTheme.body(context)
            visibility = if (prefs.getBoolean("fastMode", false))
                android.view.View.VISIBLE else android.view.View.GONE
        }
        fastLabels.addView(tvFastWarn)
        fastRow.addView(fastLabels)
        val fastSwitch = android.widget.Switch(this@buildScanTab).apply {
            isChecked = prefs.getBoolean("fastMode", false)
            setOnCheckedChangeListener { _, c ->
                fastModeEnabled = c
                HunterEngine.setPbkdf2Mode(if (c) 1 else 0)
                prefs.edit().putBoolean("fastMode", c).apply()
                tvFastWarn.visibility = if (c) android.view.View.VISIBLE
                                        else android.view.View.GONE
                if (c) Toast.makeText(this@buildScanTab,
                    "Fast scan: measures speed only, finds no wallets",
                    Toast.LENGTH_LONG).show()
            }
        }
        fastModeEnabled = prefs.getBoolean("fastMode", false)
        // Y DECÍRSELO AL MOTOR. setPbkdf2Mode sólo se llamaba al tocar el
        // interruptor, así que al reabrir la app el interruptor salía en
        // "rápido" —lo lee de preferencias— y el motor seguía en 2048
        // iteraciones. La pantalla decía una cosa y el motor hacía otra, que
        // es justo lo que este ajuste NO se puede permitir: su razón de ser
        // es medir velocidad, y con el motor en normal el número que sale no
        // es el que se cree estar midiendo.
        try { HunterEngine.setPbkdf2Mode(if (fastModeEnabled) 1 else 0) }
        catch (e: Throwable) {}
        fastRow.addView(fastSwitch)
        addView(fastRow)

        // Selector de rutas de derivación. Derivar ambas duplica las
        // derivaciones y los hash160 por candidato; PBKDF2 domina, así que
        // el ahorro es del 2-5%, pero si el dataset sólo tiene un tipo de
        // dirección la mitad del trabajo no sirve para nada.
        addView(TextView(this@buildScanTab).apply {
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
        val cb44 = android.widget.CheckBox(this@buildScanTab).apply {
            text = "BIP44 (1…)"; textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.body(context)
            isChecked = (pathMask and 1) != 0
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cb84 = android.widget.CheckBox(this@buildScanTab).apply {
            text = "BIP84 (bc1q…)"; textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.body(context)
            isChecked = (pathMask and 2) != 0
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun applyPaths(from: android.widget.CheckBox) {
            var m = (if (cb44.isChecked) 1 else 0) or (if (cb84.isChecked) 2 else 0)
            if (m == 0) {           // no dejar desmarcar las dos
                from.isChecked = true
                m = if (from === cb44) 1 else 2
            }
            pathMask = m
            HunterEngine.setBip39Paths(m)
            prefs.edit().putInt("bip39_paths", m).apply()
        }
        cb44.setOnCheckedChangeListener { _, _ -> applyPaths(cb44) }
        cb84.setOnCheckedChangeListener { _, _ -> applyPaths(cb84) }
        pathRow.addView(cb44); pathRow.addView(cb84)
        addView(pathRow)
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

    // ── SECTION: Red Multi-Dispositivo ────────────────────────────────
    tvClusterSummary = settingRow(R.drawable.ic_network, "Multiple devices", primero = false) {
        addView(TextView(this@buildScanTab).apply {
            text = "This IP: ${NetworkManager.getLocalIp(this@buildScanTab)}"
            textSize = AppTheme.SP_CAPTION
            setTextColor(AppTheme.TXT_SEC)
            typeface = Typeface.MONOSPACE   // es una dirección
            setPadding(0, dp(4), 0, dp(14))
        })
        val row1 = LinearLayout(this@buildScanTab).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val netBtn = { txt: String, action: () -> Unit ->
            Button(this@buildScanTab).apply {
                text = txt
                textSize = AppTheme.SP_BODY
                setTextColor(AppTheme.TXT_PRI)
                typeface = AppTheme.medium(context)
                isAllCaps = false
                stateListAnimator = null
                background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) }
                setOnClickListener { action() }
            }
        }
        row1.addView(netBtn("Be master") { startClusterMaster() })
        row1.addView(netBtn("Find master") {
            // Los dos avisos van como argumentos con nombre y no como
            // lambda suelta al final: con dos parámetros de función
            // seguidos, la lambda suelta se engancha al ÚLTIMO, que no es
            // el que uno cree al leerlo.
            NetworkManager.discoverMasters(this@buildScanTab,
                onFound = { ip, _ ->
                    runOnUiThread { android.widget.Toast.makeText(this@buildScanTab, "Master found: $ip", android.widget.Toast.LENGTH_SHORT).show() }
                },
                // Sin esto, no encontrar nada no decía nada: el botón se
                // quedaba mudo y parecía que no hacía nada. Y no encontrar
                // nada es lo NORMAL salvo en la misma WiFi, porque va por
                // difusión y eso no cruza routers, VPN ni datos móviles.
                onFin = { n ->
                    if (n == 0) runOnUiThread {
                        android.widget.Toast.makeText(this@buildScanTab,
                            "No master on this WiFi. From another network, " +
                            "type its address on the network screen.",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                })
        })
        addView(row1)
        val row2 = LinearLayout(this@buildScanTab).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        row2.addView(netBtn("Be worker") { startActivity(android.content.Intent(this@buildScanTab, NetworkActivity::class.java)) })
        row2.addView(netBtn("Connect") { startActivity(android.content.Intent(this@buildScanTab, NetworkActivity::class.java)) })
        addView(row2)
        val tvNetLog = TextView(this@buildScanTab).apply {
            text = ""
            textSize = AppTheme.SP_MICRO
            setTextColor(AppTheme.TXT_SEC)
            typeface = Typeface.MONOSPACE   // es un registro
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_DEEP, context)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(84)
            ).apply { topMargin = dp(12) }
        }
        NetworkManager.onLog = { msg ->
            runOnUiThread {
                val cur = tvNetLog.text.toString().lines().takeLast(5)
                tvNetLog.text = (cur + listOf(msg)).joinToString("\n")
            }
        }
        addView(tvNetLog)
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
            c.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (on) AppTheme.BG_ELEV else AppTheme.BG_CARD)
                cornerRadius = dp(AppTheme.R_INNER).toFloat()
                if (on) setStroke(dp(2), AppTheme.ACCENT)
            }
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
                    fastScanRow?.visibility =
                        if (selectedScanMode == 2) android.view.View.GONE
                        else android.view.View.VISIBLE
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
    val startBg = GradientDrawable().apply {
        setColor(AppTheme.ACCENT)
        cornerRadius = dp(AppTheme.R_CARD).toFloat()
    }
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
            setMargins(dp(AppTheme.PAD_SIDE), dp(18), dp(AppTheme.PAD_SIDE), dp(8))
        }
        setOnClickListener {
            puzzleMode = false
            HunterEngine.setMode(selectedScanMode)
            doToggle(btnToggle)
        }
    }
    btnToggle?.tag = arrayOf(startBg, stopRed)
    page.addView(btnToggle)

    scroll.addView(page)
    return scroll
}
