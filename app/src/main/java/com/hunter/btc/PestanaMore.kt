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
    filaSwitch(avisos, "Vibrate on find", null, Avisos.vibrarOn(this)) { Avisos.setVibrar(this, it) }
    filaSwitch(avisos, "Coin sound on find", "Play a sound when a key is found",
        Avisos.sonidoOn(this)) { on ->
        Avisos.setSonido(this, on)
        if (on) Sonido.moneda(this)   // muestra cómo suena al activarlo
    }

    val rendimiento = grupo("Performance")
    val ahorroPct0 = prefs.getInt("modo_ahorro_pct", Termico.ECO_PCT).coerceIn(1, 100)

    // Etiqueta + deslizador para ELEGIR el techo de CPU en segundo plano.
    val lblTecho = TextView(this).apply {
        text = "Background CPU limit · $ahorroPct0 %"
        textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI)
        typeface = AppTheme.bold(context)
    }
    val sbTecho = android.widget.SeekBar(this).apply {
        max = 100; progress = ahorroPct0
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, u: Boolean) {
                lblTecho.text = "Background CPU limit · ${p.coerceAtLeast(1)} %"
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {
                val v = (s?.progress ?: ahorroPct0).coerceAtLeast(1)
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

    val app = grupo("App")
    /* Tema claro / oscuro.
     *
     * La paleta clara estaba ENTERA en AppTheme —fondos, textos, bordes— y
     * AppTheme.toggle() no lo llamaba nadie: no habia forma de llegar a
     * ella. Un tema que existe y no se puede elegir es lo mismo que no
     * tenerlo. Estaba en el menu lateral; con el menu fuera, vive aqui.
     *
     * Hace falta recrear la pantalla: los colores se leen al construir cada
     * vista, asi que las que ya estan puestas no cambian solas. La pagina
     * se conserva por onSaveInstanceState, asi que se vuelve a More. */
    fila(app, R.drawable.ic_gear, "Appearance", null,
         if (AppTheme.isDark) "Dark" else "Light") {
        AppTheme.toggle(this)
        recreate()
    }
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
                      catch (e: Exception) { "?" }) + " · Wallet Hunter"
        textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.body(context)
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, 0)
    })

    scroll.addView(page)
    return scroll
}
