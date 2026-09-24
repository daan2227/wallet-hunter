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
 * Lo que se usa menos: Recovery, History, Debug y el tema.
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

    val tools = grupo("Tools")
    fila(tools, R.drawable.ic_recovery, "Recovery",
         "Recover a seed with missing words", null) { goTab(PAG_RECOVERY) }
    fila(tools, R.drawable.ic_stats, "History",
         "Sessions and keys checked", null) {
        startActivity(Intent(this, StatsActivity::class.java))
    }

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
