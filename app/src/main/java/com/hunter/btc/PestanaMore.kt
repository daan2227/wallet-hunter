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
 * La pestana More: ajustes, baul, copias, depuracion.
 *
 * Es una funcion de extension de MainActivity: estaba dentro de ese fichero,
 * que pasaba de 6.000 lineas. El cuerpo no cambia.
 */

// ── MORE ─────────────────────────────────────────────────────────────────
/**
 * Lo que se usa menos: Recovery, Debug y el tema.
 *
 * Estaba todo en el menú lateral, mezclado con las secciones de todos los
 * días. Aquí va agrupado —herramientas por un lado, la app por otro— y
 * cada fila dice para qué sirve, que en el menú no lo decía.
 */
internal fun MainActivity.buildMoreTab(): ScrollView {
    val scroll = ScrollView(this).apply {
        background = AppTheme.fondoApp()
        visibility = android.view.View.GONE
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(AppTheme.PAD_SIDE), 0, dp(AppTheme.PAD_SIDE), dp(32))
    }
    page.addView(Ui.pageTitle(this, "More", lados = false))

    fun grupo(titulo: String): LinearLayout {
        page.addView(TextView(this).apply {
            text = titulo
            textSize = AppTheme.SP_CAPTION + 1f
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), dp(6), 0, dp(8)) }
        })
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(AppTheme.R_CARD + 2, AppTheme.BG_CARD, context)
            // Para que el toque de la primera y la última fila no se salga
            // de las esquinas redondeadas.
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        }
        page.addView(caja)
        return caja
    }

    fun fila(caja: LinearLayout, icono: Int, titulo: String, detalle: String?,
             valor: String?, accion: () -> Unit) {
        if (caja.childCount > 0) caja.addView(android.view.View(this).apply {
            setBackgroundColor(AppTheme.BG_ELEV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { marginStart = dp(70) }
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(12), dp(14), dp(12))
            isClickable = true; isFocusable = true
            foreground = Ui.toque()
            contentDescription = if (detalle != null) "$titulo. $detalle" else titulo
            setOnClickListener { accion() }
        }
        row.addView(FrameLayout(this).apply {
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_ELEV, context)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            addView(android.widget.ImageView(this@buildMoreTab).apply {
                setImageResource(icono)
                setColorFilter(AppTheme.TXT_PRI)
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            })
        })
        val textos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) }
        }
        textos.addView(TextView(this).apply {
            text = titulo
            textSize = AppTheme.SP_BODY + 2f
            setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.bold(context)
        })
        if (detalle != null) textos.addView(TextView(this).apply {
            text = detalle
            textSize = AppTheme.SP_CAPTION + 1f
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textos)
        if (valor != null) row.addView(TextView(this).apply {
            text = valor
            textSize = AppTheme.SP_BODY
            setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, 0, dp(6), 0)
        })
        row.addView(Ui.icon(this, R.drawable.ic_chevron, 18, AppTheme.TXT_SEC))
        caja.addView(row)
    }

    // Fila con interruptor a la derecha (para ajustes de sí/no). Tocar toda la
    // fila lo cambia, no solo el interruptor.
    fun filaSwitch(caja: LinearLayout, titulo: String, detalle: String?,
                   inicial: Boolean, onChange: (Boolean) -> Unit) {
        if (caja.childCount > 0) caja.addView(android.view.View(this).apply {
            setBackgroundColor(AppTheme.BG_ELEV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(16) }
        })
        val sw = android.widget.Switch(this).apply {
            isChecked = inicial
            val on = AppTheme.ACCENT; val off = AppTheme.TXT_MUTED
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(on, AppTheme.TXT_SEC))
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf((on and 0x00FFFFFF) or (0x80 shl 24), (off and 0x00FFFFFF) or (0x60 shl 24)))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(12), dp(14), dp(12))
            isClickable = true; isFocusable = true
            foreground = Ui.toque()
            contentDescription = if (detalle != null) "$titulo. $detalle" else titulo
            setOnClickListener { sw.toggle() }
        }
        val textos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textos.addView(TextView(this).apply {
            text = titulo; textSize = AppTheme.SP_BODY + 2f
            setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.bold(context)
        })
        if (detalle != null) textos.addView(TextView(this).apply {
            text = detalle; textSize = AppTheme.SP_CAPTION + 1f
            setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textos)
        row.addView(sw)
        caja.addView(row)
    }

    val avisos = grupo("Notifications")
    filaSwitch(avisos, "Notifications", "Alert when a key is found", Avisos.notifOn(this)) { on ->
        Avisos.setNotif(this, on)
        // Si lo enciende pero el sistema las tiene bloqueadas, llévale a los ajustes.
        if (on && Build.VERSION.SDK_INT >= 33 && Avisos.faltaPermiso(this)) Avisos.abrirAjustes(this)
    }
    filaSwitch(avisos, "Payments on watched addresses",
        "Every ~30 min, ask the network about your watched and wallet addresses (via Tor if enabled in Privacy)",
        AvisosMovimientos.activos(this)) { on -> AvisosMovimientos.poner(this, on) }
    filaSwitch(avisos, "Vibrate on find", null, Avisos.vibrarOn(this)) { Avisos.setVibrar(this, it) }
    filaSwitch(avisos, "Coin sound on find", "Play a sound when a key is found",
        Avisos.sonidoOn(this)) { on ->
        Avisos.setSonido(this, on)
        if (on) Sonido.moneda(this)   // muestra cómo suena al activarlo
    }

    val privacidad = grupo("Privacy")
    filaSwitch(privacidad, "Block screenshots",
        "Hide the app in recent apps and stop screen capture", PantallaSegura.activa(this)) { on ->
        PantallaSegura.poner(this, on)
        PantallaSegura.aplicar(this)
    }

    filaSwitch(privacidad, "Balance lookups through Tor",
        "Balances, fees, broadcast, Electrum, light-client peers and updates go through the SOCKS5 proxy " +
        "(${RedPrivada.direccion(this)}, Orbot). If Tor isn't running they fail: never direct.",
        RedPrivada.activa()) { on -> RedPrivada.poner(this, on) }
    fila(privacidad, R.drawable.ic_gear, "Tor proxy address", "host:port of the SOCKS5 proxy",
         RedPrivada.direccion(this)) {
        val campo = Ui.input(this, "127.0.0.1:9050", mono = true).apply { setText(RedPrivada.direccion(this@buildMoreTab)) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Tor proxy address").setView(campo)
            .setPositiveButton("Save") { _, _ ->
                val t = campo.text.toString().trim()
                if (CoreConfig.proxyValido(t)) { RedPrivada.ponerDireccion(this, t); recreate() }
                else Toast.makeText(this, "Use host:port, e.g. 127.0.0.1:9050", Toast.LENGTH_LONG).show()
            }.setNegativeButton("Cancel", null).show()
    }

    val rendimiento = grupo("Performance")
    // Pasos redondos de %, no de uno en uno: para segundo plano lo que importa
    // es el extremo bajo, así que hay más escalones ahí.
    val PASOS = intArrayOf(5, 10, 15, 25, 50, 75, 100)
    val ahorroPct0 = prefs.getInt("modo_ahorro_pct", Termico.ECO_PCT).coerceIn(1, 100)
    fun idxPaso(pct: Int) = PASOS.indexOfFirst { it >= pct }.let { if (it < 0) PASOS.size - 1 else it }
    val idx0 = idxPaso(ahorroPct0)

    // Etiqueta + deslizador para ELEGIR el techo de CPU en segundo plano.
    val lblTecho = TextView(this).apply {
        text = "Background CPU limit · ${PASOS[idx0]} %"
        textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI)
        typeface = AppTheme.bold(context)
    }
    val sbTecho = android.widget.SeekBar(this).apply {
        max = PASOS.size - 1; progress = idx0
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, u: Boolean) {
                lblTecho.text = "Background CPU limit · ${PASOS[p.coerceIn(0, PASOS.size - 1)]} %"
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {
                val v = PASOS[(s?.progress ?: idx0).coerceIn(0, PASOS.size - 1)]
                prefs.edit().putInt("modo_ahorro_pct", v).apply()
                Termico.ponerTecho(v)
                pintarTermico()
            }
        })
    }
    val cajaTecho = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(2), dp(16), dp(14))
        addView(lblTecho)
        addView(sbTecho)
        addView(TextView(this@buildMoreTab).apply {
            text = "Only applies while Background mode is on."
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context); setPadding(0, dp(2), 0, 0)
        })
    }
    fun habilitarTecho(on: Boolean) { sbTecho.isEnabled = on; cajaTecho.alpha = if (on) 1f else 0.4f }

    filaSwitch(rendimiento, "Background mode",
        "For long runs: caps the engine CPU so the phone stays cool and the " +
        "battery lasts. Applies right away and to the next search.",
        prefs.getBoolean("modo_ahorro", false)) { on ->
        prefs.edit().putBoolean("modo_ahorro", on).apply()
        Termico.modoAhorro(on)
        habilitarTecho(on)
        pintarTermico()
    }
    rendimiento.addView(cajaTecho)
    habilitarTecho(prefs.getBoolean("modo_ahorro", false))

    val claves = grupo("Tools")
    fila(claves, R.drawable.ic_search, "Tools",
         "Addresses, Hex↔WIF, sign/verify, xpub, inspector, balance, sweep, vanity, split, QR, brainwallet, nonce", null) {
        startActivity(Intent(this, PubkeyToolsActivity::class.java))
    }
    fila(claves, R.drawable.ic_wallet, "Lightning (experimental)", "Own LDK node: invoices, payments, channels",
         if (Ln.motor != null) "On" else null) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Lightning network")
            .setItems(arrayOf("Mainnet (real bitcoin)", "Testnet")) { _, i ->
                startActivity(Intent(this, LightningActivity::class.java).putExtra(LightningActivity.EXTRA_TESTNET, i == 1))
            }.show()
    }
    fila(claves, R.drawable.ic_wallet, "Remote Lightning node (NWC)", "Control your own node with Nostr Wallet Connect",
         null) { startActivity(Intent(this, NwcActivity::class.java)) }
    // Pool del puzzle: barrer trozos que reparte un servidor (tools/pool/servidor.py) y demostrarlo.
    fila(claves, R.drawable.ic_puzzle, "Puzzle pool", "Scan ranges handed out by a pool server, with proof of work",
         if (PoolPuzzle.activo()) "On" else null) {
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val etUrl = Ui.input(this, "https://pool.example.com", mono = true).apply { setText(PoolPuzzle.url(this@buildMoreTab)) }
        val tvEstado = TextView(this).apply { textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); setPadding(0, dp(10), 0, 0) }
        caja.addView(TextView(this).apply {
            text = "The server splits the puzzle range into pieces and plants a few proof addresses in each. " +
                   "Your phone scans its piece (sequential mode) and sends back the proof keys. If the puzzle key " +
                   "appears, it goes to the pool AND to your vault. Uses the same engine as the Puzzle tab."
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        })
        caja.addView(etUrl); caja.addView(tvEstado)
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val refresco = object : Runnable { override fun run() { tvEstado.text = PoolPuzzle.estado; h.postDelayed(this, 1500) } }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Puzzle pool").setView(caja)
            .setNegativeButton("Close", null)
            .setPositiveButton(if (PoolPuzzle.activo()) "Stop" else "Start", null).create()
        dlg.setOnShowListener {
            h.post(refresco)
            val b = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            b.setOnClickListener {
                if (PoolPuzzle.activo()) { PoolPuzzle.detener(); b.text = "Start"; return@setOnClickListener }
                val u = etUrl.text.toString().trim()
                if (!PoolPuzzle.urlValida(u)) { Toast.makeText(this, "Use https:// (or http:// to a .onion address)", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                PoolPuzzle.ponerUrl(this, u)
                val hilos = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)
                PoolPuzzle.empezar(this, hilos, 70) { n -> puzzles.firstOrNull { it.num == n }?.addr }
                b.text = "Stop"
            }
        }
        dlg.setOnDismissListener { h.removeCallbacks(refresco) }
        dlg.show()
    }

    val cuenta = grupo("Account")
    val uname = WalletManager.getUsername(this)
    fila(cuenta, R.drawable.ic_incognito, "Profile", "Username and PIN · local only",
         if (uname.isNotBlank()) uname else null) {
        startActivity(Intent(this, PerfilActivity::class.java))
    }

    val app = grupo("App")
    /* Tema: Dark, Light, Midnight (azul-tinta) y Auto (día/noche por hora).
     *
     * Un toque abre el selector; al elegir se guarda el modo y se recrea la
     * pantalla, porque los colores se leen al construir cada vista y las que ya
     * están puestas no cambian solas. La página se conserva por
     * onSaveInstanceState, así que se vuelve a More. */
    fun nombreModo(m: AppTheme.Modo) = when (m) {
        AppTheme.Modo.OSCURO -> "Dark"
        AppTheme.Modo.CLARO -> "Light"
        AppTheme.Modo.MEDIANOCHE -> "Midnight"
        AppTheme.Modo.NEON -> "Neon"
        AppTheme.Modo.EXCHANGE -> "Exchange"
        AppTheme.Modo.SYNTHWAVE -> "Synthwave"
        AppTheme.Modo.AUTO -> "Auto · day / night"
    }
    fun subModo(m: AppTheme.Modo) = when (m) {
        AppTheme.Modo.MEDIANOCHE -> "Deep ink blue"
        AppTheme.Modo.NEON -> "Cyberpunk cyan"
        AppTheme.Modo.EXCHANGE -> "Trading terminal · candles"
        AppTheme.Modo.SYNTHWAVE -> "Retro magenta"
        AppTheme.Modo.AUTO -> "Follows system · dark at night"
        else -> null
    }
    fila(app, R.drawable.ic_gear, "Appearance", null, nombreModo(AppTheme.modo)) {
        val cont = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(4))
        }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Appearance")
            .setView(cont)
            .setNegativeButton("Cancel", null)
            .create()
        AppTheme.Modo.values().forEach { m ->
            val sel = m == AppTheme.modo
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true; isFocusable = true
                foreground = Ui.toque()
                background = GradientDrawable().apply {
                    setColor(AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            }
            val preview: android.view.View = if (m == AppTheme.Modo.EXCHANGE) {
                val mu = AppTheme.muestra(AppTheme.Modo.EXCHANGE)
                // Se anima también en la miniatura; al cerrar el diálogo la vista
                // se desengancha y la animación se para sola.
                Velas(this).apply { colores(mu.bg, mu.accent, AppTheme.RED); animar(true) }
            } else MuestraTema(this).apply {
                if (m == AppTheme.Modo.AUTO)
                    partida(AppTheme.muestra(AppTheme.Modo.CLARO), AppTheme.muestra(AppTheme.Modo.OSCURO))
                else individual(AppTheme.muestra(m))
            }
            preview.layoutParams = LinearLayout.LayoutParams(dp(54), dp(38)).apply { marginEnd = dp(14) }
            fila.addView(preview)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = nombreModo(m); textSize = AppTheme.SP_BODY + 1f
                setTextColor(if (sel) AppTheme.ACCENT else AppTheme.TXT_PRI)
                typeface = AppTheme.bold(context)
            })
            subModo(m)?.let { s -> col.addView(TextView(this).apply {
                text = s; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
                typeface = AppTheme.body(context); setPadding(0, dp(2), 0, 0)
            }) }
            fila.addView(col)
            // Punto de selección: relleno de acento si es el activo, borde tenue si no.
            fila.addView(android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(10) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (sel) setColor(AppTheme.ACCENT)
                    else { setColor(0x00000000); setStroke(dp(2), AppTheme.TXT_MUTED) }
                }
            })
            fila.setOnClickListener {
                AppTheme.ponerModo(this@buildMoreTab, m)
                dlg.dismiss()
                this@buildMoreTab.recreate()
            }
            cont.addView(fila)
        }
        dlg.show()
    }
    fila(app, R.drawable.ic_refresh, "Updates", "Check for a new build now",
         Actualizador.nombreInstalada(this)) {
        Actualizador.comprobarAhora(this)
    }
    filaSwitch(app, "Check for updates automatically", "Asks github.com at most every 12 h",
        Actualizador.autoOn(this)) { Actualizador.setAuto(this, it) }
    fila(app, R.drawable.ic_help, "Help", "How the weak-key audit works", null) {
        startActivity(Intent(this, HelpActivity::class.java))
    }
    fila(app, R.drawable.ic_debug, "Debug", "Engine log and files", null) {
        startActivity(Intent(this, DebugActivity::class.java))
    }

    page.addView(TextView(this).apply {
        // La versión de verdad, la que pone la CI (1.0.<compilación>).
        // Estaba escrito "v2.4" a mano y no cambiaba nunca.
        text = "v" + (try { packageManager.getPackageInfo(packageName, 0).versionName }
                      catch (e: Exception) { "?" }) + " · Hunter Suite"
        textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, 0)
    })

    scroll.addView(page)
    return scroll
}
